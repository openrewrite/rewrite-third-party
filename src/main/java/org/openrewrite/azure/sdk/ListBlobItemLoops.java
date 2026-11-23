/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.azure.sdk;

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;
import org.openrewrite.java.tree.*;

import java.util.List;

/**
 * v8 listed a container as {@code ListBlobItem}s that were blobs or directories, told apart with {@code instanceof} and
 * cast to {@code CloudBlob}; v12 lists {@code BlobItem}s, which only name a blob or a prefix. In a loop over a
 * container's listing, the container the loop lists turns each item back into a client.
 */
class ListBlobItemLoops extends JavaVisitor<ExecutionContext> {

    private static final String B = MigrateAzureStorageBlob.B;
    private static final MethodMatcher LIST_BLOBS = new MethodMatcher(B + "CloudBlobContainer listBlobs(..)", true);
    private static final MethodMatcher GET_URI = new MethodMatcher(B + "ListBlobItem getUri()", true);
    private static final String ITEM = "com.azure.storage.blob.models.BlobItem";
    private static final String CONTAINER = "com.azure.storage.blob.BlobContainerClient";

    private JavaParser.@Nullable Builder<?, ?> parser;

    @Override
    public J visitForEachLoop(J.ForEachLoop forLoop, ExecutionContext ctx) {
        J.ForEachLoop loop = (J.ForEachLoop) super.visitForEachLoop(forLoop, ctx);
        Expression iterable = loop.getControl().getIterable();
        if (iterable instanceof J.Identifier) {
            iterable = listingHeldBy((J.Identifier) iterable, forLoop);
        }
        if (!(iterable instanceof J.MethodInvocation) || !LIST_BLOBS.matches((J.MethodInvocation) iterable)) {
            return loop;
        }
        Expression container = ((J.MethodInvocation) iterable).getSelect();
        if (!(container instanceof J.Identifier || container instanceof J.FieldAccess && isPure((J.FieldAccess) container))) {
            return loop;
        }
        if (!(loop.getControl().getVariable() instanceof J.VariableDeclarations)) {
            return loop;
        }
        J.VariableDeclarations.NamedVariable item = ((J.VariableDeclarations) loop.getControl().getVariable()).getVariables().get(0);
        // A listing with details may list snapshots, each of which is a client of its own
        List<Expression> listArgs = ((J.MethodInvocation) iterable).getArguments();
        boolean snapshots = listArgs.size() > 2 && !CallRewrite.isNull(listArgs.get(2));
        Statement body = (Statement) new ItemUses(item.getSimpleName(), container, snapshots)
                .visitNonNull(loop.getBody(), ctx, new Cursor(getCursor().getParentOrThrow(), loop));
        return loop.withBody(body);
    }

    /**
     * The listing a local variable holds, when the variable is declared with it in the block that holds the loop and
     * the loop is all that reads it.
     */
    private @Nullable Expression listingHeldBy(J.Identifier variable, J.ForEachLoop loop) {
        Object parent = getCursor().getParentTreeCursor().getValue();
        if (!(parent instanceof J.Block) || variable.getFieldType() == null) {
            return null;
        }
        J.Block block = (J.Block) parent;
        Expression listing = null;
        for (Statement statement : block.getStatements()) {
            if (statement == loop || statement.getId().equals(loop.getId())) {
                break;
            }
            if (statement instanceof J.VariableDeclarations) {
                for (J.VariableDeclarations.NamedVariable v : ((J.VariableDeclarations) statement).getVariables()) {
                    if (variable.getFieldType().equals(v.getVariableType())) {
                        listing = v.getInitializer();
                    }
                }
            }
        }
        if (listing == null) {
            return null;
        }
        int[] reads = {0};
        new JavaVisitor<int[]>() {
            @Override
            public J visitIdentifier(J.Identifier identifier, int[] count) {
                if (variable.getFieldType().equals(identifier.getFieldType()) &&
                    !(getCursor().getParentTreeCursor().getValue() instanceof J.VariableDeclarations.NamedVariable)) {
                    count[0]++;
                }
                return identifier;
            }
        }.visit(block, reads);
        return reads[0] == 1 ? listing : null;
    }

    private static boolean isPure(J.FieldAccess f) {
        Expression target = f.getTarget();
        return target instanceof J.Identifier || target instanceof J.FieldAccess && isPure((J.FieldAccess) target);
    }

    private JavaParser.Builder<?, ?> parser(ExecutionContext ctx) {
        if (parser == null) {
            parser = JavaParser.fromJavaVersion().classpathFromResources(ctx, AzureCallRewriter.CLASSPATH);
        }
        return parser;
    }

    private class ItemUses extends JavaVisitor<ExecutionContext> {
        private final String item;
        private final Expression container;
        private final boolean snapshots;

        ItemUses(String item, Expression container, boolean snapshots) {
            this.item = item;
            this.container = container;
            this.snapshots = snapshots;
        }

        private boolean isItem(@Nullable Expression e) {
            return e instanceof J.Identifier && item.equals(((J.Identifier) e).getSimpleName());
        }

        @Override
        public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J j = super.visitMethodInvocation(method, ctx);
            if (j instanceof J.MethodInvocation && GET_URI.matches((J.MethodInvocation) j) &&
                isItem(((J.MethodInvocation) j).getSelect())) {
                J.MethodInvocation m = (J.MethodInvocation) j;
                return replace(m, m.getCoordinates().replace(), ctx,
                        "java.net.URI.create(#{container:any(" + CONTAINER + ")}.getBlobClient(#{item:any(" + ITEM +
                        ")}.getName()).getBlobUrl())", copy(container), m.getSelect());
            }
            return j;
        }

        @Override
        public J visitInstanceOf(J.InstanceOf instanceOf, ExecutionContext ctx) {
            J j = super.visitInstanceOf(instanceOf, ctx);
            if (!(j instanceof J.InstanceOf) || !isItem(((J.InstanceOf) j).getExpression()) ||
                ((J.InstanceOf) j).getPattern() != null) {
                return j;
            }
            J.InstanceOf i = (J.InstanceOf) j;
            if (!(i.getClazz() instanceof TypeTree)) {
                return j;
            }
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(((TypeTree) i.getClazz()).getType());
            if (type == null) {
                return j;
            }
            String condition;
            switch (type.getFullyQualifiedName()) {
                case B + "CloudBlob":
                    condition = "!Boolean.TRUE.equals(#{item:any(" + ITEM + ")}.isPrefix())";
                    break;
                case B + "CloudBlockBlob":
                case B + "CloudPageBlob":
                case B + "CloudAppendBlob":
                    String blobType = type.getClassName().equals("CloudBlockBlob") ? "BLOCK_BLOB" :
                            type.getClassName().equals("CloudPageBlob") ? "PAGE_BLOB" : "APPEND_BLOB";
                    condition = "!Boolean.TRUE.equals(#{item:any(" + ITEM + ")}.isPrefix()) && #{same:any(" + ITEM +
                                ")}.getProperties().getBlobType() == com.azure.storage.blob.models.BlobType." + blobType;
                    break;
                case B + "CloudBlobDirectory":
                    condition = "Boolean.TRUE.equals(#{item:any(" + ITEM + ")}.isPrefix())";
                    break;
                default:
                    return j;
            }
            J result = condition.contains("#{same:") ?
                    replace(i, i.getCoordinates().replace(), ctx, condition, i.getExpression(), copy(i.getExpression())) :
                    replace(i, i.getCoordinates().replace(), ctx, condition, i.getExpression());
            Object parent = getCursor().getParentTreeCursor().getValue();
            if (result instanceof J.Binary && !(parent instanceof J.Parentheses || parent instanceof J.ControlParentheses)) {
                return new J.Parentheses<>(Tree.randomId(), result.getPrefix(), result.getMarkers(),
                        JRightPadded.build(result.withPrefix(Space.EMPTY)));
            }
            return result;
        }

        @Override
        public J visitTypeCast(J.TypeCast typeCast, ExecutionContext ctx) {
            J j = super.visitTypeCast(typeCast, ctx);
            if (!(j instanceof J.TypeCast) || !isItem(((J.TypeCast) j).getExpression())) {
                return j;
            }
            J.TypeCast cast = (J.TypeCast) j;
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(cast.getClazz().getType());
            if (type == null) {
                return j;
            }
            String client;
            switch (type.getFullyQualifiedName()) {
                case B + "CloudBlob":
                case B + "CloudBlockBlob":
                    client = "";
                    break;
                case B + "CloudAppendBlob":
                    client = ".getAppendBlobClient()";
                    break;
                case B + "CloudPageBlob":
                    client = ".getPageBlobClient()";
                    break;
                default:
                    return j;
            }
            return replace(cast, cast.getCoordinates().replace(), ctx, "#{container:any(" + CONTAINER + ")}.getBlobClient(#{item:any(" +
                                                              ITEM + ")}.getName()" + (snapshots ? ", #{item}.getSnapshot()" : "") + ")" +
                                                              client, copy(container), cast.getExpression());
        }

        private J replace(J tree, JavaCoordinates coordinates, ExecutionContext ctx, String template, Object... params) {
            J result = JavaTemplate.builder(template)
                    .javaParser(parser(ctx))
                    .build()
                    .apply(new Cursor(getCursor().getParent(), tree), coordinates, params);
            // This visitor only sees the loop body, so the imports are added by the visitor of the whole file
            ListBlobItemLoops.this.doAfterVisit(ShortenFullyQualifiedTypeReferences.modifyOnly(result));
            return result;
        }

        @Override
        public <T extends J> J visitParentheses(J.Parentheses<T> parens, ExecutionContext ctx) {
            J j = super.visitParentheses(parens, ctx);
            // `((CloudBlob) item).delete()` needed the parentheses only for the cast
            if (j instanceof J.Parentheses && parens.getTree() instanceof J.TypeCast &&
                ((J.Parentheses<?>) j).getTree() instanceof J.MethodInvocation) {
                return ((J) ((J.Parentheses<?>) j).getTree()).withPrefix(j.getPrefix());
            }
            return j;
        }
    }

    private static Expression copy(Expression e) {
        return ((Expression) e.withId(Tree.randomId())).withPrefix(Space.EMPTY);
    }
}

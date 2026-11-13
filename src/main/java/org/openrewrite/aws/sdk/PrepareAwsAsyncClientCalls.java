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
package org.openrewrite.aws.sdk;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Value
@EqualsAndHashCode(callSuper = false)
public class PrepareAwsAsyncClientCalls extends ScanningRecipe<ProjectShims> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    private static final Pattern V1_CLIENT = Pattern.compile("com\\.amazonaws\\.services\\.[a-z0-9]+\\.[A-Za-z0-9]+");
    private static final Pattern V2_ASYNC_CLIENT = Pattern.compile("software\\.amazon\\.awssdk\\.services\\.[a-z0-9]+\\.[A-Za-z0-9]+AsyncClient");

    @Getter
    final String displayName = "Prepare AWS SDK v1 async client calls for v2";

    @Getter
    final String description = "Runs before `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. A v1 `*Async` client also implements " +
            "the sync interface, so `sqsAsync.sendMessage(req)` blocked and threw, whereas on the v2 async client the same call returns a " +
            "`CompletableFuture`. This renames `xxxAsync(req)` to the v2 name `xxx(req)`, so that after v2-migration " +
            "`CompleteAwsAsyncClientCalls` can tell the two apart: a former blocking call still returns the response type, a former " +
            "`xxxAsync` call a `Future`. An async client used where a sync client is expected is wrapped in a generated " +
            "`AwsSyncClients.blocking(client, SyncClient.class)`, a blocking view of the same client. Calls that take an `AsyncHandler` " +
            "are marked for manual migration.";

    @Override
    public ProjectShims getInitialValue(ExecutionContext ctx) {
        return new ProjectShims();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(ProjectShims acc) {
        TreeVisitor<?, ExecutionContext> scope = V2MigrationScope.javaFilesUsing("com.amazonaws.services..*");
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    acc.recordExisting((SourceFile) tree);
                }
                if (tree instanceof J.CompilationUnit && scope.visit(tree, ctx) != tree) {
                    boolean[] found = {false};
                    new AsyncAsSyncSites() {
                        @Override
                        Expression onSite(Expression e, JavaType.FullyQualified syncType) {
                            found[0] = true;
                            return e;
                        }
                    }.visit(tree, ctx);
                    if (found[0]) {
                        acc.require((J.CompilationUnit) tree);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(ProjectShims acc, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        return acc.generate(AwsSyncClientsShim.SHIM, AwsSyncClientsShim::source, ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(ProjectShims acc) {
        return Preconditions.check(V2MigrationScope.javaFilesUsing("com.amazonaws.services..*"), new AsyncAsSyncSites() {

            @Override
            Expression onSite(Expression e, JavaType.FullyQualified syncType) {
                String shim = acc.fqn(getCursor().firstEnclosingOrThrow(J.CompilationUnit.class), AwsSyncClientsShim.SHIM);
                if (shim == null) {
                    return e;
                }
                String shimPkg = shim.contains(".") ? shim.substring(0, shim.lastIndexOf('.')) : "";
                String syncFqn = syncType.getFullyQualifiedName();
                String syncPkg = syncFqn.substring(0, syncFqn.lastIndexOf('.'));
                maybeAddImport(shim);
                maybeAddImport(syncFqn);
                return JavaTemplate.builder(AwsSyncClientsShim.SHIM + ".blocking(#{any()}, " + syncType.getClassName() + ".class)")
                        .imports(shim, syncFqn)
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(
                                "package " + syncPkg + "; public interface " + syncType.getClassName() + " {}",
                                ProjectShims.packageLine(shimPkg) + "public final class " + AwsSyncClientsShim.SHIM +
                                " { public static <T> T blocking(Object c, Class<T> t) { return null; } }"))
                        .build()
                        .apply(new Cursor(getCursor(), e), e.getCoordinates().replace(), e);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                JavaType.Method mt = m.getMethodType();
                if (mt == null || m.getSelect() == null || !isV1AsyncClient(m.getSelect().getType())) {
                    return m;
                }

                String name = m.getSimpleName();
                if (name.endsWith("Async") && TypeUtils.isOfClassType(mt.getReturnType(), "java.util.concurrent.Future")) {
                    for (JavaType p : mt.getParameterTypes()) {
                        if (TypeUtils.isOfClassType(p, "com.amazonaws.handlers.AsyncHandler")) {
                            return SearchResult.found(m, "AsyncHandler callbacks have no v2 equivalent: call " +
                                    name.substring(0, name.length() - 5) + "(request) and attach the callback with whenComplete");
                        }
                    }
                    String syncName = name.substring(0, name.length() - 5);
                    JavaType.Method renamed = mt.withName(syncName);
                    return m.withName(m.getName().withSimpleName(syncName).withType(renamed)).withMethodType(renamed);
                }
                return m;
            }
        });
    }

    /**
     * Finds where a v1 async client flows into a v1 sync client type: returned, assigned, initialized or passed as an argument.
     * v1 async interfaces extend the sync ones, so the upcast compiles in v1 and stops compiling once both sides are retyped.
     */
    private abstract static class AsyncAsSyncSites extends JavaIsoVisitor<ExecutionContext> {

        abstract Expression onSite(Expression e, JavaType.FullyQualified syncType);

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
            JavaType.Method mt = m.getMethodType();
            if (mt == null || V2MigrationScope.isMockitoArgument(getCursor())) {
                return m;
            }
            List<JavaType> params = mt.getParameterTypes();
            return m.withArguments(ListUtils.map(m.getArguments(), (i, arg) -> {
                JavaType.FullyQualified sync = i < params.size() ? syncTarget(arg.getType(), params.get(i)) : null;
                return sync == null ? arg : onSite(arg, sync);
            }));
        }

        @Override
        public J.Return visitReturn(J.Return _return, ExecutionContext ctx) {
            J.Return r = super.visitReturn(_return, ctx);
            J.MethodDeclaration enclosing = getCursor().firstEnclosing(J.MethodDeclaration.class);
            if (r.getExpression() != null && enclosing != null && enclosing.getReturnTypeExpression() != null &&
                getCursor().firstEnclosing(J.Lambda.class) == null) {
                JavaType.FullyQualified sync = syncTarget(r.getExpression().getType(), enclosing.getReturnTypeExpression().getType());
                if (sync != null) {
                    return r.withExpression(onSite(r.getExpression(), sync));
                }
            }
            return r;
        }

        @Override
        public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, ExecutionContext ctx) {
            J.VariableDeclarations.NamedVariable v = super.visitVariable(variable, ctx);
            JavaType.FullyQualified sync = v.getInitializer() == null ? null : syncTarget(v.getInitializer().getType(), v.getType());
            return sync == null ? v : v.withInitializer(onSite(v.getInitializer(), sync));
        }

        @Override
        public J.Assignment visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
            J.Assignment a = super.visitAssignment(assignment, ctx);
            JavaType.FullyQualified sync = syncTarget(a.getAssignment().getType(), a.getVariable().getType());
            return sync == null ? a : a.withAssignment(onSite(a.getAssignment(), sync));
        }
    }

    private static JavaType.@Nullable FullyQualified syncTarget(@Nullable JavaType from, @Nullable JavaType to) {
        JavaType.FullyQualified target = TypeUtils.asFullyQualified(to);
        // Client builder chains are converted earlier in the run, so the async side may already carry its v2 type
        return target != null && (isV1AsyncClient(from) || isV2AsyncClient(from)) &&
               V1_CLIENT.matcher(target.getFullyQualifiedName()).matches() && !isV1AsyncClient(target) ? target : null;
    }

    private static boolean isV2AsyncClient(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq != null && V2_ASYNC_CLIENT.matcher(fq.getFullyQualifiedName()).matches();
    }

    static boolean isV1AsyncClient(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq == null) {
            return false;
        }
        String fqn = fq.getFullyQualifiedName();
        if (V1_CLIENT.matcher(fqn).matches() && (fqn.endsWith("Async") || fqn.endsWith("AsyncClient"))) {
            return true;
        }
        for (JavaType.FullyQualified i : fq.getInterfaces()) {
            if (isV1AsyncClient(i)) {
                return true;
            }
        }
        return fq.getSupertype() != null && isV1AsyncClient(fq.getSupertype());
    }
}

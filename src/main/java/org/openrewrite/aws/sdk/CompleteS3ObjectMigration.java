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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.nCopies;
import static java.util.Collections.singletonList;

public class CompleteS3ObjectMigration extends Recipe {

    private static final String V2_MODEL = "software.amazon.awssdk.services.s3.model.";
    private static final String V2_TRANSFER_MODEL = "software.amazon.awssdk.transfer.s3.model.";

    @Getter
    final String displayName = "Complete AWS SDK v2 S3 object calls that v2-migration renames but cannot map";

    @Getter
    final String description = "Runs after `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`, which renames v1 getters on S3 " +
            "responses, transfers and ACLs to names v2 does not have. `waitForCompletion()` on a transfer becomes " +
            "`completionFuture().join()` and `isDone()` becomes `completionFuture().isDone()`, `ratioTransferred()` standing for v1's " +
            "percentage becomes one again, v2-migration's note on an `eTag()` whose quotes it already strips is dropped, `userMetadata()` on a response becomes `metadata()`, `Download.getObjectMetadata()` reads the " +
            "completed download's response, `grantsAsList()` and `Grantee.identifier()` become `grants()` and `id()`, grants added to " +
            "an `AccessControlPolicy` rebuild it, a grantee built from a v1 `CanonicalGrantee` gets its `CANONICAL_USER` type, `setObjectTagging` becomes `putObjectTagging`, and `setBucketAcl`/`setObjectAcl` become `putBucketAcl`/`putObjectAcl`.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(Preconditions.or(new UsesType<>(V2_MODEL + "*", true), new UsesType<>("software.amazon.awssdk.transfer.s3..*", true)),
                new JavaVisitor<ExecutionContext>() {

                    @Override
                    public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        J j = super.visitMethodInvocation(method, ctx);
                        if (!(j instanceof J.MethodInvocation)) {
                            return j;
                        }
                        J.MethodInvocation m = (J.MethodInvocation) j;
                        Expression select = m.getSelect();
                        if ("setObjectTagging".equals(m.getSimpleName()) && select != null &&
                            TypeUtils.isOfClassType(select.getType(), "software.amazon.awssdk.services.s3.S3Client")) {
                            // v2-migration retypes the request to PutObjectTaggingRequest but keeps v1's method name
                            return m.withName(m.getName().withSimpleName("putObjectTagging"))
                                    .withMethodType(m.getMethodType() == null ? null : m.getMethodType().withName("putObjectTagging"));
                        }
                        if (select == null || V2MigrationScope.isMockitoStubbedOrVerified(getCursor())) {
                            return m;
                        }
                        String name = m.getSimpleName();
                        if (("setBucketAcl".equals(name) && m.getArguments().size() == 2 ||
                             "setObjectAcl".equals(name) && (m.getArguments().size() == 3 || m.getArguments().size() == 4)) &&
                            TypeUtils.isOfClassType(select.getType(), "software.amazon.awssdk.services.s3.S3Client")) {
                            J acl = putAcl(m, ctx);
                            if (acl != null) {
                                return acl;
                            }
                        }
                        if ("replaceAll".equals(name) && select instanceof J.MethodInvocation &&
                            "eTag".equals(((J.MethodInvocation) select).getSimpleName()) &&
                            m.getComments().stream().anyMatch(c -> c instanceof TextComment &&
                                                                   ((TextComment) c).getText().contains("eTag() preserves surrounding quotes"))) {
                            // v2-migration already strips the quotes here, so its note asks for nothing more
                            return m.withPrefix(Space.build(m.getPrefix().getWhitespace(), emptyList()));
                        }
                        if ("contentLength".equals(name) && m.getArguments().size() == 1 && isIntLike(m.getArguments().get(0)) &&
                            onS3Model(select, getCursor().firstEnclosing(J.CompilationUnit.class))) {
                            // v2-migration copies v1's int argument, which widened to v1's long but does not box to v2's Long
                            return m.withArguments(singletonList(JavaTemplate.apply("(long) #{any(int)}",
                                    new Cursor(getCursor(), m.getArguments().get(0)), m.getArguments().get(0).getCoordinates().replace(),
                                    m.getArguments().get(0))));
                        }
                        boolean noArgs = m.getArguments().isEmpty() || m.getArguments().get(0) instanceof J.Empty;
                        JavaType.FullyQualified selectType = TypeUtils.asFullyQualified(select.getType());
                        String fqn = selectType == null ? "" : selectType.getFullyQualifiedName();
                        if (noArgs && "waitForCompletion".equals(name) && fqn.startsWith(V2_TRANSFER_MODEL)) {
                            return join(select, selectType, m);
                        }
                        if (noArgs && "isDone".equals(name) && fqn.startsWith(V2_TRANSFER_MODEL)) {
                            J.MethodInvocation future = LstFactory.noArgCall(select, "completionFuture", selectType, LstFactory.completableFutureOf(null));
                            return LstFactory.noArgCall(future, "isDone", JavaType.ShallowClass.build("java.util.concurrent.CompletableFuture"),
                                    JavaType.Primitive.Boolean).withPrefix(m.getPrefix());
                        }
                        if (noArgs && "ratioTransferred".equals(name) && ("software.amazon.awssdk.transfer.s3.progress.TransferProgressSnapshot".equals(fqn) ||
                                                                         fqn.isEmpty() && isProgressSnapshot(select)) &&
                            !(getCursor().getParentTreeCursor().getValue() instanceof J.MethodInvocation &&
                              ((J.MethodInvocation) getCursor().getParentTreeCursor().getValue()).getSelect() == method)) {
                            // v1's getPercentTransferred was a percentage; v2's ratio is optional and between 0 and 1
                            return JavaTemplate.apply("(#{any()}.ratioTransferred().orElse(0.0) * 100)", getCursor(), method.getCoordinates().replace(), select);
                        }
                        if (noArgs && "getObjectMetadata".equals(name) && fqn.equals(V2_TRANSFER_MODEL + "FileDownload")) {
                            J.MethodInvocation joined = join(select, selectType, m);
                            return LstFactory.noArgCall(joined, "response", JavaType.ShallowClass.build(V2_TRANSFER_MODEL + "CompletedFileDownload"),
                                    JavaType.ShallowClass.build(V2_MODEL + "GetObjectResponse")).withPrefix(m.getPrefix());
                        }
                        if (noArgs && "userMetadata".equals(name) &&
                            (fqn.equals(V2_MODEL + "GetObjectResponse") || fqn.equals(V2_MODEL + "HeadObjectResponse"))) {
                            return rename(m, "metadata");
                        }
                        if (noArgs && "grantsAsList".equals(name) && fqn.equals(V2_MODEL + "AccessControlPolicy")) {
                            return rename(m, "grants");
                        }
                        if (noArgs && "identifier".equals(name) && fqn.equals(V2_MODEL + "Grantee")) {
                            return rename(m, "id");
                        }
                        if ("id".equals(name) && m.getArguments().size() == 1 && isGranteeBuilder(select)) {
                            maybeAddImport(V2_MODEL + "Type");
                            return JavaTemplate.builder("Grantee.builder().type(Type.CANONICAL_USER).id(#{any(String)})")
                                    .javaParser(MigrateS3Residuals.v2Parser(ctx))
                                    .imports(V2_MODEL + "Grantee", V2_MODEL + "Type")
                                    .build()
                                    .apply(getCursor(), method.getCoordinates().replace(), m.getArguments().get(0))
                                    .withPrefix(m.getPrefix());
                        }
                        if (fqn.equals(V2_MODEL + "AccessControlPolicy") && select instanceof J.Identifier &&
                            getCursor().getParentTreeCursor().getValue() instanceof J.Block &&
                            ("grantAllPermissions".equals(name) || "grantPermission".equals(name) && m.getArguments().size() == 2)) {
                            maybeAddImport("java.util.stream.Collectors");
                            maybeAddImport("java.util.stream.Stream");
                            String added = "grantPermission".equals(name) ?
                                    "Stream.of(Grant.builder().grantee(#{any()}).permission(#{any()}).build())" :
                                    "Stream.of(" + String.join(", ", nCopies(m.getArguments().size(), "#{any()}")) + ")";
                            if ("grantPermission".equals(name)) {
                                maybeAddImport(V2_MODEL + "Grant");
                            }
                            Object[] args = new Object[m.getArguments().size() + 3];
                            args[0] = select;
                            args[1] = select;
                            args[2] = select;
                            for (int i = 0; i < m.getArguments().size(); i++) {
                                args[i + 3] = m.getArguments().get(i);
                            }
                            // v2's policy is immutable, so each grant rebuilds it with the grants it already had
                            return JavaTemplate.builder("#{any(" + V2_MODEL + "AccessControlPolicy)} = #{any(" + V2_MODEL +
                                                        "AccessControlPolicy)}.toBuilder().grants(Stream.concat(#{any(" + V2_MODEL +
                                                        "AccessControlPolicy)}.grants().stream(), " + added +
                                                        ").collect(Collectors.toList())).build()")
                                    .javaParser(MigrateS3Residuals.v2Parser(ctx))
                                    .imports("java.util.stream.Collectors", "java.util.stream.Stream", V2_MODEL + "Grant",
                                            V2_MODEL + "AccessControlPolicy")
                                    .build()
                                    .apply(getCursor(), method.getCoordinates().replace(), args);
                        }
                        return m;
                    }

                    // v2-migration leaves v1's setBucketAcl/setObjectAcl convenience overloads, which S3Client does not have
                    private @Nullable J putAcl(J.MethodInvocation m, ExecutionContext ctx) {
                        Expression acl = m.getArguments().get(m.getArguments().size() - 1);
                        String setter;
                        if (TypeUtils.isOfClassType(acl.getType(), V2_MODEL + "AccessControlPolicy")) {
                            setter = "accessControlPolicy(#{any(" + V2_MODEL + "AccessControlPolicy)})";
                        } else if (TypeUtils.isString(acl.getType()) || TypeUtils.isOfClassType(acl.getType(), V2_MODEL + "BucketCannedACL") ||
                                   TypeUtils.isOfClassType(acl.getType(), V2_MODEL + "ObjectCannedACL")) {
                            setter = "acl(#{any()})";
                        } else {
                            return null;
                        }
                        boolean bucket = "setBucketAcl".equals(m.getSimpleName());
                        String request = bucket ? "r -> r.bucket(#{any(String)})." + setter :
                                m.getArguments().size() == 4 ? "r -> r.bucket(#{any(String)}).key(#{any(String)}).versionId(#{any(String)})." + setter :
                                        "r -> r.bucket(#{any(String)}).key(#{any(String)})." + setter;
                        Object[] args = new Object[m.getArguments().size() + 1];
                        args[0] = m.getSelect();
                        for (int i = 0; i < m.getArguments().size(); i++) {
                            args[i + 1] = m.getArguments().get(i);
                        }
                        J.MethodInvocation put = JavaTemplate.builder("#{any(software.amazon.awssdk.services.s3.S3Client)}." +
                                                                      (bucket ? "putBucketAcl" : "putObjectAcl") + "(" + request + ")")
                                .javaParser(MigrateS3Residuals.v2Parser(ctx))
                                .build()
                                .apply(getCursor(), m.getCoordinates().replace(), args);
                        // Drops v2-migration's note that it could not transform the ACL
                        return put.withPrefix(Space.build(m.getPrefix().getWhitespace(), ListUtils.filter(m.getPrefix().getComments(),
                                c -> !(c instanceof TextComment) || !((TextComment) c).getText().contains("CannedAccessControlList"))));
                    }

                    private J.MethodInvocation join(Expression select, JavaType.FullyQualified selectType, J.MethodInvocation m) {
                        J.MethodInvocation future = LstFactory.noArgCall(select, "completionFuture", selectType, LstFactory.completableFutureOf(null));
                        return LstFactory.noArgCall(future, "join", JavaType.ShallowClass.build("java.util.concurrent.CompletableFuture"), null)
                                .withPrefix(m.getPrefix());
                    }
                });
    }

    /**
     * {@code transfer.progress().snapshot()}, which v2-migration builds without types.
     */
    private static boolean isProgressSnapshot(Expression select) {
        return select instanceof J.MethodInvocation && "snapshot".equals(((J.MethodInvocation) select).getSimpleName()) &&
               ((J.MethodInvocation) select).getSelect() instanceof J.MethodInvocation &&
               "progress".equals(((J.MethodInvocation) ((J.MethodInvocation) select).getSelect()).getSimpleName());
    }

    private static boolean isGranteeBuilder(Expression select) {
        if (!(select instanceof J.MethodInvocation)) {
            return false;
        }
        J.MethodInvocation builder = (J.MethodInvocation) select;
        return "builder".equals(builder.getSimpleName()) && builder.getSelect() != null &&
               TypeUtils.isOfClassType(builder.getSelect().getType(), V2_MODEL + "Grantee");
    }

    private static J.MethodInvocation rename(J.MethodInvocation m, String name) {
        JavaType.@Nullable Method type = m.getMethodType() == null ? null : m.getMethodType().withName(name);
        return m.withName(m.getName().withSimpleName(name).withType(type)).withMethodType(type);
    }

    private static boolean isIntLike(Expression e) {
        JavaType type = e.getType();
        if (type == JavaType.Primitive.Int || type == JavaType.Primitive.Short || type == JavaType.Primitive.Byte ||
            type == JavaType.Primitive.Char) {
            return true;
        }
        // v2-migration's templates leave the call untyped, where a field named length is still an array's
        if (!(e instanceof J.FieldAccess) || !"length".equals(((J.FieldAccess) e).getSimpleName())) {
            return false;
        }
        JavaType target = ((J.FieldAccess) e).getTarget().getType();
        return target instanceof JavaType.Array || target == null || target instanceof JavaType.Unknown;
    }

    private static boolean onS3Model(@Nullable Expression select, J.@Nullable CompilationUnit cu) {
        for (Expression e = select; e != null; e = e instanceof J.MethodInvocation ? ((J.MethodInvocation) e).getSelect() : null) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(e.getType());
            if (type != null && type.getFullyQualifiedName().startsWith(V2_MODEL)) {
                return true;
            }
            if ((type == null || type instanceof JavaType.Unknown) && e instanceof J.Identifier && cu != null) {
                // an untyped builder chain starts from the model class, which the import identifies
                for (J.Import i : cu.getImports()) {
                    if (!i.isStatic() && i.getClassName().equals(((J.Identifier) e).getSimpleName()) &&
                        i.getTypeName().startsWith(V2_MODEL)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}

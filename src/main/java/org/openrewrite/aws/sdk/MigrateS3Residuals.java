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
import org.openrewrite.aws.sdk.table.S3ResidualChanges;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.Markup;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.trait.Comments;

import java.nio.file.Path;
import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Objects.requireNonNull;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateS3Residuals extends ScanningRecipe<MigrateS3Residuals.Accumulator> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    private static final String AMAZON_S3 = "com.amazonaws.services.s3.AmazonS3";
    private static final String V1_MODEL = "com.amazonaws.services.s3.model.";
    private static final String CANNED_ACL = V1_MODEL + "CannedAccessControlList";
    private static final String PRESIGN_REQUEST = V1_MODEL + "GeneratePresignedUrlRequest";
    private static final String V1_HTTP_METHOD = "com.amazonaws.HttpMethod";
    private static final String V1_HEADERS_CLASS = "com.amazonaws.services.s3.Headers";
    private static final String V2_S3_CLIENT = "software.amazon.awssdk.services.s3.S3Client";

    private static final MethodMatcher GET_OBJECT_TO_FILE = new MethodMatcher(AMAZON_S3 +
            " getObject(com.amazonaws.services.s3.model.GetObjectRequest, java.io.File)", true);
    private static final MethodMatcher DOES_OBJECT_EXIST = new MethodMatcher(AMAZON_S3 + " doesObjectExist(String, String)", true);
    private static final MethodMatcher DOES_BUCKET_EXIST_V2 = new MethodMatcher(AMAZON_S3 + " doesBucketExistV2(String)", true);
    private static final MethodMatcher DOES_BUCKET_EXIST = new MethodMatcher(AMAZON_S3 + " doesBucketExist(String)", true);
    private static final MethodMatcher PRESIGN = new MethodMatcher(AMAZON_S3 + " generatePresignedUrl(" + PRESIGN_REQUEST + ")", true);
    private static final MethodMatcher PRESIGN_4_ARGS = new MethodMatcher(AMAZON_S3 + " generatePresignedUrl(String, String, java.util.Date, " + V1_HTTP_METHOD + ")", true);
    private static final MethodMatcher LIST_OBJECTS = new MethodMatcher(AMAZON_S3 + " listObjects(..)", true);
    private static final MethodMatcher LIST_NEXT_BATCH = new MethodMatcher(AMAZON_S3 + " listNextBatchOfObjects(" + V1_MODEL + "ObjectListing)", true);
    private static final MethodMatcher GET_OBJECT_SUMMARIES = new MethodMatcher(V1_MODEL + "ObjectListing getObjectSummaries()");
    private static final MethodMatcher IS_TRUNCATED = new MethodMatcher(V1_MODEL + "ObjectListing isTruncated()");
    private static final MethodMatcher TM_BUILD = new MethodMatcher("com.amazonaws.services.s3.transfer.TransferManagerBuilder build()");
    private static final MethodMatcher TM_STANDARD = new MethodMatcher("com.amazonaws.services.s3.transfer.TransferManagerBuilder standard()");
    private static final MethodMatcher TM_NEW = new MethodMatcher("com.amazonaws.services.s3.transfer.TransferManager <constructor>(..)");

    private static final Map<String, String> OBJECT_ACL = new HashMap<>();
    private static final Map<String, String> BUCKET_ACL = new HashMap<>();
    private static final Map<String, String> V1_HEADERS = new HashMap<>();
    private static final Map<String, String> CANNED_ACL_VALUES = new HashMap<>();

    static {
        OBJECT_ACL.put("Private", "PRIVATE");
        OBJECT_ACL.put("PublicRead", "PUBLIC_READ");
        OBJECT_ACL.put("PublicReadWrite", "PUBLIC_READ_WRITE");
        OBJECT_ACL.put("AuthenticatedRead", "AUTHENTICATED_READ");
        OBJECT_ACL.put("AwsExecRead", "AWS_EXEC_READ");
        OBJECT_ACL.put("BucketOwnerRead", "BUCKET_OWNER_READ");
        OBJECT_ACL.put("BucketOwnerFullControl", "BUCKET_OWNER_FULL_CONTROL");
        BUCKET_ACL.put("Private", "PRIVATE");
        BUCKET_ACL.put("PublicRead", "PUBLIC_READ");
        BUCKET_ACL.put("PublicReadWrite", "PUBLIC_READ_WRITE");
        BUCKET_ACL.put("AuthenticatedRead", "AUTHENTICATED_READ");
        CANNED_ACL_VALUES.put("LogDeliveryWrite", "log-delivery-write");
        CANNED_ACL_VALUES.put("BucketOwnerRead", "bucket-owner-read");
        CANNED_ACL_VALUES.put("BucketOwnerFullControl", "bucket-owner-full-control");
        CANNED_ACL_VALUES.put("AwsExecRead", "aws-exec-read");
        V1_HEADERS.put("S3_CANNED_ACL", "x-amz-acl");
        V1_HEADERS.put("S3_TAGGING", "x-amz-tagging");
        V1_HEADERS.put("STORAGE_CLASS", "x-amz-storage-class");
        V1_HEADERS.put("REQUESTER_PAYS_HEADER", "x-amz-request-payer");
        V1_HEADERS.put("CONTENT_TYPE", "Content-Type");
        V1_HEADERS.put("CONTENT_LENGTH", "Content-Length");
        V1_HEADERS.put("CONTENT_MD5", "Content-MD5");
        V1_HEADERS.put("CACHE_CONTROL", "Cache-Control");
        V1_HEADERS.put("CONTENT_DISPOSITION", "Content-Disposition");
        V1_HEADERS.put("CONTENT_ENCODING", "Content-Encoding");
        V1_HEADERS.put("SERVER_SIDE_ENCRYPTION", "x-amz-server-side-encryption");
    }

    @Getter
    final String displayName = "Migrate S3 v1 calls that AWS's v2 migration does not handle";

    @Getter
    final String description = "Runs before `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2` and converts S3 calls it would leave " +
            "broken: `doesObjectExist`/`doesBucketExist[V2]` through a generated `S3Compat` that copies v1's logic, " +
            "`generatePresignedUrl(GeneratePresignedUrlRequest)` to `S3Presigner` through a generated `S3PresignerHolder`, " +
            "`CannedAccessControlList` to `ObjectCannedACL`/`BucketCannedACL`, or to v1's header string where v2 has no constant, " +
            "`listNextBatchOfObjects` loops to a paginator and `TransferManager` to `S3TransferManager` with the `S3AsyncClient` it " +
            "requires. POST and PATCH URLs, which `S3Presigner` cannot produce, are signed with SigV4 query parameters as v1 did.";

    transient S3ResidualChanges changes = new S3ResidualChanges(this);

    public static class Accumulator implements AwsMigrationScope.Limitable {
        @Nullable
        AwsMigrationScope scope;
        // Both shims share one package per project
        final Map<String, ShimTarget> locations = new HashMap<>();
        final Map<String, Set<String>> s3Compat = new HashMap<>();
        final Map<String, Set<String>> presignerHolder = new HashMap<>();
        // Projects where some presign request is built away from its call, so the request type itself is replaced
        final Map<String, Set<String>> presignRequest = new HashMap<>();
        final Set<String> existingClasses = new HashSet<>();
        final Map<String, Set<Path>> requirers = new HashMap<>();

        @Override
        public void limitTo(AwsMigrationScope scope) {
            this.scope = scope;
        }

        @Nullable
        ShimTarget target(Map<String, Set<String>> shim, String projectKey) {
            Set<String> methods = shim.get(projectKey);
            ShimTarget location = locations.get(projectKey);
            if (methods == null || location == null) {
                return null;
            }
            ShimTarget t = new ShimTarget(location.sourceRoot, location.pkg, location.main, location.markers);
            t.methods.addAll(methods);
            return t;
        }
    }

    static class ShimTarget {
        final Path sourceRoot;
        final String pkg;
        final boolean main;
        final Markers markers;
        final Set<String> methods = new TreeSet<>();

        ShimTarget(Path sourceRoot, String pkg, boolean main, Markers markers) {
            this.sourceRoot = sourceRoot;
            this.pkg = pkg;
            this.main = main;
            this.markers = markers;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return Preconditions.check(V2MigrationScope.javaFilesUsing("com.amazonaws.services.s3..*"), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                for (J.ClassDeclaration c : cu.getClasses()) {
                    if (c.getType() != null) {
                        acc.existingClasses.add(c.getType().getFullyQualifiedName());
                    }
                }
                return super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (V2MigrationScope.isMockitoStubbedOrVerified(getCursor())) {
                    return method;
                }
                if (GET_OBJECT_TO_FILE.matches(method)) {
                    if (!isPreparedDownload(method.getArguments().get(1))) {
                        need(acc.s3Compat, S3Shims.PREPARE_DOWNLOAD);
                    }
                } else if (DOES_OBJECT_EXIST.matches(method)) {
                    need(acc.s3Compat, S3Shims.DOES_OBJECT_EXIST);
                } else if (DOES_BUCKET_EXIST_V2.matches(method)) {
                    need(acc.s3Compat, S3Shims.DOES_BUCKET_EXIST_V2);
                } else if (DOES_BUCKET_EXIST.matches(method)) {
                    need(acc.s3Compat, S3Shims.DOES_BUCKET_EXIST);
                } else if (TM_BUILD.matches(method) && transferManagerBuilder(method) != null) {
                    need(acc.s3Compat, S3Shims.ASYNC_CLIENT);
                } else if (PRESIGN.matches(method) && method.getSelect() != null) {
                    PresignSite site = PresignSite.analyze(method, getCursor());
                    need(acc.presignerHolder, S3Shims.PRESIGNER);
                    if (site.unsupported != null) {
                        need(acc.presignerHolder, S3Shims.PRESIGN_REQUEST);
                        need(acc.presignRequest, S3Shims.PRESIGN_REQUEST_CLASS);
                    } else if (site.httpMethodConstant == null) {
                        need(acc.presignerHolder, S3Shims.PRESIGN);
                    }
                }
                return super.visitMethodInvocation(method, ctx);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                if (TM_NEW.matches(newClass) && newClass.getArguments().size() <= 2) {
                    need(acc.s3Compat, S3Shims.ASYNC_CLIENT);
                }
                return super.visitNewClass(newClass, ctx);
            }

            private void need(Map<String, Set<String>> shim, String shimMethod) {
                J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                if (cu.getPackageDeclaration() == null || cu.getSourcePath().getParent() == null) {
                    return;
                }
                String pkg = cu.getPackageDeclaration().getPackageName();
                boolean main = isMain(cu);
                String key = projectKey(cu);
                ShimTarget existing = acc.locations.get(key);
                // Main wins so that tests can see the shim, then the first package in path order keeps the choice stable
                if (existing == null || (!existing.main && main) ||
                    (existing.main == main && sourceRoot(cu).resolve(pkg.replace('.', '/')).toString()
                            .compareTo(existing.sourceRoot.resolve(existing.pkg.replace('.', '/')).toString()) < 0)) {
                    acc.locations.put(key, new ShimTarget(sourceRoot(cu), pkg, main, shimMarkers(cu)));
                }
                shim.computeIfAbsent(key, k -> new TreeSet<>()).add(shimMethod);
                acc.requirers.computeIfAbsent(key, k -> new HashSet<>()).add(cu.getSourcePath());
            }
        });
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        List<SourceFile> generated = new ArrayList<>();
        for (String key : acc.locations.keySet()) {
            if (acc.requirers.getOrDefault(key, emptySet()).stream().noneMatch(p -> acc.scope == null || acc.scope.includes(p))) {
                continue;
            }
            ShimTarget compat = acc.target(acc.s3Compat, key);
            if (compat != null) {
                generate(acc, compat, S3Shims.S3_COMPAT, S3Shims.s3Compat(compat.pkg, compat.methods), ctx).ifPresent(generated::add);
            }
            ShimTarget holder = acc.target(acc.presignerHolder, key);
            if (holder != null) {
                generate(acc, holder, S3Shims.PRESIGNER_HOLDER, S3Shims.presignerHolder(holder.pkg, holder.methods), ctx).ifPresent(generated::add);
            }
            ShimTarget request = acc.target(acc.presignRequest, key);
            if (request != null) {
                generate(acc, request, S3Shims.PRESIGN_REQUEST_CLASS, S3Shims.presignRequest(request.pkg), ctx).ifPresent(generated::add);
            }
        }
        return generated;
    }

    private Optional<SourceFile> generate(Accumulator acc, ShimTarget t, String className, String source, ExecutionContext ctx) {
        Path path = t.sourceRoot.resolve(t.pkg.replace('.', '/')).resolve(className + ".java");
        if (acc.existingClasses.contains(t.pkg + "." + className)) {
            return Optional.empty();
        }
        JavaParser.Builder<?, ?> parser = v2Parser(ctx);
        if (S3Shims.PRESIGNER_HOLDER.equals(className) && t.methods.contains(S3Shims.PRESIGN_REQUEST)) {
            parser.dependsOn(S3Shims.presignRequest(t.pkg));
        }
        return parser.build().parse(ctx, source)
                .findFirst()
                .map(sf -> sf.withSourcePath(path)
                        .withMarkers(t.markers));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return Preconditions.check(V2MigrationScope.javaFilesUsing("com.amazonaws..*"), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit compilationUnit, ExecutionContext ctx) {
                J.CompilationUnit cu = (J.CompilationUnit) new CannedAclVisitor().visitNonNull(compilationUnit, ctx);
                ShimTarget compat = acc.target(acc.s3Compat, projectKey(cu));
                ShimTarget holder = acc.target(acc.presignerHolder, projectKey(cu));
                cu = (J.CompilationUnit) new ResidualsVisitor(compat, holder).visitNonNull(cu, ctx);
                cu = (J.CompilationUnit) new HeaderConstantsVisitor().visitNonNull(cu, ctx);
                ShimTarget request = acc.target(acc.presignRequest, projectKey(cu));
                if (request != null) {
                    cu = (J.CompilationUnit) new ChangeType(PRESIGN_REQUEST, request.pkg + "." + S3Shims.PRESIGN_REQUEST_CLASS, true)
                            .getVisitor().visitNonNull(cu, ctx);
                }
                // The shims take the v2 method enum. Files that still call the 4-arg overload keep v1's HttpMethod, because
                // v2-migration recognises that call by the printed `HttpMethod.X` argument.
                if ((request != null || holder != null && holder.methods.contains(S3Shims.PRESIGN)) &&
                    new UsesMethod<>(PRESIGN_4_ARGS).visitNonNull(cu, ctx) == cu) {
                    cu = (J.CompilationUnit) new ChangeType(V1_HTTP_METHOD, "software.amazon.awssdk.http.SdkHttpMethod", true)
                            .getVisitor().visitNonNull(cu, ctx);
                }
                return cu;
            }
        });
    }

    /**
     * {@code CannedAccessControlList} is one enum in v1 and two in v2, so each constant is mapped by whether it is passed to a bucket
     * or an object operation. Remaining type references become {@code ObjectCannedACL}.
     */
    private class CannedAclVisitor extends JavaVisitor<ExecutionContext> {
        boolean bucketContext;

        @Override
        public J visitCompilationUnit(J.CompilationUnit compilationUnit, ExecutionContext ctx) {
            J cu = super.visitCompilationUnit(compilationUnit, ctx);
            if (cu != compilationUnit && !bucketContext) {
                cu = (J.CompilationUnit) new ChangeType(CANNED_ACL, "software.amazon.awssdk.services.s3.model.ObjectCannedACL", true)
                        .getVisitor().visitNonNull(cu, ctx);
            }
            return cu;
        }

        @Override
        public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
            JavaType.Method mt = m.getMethodType();
            // v2-migration renames withCannedAcl to withAcl only while its parameter is still CannedAccessControlList
            if (mt != null && mt.getDeclaringType().getFullyQualifiedName().startsWith(V1_MODEL) &&
                ("withCannedAcl".equals(m.getSimpleName()) || "setCannedAcl".equals(m.getSimpleName())) &&
                mt.getParameterTypes().size() == 1 && TypeUtils.isOfClassType(mt.getParameterTypes().get(0), CANNED_ACL)) {
                String name = m.getSimpleName().startsWith("with") ? "withAcl" : "setAcl";
                JavaType.Method renamed = mt.withName(name);
                return m.withName(m.getName().withSimpleName(name).withType(renamed)).withMethodType(renamed);
            }
            return m;
        }

        @Override
        public J visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
            J.FieldAccess fa = (J.FieldAccess) super.visitFieldAccess(fieldAccess, ctx);
            if (!TypeUtils.isOfClassType(fa.getTarget().getType(), CANNED_ACL) || fa.getName().getFieldType() == null) {
                return fa;
            }
            boolean bucket = isBucketOperation(getCursor());
            String v2Enum = bucket ? "BucketCannedACL" : "ObjectCannedACL";
            String constant = (bucket ? BUCKET_ACL : OBJECT_ACL).get(fa.getSimpleName());
            String sourcePath = getCursor().firstEnclosingOrThrow(SourceFile.class).getSourcePath().toString();
            String comment = null;
            String value = CANNED_ACL_VALUES.get(fa.getSimpleName());
            if (constant == null && value != null && isArgument(getCursor())) {
                // v2's request builders also take the canned ACL as a string, so v1's header value is sent unchanged
                changes.insertRow(ctx, new S3ResidualChanges.Row(sourcePath, "CannedAccessControlList." + fa.getSimpleName(), "migrated",
                        "No " + v2Enum + " constant; passed as the string \"" + value + "\""));
                return new J.Literal(Tree.randomId(), fa.getPrefix(), fa.getMarkers(), value, "\"" + value + "\"", null, JavaType.Primitive.String);
            }
            if (constant == null && !bucket && "LogDeliveryWrite".equals(fa.getSimpleName())) {
                // S3 accepts log-delivery-write only on buckets, so v1 sent a header S3 rejected for objects
                constant = "BUCKET_OWNER_FULL_CONTROL";
                comment = " S3 accepts log-delivery-write only for buckets; v2 ObjectCannedACL has no such constant, so the " +
                          "object is given to the bucket owner instead ";
            }
            if (constant == null) {
                bucketContext |= bucket;
                changes.insertRow(ctx, new S3ResidualChanges.Row(sourcePath, "CannedAccessControlList." + fa.getSimpleName(), "unmigrated",
                        "No " + v2Enum + " constant"));
                return SearchResult.found(fa, "v2 " + v2Enum + " has no equivalent of " + fa.getSimpleName());
            }
            String fqn = "software.amazon.awssdk.services.s3.model." + v2Enum;
            maybeRemoveImport(CANNED_ACL);
            maybeAddImport(fqn);
            J.FieldAccess replaced = JavaTemplate.builder(v2Enum + "." + constant)
                    .imports(fqn)
                    .javaParser(v2Parser(ctx))
                    .build()
                    .apply(getCursor(), fa.getCoordinates().replace());
            return comment == null ? replaced :
                    Comments.of(new Cursor(getCursor().getParentOrThrow(), replaced)).multilineComment(comment, Comments.Placement.BEFORE, " ");
        }

        private boolean isArgument(Cursor cursor) {
            Object parent = cursor.getParentTreeCursor().getValue();
            return parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getArguments().contains(cursor.<Expression>getValue()) ||
                   parent instanceof J.NewClass && ((J.NewClass) parent).getArguments().contains(cursor.<Expression>getValue());
        }

        private boolean isBucketOperation(Cursor cursor) {
            for (Iterator<Object> it = cursor.getPath(); it.hasNext(); ) {
                Object o = it.next();
                if (o instanceof J.MethodInvocation) {
                    J.MethodInvocation m = (J.MethodInvocation) o;
                    JavaType.FullyQualified declaring = m.getMethodType() == null ? null : m.getMethodType().getDeclaringType();
                    return m.getSimpleName().contains("Bucket") ||
                           (declaring != null && declaring.getClassName().contains("Bucket"));
                } else if (o instanceof J.NewClass) {
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(((J.NewClass) o).getType());
                    return type != null && type.getClassName().contains("Bucket");
                } else if (o instanceof Statement && !(o instanceof Expression)) {
                    return false;
                }
            }
            return false;
        }
    }

    /**
     * v1's {@code Headers} constants are plain header names, so they become the string they stood for.
     */
    private static class HeaderConstantsVisitor extends JavaVisitor<ExecutionContext> {
        @Override
        public J visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
            J j = super.visitFieldAccess(fieldAccess, ctx);
            return j instanceof J.FieldAccess ? headerLiteral((J.FieldAccess) j, ((J.FieldAccess) j).getName()) : j;
        }

        @Override
        public J visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
            return getCursor().getParentTreeCursor().getValue() instanceof J.FieldAccess ||
                   getCursor().getParentTreeCursor().getValue() instanceof J.Import ? identifier : headerLiteral(identifier, identifier);
        }

        private J headerLiteral(Expression e, J.Identifier name) {
            String header = V1_HEADERS.get(name.getSimpleName());
            if (header == null || name.getFieldType() == null || !TypeUtils.isOfClassType(name.getFieldType().getOwner(), V1_HEADERS_CLASS)) {
                return e;
            }
            maybeRemoveImport(V1_HEADERS_CLASS);
            return new J.Literal(Tree.randomId(), e.getPrefix(), e.getMarkers(), header, "\"" + header + "\"", null, JavaType.Primitive.String);
        }
    }

    private class ResidualsVisitor extends JavaVisitor<ExecutionContext> {
        private final @Nullable ShimTarget compat;
        private final @Nullable ShimTarget holder;
        private final Set<UUID> removeStatements = new HashSet<>();
        private final Set<UUID> unwrapDoWhile = new HashSet<>();

        ResidualsVisitor(@Nullable ShimTarget compat, @Nullable ShimTarget holder) {
            this.compat = compat;
            this.holder = holder;
        }

        @Override
        public J visitBlock(J.Block block, ExecutionContext ctx) {
            J.Block b = (J.Block) super.visitBlock(block, ctx);
            if (removeStatements.isEmpty() && unwrapDoWhile.isEmpty()) {
                return b;
            }
            Set<UUID> unwrapped = new HashSet<>();
            J.Block edited = b.withStatements(ListUtils.map(b.getStatements(), s -> {
                if (removeStatements.contains(s.getId())) {
                    return null;
                }
                if (s instanceof J.DoWhileLoop && unwrapDoWhile.contains(s.getId())) {
                    Statement loop = ((J.Block) ((J.DoWhileLoop) s).getBody()).getStatements().get(0).withPrefix(s.getPrefix());
                    unwrapped.add(loop.getId());
                    return loop;
                }
                return s;
            }));
            // The loop moved out of the do/while, one level of indentation up
            return edited.withStatements(ListUtils.map(edited.getStatements(), s -> unwrapped.contains(s.getId()) ?
                    autoFormat(s, ctx, new Cursor(getCursor().getParentOrThrow(), edited)) : s));
        }

        @Override
        public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J j = super.visitMethodInvocation(method, ctx);
            if (!(j instanceof J.MethodInvocation)) {
                return j;
            }
            J.MethodInvocation m = (J.MethodInvocation) j;
            String sourcePath = getCursor().firstEnclosingOrThrow(SourceFile.class).getSourcePath().toString();
            boolean doesExist = DOES_OBJECT_EXIST.matches(m) || DOES_BUCKET_EXIST_V2.matches(m) || DOES_BUCKET_EXIST.matches(m);
            if (doesExist || PRESIGN.matches(m)) {
                if (V2MigrationScope.isMockitoStubbedOrVerified(getCursor())) {
                    if (doesExist) {
                        // CompleteV2MockitoStubs stubs the call S3Compat makes once the client is v2
                        return m;
                    }
                    changes.insertRow(ctx, new S3ResidualChanges.Row(sourcePath, m.getSimpleName(), "unmigrated", "Mockito stub"));
                    return SearchResult.found(m, "Stubs a v1-only method: stub the v2 call that " + S3Shims.PRESIGNER_HOLDER +
                            " makes instead");
                }
                if (m.getSelect() == null) {
                    return m;
                }
            }
            if (doesExist && compat != null) {
                changes.insertRow(ctx, new S3ResidualChanges.Row(sourcePath, m.getSimpleName(), "migrated",
                        S3Shims.S3_COMPAT + "." + m.getSimpleName() + " copies v1's status code handling"));
                String args = m.getArguments().size() == 2 ? ", #{any(String)}, #{any(String)})" : ", #{any(String)})";
                List<Object> params = new ArrayList<>();
                params.add(requireNonNull(m.getSelect()));
                params.addAll(m.getArguments());
                return shimCall(compat, S3Shims.S3_COMPAT, S3Shims.S3_COMPAT + "." + m.getSimpleName() +
                        "(#{any(" + V2_S3_CLIENT + ")}" + args, params, ctx);
            }
            if (PRESIGN.matches(method) && holder != null) {
                return presign(m, sourcePath, ctx);
            }
            if (GET_OBJECT_TO_FILE.matches(m) && compat != null && !V2MigrationScope.isMockitoStubbedOrVerified(getCursor())) {
                return prepareDownload(m, compat, sourcePath, ctx);
            }
            if (TM_BUILD.matches(method) && compat != null) {
                TransferManagerBuilderChain chain = transferManagerBuilder(method);
                if (chain != null) {
                    changes.insertRow(ctx, new S3ResidualChanges.Row(sourcePath, "TransferManagerBuilder", "migrated",
                            "S3TransferManager on a multipart S3AsyncClient built from the sync client's region, credentials and endpoint" +
                            (chain.executor != null ? "; the executor now completes futures and runs directory transfers instead of part uploads" : "")));
                    return transferManager(chain.s3Client, chain.executor, ctx);
                }
            }
            return m;
        }

        @Override
        public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
            J j = super.visitNewClass(newClass, ctx);
            if (!(j instanceof J.NewClass)) {
                return j;
            }
            J.NewClass n = (J.NewClass) j;
            if (TM_NEW.matches(n) && compat != null && !n.getArguments().isEmpty() && n.getArguments().size() <= 2 &&
                TypeUtils.isAssignableTo(AMAZON_S3, n.getArguments().get(0).getType())) {
                String sourcePath = getCursor().firstEnclosingOrThrow(SourceFile.class).getSourcePath().toString();
                Expression executor = n.getArguments().size() == 2 ? n.getArguments().get(1) : null;
                changes.insertRow(ctx, new S3ResidualChanges.Row(sourcePath, "new TransferManager", "migrated",
                        "S3TransferManager on a multipart S3AsyncClient built from the sync client's region, credentials and endpoint" +
                        (executor != null ? "; the executor now completes futures and runs directory transfers instead of part " +
                                "uploads, and closing the transfer manager no longer shuts it down" : "")));
                return transferManager(n.getArguments().get(0), executor, ctx);
            }
            return n;
        }

        private J transferManager(Expression s3, @Nullable Expression executor, ExecutionContext ctx) {
            maybeRemoveImport("com.amazonaws.services.s3.transfer.TransferManagerBuilder");
            maybeAddImport("software.amazon.awssdk.transfer.s3.S3TransferManager");
            List<Object> params = new ArrayList<>();
            params.add(s3);
            String code = "S3TransferManager.builder().s3Client(" + S3Shims.S3_COMPAT + "." + S3Shims.ASYNC_CLIENT +
                          "(#{any(" + V2_S3_CLIENT + ")}))";
            if (executor != null) {
                code += ".executor(#{any(java.util.concurrent.Executor)})";
                params.add(executor);
            }
            return shimCall(requireNonNull(compat), S3Shims.S3_COMPAT, code + ".build()", params, ctx,
                    "software.amazon.awssdk.transfer.s3.S3TransferManager");
        }

        private J presign(J.MethodInvocation m, String sourcePath, ExecutionContext ctx) {
            PresignSite site = PresignSite.analyze(m, getCursor());
            if (site.unsupported != null) {
                changes.insertRow(ctx, new S3ResidualChanges.Row(sourcePath, "generatePresignedUrl(GeneratePresignedUrlRequest)",
                        "migrated", "The request is built elsewhere (" + site.unsupported + "), so it becomes a generated " +
                                    S3Shims.PRESIGN_REQUEST_CLASS + " signed by " + S3Shims.PRESIGNER_HOLDER + ".presign"));
                return shimCall(requireNonNull(holder), S3Shims.PRESIGNER_HOLDER, S3Shims.PRESIGNER_HOLDER +
                        ".presign(#{any(" + V2_S3_CLIENT + ")}, #{any()})", Arrays.asList(m.getSelect(), m.getArguments().get(0)), ctx);
            }
            List<Object> params = new ArrayList<>();
            params.add(requireNonNull(m.getSelect()));
            String duration;
            if (site.expiration != null) {
                maybeAddImport("java.time.Instant");
                duration = "Duration.between(Instant.now(), #{any(java.util.Date)}.toInstant())";
            } else {
                // v1 signs for 15 minutes when no expiration is set
                duration = "Duration.ofMinutes(15)";
            }
            maybeAddImport("java.time.Duration");
            String code;
            List<String> imports = new ArrayList<>(Arrays.asList("java.time.Duration", "java.time.Instant"));
            if (site.httpMethodConstant == null) {
                code = S3Shims.PRESIGNER_HOLDER + ".presign(#{any(" + V2_S3_CLIENT + ")}, #{any(software.amazon.awssdk.http.SdkHttpMethod)}, " +
                       "#{any(String)}, #{any(String)}, " + duration + ")";
                params.add(requireNonNull(site.httpMethod));
                params.add(site.bucket);
                params.add(site.key);
                if (site.expiration != null) {
                    params.add(site.expiration);
                }
            } else {
                String op = site.httpMethodConstant.charAt(0) + site.httpMethodConstant.substring(1).toLowerCase(Locale.ROOT);
                StringBuilder request = new StringBuilder("objectRequest -> objectRequest.bucket(#{any(String)}).key(#{any(String)})");
                List<Object> requestParams = new ArrayList<>(Arrays.asList(site.bucket, site.key));
                if (site.contentType != null) {
                    request.append(".contentType(#{any(String)})");
                    requestParams.add(site.contentType);
                }
                if (!site.queryParameters.isEmpty()) {
                    // v1 signed these as query parameters, so the client using the URL must not have to send them as headers
                    request.append(".overrideConfiguration(override -> override");
                    for (Map.Entry<String, Expression> e : site.queryParameters) {
                        request.append(".putRawQueryParameter(\"").append(e.getKey()).append("\", #{any(String)})");
                        requestParams.add(e.getValue());
                    }
                    request.append(")");
                }
                code = S3Shims.PRESIGNER_HOLDER + ".presigner(#{any(" + V2_S3_CLIENT + ")})\n.presign" + op + "Object(presignRequest -> " +
                       "presignRequest\n.signatureDuration(" + duration + ")\n." + op.toLowerCase(Locale.ROOT) + "ObjectRequest(" + request + "))\n.url()";
                if (site.expiration != null) {
                    params.add(site.expiration);
                }
                params.addAll(requestParams);
            }
            removeStatements.addAll(site.removeStatements);
            maybeRemoveImport(PRESIGN_REQUEST);
            maybeRemoveImport("com.amazonaws.services.s3.Headers");
            if (site.httpMethodConstant != null) {
                maybeRemoveImport(V1_HTTP_METHOD);
            }
            changes.insertRow(ctx, new S3ResidualChanges.Row(sourcePath, "generatePresignedUrl(GeneratePresignedUrlRequest)", "migrated",
                    "S3Presigner from " + S3Shims.PRESIGNER_HOLDER + (site.expiration == null ? "; v1's default 15 minute expiry made explicit" : "") +
                    (site.httpMethodConstant == null ? "; POST and PATCH are signed directly with SigV4, as S3Presigner has neither" : "")));
            J presigned = shimCall(requireNonNull(holder), S3Shims.PRESIGNER_HOLDER, code, params, ctx, imports.toArray(new String[0]));
            return autoFormat(presigned, ctx, getCursor().getParentOrThrow());
        }

        @Override
        public J visitForEachLoop(J.ForEachLoop forLoop, ExecutionContext ctx) {
            J.ForEachLoop f = (J.ForEachLoop) super.visitForEachLoop(forLoop, ctx);
            Expression iterable = f.getControl().getIterable();
            if (!(iterable instanceof J.MethodInvocation) || !GET_OBJECT_SUMMARIES.matches(iterable) ||
                !(((J.MethodInvocation) iterable).getSelect() instanceof J.Identifier)) {
                return f;
            }
            String listing = ((J.Identifier) requireNonNull(((J.MethodInvocation) iterable).getSelect())).getSimpleName();
            Cursor doBodyCursor = getCursor().getParentTreeCursor();
            if (!(doBodyCursor.getValue() instanceof J.Block) || !(doBodyCursor.getParentTreeCursor().getValue() instanceof J.DoWhileLoop)) {
                return f;
            }
            J.Block doBody = doBodyCursor.getValue();
            J.DoWhileLoop doWhile = doBodyCursor.getParentTreeCursor().getValue();
            Cursor outerCursor = doBodyCursor.getParentTreeCursor().getParentTreeCursor();
            if (!(outerCursor.getValue() instanceof J.Block)) {
                return f;
            }
            J.Block outer = outerCursor.getValue();
            // Canonical shape: process the page, fetch the next one, loop while it is truncated
            if (doBody.getStatements().size() != 2 || doBody.getStatements().get(0) != forLoop ||
                !isAssignmentOf(doBody.getStatements().get(1), listing, LIST_NEXT_BATCH) ||
                !(doWhile.getWhileCondition().getTree() instanceof J.MethodInvocation) ||
                !IS_TRUNCATED.matches(doWhile.getWhileCondition().getTree()) ||
                !isIdentifier(((J.MethodInvocation) doWhile.getWhileCondition().getTree()).getSelect(), listing) ||
                countReferences(forLoop.getBody(), listing) > 0) {
                return f;
            }
            int doIndex = outer.getStatements().indexOf(doWhile);
            if (doIndex < 1) {
                return f;
            }
            Statement init = outer.getStatements().get(doIndex - 1);
            J.MethodInvocation listObjects = listObjectsInitializer(init, listing);
            if (listObjects == null || listObjects.getSelect() == null ||
                listObjects.getArguments().isEmpty() || listObjects.getArguments().size() > 2 ||
                !listObjects.getArguments().stream().allMatch(a -> TypeUtils.isString(a.getType()))) {
                return f;
            }
            Set<UUID> remove = new HashSet<>();
            remove.add(init.getId());
            for (int i = 0; i < outer.getStatements().size(); i++) {
                Statement s = outer.getStatements().get(i);
                if (i == doIndex || i == doIndex - 1) {
                    continue;
                }
                if (countReferences(s, listing) > 0) {
                    if (i < doIndex && isUnusedDeclaration(s, listing)) {
                        remove.add(s.getId());
                    } else {
                        return f;
                    }
                }
            }
            removeStatements.addAll(remove);
            unwrapDoWhile.add(doWhile.getId());
            String sourcePath = getCursor().firstEnclosingOrThrow(SourceFile.class).getSourcePath().toString();
            changes.insertRow(ctx, new S3ResidualChanges.Row(sourcePath, "listNextBatchOfObjects loop", "bug fixed",
                    "Paginator over ListObjectsV2. The v1 do/while fetched the next page before testing isTruncated, so the last page " +
                    "was never processed"));
            maybeRemoveImport(V1_MODEL + "ObjectListing");
            maybeAddImport("software.amazon.awssdk.services.s3.model.ListObjectsV2Request");
            List<Object> params = new ArrayList<>();
            params.add(listObjects.getSelect());
            params.addAll(listObjects.getArguments());
            String code = "#{any(" + V2_S3_CLIENT + ")}.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(#{any(String)})" +
                          (listObjects.getArguments().size() == 2 ? ".prefix(#{any(String)})" : "") + ".build()).contents()";
            Expression paginated = JavaTemplate.builder(code)
                    .imports("software.amazon.awssdk.services.s3.model.ListObjectsV2Request")
                    .javaParser(v2Parser(ctx))
                    .build()
                    .apply(new Cursor(getCursor(), iterable), iterable.getCoordinates().replace(), params.toArray());
            return f.withControl(f.getControl().withIterable(paginated));
        }

        /**
         * v2-migration turns the file into {@code file.toPath()}, so the prepared file keeps the call a v1 one it converts.
         */
        private J prepareDownload(J.MethodInvocation m, ShimTarget compat, String sourcePath, ExecutionContext ctx) {
            Expression file = m.getArguments().get(1);
            if (isPreparedDownload(file)) {
                return m;
            }
            changes.insertRow(ctx, new S3ResidualChanges.Row(sourcePath, "getObject(GetObjectRequest, File)", "migrated",
                    S3Shims.S3_COMPAT + ".prepareDownload creates the directories and replaces the file, as v1 did"));
            String fqn = compat.pkg + "." + S3Shims.S3_COMPAT;
            maybeAddImport(fqn);
            Expression prepared = JavaTemplate.builder(S3Shims.S3_COMPAT + ".prepareDownload(#{any(java.io.File)})")
                    .imports(fqn)
                    .javaParser(v2Parser(ctx).dependsOn(S3Shims.s3Compat(compat.pkg, singleton(S3Shims.PREPARE_DOWNLOAD))))
                    .build()
                    .apply(new Cursor(getCursor(), file), file.getCoordinates().replace(), file);
            return m.withArguments(ListUtils.map(m.getArguments(), (i, a) -> i == 1 ? prepared : a));
        }

        private J shimCall(ShimTarget target, String className, String code, List<Object> params, ExecutionContext ctx, String... imports) {
            String fqn = target.pkg + "." + className;
            maybeAddImport(fqn);
            for (String i : imports) {
                maybeAddImport(i);
            }
            String stub = S3Shims.S3_COMPAT.equals(className) ?
                    S3Shims.s3Compat(target.pkg, new HashSet<>(Arrays.asList(S3Shims.DOES_OBJECT_EXIST, S3Shims.DOES_BUCKET_EXIST,
                            S3Shims.DOES_BUCKET_EXIST_V2, S3Shims.ASYNC_CLIENT))) :
                    S3Shims.presignerHolder(target.pkg, new HashSet<>(Arrays.asList(S3Shims.PRESIGN, S3Shims.PRESIGN_REQUEST)));
            List<String> allImports = new ArrayList<>(Arrays.asList(imports));
            allImports.add(fqn);
            return JavaTemplate.builder(code)
                    .imports(allImports.toArray(new String[0]))
                    .javaParser(v2Parser(ctx).dependsOn(stub, S3Shims.presignRequest(target.pkg)))
                    .build()
                    .apply(getCursor(), getCursor().getValue() instanceof J.NewClass ?
                            ((J.NewClass) getCursor().getValue()).getCoordinates().replace() :
                            ((J.MethodInvocation) getCursor().getValue()).getCoordinates().replace(), params.toArray());
        }
    }

    private static boolean isPreparedDownload(Expression file) {
        return file instanceof J.MethodInvocation && S3Shims.PREPARE_DOWNLOAD.equals(((J.MethodInvocation) file).getSimpleName());
    }

    static class TransferManagerBuilderChain {
        final Expression s3Client;
        final @Nullable Expression executor;

        TransferManagerBuilderChain(Expression s3Client, @Nullable Expression executor) {
            this.s3Client = s3Client;
            this.executor = executor;
        }
    }

    /**
     * {@code TransferManagerBuilder.standard().withS3Client(s3)[.withExecutorFactory(() -> executor)].build()}; other settings have no
     * one-to-one v2 equivalent and are left to v2-migration's comments.
     */
    private static @Nullable TransferManagerBuilderChain transferManagerBuilder(J.MethodInvocation build) {
        Expression s3 = null;
        Expression executor = null;
        Expression e = build.getSelect();
        while (e instanceof J.MethodInvocation && !TM_STANDARD.matches(e)) {
            J.MethodInvocation m = (J.MethodInvocation) e;
            if ("withS3Client".equals(m.getSimpleName()) && s3 == null) {
                s3 = m.getArguments().get(0);
            } else if ("withExecutorFactory".equals(m.getSimpleName()) && m.getArguments().get(0) instanceof J.Lambda &&
                       ((J.Lambda) m.getArguments().get(0)).getBody() instanceof Expression) {
                executor = (Expression) ((J.Lambda) m.getArguments().get(0)).getBody();
            } else {
                return null;
            }
            e = m.getSelect();
        }
        return TM_STANDARD.matches(e) && s3 != null ? new TransferManagerBuilderChain(s3, executor) : null;
    }

    /**
     * A {@code GeneratePresignedUrlRequest} built inline, or in a local variable that is only configured and then passed to
     * {@code generatePresignedUrl} in the same block.
     */
    static class PresignSite {
        @Nullable String unsupported;

        Expression bucket;
        Expression key;

        @Nullable Expression httpMethod;

        @Nullable String httpMethodConstant = "GET";

        @Nullable Expression expiration;

        @Nullable Expression contentType;

        final List<Map.Entry<String, Expression>> queryParameters = new ArrayList<>();
        final Set<UUID> removeStatements = new HashSet<>();

        private static PresignSite unsupported(String reason) {
            PresignSite site = new PresignSite();
            site.unsupported = reason;
            return site;
        }

        @SuppressWarnings("DataFlowIssue")
        static PresignSite analyze(J.MethodInvocation call, Cursor callCursor) {
            if (call.getSelect() == null) {
                return unsupported("called on an implicit this");
            }
            PresignSite site = new PresignSite();
            Expression arg = call.getArguments().get(0).unwrap();
            List<J.MethodInvocation> mutators = new ArrayList<>();
            if (arg instanceof J.Identifier) {
                String name = ((J.Identifier) arg).getSimpleName();
                Cursor statementCursor = callCursor;
                while (statementCursor.getParent() != null && !(statementCursor.getParentTreeCursor().getValue() instanceof J.Block)) {
                    statementCursor = statementCursor.getParentTreeCursor();
                }
                if (!(statementCursor.getValue() instanceof Statement) || statementCursor.getParent() == null) {
                    return unsupported("the request is not built in the calling block");
                }
                J.Block block = statementCursor.getParentTreeCursor().getValue();
                int callIndex = block.getStatements().indexOf(statementCursor.getValue());
                int declIndex = -1;
                for (int i = 0; i < callIndex; i++) {
                    Statement s = block.getStatements().get(i);
                    if (s instanceof J.VariableDeclarations && ((J.VariableDeclarations) s).getVariables().size() == 1 &&
                        ((J.VariableDeclarations) s).getVariables().get(0).getSimpleName().equals(name)) {
                        declIndex = i;
                    }
                }
                if (declIndex < 0 || countReferences(block.getStatements().get(callIndex), name) != 1) {
                    return unsupported("the request is not built in the calling block");
                }
                J.VariableDeclarations decl = (J.VariableDeclarations) block.getStatements().get(declIndex);
                site.removeStatements.add(decl.getId());
                Expression init = decl.getVariables().get(0).getInitializer();
                if (init == null) {
                    return unsupported("the request is not built in the calling block");
                }
                arg = init;
                for (int i = declIndex + 1; i < block.getStatements().size(); i++) {
                    Statement s = block.getStatements().get(i);
                    if (i == callIndex || countReferences(s, name) == 0) {
                        continue;
                    }
                    if (i < callIndex && s instanceof J.MethodInvocation && isIdentifier(((J.MethodInvocation) s).getSelect(), name) &&
                        countReferences(s, name) == 1) {
                        mutators.add((J.MethodInvocation) s);
                        site.removeStatements.add(s.getId());
                    } else {
                        return unsupported("the request is used outside of generatePresignedUrl");
                    }
                }
            }
            List<J.MethodInvocation> chain = new ArrayList<>();
            while (arg instanceof J.MethodInvocation && ((J.MethodInvocation) arg).getSelect() != null &&
                   TypeUtils.isOfClassType(((J.MethodInvocation) arg).getType(), PRESIGN_REQUEST)) {
                chain.add(0, (J.MethodInvocation) arg);
                arg = ((J.MethodInvocation) arg).getSelect();
            }
            if (!(arg instanceof J.NewClass) || !TypeUtils.isOfClassType(((J.NewClass) arg).getType(), PRESIGN_REQUEST)) {
                return unsupported("the request comes from elsewhere");
            }
            List<Expression> ctorArgs = ((J.NewClass) arg).getArguments();
            if (ctorArgs.size() != 2 && ctorArgs.size() != 3) {
                return unsupported("unsupported GeneratePresignedUrlRequest constructor");
            }
            site.bucket = ctorArgs.get(0);
            site.key = ctorArgs.get(1);
            site.httpMethod = ctorArgs.size() == 3 ? ctorArgs.get(2) : null;
            chain.addAll(mutators);
            for (J.MethodInvocation m : chain) {
                Expression a = m.getArguments().isEmpty() ? null : m.getArguments().get(0);
                switch (m.getSimpleName()) {
                    case "withMethod":
                    case "setMethod":
                        site.httpMethod = a;
                        break;
                    case "withExpiration":
                    case "setExpiration":
                        site.expiration = a;
                        break;
                    case "withContentType":
                    case "setContentType":
                        site.contentType = a;
                        break;
                    case "withBucketName":
                    case "setBucketName":
                        site.bucket = a;
                        break;
                    case "withKey":
                    case "setKey":
                        site.key = a;
                        break;
                    case "addRequestParameter":
                        String header = headerName(m.getArguments().get(0));
                        if (header == null) {
                            return unsupported("request parameter name is not a constant");
                        }
                        site.queryParameters.add(new AbstractMap.SimpleEntry<>(header, m.getArguments().get(1)));
                        break;
                    default:
                        return unsupported(m.getSimpleName() + " has no S3Presigner equivalent here");
                }
            }
            if (site.httpMethod != null) {
                site.httpMethodConstant = null;
                Expression hm = site.httpMethod.unwrap();
                J.Identifier constant = hm instanceof J.FieldAccess ? ((J.FieldAccess) hm).getName() : hm instanceof J.Identifier ? (J.Identifier) hm : null;
                if (constant != null && constant.getFieldType() != null &&
                    TypeUtils.isOfClassType(constant.getFieldType().getOwner(), V1_HTTP_METHOD)) {
                    site.httpMethodConstant = constant.getSimpleName();
                }
            }
            if (site.httpMethodConstant != null && !Arrays.asList("GET", "PUT", "DELETE", "HEAD").contains(site.httpMethodConstant)) {
                return unsupported("S3Presigner has no " + site.httpMethodConstant + " presigning");
            }
            if (site.contentType != null && !"PUT".equals(site.httpMethodConstant)) {
                return unsupported("a content type is only supported for PUT");
            }
            if (site.httpMethodConstant == null && !site.queryParameters.isEmpty()) {
                return unsupported("request parameters with an HTTP method only known at run time");
            }
            if (("DELETE".equals(site.httpMethodConstant) || "HEAD".equals(site.httpMethodConstant)) && !site.queryParameters.isEmpty()) {
                return unsupported("request parameters on " + site.httpMethodConstant);
            }
            return site;
        }

        private static @Nullable String headerName(Expression e) {
            Expression u = e.unwrap();
            if (u instanceof J.Literal && ((J.Literal) u).getValue() instanceof String) {
                return (String) ((J.Literal) u).getValue();
            }
            J.Identifier name = u instanceof J.FieldAccess ? ((J.FieldAccess) u).getName() : u instanceof J.Identifier ? (J.Identifier) u : null;
            if (name != null && name.getFieldType() != null &&
                TypeUtils.isOfClassType(name.getFieldType().getOwner(), "com.amazonaws.services.s3.Headers")) {
                return V1_HEADERS.get(name.getSimpleName());
            }
            return null;
        }
    }

    private static boolean isAssignmentOf(Statement s, String variable, MethodMatcher matcher) {
        return s instanceof J.Assignment && isIdentifier(((J.Assignment) s).getVariable(), variable) &&
               ((J.Assignment) s).getAssignment() instanceof J.MethodInvocation && matcher.matches(((J.Assignment) s).getAssignment());
    }

    private static J.@Nullable MethodInvocation listObjectsInitializer(Statement s, String variable) {
        if (isAssignmentOf(s, variable, LIST_OBJECTS)) {
            return (J.MethodInvocation) ((J.Assignment) s).getAssignment();
        }
        if (s instanceof J.VariableDeclarations && ((J.VariableDeclarations) s).getVariables().size() == 1) {
            J.VariableDeclarations.NamedVariable v = ((J.VariableDeclarations) s).getVariables().get(0);
            if (v.getSimpleName().equals(variable) && v.getInitializer() instanceof J.MethodInvocation && LIST_OBJECTS.matches(v.getInitializer())) {
                return (J.MethodInvocation) v.getInitializer();
            }
        }
        return null;
    }

    private static boolean isUnusedDeclaration(Statement s, String variable) {
        if (!(s instanceof J.VariableDeclarations) || ((J.VariableDeclarations) s).getVariables().size() != 1) {
            return false;
        }
        J.VariableDeclarations.NamedVariable v = ((J.VariableDeclarations) s).getVariables().get(0);
        return v.getSimpleName().equals(variable) &&
               (v.getInitializer() == null || J.Literal.isLiteralValue(v.getInitializer(), null));
    }

    private static boolean isIdentifier(@Nullable Expression e, String name) {
        return e instanceof J.Identifier && ((J.Identifier) e).getSimpleName().equals(name);
    }

    private static int countReferences(J tree, String variable) {
        int[] count = {0};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                if (identifier.getSimpleName().equals(variable) && identifier.getFieldType() != null) {
                    count[0]++;
                }
                return identifier;
            }
        }.visit(tree, 0);
        return count[0];
    }

    private static String projectKey(SourceFile sf) {
        return sf.getMarkers().findFirst(JavaProject.class).map(JavaProject::getProjectName).orElse("");
    }

    private static boolean isMain(SourceFile sf) {
        return sf.getMarkers().findFirst(JavaSourceSet.class).map(s -> "main".equals(s.getName()))
                .orElse(!sf.getSourcePath().toString().replace('\\', '/').contains("/test/"));
    }

    private static Path sourceRoot(J.CompilationUnit cu) {
        Path dir = requireNonNull(cu.getSourcePath().getParent());
        if (cu.getPackageDeclaration() != null) {
            for (String ignored : cu.getPackageDeclaration().getPackageName().split("\\.")) {
                Path parent = dir.getParent();
                dir = parent == null ? java.nio.file.Paths.get("") : parent;
            }
        }
        return dir;
    }

    // Accumulators must not hold classpaths, so the source set is kept without one
    private static Markers shimMarkers(J.CompilationUnit cu) {
        List<Marker> kept = new ArrayList<>();
        for (Marker m : cu.getMarkers().getMarkers()) {
            if (m instanceof JavaSourceSet) {
                kept.add(new JavaSourceSet(m.getId(), ((JavaSourceSet) m).getName(), emptyList(), emptyMap()));
            } else if (!(m instanceof Heavyweight) && !(m instanceof SearchResult) && !(m instanceof Markup)) {
                kept.add(m);
            }
        }
        return Markers.build(kept);
    }

    static JavaParser.Builder<?, ?> v2Parser(ExecutionContext ctx) {
        return JavaParser.fromJavaVersion().classpathFromResources(ctx,
                "s3-2", "s3-transfer-manager", "sdk-core", "aws-core", "regions", "auth", "utils", "http-client-spi", "identity-spi",
                "http-auth-aws", "http-auth-spi");
    }
}

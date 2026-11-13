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
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.*;

/**
 * v1's {@code ObjectMetadata} is a mutable bag of headers that code builds in one method and hands to another. v2 has no such
 * type: v2-migration rewrites it to an immutable {@code HeadObjectResponse}, which cannot be filled in and is never sent, so the
 * metadata is silently dropped. Where the metadata crosses a method or uses accessors v2-migration cannot fold into a request,
 * the code keeps a generated mutable {@code ObjectMetadata} with v1's accessors, and the v1 calls that send or return metadata
 * go through its static helpers.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateS3ObjectMetadata extends ScanningRecipe<MigrateS3ObjectMetadata.Accumulator> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    private static final String V1_MODEL = "com.amazonaws.services.s3.model.";
    private static final String V1_METADATA = V1_MODEL + "ObjectMetadata";
    private static final String V1_S3 = "com.amazonaws.services.s3.AmazonS3";
    private static final String V1_TRANSFER = "com.amazonaws.services.s3.transfer.";
    private static final String V1_PUT_REQUEST = V1_MODEL + "PutObjectRequest";
    private static final String V1_COPY_REQUEST = V1_MODEL + "CopyObjectRequest";
    private static final String STREAMS = "S3_OBJECT_METADATA_STREAMS";

    // Accessors that v2-migration cannot turn into request builder calls
    private static final Set<String> UNFOLDABLE = new HashSet<>(Arrays.asList("setHeader", "clone", "setLastModified",
            "setExpirationTime", "setExpirationTimeRuleId", "getRawMetadata", "getRawMetadataValue", "setHttpExpiresDate"));

    private static final List<String> HELPERS = Arrays.asList("getObjectMetadata", "of", "putObject", "putObjectRequest",
            "withMetadata", "withNewObjectMetadata", "withCannedAcl", "withAccessControlList", "upload");

    @Getter
    final String displayName = "Migrate AWS SDK v1 S3 `ObjectMetadata` shared between methods";

    @Getter
    final String description = "Runs before `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. Where an S3 `ObjectMetadata` " +
            "is returned, passed as a parameter, kept in a field, or uses accessors such as `setHeader` or `clone`, it becomes a " +
            "generated mutable `ObjectMetadata` with v1's accessors. `putObject`, `getObjectMetadata`, `S3Object.getObjectMetadata`, " +
            "`PutObjectRequest.setMetadata`, `CopyObjectRequest.withNewObjectMetadata` and `TransferManager.upload` of a stream go " +
            "through its helpers, which copy the values to the v2 request or from the v2 response.";

    public static class Accumulator extends ShimRoots {
        final Set<String> transferProjects = ConcurrentHashMap.newKeySet();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    acc.shims.recordExisting((SourceFile) tree);
                }
                if (!(tree instanceof J.CompilationUnit) || !((J.CompilationUnit) tree).getSourcePath().toString().endsWith(".java") ||
                    new UsesType<>(V1_METADATA, true).visit(tree, ctx) == tree) {
                    return tree;
                }
                J.CompilationUnit cu = (J.CompilationUnit) tree;
                String project = ProjectShims.projectName(cu);
                boolean[] producer = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                        JavaType.Method mt = method.getMethodType();
                        if (mt != null && (isV1Metadata(mt.getReturnType()) || mt.getParameterTypes().stream().anyMatch(t -> isV1Metadata(t)))) {
                            producer[0] = true;
                        }
                        return super.visitMethodDeclaration(method, ctx);
                    }

                    @Override
                    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                        if (isV1Metadata(multiVariable.getType()) && getCursor().getParentTreeCursor().getValue() instanceof J.Block &&
                            getCursor().getParentTreeCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration) {
                            producer[0] = true;
                        }
                        return super.visitVariableDeclarations(multiVariable, ctx);
                    }

                    @Override
                    public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                        if (isV1Metadata(fieldAccess.getTarget().getType())) {
                            producer[0] = true;
                        }
                        return super.visitFieldAccess(fieldAccess, ctx);
                    }

                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        JavaType.Method mt = method.getMethodType();
                        if (mt != null) {
                            if (isV1Metadata(mt.getDeclaringType()) && UNFOLDABLE.contains(method.getSimpleName())) {
                                producer[0] = true;
                            }
                            if (!mt.getDeclaringType().getFullyQualifiedName().startsWith("com.amazonaws.") && signatureHasMetadata(mt)) {
                                acc.reference(project, mt.getDeclaringType().getFullyQualifiedName());
                            }
                        }
                        return super.visitMethodInvocation(method, ctx);
                    }

                    @Override
                    public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                        JavaType.Method mt = newClass.getMethodType();
                        if (mt != null && !mt.getDeclaringType().getFullyQualifiedName().startsWith("com.amazonaws.") && signatureHasMetadata(mt)) {
                            acc.reference(project, mt.getDeclaringType().getFullyQualifiedName());
                        }
                        return super.visitNewClass(newClass, ctx);
                    }
                }.visit(cu, ctx);
                if (new UsesType<>(V1_TRANSFER + "*", true).visit(cu, ctx) != cu) {
                    acc.transferProjects.add(project);
                }
                if (producer[0]) {
                    acc.producer(cu);
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        Set<String> roots = acc.retainRoots();
        Set<String> transferRoots = new HashSet<>();
        for (String p : acc.transferProjects) {
            String root = acc.root(p);
            if (root != null) {
                transferRoots.add(root);
            }
        }
        Set<String> transferPackages = new HashSet<>();
        for (String root : roots) {
            String fqn = acc.shims.fqnForProject(root, S3ObjectMetadataShim.SHIM);
            if (fqn != null && transferRoots.contains(root)) {
                transferPackages.add(packageOf(fqn));
            }
        }
        return acc.shims.generate(S3ObjectMetadataShim.SHIM, pkg -> S3ObjectMetadataShim.source(pkg, transferPackages.contains(pkg)),
                MigrateS3Residuals.v2Parser(ctx), ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return JavaOnly.check(new UsesType<>(V1_METADATA, true), new JavaVisitor<ExecutionContext>() {
            @Nullable
            String shim;

            @Override
            public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                shim = acc.shimFor(cu, S3ObjectMetadataShim.SHIM);
                if (shim == null || !namesMetadata(cu, ctx)) {
                    return cu;
                }
                J.CompilationUnit c = (J.CompilationUnit) super.visitCompilationUnit(cu, ctx);
                doAfterVisit(new ChangeType(V1_METADATA, shim, true).getVisitor());
                return c;
            }

            private boolean namesMetadata(J.CompilationUnit cu, ExecutionContext ctx) {
                if (new UsesType<>(V1_METADATA, false).visit(cu, ctx) != cu) {
                    return true;
                }
                String project = ProjectShims.projectName(cu);
                Set<String> refs = acc.references.getOrDefault(project, emptySet());
                for (JavaType.Method mt : cu.getTypesInUse().getUsedMethods()) {
                    if (refs.contains(mt.getDeclaringType().getFullyQualifiedName()) && signatureHasMetadata(mt)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public J visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                getCursor().putMessage(STREAMS, new HashMap<String, Expression[]>());
                return super.visitMethodDeclaration(method, ctx);
            }

            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J j = super.visitNewClass(newClass, ctx);
                if (!(j instanceof J.NewClass)) {
                    return j;
                }
                J.NewClass n = (J.NewClass) j;
                JavaType.Method mt = n.getMethodType();
                if (mt == null || !TypeUtils.isOfClassType(mt.getDeclaringType(), V1_PUT_REQUEST) || n.getArguments().size() != 4 ||
                    !TypeUtils.isOfClassType(mt.getParameterTypes().get(2), "java.io.InputStream") ||
                    !isV1Metadata(mt.getParameterTypes().get(3)) ||
                    !(n.getArguments().get(2) instanceof J.Identifier) || !(n.getArguments().get(3) instanceof J.Identifier)) {
                    return n;
                }
                Object parent = getCursor().getParentTreeCursor().getValue();
                Map<String, Expression[]> streams = getCursor().getNearestMessage(STREAMS);
                if (!(parent instanceof J.VariableDeclarations.NamedVariable) || streams == null) {
                    return n;
                }
                streams.put(((J.VariableDeclarations.NamedVariable) parent).getSimpleName(),
                        new Expression[]{n.getArguments().get(2), n.getArguments().get(3)});
                return helper("putObjectRequest", n.getType(), Arrays.asList(n.getArguments().get(0), n.getArguments().get(1),
                        n.getArguments().get(3)), Arrays.asList(mt.getParameterTypes().get(0), mt.getParameterTypes().get(1),
                        mt.getParameterTypes().get(3)), n.getPrefix());
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J j = super.visitMethodInvocation(method, ctx);
                if (!(j instanceof J.MethodInvocation)) {
                    return j;
                }
                J.MethodInvocation m = (J.MethodInvocation) j;
                JavaType.Method mt = m.getMethodType();
                Expression select = m.getSelect();
                if (mt == null || select == null || V2MigrationScope.isMockitoStubbedOrVerified(getCursor())) {
                    return m;
                }
                String name = m.getSimpleName();
                List<Expression> args = m.getArguments();
                List<JavaType> params = mt.getParameterTypes();
                JavaType.FullyQualified declaring = mt.getDeclaringType();
                Map<String, Expression[]> streams = getCursor().getNearestMessage(STREAMS);
                Expression[] stream = streams != null && args.size() == 1 && args.get(0) instanceof J.Identifier ?
                        streams.get(((J.Identifier) args.get(0)).getSimpleName()) : null;
                Expression[] target = streams != null && select instanceof J.Identifier ?
                        streams.get(((J.Identifier) select).getSimpleName()) : null;

                if (TypeUtils.isAssignableTo(V1_S3, declaring)) {
                    if ("getObjectMetadata".equals(name) && args.size() == 2 && isString(params.get(0))) {
                        return helper("getObjectMetadata", mt.getReturnType(), prepend(select, args), prepend(select.getType(), params), m.getPrefix());
                    }
                    if ("putObject".equals(name) && args.size() == 4 && isV1Metadata(params.get(3))) {
                        return helper("putObject", mt.getReturnType(), prepend(select, args), prepend(select.getType(), params), m.getPrefix());
                    }
                    if ("putObject".equals(name) && stream != null) {
                        return helper("putObject", mt.getReturnType(), Arrays.asList(select, args.get(0), stream[0], stream[1]),
                                Arrays.asList(select.getType(), params.get(0), stream[0].getType(), stream[1].getType()), m.getPrefix());
                    }
                }
                if (declaring.getFullyQualifiedName().startsWith(V1_TRANSFER) && "upload".equals(name) && stream != null) {
                    return helper("upload", mt.getReturnType(), Arrays.asList(select, args.get(0), stream[0], stream[1]),
                            Arrays.asList(select.getType(), params.get(0), stream[0].getType(), stream[1].getType()), m.getPrefix());
                }
                if ("getObjectMetadata".equals(name) && args.isEmpty() && isV1Metadata(mt.getReturnType()) &&
                    (TypeUtils.isOfClassType(declaring, V1_MODEL + "S3Object") || declaring.getFullyQualifiedName().startsWith(V1_TRANSFER))) {
                    return helper("of", mt.getReturnType(), singletonList(select),
                            singletonList(select.getType()), m.getPrefix());
                }
                if (getCursor().getParentTreeCursor().getValue() instanceof J.Block && select instanceof J.Identifier && args.size() == 1) {
                    String helper = null;
                    boolean putRequest = TypeUtils.isAssignableTo(V1_PUT_REQUEST, select.getType());
                    if (putRequest && "setMetadata".equals(name)) {
                        helper = "withMetadata";
                    } else if (TypeUtils.isAssignableTo(V1_COPY_REQUEST, select.getType()) && "setNewObjectMetadata".equals(name)) {
                        helper = "withNewObjectMetadata";
                    } else if (target != null && putRequest &&
                               ("setCannedAcl".equals(name) || "setAcl".equals(name))) {
                        helper = "withCannedAcl";
                    } else if (target != null && putRequest && "setAccessControlList".equals(name)) {
                        helper = "withAccessControlList";
                    }
                    if (helper != null) {
                        J.MethodInvocation call = helper(helper, select.getType(), Arrays.asList(select, args.get(0)),
                                Arrays.asList(select.getType(), params.get(0)), Space.EMPTY);
                        J.Assignment assignment = JavaTemplate.builder("#{any()} = #{any()}")
                                .contextSensitive()
                                .build()
                                .apply(getCursor(), m.getCoordinates().replace(), select, call);
                        return assignment.withPrefix(m.getPrefix());
                    }
                }
                if (!(getCursor().getParentTreeCursor().getValue() instanceof J.MethodInvocation &&
                      ((J.MethodInvocation) getCursor().getParentTreeCursor().getValue()).getSelect() == method)) {
                    return liftMetadataWither(m);
                }
                return m;
            }

            /**
             * {@code new CopyObjectRequest(..).withNewObjectMetadata(md).withX(..)} becomes
             * {@code withNewObjectMetadata(new CopyObjectRequest(..).withX(..), md)}, so v2-migration still sees one builder chain.
             */
            private Expression liftMetadataWither(J.MethodInvocation top) {
                J.MethodInvocation wither = null;
                for (Expression e = top; e instanceof J.MethodInvocation; e = ((J.MethodInvocation) e).getSelect()) {
                    J.MethodInvocation link = (J.MethodInvocation) e;
                    JavaType.Method mt = link.getMethodType();
                    if (mt != null && link.getSelect() != null && link.getArguments().size() == 1 &&
                        (TypeUtils.isAssignableTo(V1_PUT_REQUEST, link.getSelect().getType()) && "withMetadata".equals(link.getSimpleName()) ||
                         TypeUtils.isAssignableTo(V1_COPY_REQUEST, link.getSelect().getType()) && "withNewObjectMetadata".equals(link.getSimpleName()))) {
                        wither = link;
                        break;
                    }
                }
                if (wither == null) {
                    return top;
                }
                J.MethodInvocation found = wither;
                Expression request = top == found ? found.getSelect() : unlink(top, found);
                JavaType.Method mt = Objects.requireNonNull(found.getMethodType());
                String helper = "withMetadata".equals(found.getSimpleName()) ? "withMetadata" : "withNewObjectMetadata";
                return helper(helper, top.getType(), Arrays.asList(Objects.requireNonNull(request), found.getArguments().get(0)),
                        Arrays.asList(found.getSelect() == null ? null : found.getSelect().getType(), mt.getParameterTypes().get(0)), top.getPrefix());
            }

            private Expression unlink(J.MethodInvocation link, J.MethodInvocation remove) {
                if (link.getSelect() == remove) {
                    return link.withSelect(remove.getSelect());
                }
                return link.withSelect(unlink((J.MethodInvocation) Objects.requireNonNull(link.getSelect()), remove));
            }

            private J.MethodInvocation helper(String name, @Nullable JavaType returnType, List<Expression> args,
                                              List<@Nullable JavaType> paramTypes, Space prefix) {
                String shimFqn = Objects.requireNonNull(shim);
                StringJoiner placeholders = new StringJoiner(", ");
                for (int i = 0; i < args.size(); i++) {
                    placeholders.add("#{any()}");
                }
                J call = JavaTemplate.builder(S3ObjectMetadataShim.SHIM + "." + name + "(" + placeholders + ")")
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(stub(shimFqn)))
                        .imports(shimFqn)
                        .build()
                        .apply(getCursor(), ((Expression) getCursor().getValue()).getCoordinates().replace(), args.toArray());
                maybeAddImport(shimFqn, false);
                // The template cannot attribute v1 types, so the call keeps them for v2-migration to retype
                JavaType.FullyQualified shimType = JavaType.ShallowClass.build(shimFqn);
                List<String> names = new ArrayList<>();
                List<JavaType> types = new ArrayList<>();
                for (int i = 0; i < paramTypes.size(); i++) {
                    names.add("arg" + i);
                    JavaType t = paramTypes.get(i);
                    types.add(t == null ? JavaType.Unknown.getInstance() : t);
                }
                JavaType.Method type = new JavaType.Method(null, Flag.Public.getBitMask() | Flag.Static.getBitMask(), shimType, name,
                        returnType, names, types, emptyList(), emptyList(), null, null);
                J.MethodInvocation invocation = (J.MethodInvocation) call;
                return invocation.withMethodType(type).withName(invocation.getName().withType(type)).withPrefix(prefix);
            }
        });
    }

    private static String stub(String shim) {
        StringBuilder s = new StringBuilder(ProjectShims.packageLine(packageOf(shim)))
                .append("public class ").append(S3ObjectMetadataShim.SHIM).append(" {\n");
        for (String h : HELPERS) {
            s.append("    public static <T> T ").append(h).append("(Object... args) { return null; }\n");
        }
        return s.append("}").toString();
    }

    private static String packageOf(String fqn) {
        return fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
    }

    private static <T> List<T> prepend(T first, List<? extends T> rest) {
        List<T> all = new ArrayList<>(rest.size() + 1);
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private static boolean isString(@Nullable JavaType type) {
        return TypeUtils.isString(type);
    }

    private static boolean signatureHasMetadata(JavaType.Method mt) {
        return isV1Metadata(mt.getReturnType()) || mt.getParameterTypes().stream().anyMatch(MigrateS3ObjectMetadata::isV1Metadata);
    }

    private static boolean isV1Metadata(@Nullable JavaType type) {
        return TypeUtils.isOfClassType(type, V1_METADATA);
    }
}

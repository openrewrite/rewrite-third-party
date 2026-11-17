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
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAwsClientBuilderSubclasses extends ScanningRecipe<MigrateAwsClientBuilderSubclasses.Accumulator> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    private static final String V1_BUILDER = "com.amazonaws.client.builder.AwsClientBuilder";

    @Getter
    final String displayName = "Migrate subclasses of AWS SDK v1 `AwsClientBuilder`";

    @Getter
    final String description = "Runs before builder folding. A class that extends v1's `AwsClientBuilder` and only defines " +
            "constructors and `build()` extends a generated `RecordingAwsClientBuilder` that implements v2's `AwsSyncClientBuilder` " +
            "and records the endpoint, region and other settings. `getEndpoint()` and `getRegion()` on a v1 builder in the same " +
            "source set read the recorded values.";

    public static class Accumulator implements AwsMigrationScope.Limitable {
        final ProjectShims shims = new ProjectShims();

        @Override
        public void limitTo(AwsMigrationScope scope) {
            shims.limitTo(scope);
        }
        final Set<String> unsupported = ConcurrentHashMap.newKeySet();
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
                if (!(tree instanceof J.CompilationUnit) || !((J.CompilationUnit) tree).getSourcePath().toString().endsWith(".java")) {
                    return tree;
                }
                J.CompilationUnit cu = (J.CompilationUnit) tree;
                for (J.ClassDeclaration c : cu.getClasses()) {
                    if (!extendsV1Builder(c)) {
                        continue;
                    }
                    if (supported(c)) {
                        acc.shims.require(cu);
                    } else {
                        acc.unsupported.add(cu.getSourcePath().toString());
                    }
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
        return acc.shims.generate(RecordingAwsClientBuilderShim.SHIM, RecordingAwsClientBuilderShim::source, ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return JavaOnly.check(new UsesType<>(V1_BUILDER, true), new JavaVisitor<ExecutionContext>() {
            @Nullable
            String shim;

            @Override
            public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                shim = acc.unsupported.contains(cu.getSourcePath().toString()) ? null :
                        acc.shims.fqn(cu, RecordingAwsClientBuilderShim.SHIM);
                return shim == null ? cu : super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration c = (J.ClassDeclaration) super.visitClassDeclaration(classDecl, ctx);
                if (!extendsV1Builder(classDecl) || c.getExtends() == null) {
                    return c;
                }
                String shimFqn = Objects.requireNonNull(shim);
                JavaType.ShallowClass shimType = JavaType.ShallowClass.build(shimFqn);
                c = c.withExtends(new J.Identifier(Tree.randomId(), c.getExtends().getPrefix(), Markers.EMPTY, emptyList(),
                        RecordingAwsClientBuilderShim.SHIM, shimType, null));
                c = c.withBody(c.getBody().withStatements(ListUtils.map(c.getBody().getStatements(), s -> {
                    if (!(s instanceof J.MethodDeclaration) || !((J.MethodDeclaration) s).isConstructor() ||
                        ((J.MethodDeclaration) s).getBody() == null) {
                        return s;
                    }
                    J.MethodDeclaration ctor = (J.MethodDeclaration) s;
                    J.Block body = Objects.requireNonNull(ctor.getBody());
                    body = body.withStatements(ListUtils.map(body.getStatements(), st -> isSuperCall(st) ? null : st));
                    boolean noParams = ctor.getParameters().isEmpty() || ctor.getParameters().get(0) instanceof J.Empty;
                    return body.getStatements().isEmpty() && noParams ? null : ctor.withBody(body);
                })));
                if (!shimFqn.contains(".") || !packageOf(shimFqn).equals(packageOf(c))) {
                    maybeAddImport(shimFqn);
                }
                maybeRemoveImport(V1_BUILDER);
                maybeRemoveImport("com.amazonaws.ClientConfigurationFactory");
                return c;
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
                String shimFqn = Objects.requireNonNull(shim);
                if (select instanceof J.MethodInvocation && "endpointOf".equals(((J.MethodInvocation) select).getSimpleName()) &&
                    mt != null && ("getServiceEndpoint".equals(m.getSimpleName()) || "getSigningRegion".equals(m.getSimpleName()))) {
                    JavaType.Method retyped = mt.withDeclaringType(JavaType.ShallowClass.build(shimFqn + "$EndpointConfiguration"));
                    return m.withMethodType(retyped).withName(m.getName().withType(retyped));
                }
                if (mt == null || select == null || !m.getArguments().isEmpty() && !(m.getArguments().get(0) instanceof J.Empty) ||
                    !TypeUtils.isAssignableTo(V1_BUILDER, mt.getDeclaringType()) || isServiceBuilder(select.getType()) ||
                    !("getEndpoint".equals(m.getSimpleName()) || "getRegion".equals(m.getSimpleName()))) {
                    return m;
                }
                String helper = "getEndpoint".equals(m.getSimpleName()) ? "endpointOf" : "regionOf";
                J call = JavaTemplate.builder(RecordingAwsClientBuilderShim.SHIM + "." + helper + "(#{any()})")
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(ProjectShims.packageLine(packageOf(shimFqn)) +
                                "public abstract class " + RecordingAwsClientBuilderShim.SHIM + " {\n" +
                                "    public static Object endpointOf(Object b) { return null; }\n" +
                                "    public static String regionOf(Object b) { return null; }\n" +
                                "}"))
                        .imports(shimFqn)
                        .build()
                        .apply(getCursor(), method.getCoordinates().replace(), select);
                maybeAddImport(shimFqn);
                JavaType returnType = "endpointOf".equals(helper) ? JavaType.ShallowClass.build(shimFqn + "$EndpointConfiguration") :
                        JavaType.ShallowClass.build("java.lang.String");
                JavaType.Method type = new JavaType.Method(null, Flag.Public.getBitMask() | Flag.Static.getBitMask(),
                        JavaType.ShallowClass.build(shimFqn), helper, returnType, singletonList("builder"),
                        singletonList(JavaType.ShallowClass.build("java.lang.Object")), emptyList(), emptyList(), null, null);
                J.MethodInvocation invocation = (J.MethodInvocation) call;
                return invocation.withMethodType(type).withName(invocation.getName().withType(type));
            }
        });
    }

    private static boolean extendsV1Builder(J.ClassDeclaration c) {
        return c.getExtends() != null && TypeUtils.isOfClassType(c.getExtends().getType(), V1_BUILDER);
    }

    // The generated helpers cast to the recording builder, so SDK service builders, which never are one, keep their calls
    private static boolean isServiceBuilder(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq != null && fq.getFullyQualifiedName().startsWith("com.amazonaws.") && !TypeUtils.isOfClassType(fq, V1_BUILDER);
    }

    private static boolean supported(J.ClassDeclaration c) {
        for (Statement s : c.getBody().getStatements()) {
            if (s instanceof J.MethodDeclaration) {
                J.MethodDeclaration md = (J.MethodDeclaration) s;
                if (md.isConstructor()) {
                    continue;
                }
                if (!"build".equals(md.getSimpleName())) {
                    return false;
                }
            } else if (!(s instanceof J.VariableDeclarations)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSuperCall(Statement s) {
        return s instanceof J.MethodInvocation && "super".equals(((J.MethodInvocation) s).getSimpleName());
    }

    private static String packageOf(String fqn) {
        return fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
    }

    private static String packageOf(J.ClassDeclaration c) {
        return c.getType() == null ? "" : c.getType().getPackageName();
    }
}

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
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A v1 {@code EndpointConfiguration} kept as a value, such as a Spring bean, has no v2 type to become. Builder folding only
 * handles one created inline in a client builder chain, so code that only keeps and reads such values gets a generated value
 * class with the same accessors.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAwsEndpointConfigurationValues extends ScanningRecipe<MigrateAwsEndpointConfigurationValues.Accumulator> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    private static final String V1_BUILDER = "com.amazonaws.client.builder.AwsClientBuilder";
    private static final String V1_ENDPOINT = V1_BUILDER + "$EndpointConfiguration";

    @Getter
    final String displayName = "Migrate AWS SDK v1 `EndpointConfiguration` values";

    @Getter
    final String description = "Runs before builder folding. Where an `AwsClientBuilder.EndpointConfiguration` is returned, passed or " +
            "kept in a variable but never given to a client builder, it becomes a generated `AwsEndpointConfiguration` holding the " +
            "endpoint and signing region with v1's getters. Projects that also pass a non-inline `EndpointConfiguration` to a " +
            "builder, read it back from a builder or extend `AwsClientBuilder` are left alone.";

    public static class Accumulator extends ShimRoots {
        final Set<String> excludedProjects = ConcurrentHashMap.newKeySet();
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
                    new UsesType<>(V1_BUILDER + "*", true).visit(tree, ctx) == tree) {
                    return tree;
                }
                J.CompilationUnit cu = (J.CompilationUnit) tree;
                String project = ProjectShims.projectName(cu);
                boolean[] value = {false};
                boolean[] builderUse = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                        JavaType.Method mt = method.getMethodType();
                        if (mt != null && (isV1(mt.getReturnType()) || mt.getParameterTypes().stream().anyMatch(MigrateAwsEndpointConfigurationValues::isV1))) {
                            value[0] = true;
                        }
                        return super.visitMethodDeclaration(method, ctx);
                    }

                    @Override
                    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                        if (isV1(multiVariable.getType())) {
                            value[0] = true;
                        }
                        return super.visitVariableDeclarations(multiVariable, ctx);
                    }

                    @Override
                    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                        if (classDecl.getType() != null && TypeUtils.isAssignableTo(V1_BUILDER, classDecl.getType().getSupertype())) {
                            acc.excludedProjects.add(project);
                        }
                        return super.visitClassDeclaration(classDecl, ctx);
                    }

                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        JavaType.Method mt = method.getMethodType();
                        if (mt != null && TypeUtils.isAssignableTo(V1_BUILDER, mt.getDeclaringType())) {
                            if ("getEndpoint".equals(method.getSimpleName())) {
                                acc.excludedProjects.add(project);
                            } else if (method.getSimpleName().endsWith("EndpointConfiguration")) {
                                builderUse[0] = true;
                                if (method.getArguments().size() == 1 && !(method.getArguments().get(0) instanceof J.NewClass)) {
                                    acc.excludedProjects.add(project);
                                }
                            }
                        }
                        if (mt != null && !mt.getDeclaringType().getFullyQualifiedName().startsWith("com.amazonaws.") &&
                            (isV1(mt.getReturnType()) || mt.getParameterTypes().stream().anyMatch(MigrateAwsEndpointConfigurationValues::isV1))) {
                            acc.reference(project, mt.getDeclaringType().getFullyQualifiedName());
                        }
                        return super.visitMethodInvocation(method, ctx);
                    }
                }.visit(cu, ctx);
                if (value[0]) {
                    // A file that both keeps a value and builds clients inline would need both types at once
                    if (builderUse[0]) {
                        acc.excludedProjects.add(project);
                    }
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
        acc.producerProjects.removeAll(acc.excludedProjects);
        acc.retainRoots();
        return acc.shims.generate(AwsEndpointConfigurationShim.SHIM, AwsEndpointConfigurationShim::source, ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return JavaOnly.check(new UsesType<>(V1_ENDPOINT, true), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                String shim = acc.shimFor(cu, AwsEndpointConfigurationShim.SHIM);
                if (shim == null) {
                    return cu;
                }
                J.CompilationUnit c = (J.CompilationUnit) new ChangeType(V1_ENDPOINT, shim, true).getVisitor().visitNonNull(cu, ctx);
                if (c != cu) {
                    c = (J.CompilationUnit) new RemoveUnusedAwsSdkV1Imports().getVisitor().visitNonNull(c, ctx);
                }
                return c;
            }
        });
    }

    private static boolean isV1(@Nullable JavaType type) {
        return TypeUtils.isOfClassType(type, V1_ENDPOINT);
    }
}

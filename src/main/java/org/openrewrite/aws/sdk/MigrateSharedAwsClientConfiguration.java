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
import org.openrewrite.marker.SearchResult;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;

/**
 * Where a v1 {@code ClientConfiguration} is built in one method and used in another, neither this repository's builder folding
 * nor v2-migration can split it into v2's override configuration and HTTP client settings. Such code keeps a generated mutable
 * equivalent instead. Files of projects that receive it from another project use that project's class, so the types still match.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateSharedAwsClientConfiguration extends ScanningRecipe<MigrateSharedAwsClientConfiguration.Accumulator> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    private static final String V1_CLIENT_CONFIGURATION = "com.amazonaws.ClientConfiguration";
    private static final String V1_CLIENT_BUILDER = "com.amazonaws.client.builder.AwsClientBuilder";
    private static final String V1_PREDEFINED = "com.amazonaws.PredefinedClientConfigurations";
    private static final String SHIM_KEY = "AWS_CLIENT_CONFIGURATION_SHIM";

    // v1 accessors the generated class has; others have no v2 setting to carry them
    private static final Pattern SUPPORTED = Pattern.compile("(get|set|with)(ConnectionTimeout|SocketTimeout|MaxConnections|" +
            "MaxErrorRetry|RetryPolicy|ConnectionTTL|ConnectionMaxIdleMillis|ValidateAfterInactivityMillis|RequestTimeout|" +
            "ClientExecutionTimeout|MaxConsecutiveRetriesBeforeThrottling|ResponseMetadataCacheSize|LocalAddress|Protocol|ProxyHost|" +
            "ProxyPort|ProxyUsername|ProxyPassword|ProxyDomain|ProxyWorkstation|NonProxyHosts|ProxyProtocol|SignerOverride|" +
            "UserAgentPrefix|UserAgentSuffix|CacheResponseMetadata|UserAgent|SocketBufferSizeHints|Headers?)|" +
            "useReaper|setUseReaper|withReaper|useGzip|setUseGzip|withGzip|useTcpKeepAlive|setUseTcpKeepAlive|withTcpKeepAlive|" +
            "isUseExpectContinue|setUseExpectContinue|withUseExpectContinue|useThrottledRetries|setUseThrottleRetries|" +
            "withThrottledRetries|isPreemptiveBasicProxyAuth|setPreemptiveBasicProxyAuth|withPreemptiveBasicProxyAuth|addHeader|" +
            "equals|hashCode|toString|getClass");

    @Getter
    final String displayName = "Migrate AWS SDK v1 `ClientConfiguration` shared between methods";

    @Getter
    final String description = "Runs before `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. Where a `ClientConfiguration` " +
            "is returned, passed as a parameter or kept in a field, it becomes a generated `AwsClientConfiguration` with v1's " +
            "accessors and defaults, and `withClientConfiguration` on a client builder becomes `AwsClientConfiguration.applyTo`, " +
            "which sets the v2 builder's override configuration and Apache HTTP client settings.";

    public static class Accumulator extends ShimRoots {
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
                    new UsesType<>(V1_CLIENT_CONFIGURATION, false).visit(tree, ctx) == tree) {
                    return tree;
                }
                J.CompilationUnit cu = (J.CompilationUnit) tree;
                String project = ProjectShims.projectName(cu);
                boolean[] producer = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                        JavaType.Method mt = method.getMethodType();
                        if (mt != null && (isV1(mt.getReturnType()) || mt.getParameterTypes().stream().anyMatch(t -> isV1(t)))) {
                            producer[0] = true;
                        }
                        return super.visitMethodDeclaration(method, ctx);
                    }

                    @Override
                    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                        if (isV1(multiVariable.getType()) && getCursor().getParentTreeCursor().getValue() instanceof J.Block &&
                            getCursor().getParentTreeCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration) {
                            producer[0] = true;
                        }
                        return super.visitVariableDeclarations(multiVariable, ctx);
                    }

                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        reference(method.getMethodType());
                        return super.visitMethodInvocation(method, ctx);
                    }

                    @Override
                    public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                        reference(newClass.getMethodType());
                        return super.visitNewClass(newClass, ctx);
                    }

                    private void reference(JavaType.@Nullable Method mt) {
                        if (mt != null && !mt.getDeclaringType().getFullyQualifiedName().startsWith("com.amazonaws.") &&
                            (isV1(mt.getReturnType()) || mt.getParameterTypes().stream().anyMatch(t -> isV1(t)))) {
                            acc.reference(project, mt.getDeclaringType().getFullyQualifiedName());
                        }
                    }
                }.visit(cu, ctx);
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
        acc.retainRoots();
        return acc.shims.generate(AwsClientConfigurationShim.SHIM, AwsClientConfigurationShim::source, ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return JavaOnly.check(new UsesType<>(V1_CLIENT_CONFIGURATION, false), new JavaVisitor<ExecutionContext>() {

            @Override
            public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                String shim = acc.shimFor(cu, AwsClientConfigurationShim.SHIM);
                if (shim == null) {
                    return cu;
                }
                getCursor().putMessage(SHIM_KEY, shim);
                J.CompilationUnit c = (J.CompilationUnit) super.visitCompilationUnit(cu, ctx);
                doAfterVisit(new ChangeType(V1_CLIENT_CONFIGURATION, shim, true).getVisitor());
                maybeRemoveImport(V1_PREDEFINED);
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
                String shim = getCursor().getNearestMessage(SHIM_KEY);
                if (mt == null || shim == null) {
                    return m;
                }
                if (("withClientConfiguration".equals(m.getSimpleName()) || "setClientConfiguration".equals(m.getSimpleName())) &&
                    m.getSelect() != null && m.getArguments().size() == 1 && TypeUtils.isAssignableTo(V1_CLIENT_BUILDER, mt.getDeclaringType())) {
                    return applyTo(m, shim, ctx);
                }
                if (TypeUtils.isOfClassType(mt.getDeclaringType(), V1_PREDEFINED) && "defaultConfig".equals(m.getSimpleName())) {
                    return JavaTemplate.builder("new " + simpleName(shim) + "()")
                            .javaParser(JavaParser.fromJavaVersion().dependsOn(stub(shim)))
                            .imports(shim)
                            .build()
                            .apply(getCursor(), m.getCoordinates().replace());
                }
                if (TypeUtils.isOfClassType(mt.getDeclaringType(), V1_CLIENT_CONFIGURATION) && !SUPPORTED.matcher(m.getSimpleName()).matches()) {
                    return SearchResult.found(m, "AWS SDK v1 ClientConfiguration setting with no v2 equivalent in " +
                            AwsClientConfigurationShim.SHIM);
                }
                return m;
            }

            private J applyTo(J.MethodInvocation m, String shim, ExecutionContext ctx) {
                Expression builder = m.getSelect();
                J.MethodInvocation applied = JavaTemplate.builder(simpleName(shim) + ".applyTo(#{any()}, #{any()})")
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(stub(shim)))
                        .imports(shim)
                        .build()
                        .apply(getCursor(), m.getCoordinates().replace(), builder, m.getArguments().get(0));
                maybeAddImport(shim);
                // The template cannot attribute the v1 builder, so the call keeps the builder's type for v2-migration to change
                JavaType.FullyQualified shimType = JavaType.ShallowClass.build(shim);
                JavaType.Method type = new JavaType.Method(null, Flag.Public.getBitMask() | Flag.Static.getBitMask(), shimType,
                        "applyTo", builder.getType(), Arrays.asList("builder", "configuration"),
                        Arrays.asList(builder.getType(), shimType), emptyList(), emptyList(), null, null);
                return applied.withMethodType(type).withName(applied.getName().withType(type)).withPrefix(m.getPrefix());
            }
        });
    }

    private static String stub(String shim) {
        String pkg = shim.contains(".") ? shim.substring(0, shim.lastIndexOf('.')) : "";
        return ProjectShims.packageLine(pkg) + "public class " + AwsClientConfigurationShim.SHIM + " {\n" +
               "    public static <B> B applyTo(B builder, Object configuration) { return builder; }\n" +
               "}";
    }

    private static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    private static boolean isV1(@Nullable JavaType type) {
        return TypeUtils.isOfClassType(type, V1_CLIENT_CONFIGURATION);
    }
}

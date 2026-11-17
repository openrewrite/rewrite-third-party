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
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.LinkedHashSet;
import java.util.Set;

import static java.util.Collections.emptySet;
import static org.openrewrite.aws.sdk.AwsClientChain.isChainElement;
import static org.openrewrite.aws.sdk.AwsClientChain.parseChain;

// v2-migration retypes v1's base as `AwsSyncClientBuilder`, which async builders do not implement and which has none of the settings
public class MigrateGenericAwsClientBuilders extends Recipe {

    private static final String V1_BUILDER = "com.amazonaws.client.builder.AwsClientBuilder";
    private static final String V1_ENDPOINT_CONFIGURATION = "com.amazonaws.client.builder.AwsClientBuilder$EndpointConfiguration";
    private static final String V2_BUILDER = "software.amazon.awssdk.awscore.client.builder.AwsClientBuilder";

    @Getter
    final String displayName = "Migrate code written against the AWS SDK v1 `AwsClientBuilder` base type";

    @Getter
    final String description = "Runs before `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. Where a client builder is " +
            "handled as v1's `AwsClientBuilder` (typically a generic `<T extends AwsClientBuilder<?, ?>>` helper), the type becomes " +
            "v2's `AwsClientBuilder`, and `setEndpointConfiguration(new EndpointConfiguration(endpoint, region))`, `setRegion(String)` " +
            "and `setCredentials` statements, and their `with` forms, become `endpointOverride`, `region` and `credentialsProvider`. " +
            "Concrete client builder chains in the same file are converted to v2 along with it.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(new UsesType<>(V1_BUILDER, false), new JavaVisitor<ExecutionContext>() {

            @Override
            public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                if (extendsV1Builder(cu)) {
                    return cu;
                }
                J.CompilationUnit start = cu;
                Set<String> added = new LinkedHashSet<>();
                Set<String> removed = new LinkedHashSet<>();
                // v2-migration only renames concrete builder calls while their base is v1's, so retyping the base needs them on v2 first
                if (callsOnConcreteBuilders(cu)) {
                    start = convertConcreteChains(cu, getCursor().getParentOrThrow(), ctx, added, removed);
                    if (start == null || callsOnConcreteBuilders(start)) {
                        return cu;
                    }
                }
                J.CompilationUnit c = (J.CompilationUnit) super.visitCompilationUnit(start, ctx);
                // Retyping the base would also move EndpointConfiguration, which has no v2 class; v2 setter calls on a v1 base would not compile
                if (usesEndpointConfiguration(c)) {
                    return cu;
                }
                added.forEach(this::maybeAddImport);
                removed.forEach(this::maybeRemoveImport);
                doAfterVisit(new ChangeType(V1_BUILDER, V2_BUILDER, true).getVisitor());
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
                if (mt == null || m.getSelect() == null || m.getArguments().size() != 1 ||
                    !TypeUtils.isOfClassType(mt.getDeclaringType(), V1_BUILDER) || !isGenericBuilder(m.getSelect().getType()) ||
                    !(getCursor().getParentTreeCursor().getValue() instanceof J.Block)) {
                    return m;
                }
                String setting = m.getSimpleName().replaceFirst("^(set|with)", "");
                Expression arg = m.getArguments().get(0);
                switch (setting) {
                    case "EndpointConfiguration":
                        if (arg instanceof J.NewClass && TypeUtils.isOfClassType(arg.getType(), V1_ENDPOINT_CONFIGURATION) &&
                            ((J.NewClass) arg).getArguments().size() == 2) {
                            J.NewClass n = (J.NewClass) arg;
                            maybeAddImport("java.net.URI");
                            maybeRemoveImport(V1_ENDPOINT_CONFIGURATION.replace('$', '.'));
                            maybeAddImport("software.amazon.awssdk.regions.Region");
                            return template("#{any(" + V2_BUILDER + "<?, ?>)}.endpointOverride(URI.create(#{any(String)})).region(Region.of(#{any(String)}))",
                                    m, ctx, m.getSelect(), n.getArguments().get(0), n.getArguments().get(1));
                        }
                        return m;
                    case "Region":
                        if (TypeUtils.isString(arg.getType())) {
                            maybeAddImport("software.amazon.awssdk.regions.Region");
                            return template("#{any(" + V2_BUILDER + "<?, ?>)}.region(Region.of(#{any(String)}))", m, ctx, m.getSelect(), arg);
                        }
                        return m;
                    case "Credentials":
                        JavaType.Method renamed = mt.withName("credentialsProvider");
                        return m.withName(m.getName().withSimpleName("credentialsProvider").withType(renamed)).withMethodType(renamed);
                    default:
                        return m;
                }
            }

            private J template(String code, J.MethodInvocation m, ExecutionContext ctx, Object... args) {
                return JavaTemplate.builder(code)
                        .imports("java.net.URI", "software.amazon.awssdk.regions.Region")
                        .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "aws-core-2", "regions-2", "sdk-core-2", "utils-2", "http-client-spi-2", "auth-2", "identity-spi-2"))
                        .build()
                        .apply(getCursor(), m.getCoordinates().replace(), args);
            }
        });
    }

    /**
     * A builder typed only as v1's base, not a concrete service builder, whose chains MigrateAwsClientBuildersToV2 converts.
     */
    private static boolean isGenericBuilder(@Nullable JavaType type) {
        if (type instanceof JavaType.GenericTypeVariable) {
            for (JavaType bound : ((JavaType.GenericTypeVariable) type).getBounds()) {
                if (TypeUtils.isOfClassType(bound, V1_BUILDER)) {
                    return true;
                }
            }
            return false;
        }
        return TypeUtils.isOfClassType(type, V1_BUILDER);
    }

    /**
     * Converts every concrete client builder chain to v2, or returns null when one of them cannot be.
     */
    private static J.@Nullable CompilationUnit convertConcreteChains(J.CompilationUnit cu, Cursor parent, ExecutionContext ctx,
                                                                     Set<String> added, Set<String> removed) {
        boolean[] failed = {false};
        J.CompilationUnit converted = (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (!isChainElement(m) || extendsChain(m)) {
                    return m;
                }
                if (onGenericBuilder(m)) {
                    return m;
                }
                AwsClientChain.Parsed parsed = parseChain(m, getCursor(), emptySet(), false, true);
                AwsClientChain chain = parsed.chain;
                if (chain == null || !chain.foldedStatements.isEmpty() || chain.warning != null) {
                    failed[0] = true;
                    return m;
                }
                added.addAll(chain.imports);
                for (Expression e = m; e instanceof J.MethodInvocation; e = ((J.MethodInvocation) e).getSelect()) {
                    J.MethodInvocation link = (J.MethodInvocation) e;
                    if (AwsClientChain.isRoot(link)) {
                        JavaType.FullyQualified v1Builder = TypeUtils.asFullyQualified(link.getMethodType().getReturnType());
                        if (v1Builder != null) {
                            removed.add(v1Builder.getFullyQualifiedName());
                        }
                        break;
                    }
                }
                removed.add(AwsClientChain.V1_REGIONS);
                return JavaTemplate.builder(chain.template())
                        .imports(chain.imports.toArray(new String[0]))
                        .javaParser(MigrateAwsClientBuildersToV2.javaParser(ctx, chain))
                        .build()
                        .apply(getCursor(), m.getCoordinates().replace(), chain.params.toArray());
            }

            /**
             * Calls on a builder typed as the generic base are converted by the outer visitor, and `helper(builder).build()`
             * needs no change because v2's base has the same `build()`.
             */
            private boolean onGenericBuilder(J.MethodInvocation m) {
                J.MethodInvocation innermost = m;
                while (!AwsClientChain.isRoot(innermost) && innermost.getSelect() instanceof J.MethodInvocation &&
                       isChainElement((J.MethodInvocation) innermost.getSelect())) {
                    innermost = (J.MethodInvocation) innermost.getSelect();
                }
                if (AwsClientChain.isRoot(innermost) || innermost.getSelect() == null) {
                    return false;
                }
                Expression receiver = innermost.getSelect();
                return isGenericBuilder(receiver.getType()) ||
                       receiver instanceof J.MethodInvocation && "build".equals(m.getSimpleName()) && innermost == m;
            }

            private boolean extendsChain(J.MethodInvocation m) {
                Object parent = getCursor().getParentTreeCursor().getValue();
                return parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() != null &&
                       ((J.MethodInvocation) parent).getSelect().getId().equals(m.getId()) &&
                       isChainElement((J.MethodInvocation) parent);
            }
        }.visitNonNull(cu, ctx, parent);
        return failed[0] ? null : converted;
    }

    private static boolean callsOnConcreteBuilders(J.CompilationUnit cu) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                JavaType.Method mt = method.getMethodType();
                found[0] |= mt != null && TypeUtils.isOfClassType(mt.getDeclaringType(), V1_BUILDER) &&
                            (method.getSelect() == null || !isGenericBuilder(method.getSelect().getType()));
                return super.visitMethodInvocation(method, p);
            }
        }.visit(cu, 0);
        return found[0];
    }

    /**
     * Ignores imports, whose removal is still pending when this runs.
     */
    private static boolean usesEndpointConfiguration(J.CompilationUnit cu) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Import visitImport(J.Import _import, Integer p) {
                return _import;
            }

            @Override
            public @Nullable JavaType visitType(@Nullable JavaType javaType, Integer p) {
                found[0] |= TypeUtils.isOfClassType(javaType, V1_ENDPOINT_CONFIGURATION);
                return javaType;
            }
        }.visit(cu, 0);
        return found[0];
    }

    /**
     * A subclass of v1's abstract builder cannot implement v2's builder interface by a type change.
     */
    private static boolean extendsV1Builder(J.CompilationUnit cu) {
        for (J.ClassDeclaration c : cu.getClasses()) {
            if (c.getExtends() != null && TypeUtils.isOfClassType(c.getExtends().getType(), V1_BUILDER)) {
                return true;
            }
        }
        return false;
    }
}

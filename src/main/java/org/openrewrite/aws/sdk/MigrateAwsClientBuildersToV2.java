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
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.SearchResult;

import java.util.*;
import java.util.regex.Pattern;

import static org.openrewrite.aws.sdk.AwsClientChain.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAwsClientBuildersToV2 extends ScanningRecipe<Set<String>> {

    private static final String[] V2_ARTIFACTS = {"apache-client", "auth", "aws-core", "http-client-spi", "identity-spi",
            "netty-nio-client", "regions", "s3-2", "sdk-core", "utils"};
    private static final Pattern UNFOLDED_CLIENT_SETTING = Pattern.compile("(with|set)(MaxErrorRetry|Proxy(Host|Port|Username|Password))");
    private static final String V1_CLIENT_BASE = "com.amazonaws.AmazonWebServiceClient";

    @Getter
    String displayName = "Migrate AWS SDK v1 client builders that v2-migration cannot convert";

    @Getter
    String description = "Converts a v1 client builder chain to its v2 form when it contains something " +
            "`software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2` cannot do: `withEndpointConfiguration`, path-style access, " +
            "a `ClientConfiguration` with retries or a proxy, `RequestHandler2`s, or a legacy client constructor with arguments. " +
            "`RequestHandler2` subclasses that only edit the request become `ExecutionInterceptor`s. Other chains are left for " +
            "v2-migration, so this must run before it. Sites that cannot be converted are marked.";

    @Override
    public Set<String> getInitialValue(ExecutionContext ctx) {
        return new HashSet<>();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Set<String> convertibleHandlers) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                if (classDecl.getType() != null && RequestHandlerToInterceptor.convertibleBeforeExecution(classDecl) != null) {
                    convertibleHandlers.add(classDecl.getType().getFullyQualifiedName());
                }
                return super.visitClassDeclaration(classDecl, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Set<String> convertibleHandlers) {
        return JavaOnly.check(new UsesType<>("com.amazonaws..*", false), new JavaVisitor<ExecutionContext>() {
            private static final String REMOVE = "AWS_CLIENT_CHAIN_REMOVE";

            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = (J.Block) super.visitBlock(block, ctx);
                Set<UUID> remove = getCursor().getMessage(REMOVE);
                if (remove != null) {
                    b = b.withStatements(ListUtils.map(b.getStatements(), s -> remove.contains(s.getId()) ? null : s));
                }
                return b;
            }

            @Override
            public J visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = (J.ClassDeclaration) super.visitClassDeclaration(classDecl, ctx);
                J.MethodDeclaration beforeExecution = RequestHandlerToInterceptor.convertibleBeforeExecution(cd);
                if (beforeExecution != null) {
                    cd = RequestHandlerToInterceptor.convert(cd, beforeExecution, getCursor(), ctx);
                    maybeAddImport(RequestHandlerToInterceptor.EXECUTION_INTERCEPTOR);
                    maybeAddImport("software.amazon.awssdk.core.SdkRequest");
                    maybeAddImport("software.amazon.awssdk.core.interceptor.Context");
                    maybeRemoveImport(V1_REQUEST_HANDLER);
                    maybeAddImport("software.amazon.awssdk.core.interceptor.ExecutionAttributes");
                    doAfterVisit(new ChangeType("com.amazonaws.AmazonWebServiceRequest", "software.amazon.awssdk.core.SdkRequest", null).getVisitor());
                }
                return cd;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (isChainElement(m) && !extendsChain(m)) {
                    return apply(m, parseChain(m, getCursor(), convertibleHandlers, false), ctx);
                }
                JavaType.Method mt = m.getMethodType();
                if (mt == null) {
                    return m;
                }
                if (TypeUtils.isOfClassType(mt.getDeclaringType(), V1_CLIENT_CONFIGURATION) &&
                    UNFOLDED_CLIENT_SETTING.matcher(m.getSimpleName()).matches()) {
                    return SearchResult.found(m, "AWS SDK v1 ClientConfiguration setting not migrated: build the client from a local `new ClientConfiguration()` so it can be folded into the v2 builder");
                }
                String declaring = mt.getDeclaringType().getFullyQualifiedName();
                if (("setEndpoint".equals(m.getSimpleName()) || "setRegion".equals(m.getSimpleName())) &&
                    (V1_CLIENT_BASE.equals(declaring) || declaring.startsWith("com.amazonaws.services.") && !declaring.contains(".model."))) {
                    return SearchResult.found(m, "v2 clients are immutable: move this endpoint or region into the client builder");
                }
                return m;
            }

            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J.NewClass n = (J.NewClass) super.visitNewClass(newClass, ctx);
                if (TypeUtils.isOfClassType(n.getType(), V1_ENDPOINT_CONFIGURATION)) {
                    // Consumed by an enclosing chain when it is converted; otherwise left for a manual fix
                    return SearchResult.found(n, "AWS SDK v1 EndpointConfiguration not migrated: pass the endpoint and region to the v2 client builder");
                }
                return apply(n, parseConstructor(n, getCursor(), false), ctx);
            }

            private boolean extendsChain(J.MethodInvocation m) {
                Object parent = getCursor().getParentTreeCursor().getValue();
                return parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() != null &&
                       ((J.MethodInvocation) parent).getSelect().getId().equals(m.getId()) &&
                       isChainElement((J.MethodInvocation) parent);
            }

            private J apply(Expression original, Parsed parsed, ExecutionContext ctx) {
                if (parsed.failure != null) {
                    String message = "AWS SDK v1 client not migrated: " + parsed.failure;
                    J site = parsed.failureSite;
                    if (site == null || site == original) {
                        return SearchResult.found(original, message);
                    }
                    return new JavaIsoVisitor<Integer>() {
                        @Override
                        public @Nullable J preVisit(J tree, Integer p) {
                            return tree.getId().equals(site.getId()) ? SearchResult.found(tree, message) : tree;
                        }
                    }.visitNonNull(original, 0);
                }
                AwsClientChain chain = parsed.chain;
                if (chain == null) {
                    return original;
                }
                J result = JavaTemplate.builder(chain.template())
                        .imports(chain.imports.toArray(new String[0]))
                        .javaParser(javaParser(ctx, chain))
                        .build()
                        .apply(getCursor(), original.getCoordinates().replace(), chain.params.toArray());
                for (String fqn : chain.imports) {
                    maybeAddImport(fqn);
                }
                for (String v1 : Arrays.asList(V1_CLIENT_BUILDER, "com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration",
                        V1_CLIENT_CONFIGURATION, V1_PROTOCOL, V1_REGIONS)) {
                    maybeRemoveImport(v1);
                }
                JavaType.FullyQualified v1Type = TypeUtils.asFullyQualified(original instanceof J.MethodInvocation ?
                        rootReturnType((J.MethodInvocation) original) : original.getType());
                if (v1Type != null) {
                    maybeRemoveImport(v1Type);
                }
                if (!chain.foldedStatements.isEmpty()) {
                    Set<UUID> ids = new HashSet<>();
                    chain.foldedStatements.forEach(s -> ids.add(s.getId()));
                    getCursor().dropParentUntil(p -> p instanceof J.Block && ((J.Block) p).getStatements().stream().anyMatch(s -> ids.contains(s.getId())))
                            .computeMessageIfAbsent(REMOVE, k -> new HashSet<UUID>()).addAll(ids);
                }
                return chain.warning == null ? result : SearchResult.found(result, chain.warning);
            }
        });
    }

    private static @Nullable JavaType rootReturnType(J.MethodInvocation m) {
        Expression e = m;
        while (e instanceof J.MethodInvocation && !isRoot((J.MethodInvocation) e)) {
            e = ((J.MethodInvocation) e).getSelect();
        }
        return e instanceof J.MethodInvocation && ((J.MethodInvocation) e).getMethodType() != null ?
                ((J.MethodInvocation) e).getMethodType().getReturnType() : null;
    }

    /**
     * Only S3's client is on the template classpath; other v2 clients get a stub of their builder interface, which is all a
     * converted chain calls.
     */
    static JavaParser.Builder<?, ?> javaParser(ExecutionContext ctx, AwsClientChain chain) {
        JavaParser.Builder<?, ?> parser = JavaParser.fromJavaVersion().classpathFromResources(ctx, V2_ARTIFACTS);
        if (!chain.isS3()) {
            String pkg = chain.v2Client.substring(0, chain.v2Client.lastIndexOf('.'));
            String client = simpleName(chain.v2Client);
            String builder = client + "Builder";
            String base = chain.async ? "AwsAsyncClientBuilder" : "AwsSyncClientBuilder";
            parser.dependsOn(
                    "package " + pkg + ";\n" +
                    "import software.amazon.awssdk.awscore.client.builder.*;\n" +
                    "public interface " + builder + " extends " + base + "<" + builder + ", " + client + ">, AwsClientBuilder<" + builder + ", " + client + "> {}",
                    "package " + pkg + ";\n" +
                    "public interface " + client + " extends software.amazon.awssdk.awscore.AwsClient {\n" +
                    "    static " + builder + " builder() { return null; }\n" +
                    "}");
        }
        return parser;
    }
}

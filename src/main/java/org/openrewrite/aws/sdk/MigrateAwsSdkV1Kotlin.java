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
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.kotlin.KotlinVisitor;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.SearchResult;
import software.amazon.awssdk.v2migration.internal.utils.NamingConversionUtils;

import java.util.*;

import static java.util.Collections.*;
import static org.openrewrite.aws.sdk.AwsClientChain.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAwsSdkV1Kotlin extends Recipe {

    private static final String[] V2_ARTIFACTS = {"apache-client", "auth", "aws-core", "http-client-spi", "identity-spi",
            "netty-nio-client", "regions", "s3-2", "sdk-core", "utils"};

    @Getter
    String displayName = "Migrate AWS SDK v1 client setup in Kotlin to v2";

    @Getter
    String description = "AWS's v2-migration does not support Kotlin, so this converts the Kotlin shapes it would handle in Java: " +
            "client builder chains, `defaultClient()`, legacy client constructors, credentials providers, `Regions`, the client " +
            "and credential types, S3/SNS/SQS requests, client calls and result properties, and `StringUtils`. Kotlin files that use the DynamoDB mapper, document API or DynamoDB Local are left to the DynamoDB migration. Remaining v1 calls are marked.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                Preconditions.and(
                        new UsesType<>("com.amazonaws..*", false),
                        Preconditions.not(Preconditions.or(
                                new UsesType<>("com.amazonaws.services.dynamodbv2.datamodeling..*", false),
                                new UsesType<>("com.amazonaws.services.dynamodbv2.document..*", false),
                                new UsesType<>("com.amazonaws.services.dynamodbv2.local..*", false)))),
                new KotlinVisitor<ExecutionContext>() {
                    @Override
                    public J visitCompilationUnit(K.CompilationUnit cu, ExecutionContext ctx) {
                        Set<String> v1Types = new TreeSet<>();
                        for (JavaType type : cu.getTypesInUse().getTypesInUse()) {
                            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                            if (fq != null) {
                                v1Types.add(fq.getFullyQualifiedName());
                            }
                        }
                        J result = super.visitCompilationUnit(cu, ctx);
                        Set<UUID> folded = getCursor().getMessage(KotlinAwsModelCalls.FOLDED_STATEMENTS);
                        if (folded != null) {
                            removeStatements(folded);
                        }
                        for (String v1 : v1Types) {
                            String v2 = v2Type(v1);
                            if (v2 == null) {
                                v2 = KotlinAwsModelCalls.convertedRequestType(v1, result);
                            }
                            if (v2 != null) {
                                doAfterVisit(new ChangeType(v1, v2, null).getVisitor());
                            }
                        }
                        // Converted calls, and v1 imports that were already unused, leave v1 imports behind. This runs after
                        // the renames, because an import added for a same-named v2 type only lands once the v1 one is renamed.
                        for (J.Import imp : cu.getImports()) {
                            String name = imp.getQualid().toString();
                            if (name.startsWith("com.amazonaws.") && !"*".equals(imp.getQualid().getSimpleName())) {
                                maybeRemoveImport(name);
                            }
                        }
                        Set<String> shorten = getCursor().getMessage(KotlinAwsModelCalls.SHORTEN);
                        if (shorten != null) {
                            doAfterVisit(ShortenFullyQualifiedTypeReferences.modifyOnly(result, fqn -> !shorten.contains(fqn)));
                        }
                        return result;
                    }

                    @Override
                    public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        J j = super.visitMethodInvocation(method, ctx);
                        if (!(j instanceof J.MethodInvocation)) {
                            return j;
                        }
                        J.MethodInvocation m = (J.MethodInvocation) j;
                        if (isChainElement(m)) {
                            return extendsChain(m) ? m : apply(m, parseChain(m, getCursor(), emptySet(), true), ctx);
                        }
                        J credentials = KotlinAwsCredentialMappings.rewrite(this, m, ctx);
                        if (credentials != null) {
                            return credentials;
                        }
                        J model = KotlinAwsModelCalls.methodInvocation(this, m, ctx);
                        if (model != null) {
                            return model;
                        }
                        J utility = KotlinAwsUtilities.rewrite(this, m, ctx);
                        if (utility != null) {
                            return utility;
                        }
                        JavaType.Method mt = m.getMethodType();
                        if (mt == null) {
                            return m;
                        }
                        if ("defaultClient".equals(m.getSimpleName()) && mt.hasFlags(Flag.Static)) {
                            String v2Client = v2ClientForV1Builder(mt.getDeclaringType());
                            if (v2Client != null) {
                                String reference = clientReference(getCursor(), v2Client);
                                return template(m, reference + ".create()",
                                        reference.equals(v2Client) ? emptySet() : singleton(v2Client),
                                        emptyList(), ctx);
                            }
                        }
                        if ("getName".equals(m.getSimpleName()) && TypeUtils.isOfClassType(mt.getDeclaringType(), V1_REGIONS)) {
                            return m.withName(m.getName().withSimpleName("id"));
                        }
                        return isV1Sdk(mt.getDeclaringType().getFullyQualifiedName()) ?
                                SearchResult.found(m, "AWS SDK v1 call not migrated in Kotlin") : m;
                    }

                    @Override
                    public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                        J j = super.visitNewClass(newClass, ctx);
                        if (!(j instanceof J.NewClass)) {
                            return j;
                        }
                        J.NewClass n = (J.NewClass) j;
                        J credentials = KotlinAwsCredentialMappings.rewrite(this, n, ctx);
                        if (credentials != null) {
                            return credentials;
                        }
                        if (TypeUtils.isOfClassType(n.getType(), V1_ENDPOINT_CONFIGURATION)) {
                            return SearchResult.found(n, "AWS SDK v1 EndpointConfiguration not migrated: pass the endpoint and region to the v2 client builder");
                        }
                        Parsed parsed = parseConstructor(n, getCursor(), true);
                        if (parsed.chain != null || parsed.failure != null) {
                            return apply(n, parsed, ctx);
                        }
                        J model = KotlinAwsModelCalls.newClass(this, n, ctx);
                        if (model != null) {
                            return model;
                        }
                        JavaType.FullyQualified type = TypeUtils.asFullyQualified(n.getType());
                        return type != null && isV1Sdk(type.getFullyQualifiedName()) ?
                                SearchResult.found(n, "AWS SDK v1 construction not migrated in Kotlin") : n;
                    }

                    @Override
                    public J visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                        J.FieldAccess f = (J.FieldAccess) super.visitFieldAccess(fieldAccess, ctx);
                        String constant = regionsConstant(f);
                        if (constant != null && !constant.equals(v2RegionConstant(constant))) {
                            return f.withName(f.getName().withSimpleName(v2RegionConstant(constant)));
                        }
                        // v1's endpoint prefix is v2's service metadata id, e.g. "es" for Elasticsearch
                        JavaType.FullyQualified target = TypeUtils.asFullyQualified(f.getTarget().getType());
                        if ("ENDPOINT_PREFIX".equals(f.getSimpleName()) && target != null &&
                            target.getFullyQualifiedName().startsWith("com.amazonaws.services.") && v2Type(target.getFullyQualifiedName()) != null) {
                            return f.withName(f.getName().withSimpleName("SERVICE_METADATA_ID"));
                        }
                        J result = KotlinAwsModelCalls.fieldAccess(this, fieldAccess, f, ctx);
                        return result == null ? f : result;
                    }

                    private boolean extendsChain(J.MethodInvocation m) {
                        Object parent = getCursor().getParentTreeCursor().getValue();
                        return parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() != null &&
                               ((J.MethodInvocation) parent).getSelect().getId().equals(m.getId()) &&
                               isChainElement((J.MethodInvocation) parent);
                    }

                    private J apply(Expression original, Parsed parsed, ExecutionContext ctx) {
                        if (parsed.failure != null) {
                            return SearchResult.found(original, "AWS SDK v1 client not migrated: " + parsed.failure);
                        }
                        AwsClientChain chain = Objects.requireNonNull(parsed.chain);
                        J result = template(original, chain.template(), chain.imports, chain.params, ctx);
                        if (!chain.foldedStatements.isEmpty()) {
                            Set<UUID> ids = new HashSet<>();
                            chain.foldedStatements.forEach(s -> ids.add(s.getId()));
                            removeStatements(ids);
                        }
                        return chain.warning == null ? result : SearchResult.found(result, chain.warning);
                    }

                    private void removeStatements(Set<UUID> ids) {
                        doAfterVisit(new KotlinVisitor<ExecutionContext>() {
                            @Override
                            public J visitBlock(J.Block block, ExecutionContext ctx) {
                                J.Block b = (J.Block) super.visitBlock(block, ctx);
                                return b.withStatements(ListUtils.map(b.getStatements(), s -> ids.contains(s.getId()) ? null : s));
                            }
                        });
                    }

                    private J template(Expression original, String code, Collection<String> imports, List<Expression> params, ExecutionContext ctx) {
                        J result = KotlinTemplate.builder(code)
                                .imports(imports.toArray(new String[0]))
                                .parser(KotlinParser.builder().classpathFromResources(ctx, V2_ARTIFACTS))
                                .build()
                                .apply(getCursor(), original.getCoordinates().replace(), params.toArray());
                        for (String fqn : imports) {
                            // Only S3's v2 client is on the template classpath, so other clients are imported without a type check
                            maybeAddImport(fqn, !fqn.startsWith("software.amazon.awssdk.services.") || fqn.startsWith("software.amazon.awssdk.services.s3."));
                        }
                        for (String v1 : Arrays.asList(V1_CLIENT_BUILDER, "com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration",
                                V1_CLIENT_CONFIGURATION, V1_PROTOCOL, V1_REGIONS)) {
                            maybeRemoveImport(v1);
                        }
                        JavaType.FullyQualified v1Type = TypeUtils.asFullyQualified(original.getType());
                        if (v1Type != null) {
                            maybeRemoveImport(v1Type);
                        }
                        if (original instanceof J.MethodInvocation) {
                            for (Expression e = original; e instanceof J.MethodInvocation; e = ((J.MethodInvocation) e).getSelect()) {
                                JavaType.Method mt = ((J.MethodInvocation) e).getMethodType();
                                if (mt != null) {
                                    maybeRemoveImport(mt.getDeclaringType());
                                }
                            }
                        }
                        return result.withPrefix(original.getPrefix());
                    }
                });
    }

    /**
     * The v2 name of a v1 client, credentials or region type; null for types this recipe does not rename.
     */
    static @org.jspecify.annotations.Nullable String v2Type(String v1) {
        String credentials = KotlinAwsCredentialMappings.TYPES.get(v1);
        if (credentials != null) {
            return credentials;
        }
        if (V1_REGIONS.equals(v1)) {
            return "software.amazon.awssdk.regions.Region";
        }
        int lastDot = v1.lastIndexOf('.');
        String pkg = v1.substring(0, lastDot);
        String name = v1.substring(lastDot + 1);
        if (pkg.startsWith("com.amazonaws.services.") && pkg.indexOf('.', "com.amazonaws.services.".length()) < 0 &&
            (name.startsWith("Amazon") || name.startsWith("AWS")) && !name.contains("$") && !name.endsWith("Builder") &&
            !name.contains("Encryption") && !name.endsWith("Exception")) {
            return NamingConversionUtils.getV2Equivalent(v1);
        }
        return null;
    }

    private static boolean isV1Sdk(String fqn) {
        return fqn.startsWith("com.amazonaws.") && !fqn.startsWith("com.amazonaws.services.lambda.runtime") &&
               !fqn.startsWith("com.amazonaws.xray") && !fqn.startsWith("com.amazonaws.serverless");
    }
}

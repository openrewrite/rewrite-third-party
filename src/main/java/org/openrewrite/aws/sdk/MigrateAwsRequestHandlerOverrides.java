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
import org.openrewrite.*;
import org.openrewrite.binary.Binary;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;
import org.openrewrite.quark.Quark;
import org.openrewrite.remote.Remote;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.openrewrite.Tree.randomId;

/**
 * SDK v1 loads a service's handler chain from {@code com/amazonaws/services/<service>/request.handlers} (or
 * {@code request.handler2s}) on the classpath, so a project file of that name replaces the SDK's own chain. Leaving out a
 * default handler disables what it does; v2 ignores these files, which would silently turn the behaviour back on.
 */
public class MigrateAwsRequestHandlerOverrides extends ScanningRecipe<MigrateAwsRequestHandlerOverrides.Accumulator> {

    private static final Pattern OVERRIDE = Pattern.compile("(?:.*/)?com/amazonaws/services/([^/]+)/(request\\.handler2?s)");

    static final String SQS_MD5_HANDLER = "com.amazonaws.services.sqs.MessageMD5ChecksumHandler";

    // The chains v1 ships, as listed in the aws-java-sdk-* jars
    private static final Map<String, List<String>> V1_CHAINS = new HashMap<>();

    static {
        chain("sqs/request.handlers", "com.amazonaws.services.sqs.QueueUrlHandler",
                "com.amazonaws.services.sqs.internal.SQSRequestHandler", SQS_MD5_HANDLER);
        chain("route53/request.handlers", "com.amazonaws.services.route53.internal.Route53IdRequestHandler");
        chain("glacier/request.handlers", "com.amazonaws.services.glacier.internal.AcceptJsonRequestHandler",
                "com.amazonaws.services.glacier.internal.GlacierRequestHandler");
        chain("ec2/request.handlers", "com.amazonaws.services.ec2.model.transform.EC2RequestHandler");
        chain("ec2/request.handler2s", "com.amazonaws.services.ec2.model.transform.GeneratePreSignUrlRequestHandler",
                "com.amazonaws.services.ec2.model.transform.TimestampFormatRequestHandler");
        chain("logs/request.handlers", "com.amazonaws.services.logs.internal.AcceptJsonRequestHandler");
        chain("apigateway/request.handler2s", "com.amazonaws.services.apigateway.internal.AcceptJsonRequestHandler");
        chain("cloudsearchdomain/request.handler2s", "com.amazonaws.services.cloudsearchdomain.SwitchToPostHandler");
        chain("dynamodbv2/request.handler2s", "com.amazonaws.services.dynamodbv2.AccountEndpointOverrideRequestHandler");
        chain("machinelearning/request.handler2s", "com.amazonaws.services.machinelearning.internal.PredictEndpointHandler",
                "com.amazonaws.services.machinelearning.internal.RandomIdHandler");
    }

    private static void chain(String key, String... handlers) {
        V1_CHAINS.put(key, Arrays.asList(handlers));
    }

    /**
     * Default handlers whose removal v2 can express. Every other v2 counterpart is internal and always on.
     */
    private static final Set<String> TRANSLATABLE_REMOVALS = singleton(SQS_MD5_HANDLER);

    private static final MethodMatcher SQS_BUILDER = new MethodMatcher("software.amazon.awssdk.services.sqs.SqsClient builder()");
    private static final MethodMatcher SQS_ASYNC_BUILDER = new MethodMatcher("software.amazon.awssdk.services.sqs.SqsAsyncClient builder()");
    private static final MethodMatcher SQS_CREATE = new MethodMatcher("software.amazon.awssdk.services.sqs.SqsClient create()");
    private static final MethodMatcher SQS_ASYNC_CREATE = new MethodMatcher("software.amazon.awssdk.services.sqs.SqsAsyncClient create()");

    @Getter
    final String displayName = "Migrate AWS SDK v1 request handler chain overrides";

    @Getter
    final String description = "Translates a project's `com/amazonaws/services/<service>/request.handlers` or `request.handler2s` " +
                               "file, which replaces SDK v1's built-in handler chain for that service, and then deletes it. Leaving " +
                               "out SQS's `MessageMD5ChecksumHandler` becomes `checksumValidationEnabled(false)` on every SQS client " +
                               "builder of the module, Java or Kotlin, and `SqsClient.create()` becomes such a builder. Files that list custom handlers, or drop a handler v2 cannot turn off, are kept.";

    public static class Accumulator {
        final Map<String, Set<String>> removalsByModule = new HashMap<>();
    }

    /**
     * The v1 default handlers a handler chain override leaves out, or null if the file is not an override that can be
     * migrated: one that lists handlers v1 does not ship, or drops one v2 has no switch for.
     */
    public static @Nullable Set<String> translatableRemovals(SourceFile s) {
        Matcher m = OVERRIDE.matcher(s.getSourcePath().toString().replace('\\', '/'));
        if (!m.matches() || s instanceof Quark || s instanceof Remote || s instanceof Binary) {
            return null;
        }
        List<String> defaults = V1_CHAINS.get(m.group(1) + "/" + m.group(2));
        if (defaults == null) {
            return null;
        }
        Set<String> listed = new LinkedHashSet<>();
        for (String line : s.printAll().split("\\R")) {
            String handler = line.trim();
            if (!handler.isEmpty() && !handler.startsWith("#")) {
                listed.add(handler);
            }
        }
        if (!defaults.containsAll(listed)) {
            return null;
        }
        Set<String> removed = new LinkedHashSet<>(defaults);
        removed.removeAll(listed);
        return TRANSLATABLE_REMOVALS.containsAll(removed) ? removed : null;
    }

    public static String moduleOf(SourceFile s) {
        String path = s.getSourcePath().toString().replace('\\', '/');
        if (path.startsWith("src/")) {
            return "";
        }
        int src = path.indexOf("/src/");
        return src < 0 ? "" : path.substring(0, src);
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
                    Set<String> removals = translatableRemovals((SourceFile) tree);
                    if (removals != null) {
                        acc.removalsByModule.computeIfAbsent(moduleOf((SourceFile) tree), k -> new HashSet<>()).addAll(removals);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile) || acc.removalsByModule.isEmpty()) {
                    return tree;
                }
                SourceFile s = (SourceFile) tree;
                if (translatableRemovals(s) != null) {
                    return null;
                }
                Set<String> removals = acc.removalsByModule.get(moduleOf(s));
                if ((s instanceof J.CompilationUnit || s instanceof K.CompilationUnit) && removals != null &&
                    removals.contains(SQS_MD5_HANDLER)) {
                    return new DisableSqsChecksumValidation().visit(s, ctx);
                }
                return s;
            }
        };
    }

    private static class DisableSqsChecksumValidation extends JavaIsoVisitor<ExecutionContext> {
        private static final String SQS = "software.amazon.awssdk.services.sqs.";

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
            Boolean async = sqsClientCall(m, "builder", SQS_BUILDER, SQS_ASYNC_BUILDER);
            if (async != null) {
                return chainSetsChecksumValidation() ? m : disable(m, async, ctx);
            }
            async = sqsClientCall(m, "create", SQS_CREATE, SQS_ASYNC_CREATE);
            if (async == null) {
                return m;
            }
            // `create()` has no builder to configure, so it becomes the builder it stands for
            if (isKotlin()) {
                JavaType.FullyQualified builderType = JavaType.ShallowClass.build(builderFqn(async));
                JavaType.FullyQualified clientType = m.getMethodType() == null ? null : m.getMethodType().getDeclaringType();
                J.MethodInvocation builder = call(m.getSelect(), "builder", null, clientType, builderType);
                return call(checksumDisabled(builder, async), "build", null, builderType, clientType);
            }
            String client = async ? "SqsAsyncClient" : "SqsClient";
            maybeAddImport(SQS + client);
            return JavaTemplate.builder(client + ".builder().checksumValidationEnabled(false).build()")
                    .imports(SQS + client)
                    .javaParser(parser(ctx))
                    .build()
                    .apply(getCursor(), m.getCoordinates().replace());
        }

        private J.MethodInvocation disable(J.MethodInvocation builder, boolean async, ExecutionContext ctx) {
            if (isKotlin()) {
                return checksumDisabled(builder, async);
            }
            return JavaTemplate.builder("#{any(" + builderFqn(async) + ")}.checksumValidationEnabled(false)")
                    .javaParser(parser(ctx))
                    .build()
                    .apply(getCursor(), builder.getCoordinates().replace(), builder);
        }

        private boolean isKotlin() {
            return getCursor().firstEnclosing(K.CompilationUnit.class) != null;
        }

        // JavaTemplate cannot produce Kotlin, so Kotlin calls are assembled as the Kotlin parser would
        private static J.MethodInvocation checksumDisabled(J.MethodInvocation builder, boolean async) {
            JavaType.FullyQualified builderType = JavaType.ShallowClass.build(builderFqn(async));
            return call(builder, "checksumValidationEnabled",
                    new J.Literal(randomId(), Space.EMPTY, Markers.EMPTY, false, "false", null, JavaType.Primitive.Boolean),
                    builderType, builderType);
        }

        private static JavaParser.Builder<?, ?> parser(ExecutionContext ctx) {
            return JavaParser.fromJavaVersion().classpathFromResources(ctx, "sqs-2", "aws-core-2",
                    "sdk-core-2", "regions-2", "utils-2", "auth-2", "identity-spi-2", "http-client-spi-2");
        }

        private static String builderFqn(boolean async) {
            return SQS + (async ? "SqsAsyncClientBuilder" : "SqsClientBuilder");
        }

        private static J.MethodInvocation call(@Nullable Expression select, String name, @Nullable Expression argument,
                                               JavaType.@Nullable FullyQualified declaringType, @Nullable JavaType returnType) {
            JavaType.Method type = declaringType == null ? null : new JavaType.Method(null, Flag.Public.getBitMask(),
                    declaringType, name, returnType, emptyList(),
                    argument == null ? emptyList() : singletonList(JavaType.Primitive.Boolean), emptyList(), emptyList(), null, null);
            return new J.MethodInvocation(randomId(), select == null ? Space.EMPTY : select.getPrefix(), Markers.EMPTY,
                    select == null ? null : JRightPadded.build(select.withPrefix(Space.EMPTY)), null,
                    new J.Identifier(randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), name, type, null),
                    JContainer.build(singletonList(JRightPadded.build(argument == null ?
                            new J.Empty(randomId(), Space.EMPTY, Markers.EMPTY) : argument))),
                    type);
        }

        /**
         * True for an async client, false for a sync one, null when the call is not {@code name()} on an SQS client.
         */
        private @Nullable Boolean sqsClientCall(J.MethodInvocation m, String name, MethodMatcher sync, MethodMatcher async) {
            if (async.matches(m)) {
                return true;
            }
            if (sync.matches(m)) {
                return false;
            }
            if (m.getMethodType() == null && name.equals(m.getSimpleName()) &&
                m.getArguments().stream().allMatch(J.Empty.class::isInstance) &&
                m.getSelect() instanceof J.Identifier && importsSqsClient(((J.Identifier) m.getSelect()).getSimpleName())) {
                // Freshly migrated sources may not carry v2 types yet
                return "SqsAsyncClient".equals(((J.Identifier) m.getSelect()).getSimpleName());
            }
            return null;
        }

        private boolean importsSqsClient(String simpleName) {
            if (!"SqsClient".equals(simpleName) && !"SqsAsyncClient".equals(simpleName)) {
                return false;
            }
            JavaSourceFile cu = getCursor().firstEnclosing(JavaSourceFile.class);
            return cu != null && cu.getImports().stream().anyMatch(i ->
                    (SQS + simpleName).equals(i.getTypeName()) ||
                    (!i.isStatic() && (SQS + "*").equals(i.getQualid().toString())));
        }

        private boolean chainSetsChecksumValidation() {
            Object child = getCursor().getValue();
            for (Cursor c = getCursor().getParentTreeCursor(); c.getValue() instanceof J.MethodInvocation; c = c.getParentTreeCursor()) {
                J.MethodInvocation outer = c.getValue();
                if (outer.getSelect() != child) {
                    break;
                }
                if ("checksumValidationEnabled".equals(outer.getSimpleName())) {
                    return true;
                }
                child = outer;
            }
            return false;
        }
    }
}

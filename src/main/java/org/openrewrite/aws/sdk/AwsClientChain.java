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

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.*;
import software.amazon.awssdk.v2migration.internal.utils.NamingConversionUtils;

import java.util.*;

import static java.util.Collections.emptyList;

/**
 * A v1 client builder chain or legacy client constructor, parsed into the calls of the equivalent v2 builder chain.
 * Shared by the Java and Kotlin recipes so both produce the same v2 configuration.
 */
final class AwsClientChain {

    static final String V1_CLIENT_BUILDER = "com.amazonaws.client.builder.AwsClientBuilder";
    static final String V1_ENDPOINT_CONFIGURATION = "com.amazonaws.client.builder.AwsClientBuilder$EndpointConfiguration";
    static final String V1_CLIENT_CONFIGURATION = "com.amazonaws.ClientConfiguration";
    static final String V1_PROTOCOL = "com.amazonaws.Protocol";
    static final String V1_REGIONS = "com.amazonaws.regions.Regions";
    static final String V1_CREDENTIALS = "com.amazonaws.auth.AWSCredentials";
    static final String V1_CREDENTIALS_PROVIDER = "com.amazonaws.auth.AWSCredentialsProvider";
    static final String V1_REQUEST_HANDLER = "com.amazonaws.handlers.RequestHandler2";

    static final String S3_CLIENT = "software.amazon.awssdk.services.s3.S3Client";

    private static final Set<String> HTTP_SETTINGS = new HashSet<>(Arrays.asList(
            "MaxConnections", "ConnectionTimeout", "SocketTimeout", "ConnectionTTL", "ConnectionMaxIdleMillis",
            "TcpKeepAlive", "ProxyHost", "ProxyPort", "ProxyUsername", "ProxyPassword"));
    private static final Set<String> OVERRIDE_SETTINGS = new HashSet<>(Arrays.asList(
            "MaxErrorRetry", "RequestTimeout", "ClientExecutionTimeout"));

    final String v2Client;
    final String clientReference;
    final boolean async;
    final boolean kotlin;
    final List<String> code = new ArrayList<>();
    final List<Expression> params = new ArrayList<>();
    final Set<String> imports = new LinkedHashSet<>();
    final List<Statement> foldedStatements = new ArrayList<>();
    @Nullable String warning;

    private AwsClientChain(String v2Client, Cursor cursor, boolean kotlin) {
        this.v2Client = v2Client;
        this.clientReference = clientReference(cursor, v2Client);
        this.async = v2Client.endsWith("AsyncClient");
        this.kotlin = kotlin;
        if (clientReference.equals(simpleName(v2Client))) {
            imports.add(v2Client);
        }
    }

    /**
     * The simple name of a v2 client, or its fully qualified name when the file already uses a different type of that name,
     * such as a project's own {@code SnsClient} interface.
     */
    static String clientReference(Cursor cursor, String v2Client) {
        String simpleName = simpleName(v2Client);
        JavaSourceFile sourceFile = cursor.firstEnclosing(JavaSourceFile.class);
        if (sourceFile == null) {
            return simpleName;
        }
        for (JavaType type : sourceFile.getTypesInUse().getTypesInUse()) {
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
            if (fq != null && simpleName.equals(simpleName(fq.getFullyQualifiedName().replace('$', '.'))) &&
                !v2Client.equals(fq.getFullyQualifiedName())) {
                return v2Client;
            }
        }
        for (J.ClassDeclaration classDecl : sourceFile.getClasses()) {
            if (simpleName.equals(classDecl.getSimpleName())) {
                return v2Client;
            }
        }
        return simpleName;
    }

    boolean isS3() {
        return S3_CLIENT.equals(v2Client);
    }

    String template() {
        return String.join("", code);
    }

    /**
     * Outcome of parsing: a convertible chain, a reason it cannot be converted (with the site to mark), or not applicable
     * because nothing in it needs more than AWS's v2-migration provides.
     */
    static final class Parsed {
        final @Nullable AwsClientChain chain;
        final @Nullable String failure;
        final @Nullable J failureSite;

        private Parsed(@Nullable AwsClientChain chain, @Nullable String failure, @Nullable J failureSite) {
            this.chain = chain;
            this.failure = failure;
            this.failureSite = failureSite;
        }

        static final Parsed NOT_APPLICABLE = new Parsed(null, null, null);
    }

    /**
     * Whether {@code method} is part of a v1 client builder chain: a static {@code standard()}/{@code builder()}/{@code asyncBuilder()}
     * returning a v1 builder, or a call on a v1 builder.
     */
    static boolean isChainElement(J.MethodInvocation method) {
        JavaType.Method mt = method.getMethodType();
        if (mt == null) {
            return false;
        }
        if (isRoot(method)) {
            return true;
        }
        return !mt.hasFlags(Flag.Static) && isV1ClientBuilder(mt.getDeclaringType());
    }

    static boolean isRoot(J.MethodInvocation method) {
        JavaType.Method mt = method.getMethodType();
        return mt != null && mt.hasFlags(Flag.Static) && method.getArguments().stream().allMatch(J.Empty.class::isInstance) &&
               ("standard".equals(mt.getName()) || "builder".equals(mt.getName()) || "asyncBuilder".equals(mt.getName())) &&
               isV1ClientBuilder(mt.getReturnType());
    }

    /**
     * A builder retyped by {@code ChangeType} earlier in the run (e.g. the messaging migration's async to sync retyping) is a
     * shallow class without supertypes, so it is recognized by name.
     */
    private static boolean isV1ClientBuilder(@Nullable JavaType type) {
        if (TypeUtils.isAssignableTo(V1_CLIENT_BUILDER, type)) {
            return true;
        }
        return type instanceof JavaType.ShallowClass &&
               ((JavaType.ShallowClass) type).getFullyQualifiedName().startsWith("com.amazonaws.services.") &&
               ((JavaType.ShallowClass) type).getClassName().endsWith("ClientBuilder");
    }

    static @Nullable String v2ClientForV1Builder(JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq == null || !fq.getFullyQualifiedName().startsWith("com.amazonaws.services.") ||
            !fq.getClassName().endsWith("ClientBuilder") || isSkipped(fq.getFullyQualifiedName())) {
            return null;
        }
        String v2Builder = NamingConversionUtils.getV2Equivalent(fq.getFullyQualifiedName());
        return v2Builder.endsWith("Builder") ? v2Builder.substring(0, v2Builder.length() - "Builder".length()) : null;
    }

    /**
     * Only plain service clients such as {@code com.amazonaws.services.sqs.AmazonSQSClient}. Clients in sub-packages
     * (the SQS buffered client, S3 encryption) have no same-named v2 client.
     */
    static @Nullable String v2ClientForV1Client(JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq == null) {
            return null;
        }
        String fqn = fq.getFullyQualifiedName();
        String pkg = fq.getPackageName();
        String name = fq.getClassName();
        if (!pkg.startsWith("com.amazonaws.services.") || pkg.indexOf('.', "com.amazonaws.services.".length()) >= 0 ||
            !name.endsWith("Client") || !(name.startsWith("Amazon") || name.startsWith("AWS")) || isSkipped(fqn)) {
            return null;
        }
        return NamingConversionUtils.getV2Equivalent(fqn);
    }

    private static boolean isSkipped(String fqn) {
        return fqn.contains("Encryption") || fqn.startsWith("com.amazonaws.services.kinesis.clientlibrary") ||
               fqn.startsWith("com.amazonaws.services.lambda.runtime");
    }

    static Parsed parseChain(J.MethodInvocation outermost, Cursor cursor, Set<String> convertibleHandlers, boolean kotlin) {
        return parseChain(outermost, cursor, convertibleHandlers, kotlin, kotlin);
    }

    /**
     * @param force convert the chain even when AWS's v2-migration could, for files whose other code needs it on v2 already
     */
    static Parsed parseChain(J.MethodInvocation outermost, Cursor cursor, Set<String> convertibleHandlers, boolean kotlin,
                             boolean force) {
        LinkedList<J.MethodInvocation> elements = new LinkedList<>();
        Expression current = outermost;
        while (current instanceof J.MethodInvocation && isChainElement((J.MethodInvocation) current)) {
            J.MethodInvocation m = (J.MethodInvocation) current;
            elements.addFirst(m);
            if (isRoot(m)) {
                break;
            }
            current = m.getSelect();
        }
        J.MethodInvocation root = elements.getFirst();
        boolean triggered = force;
        String failure = null;
        J failureSite = null;
        for (J.MethodInvocation m : elements) {
            if (isTrigger(m.getSimpleName()) && !onlyXRayHandlers(m)) {
                triggered = true;
                if (failureSite == null) {
                    failureSite = m;
                }
            }
        }
        if (!isRoot(root)) {
            return triggered ? new Parsed(null, "the v1 builder is not created in this expression, so it cannot be converted as a whole", failureSite) :
                    Parsed.NOT_APPLICABLE;
        }
        String v2Client = v2ClientForV1Builder(root.getMethodType().getReturnType());
        if (v2Client == null) {
            return triggered ? new Parsed(null, "no v2 client is known for this v1 builder", failureSite) : Parsed.NOT_APPLICABLE;
        }

        AwsClientChain chain = new AwsClientChain(v2Client, cursor, kotlin);
        chain.code.add(chain.clientReference + whitespaceBefore(root) + ".builder()");
        Settings settings = new Settings();
        List<Expression> interceptors = new ArrayList<>();
        int overrideSlot = -1;
        String overrideWhitespace = "";
        for (J.MethodInvocation m : elements.subList(1, elements.size())) {
            String ws = whitespaceBefore(m);
            List<Expression> args = arguments(m);
            switch (m.getSimpleName()) {
                case "withRegion":
                    if (!chain.region(ws, args.get(0))) {
                        failure = "the region argument has no v2 equivalent";
                    }
                    break;
                case "withCredentials":
                    chain.call(ws, ".credentialsProvider(" + args.get(0).getPrefix().getWhitespace() +
                                   chain.p("software.amazon.awssdk.auth.credentials.AwsCredentialsProvider") + ")", args.get(0));
                    break;
                case "withEndpointConfiguration": {
                    List<Expression> endpointArgs = constructorArguments(args.get(0), V1_ENDPOINT_CONFIGURATION);
                    if (endpointArgs == null) {
                        Folded folded = foldLocal(args.get(0), cursor, V1_ENDPOINT_CONFIGURATION);
                        if (folded != null && folded.setters.isEmpty()) {
                            endpointArgs = constructorArguments(folded.initializer, V1_ENDPOINT_CONFIGURATION);
                            chain.foldedStatements.addAll(folded.statements);
                        }
                    }
                    if (endpointArgs == null || endpointArgs.size() != 2) {
                        failure = "the EndpointConfiguration is not created in this method";
                        failureSite = m;
                    } else {
                        chain.endpoint(ws, endpointArgs.get(0), endpointArgs.get(1));
                    }
                    break;
                }
                case "withClientConfiguration": {
                    String reason = settings.fold(args.get(0), cursor, chain);
                    if (reason != null) {
                        failure = reason;
                    } else {
                        triggered |= settings.isTrigger();
                        if (failureSite == null) {
                            failureSite = m;
                        }
                        chain.httpClient(ws, settings);
                        if (overrideSlot < 0) {
                            overrideSlot = chain.code.size();
                            overrideWhitespace = settings.http.isEmpty() ? ws : indentation(ws);
                        }
                    }
                    break;
                }
                case "withPathStyleAccessEnabled":
                    if (!chain.isS3()) {
                        failure = "path-style access only applies to S3";
                    } else {
                        chain.call(ws, ".forcePathStyle(" + chain.p("java.lang.Boolean") + ")", args.get(0));
                    }
                    break;
                case "enablePathStyleAccess":
                    chain.call(ws, ".forcePathStyle(true)");
                    break;
                case "withRequestHandlers":
                    for (Expression handler : args) {
                        JavaType.FullyQualified type = TypeUtils.asFullyQualified(handler.getType());
                        if (isXRayHandler(handler)) {
                            // Kept as is for MigrateXRayTracingHandler, which turns it into a TracingInterceptor after v2-migration
                            chain.call(ws, ".withRequestHandlers(" + chain.p("java.lang.Object") + ")", handler);
                            ws = indentation(ws);
                        } else if (type == null || !convertibleHandlers.contains(type.getFullyQualifiedName())) {
                            failure = "request handler `" + (type == null ? handler.printTrimmed(cursor) : type.getClassName()) +
                                      "` has no ExecutionInterceptor equivalent";
                            failureSite = m;
                        } else {
                            interceptors.add(handler);
                        }
                    }
                    if (overrideSlot < 0) {
                        overrideSlot = chain.code.size();
                        overrideWhitespace = ws;
                    }
                    break;
                case "build":
                    chain.call(ws, ".build()");
                    break;
                default:
                    failure = "`" + m.getSimpleName() + "` has no v2 mapping in this recipe";
                    failureSite = m;
            }
        }
        if (!triggered) {
            return Parsed.NOT_APPLICABLE;
        }
        if (failure != null) {
            return new Parsed(null, failure, failureSite == null ? outermost : failureSite);
        }
        if (overrideSlot >= 0) {
            chain.overrideConfiguration(overrideSlot, overrideWhitespace, settings, interceptors);
        }
        return new Parsed(chain, null, null);
    }

    /**
     * Legacy constructors such as {@code new AmazonS3Client(credentialsProvider)}. They always talk to us-east-1 (S3 also follows
     * a bucket's region), unlike a builder, which reads the region from the environment.
     */
    static Parsed parseConstructor(J.NewClass newClass, Cursor cursor, boolean kotlin) {
        JavaType.Method ctor = newClass.getMethodType();
        String v2Client = v2ClientForV1Client(newClass.getType());
        if (ctor == null || v2Client == null) {
            return Parsed.NOT_APPLICABLE;
        }
        List<Expression> args = arguments(newClass.getArguments());
        if (args.isEmpty() && !kotlin) {
            return Parsed.NOT_APPLICABLE;
        }
        AwsClientChain chain = new AwsClientChain(v2Client, cursor, kotlin);
        chain.code.add(chain.clientReference + ".builder()");
        chain.call("", ".region(Region.US_EAST_1)");
        chain.imports.add("software.amazon.awssdk.regions.Region");
        if (chain.isS3()) {
            chain.call("", ".crossRegionAccessEnabled(true)");
        }
        Settings settings = new Settings();
        for (int i = 0; i < args.size(); i++) {
            JavaType paramType = i < ctor.getParameterTypes().size() ? ctor.getParameterTypes().get(i) : null;
            Expression arg = args.get(i);
            if (TypeUtils.isOfClassType(paramType, V1_CREDENTIALS_PROVIDER)) {
                chain.call("", ".credentialsProvider(" + chain.p("software.amazon.awssdk.auth.credentials.AwsCredentialsProvider") + ")", arg);
            } else if (TypeUtils.isOfClassType(paramType, V1_CREDENTIALS)) {
                chain.imports.add("software.amazon.awssdk.auth.credentials.StaticCredentialsProvider");
                chain.call("", ".credentialsProvider(StaticCredentialsProvider.create(" +
                               chain.p("software.amazon.awssdk.auth.credentials.AwsCredentials") + "))", arg);
            } else if (TypeUtils.isOfClassType(paramType, V1_CLIENT_CONFIGURATION)) {
                String reason = settings.fold(arg, cursor, chain);
                if (reason != null) {
                    return new Parsed(null, reason, newClass);
                }
                chain.httpClient("", settings);
                chain.overrideConfiguration(chain.code.size(), "", settings, emptyList());
            } else {
                return new Parsed(null, "this constructor has no v2 builder equivalent", newClass);
            }
        }
        chain.call("", ".build()");
        return new Parsed(chain, null, null);
    }

    private static final String XRAY_TRACING_HANDLER = "com.amazonaws.xray.handlers.TracingHandler";

    private static boolean isXRayHandler(Expression handler) {
        return TypeUtils.isOfClassType(handler.getType(), XRAY_TRACING_HANDLER);
    }

    private static boolean onlyXRayHandlers(J.MethodInvocation m) {
        return "withRequestHandlers".equals(m.getSimpleName()) &&
               m.getArguments().stream().allMatch(AwsClientChain::isXRayHandler);
    }

    private static boolean isTrigger(String name) {
        return "withEndpointConfiguration".equals(name) || "withPathStyleAccessEnabled".equals(name) ||
               "enablePathStyleAccess".equals(name) || "withRequestHandlers".equals(name);
    }

    private String p(String type) {
        return kotlin ? "#{any()}" : "#{any(" + type + ")}";
    }

    private void call(String ws, String call, Expression... args) {
        code.add(ws + call);
        params.addAll(Arrays.asList(args));
    }

    private static final MethodMatcher REGIONS_FROM_NAME = new MethodMatcher(V1_REGIONS + " fromName(String)");

    private boolean region(String ws, Expression region) {
        imports.add("software.amazon.awssdk.regions.Region");
        if (TypeUtils.isString(region.getType()) || TypeUtils.isOfClassType(region.getType(), "kotlin.String")) {
            call(ws, ".region(Region.of(" + p("java.lang.String") + "))", region);
            return true;
        }
        if (region instanceof J.MethodInvocation && REGIONS_FROM_NAME.matches((J.MethodInvocation) region)) {
            call(ws, ".region(Region.of(" + p("java.lang.String") + "))", ((J.MethodInvocation) region).getArguments().get(0));
            return true;
        }
        if (TypeUtils.isOfClassType(region.getType(), V1_REGIONS)) {
            String constant = regionsConstant(region);
            if (constant != null) {
                call(ws, ".region(Region." + v2RegionConstant(constant) + ")");
                return true;
            }
            if (!kotlin) {
                call(ws, ".region(Region.of(" + p(V1_REGIONS) + ".getName()))", region);
                return true;
            }
        }
        return false;
    }

    private void endpoint(String ws, Expression endpoint, Expression signingRegion) {
        imports.add("java.net.URI");
        call(ws, ".endpointOverride(URI.create(" + p("java.lang.String") + "))", withScheme(endpoint));
        if (isNullOrBlank(signingRegion)) {
            warning = "v1 derived the signing region from the endpoint; v2 reads it from the environment. Set `.region(..)` if the environment has none";
        } else {
            imports.add("software.amazon.awssdk.regions.Region");
            call(indentation(ws), ".region(Region.of(" + p("java.lang.String") + "))", signingRegion);
        }
    }

    /**
     * v1 prepends the client's protocol (HTTPS by default) to an endpoint without a scheme. v2 requires one.
     */
    private static Expression withScheme(Expression endpoint) {
        if (endpoint instanceof J.Literal && ((J.Literal) endpoint).getValue() instanceof String) {
            J.Literal literal = (J.Literal) endpoint;
            String value = (String) literal.getValue();
            if (!value.contains("://") && literal.getValueSource() != null) {
                return literal.withValue("https://" + value)
                        .withValueSource(literal.getValueSource().replaceFirst("^\"", "\"https://"));
            }
        }
        return endpoint;
    }

    private static boolean isNullOrBlank(Expression e) {
        if (e instanceof J.Literal) {
            Object value = ((J.Literal) e).getValue();
            return value == null || value instanceof String && ((String) value).trim().isEmpty();
        }
        return false;
    }

    private void httpClient(String ws, Settings settings) {
        if (settings.http.isEmpty()) {
            return;
        }
        StringBuilder b = new StringBuilder();
        List<Expression> args = new ArrayList<>();
        String clientClass = async ? "software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient" : "software.amazon.awssdk.http.apache.ApacheHttpClient";
        imports.add(clientClass);
        b.append(".httpClientBuilder(").append(simpleName(clientClass)).append(".builder()");
        for (Map.Entry<String, Expression> e : settings.http.entrySet()) {
            String millis = "Duration.ofMillis(" + p("long") + ")";
            switch (e.getKey()) {
                case "MaxConnections":
                    b.append(async ? ".maxConcurrency(" : ".maxConnections(").append(p("java.lang.Integer")).append(")");
                    break;
                case "ConnectionTimeout":
                    b.append(".connectionTimeout(").append(millis).append(")");
                    break;
                case "SocketTimeout":
                    b.append(".socketTimeout(").append(millis).append(")");
                    break;
                case "ConnectionTTL":
                    b.append(".connectionTimeToLive(").append(millis).append(")");
                    break;
                case "ConnectionMaxIdleMillis":
                    b.append(".connectionMaxIdleTime(").append(millis).append(")");
                    break;
                case "TcpKeepAlive":
                    b.append(".tcpKeepAlive(").append(p("java.lang.Boolean")).append(")");
                    break;
                default:
                    // proxy settings are rendered together below
                    continue;
            }
            if (e.getKey().endsWith("Timeout") || e.getKey().endsWith("TTL") || e.getKey().endsWith("Millis")) {
                imports.add("java.time.Duration");
            }
            args.add(e.getValue());
        }
        Expression host = settings.http.get("ProxyHost");
        if (host != null) {
            Expression port = settings.http.get("ProxyPort");
            Expression user = settings.http.get("ProxyUsername");
            Expression password = settings.http.get("ProxyPassword");
            if (async) {
                String proxy = "software.amazon.awssdk.http.nio.netty.ProxyConfiguration";
                imports.add(proxy);
                b.append(".proxyConfiguration(ProxyConfiguration.builder().host(").append(p("java.lang.String")).append(")");
                args.add(host);
                if (port != null) {
                    b.append(".port(").append(p("int")).append(")");
                    args.add(port);
                }
            } else {
                // v1 talks to the proxy over HTTP unless a proxy protocol is set
                imports.add("software.amazon.awssdk.http.apache.ProxyConfiguration");
                imports.add("java.net.URI");
                b.append(".proxyConfiguration(ProxyConfiguration.builder().endpoint(URI.create(\"http://\" + ").append(p("java.lang.String"));
                args.add(host);
                if (port != null) {
                    b.append(" + \":\" + ").append(p("int"));
                    args.add(port);
                }
                b.append("))");
            }
            if (user != null) {
                b.append(".username(").append(p("java.lang.String")).append(")");
                args.add(user);
            }
            if (password != null) {
                b.append(".password(").append(p("java.lang.String")).append(")");
                args.add(password);
            }
            b.append(".build())");
        }
        b.append(")");
        call(ws, b.toString(), args.toArray(new Expression[0]));
    }

    private void overrideConfiguration(int slot, String ws, Settings settings, List<Expression> interceptors) {
        if (settings.override.isEmpty() && interceptors.isEmpty()) {
            return;
        }
        StringBuilder b = new StringBuilder(kotlin ? ".overrideConfiguration { o -> o" : ".overrideConfiguration(o -> o");
        List<Expression> args = new ArrayList<>();
        Expression retries = settings.override.get("MaxErrorRetry");
        if (retries != null) {
            // v1 counts retries, v2 counts attempts including the first
            String attempts;
            if (retries instanceof J.Literal && ((J.Literal) retries).getValue() instanceof Integer) {
                attempts = String.valueOf((Integer) ((J.Literal) retries).getValue() + 1);
            } else {
                attempts = p("int") + " + 1";
                args.add(retries);
            }
            b.append(kotlin ? ".retryStrategy { b -> b.maxAttempts(" + attempts + ") }" : ".retryStrategy(b -> b.maxAttempts(" + attempts + "))");
        }
        for (String name : Arrays.asList("RequestTimeout", "ClientExecutionTimeout")) {
            Expression timeout = settings.override.get(name);
            if (timeout != null) {
                imports.add("java.time.Duration");
                b.append("RequestTimeout".equals(name) ? ".apiCallAttemptTimeout(" : ".apiCallTimeout(")
                        .append("Duration.ofMillis(").append(p("long")).append("))");
                args.add(timeout);
            }
        }
        for (Expression interceptor : interceptors) {
            b.append(".addExecutionInterceptor(").append(p("software.amazon.awssdk.core.interceptor.ExecutionInterceptor")).append(")");
            args.add(interceptor);
        }
        b.append(kotlin ? " }" : ")");
        code.add(slot, ws + b);
        int paramIndex = 0;
        for (int i = 0; i < slot; i++) {
            paramIndex += count(code.get(i), "#{");
        }
        params.addAll(paramIndex, args);
    }

    private static int count(String s, String token) {
        int n = 0;
        for (int i = s.indexOf(token); i >= 0; i = s.indexOf(token, i + 1)) {
            n++;
        }
        return n;
    }

    /**
     * The settings of a {@code ClientConfiguration}, keyed by the v1 property name.
     */
    static final class Settings {
        final Map<String, Expression> http = new LinkedHashMap<>();
        final Map<String, Expression> override = new LinkedHashMap<>();

        boolean isTrigger() {
            return override.containsKey("MaxErrorRetry") || http.containsKey("ProxyHost");
        }

        /**
         * @return why the configuration cannot be folded into the builder, or null when it was.
         */
        @Nullable String fold(Expression config, Cursor cursor, AwsClientChain chain) {
            List<J.MethodInvocation> setters = new ArrayList<>();
            Expression initializer = config;
            Folded folded = foldLocal(config, cursor, V1_CLIENT_CONFIGURATION);
            if (folded != null) {
                initializer = folded.initializer;
                setters.addAll(folded.setters);
            }
            while (initializer instanceof J.MethodInvocation) {
                setters.add(0, (J.MethodInvocation) initializer);
                initializer = ((J.MethodInvocation) initializer).getSelect();
            }
            if (!(initializer instanceof J.NewClass) || !TypeUtils.isOfClassType(initializer.getType(), V1_CLIENT_CONFIGURATION) ||
                !arguments(((J.NewClass) initializer).getArguments()).isEmpty()) {
                return "the ClientConfiguration is not created in this method";
            }
            for (J.MethodInvocation setter : setters) {
                String name = setter.getSimpleName().replaceFirst("^(with|set)", "");
                List<Expression> args = arguments(setter);
                if (args.size() != 1) {
                    return "ClientConfiguration `" + setter.getSimpleName() + "` has no v2 mapping in this recipe";
                }
                Expression arg = args.get(0);
                if (HTTP_SETTINGS.contains(name)) {
                    if (chain.async && "SocketTimeout".equals(name)) {
                        return "the async client has separate read and write timeouts";
                    }
                    http.put(name, arg);
                } else if (OVERRIDE_SETTINGS.contains(name)) {
                    override.put(name, arg);
                } else if (!("Protocol".equals(name) && isHttps(arg))) {
                    return "ClientConfiguration `" + setter.getSimpleName() + "` has no v2 mapping in this recipe";
                }
            }
            if (folded != null) {
                chain.foldedStatements.addAll(folded.statements);
            }
            return null;
        }

        private static boolean isHttps(Expression arg) {
            return arg instanceof J.FieldAccess && "HTTPS".equals(((J.FieldAccess) arg).getSimpleName()) &&
                   TypeUtils.isOfClassType(arg.getType(), V1_PROTOCOL) ||
                   arg instanceof J.Identifier && "HTTPS".equals(((J.Identifier) arg).getSimpleName()) &&
                   TypeUtils.isOfClassType(arg.getType(), V1_PROTOCOL);
        }
    }

    /**
     * A local variable initialized with {@code new X(..)}, followed by setter statements, and otherwise used only once.
     */
    static final class Folded {
        final Expression initializer;
        final List<J.MethodInvocation> setters;
        final List<Statement> statements;

        Folded(Expression initializer, List<J.MethodInvocation> setters, List<Statement> statements) {
            this.initializer = initializer;
            this.setters = setters;
            this.statements = statements;
        }
    }

    static @Nullable Folded foldLocal(Expression reference, Cursor cursor, String type) {
        if (!(reference instanceof J.Identifier)) {
            return null;
        }
        String name = ((J.Identifier) reference).getSimpleName();
        for (Cursor c = cursor; c != null; c = c.getParent()) {
            if (!(c.getValue() instanceof J.Block)) {
                continue;
            }
            J.Block block = c.getValue();
            J.VariableDeclarations declaration = null;
            List<J.MethodInvocation> setters = new ArrayList<>();
            List<Statement> statements = new ArrayList<>();
            for (Statement s : block.getStatements()) {
                if (declaration == null) {
                    if (s instanceof J.VariableDeclarations && ((J.VariableDeclarations) s).getVariables().size() == 1 &&
                        name.equals(((J.VariableDeclarations) s).getVariables().get(0).getSimpleName())) {
                        declaration = (J.VariableDeclarations) s;
                        statements.add(s);
                    }
                } else if (s instanceof J.MethodInvocation && ((J.MethodInvocation) s).getSelect() instanceof J.Identifier &&
                           name.equals(((J.Identifier) ((J.MethodInvocation) s).getSelect()).getSimpleName())) {
                    setters.add((J.MethodInvocation) s);
                    statements.add(s);
                }
            }
            if (declaration == null) {
                continue;
            }
            Expression initializer = declaration.getVariables().get(0).getInitializer();
            Expression root = initializer;
            while (root instanceof J.MethodInvocation) {
                root = ((J.MethodInvocation) root).getSelect();
            }
            if (initializer == null || !TypeUtils.isOfClassType(root == null ? null : root.getType(), type) ||
                references(block, name) != setters.size() + 1) {
                return null;
            }
            return new Folded(initializer, setters, statements);
        }
        return null;
    }

    private static int references(J.Block block, String name) {
        int[] count = {0};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                if (name.equals(identifier.getSimpleName()) && !(getCursor().getParentTreeCursor().getValue() instanceof J.VariableDeclarations.NamedVariable)) {
                    count[0]++;
                }
                return identifier;
            }
        }.visit(block, 0);
        return count[0];
    }

    static @Nullable List<Expression> constructorArguments(Expression e, String type) {
        if (e instanceof J.NewClass && TypeUtils.isOfClassType(e.getType(), type)) {
            return arguments(((J.NewClass) e).getArguments());
        }
        // Kotlin `AwsClientBuilder.EndpointConfiguration(a, b)` is a qualified constructor call
        if (e instanceof J.FieldAccess) {
            return constructorArguments(((J.FieldAccess) e).getName(), type);
        }
        if (e instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) e;
            if (m.getMethodType() != null && m.getMethodType().isConstructor() &&
                TypeUtils.isOfClassType(m.getMethodType().getDeclaringType(), type)) {
                return arguments(m);
            }
        }
        return null;
    }

    static @Nullable String regionsConstant(Expression e) {
        if (e instanceof J.FieldAccess && TypeUtils.isOfClassType(((J.FieldAccess) e).getTarget().getType(), V1_REGIONS)) {
            return ((J.FieldAccess) e).getSimpleName();
        }
        if (e instanceof J.Identifier && ((J.Identifier) e).getFieldType() != null &&
            TypeUtils.isOfClassType(((J.Identifier) e).getFieldType().getOwner(), V1_REGIONS)) {
            return ((J.Identifier) e).getSimpleName();
        }
        return null;
    }

    static String v2RegionConstant(String v1Constant) {
        return "GovCloud".equals(v1Constant) ? "US_GOV_WEST_1" : v1Constant;
    }

    static List<Expression> arguments(J.MethodInvocation m) {
        return arguments(m.getArguments());
    }

    static List<Expression> arguments(@Nullable List<Expression> args) {
        List<Expression> result = new ArrayList<>();
        if (args != null) {
            for (Expression a : args) {
                if (!(a instanceof J.Empty)) {
                    result.add(a);
                }
            }
        }
        return result;
    }

    static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    /**
     * The line break and indentation of a chain call without its comments, for further calls that one call becomes.
     */
    static String indentation(String whitespace) {
        int lastLine = whitespace.lastIndexOf('\n');
        return lastLine < 0 ? "" : whitespace.substring(lastLine);
    }

    /**
     * Whitespace and comments before a chain call, so a converted chain keeps its line breaks and commented-out calls.
     */
    static String whitespaceBefore(J.MethodInvocation m) {
        JRightPadded<Expression> select = m.getPadding().getSelect();
        if (select == null) {
            return "";
        }
        Space after = select.getAfter();
        StringBuilder ws = new StringBuilder(after.getWhitespace());
        for (Comment comment : after.getComments()) {
            if (!(comment instanceof TextComment)) {
                break;
            }
            TextComment text = (TextComment) comment;
            ws.append(text.isMultiline() ? "/*" + text.getText() + "*/" : "//" + text.getText()).append(comment.getSuffix());
        }
        return ws.toString();
    }
}

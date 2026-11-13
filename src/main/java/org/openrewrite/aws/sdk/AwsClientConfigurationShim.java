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

/**
 * Source of {@code AwsClientConfiguration}, which {@link MigrateSharedAwsClientConfiguration} generates where a v1
 * {@code ClientConfiguration} is passed between methods. v2 splits it into an immutable {@code ClientOverrideConfiguration} and
 * HTTP client builder settings, so a mutable class with v1's accessors keeps that code (including reflective setter calls) working
 * and applies the settings to a v2 client builder.
 */
final class AwsClientConfigurationShim {

    static final String SHIM = "AwsClientConfiguration";

    // type, property, default; each gets v1's getX/setX/withX
    private static final String[][] PROPERTIES = {
            {"int", "ConnectionTimeout", "10000"},
            {"int", "SocketTimeout", "50000"},
            {"int", "MaxConnections", "50"},
            {"int", "MaxErrorRetry", "-1"},
            {"RetryPolicy", "RetryPolicy", "null"},
            {"long", "ConnectionTTL", "-1"},
            {"long", "ConnectionMaxIdleMillis", "60000"},
            {"int", "ValidateAfterInactivityMillis", "5000"},
            {"int", "RequestTimeout", "0"},
            {"int", "ClientExecutionTimeout", "0"},
            {"int", "MaxConsecutiveRetriesBeforeThrottling", "100"},
            {"int", "ResponseMetadataCacheSize", "50"},
            {"InetAddress", "LocalAddress", "null"},
            {"Protocol", "Protocol", "Protocol.HTTPS"},
            {"String", "ProxyHost", "null"},
            {"int", "ProxyPort", "-1"},
            {"String", "ProxyUsername", "null"},
            {"String", "ProxyPassword", "null"},
            {"String", "ProxyDomain", "null"},
            {"String", "ProxyWorkstation", "null"},
            {"String", "NonProxyHosts", "null"},
            {"Protocol", "ProxyProtocol", "Protocol.HTTP"},
            {"String", "SignerOverride", "null"},
            {"String", "UserAgentPrefix", "null"},
            {"String", "UserAgentSuffix", "null"},
            {"boolean", "CacheResponseMetadata", "true"}
    };

    // type, field, default, v1 getter, setter, wither
    private static final String[][] FLAGS = {
            {"boolean", "useReaper", "true", "useReaper", "setUseReaper", "withReaper"},
            {"boolean", "useGzip", "false", "useGzip", "setUseGzip", "withGzip"},
            {"boolean", "useTcpKeepAlive", "false", "useTcpKeepAlive", "setUseTcpKeepAlive", "withTcpKeepAlive"},
            {"boolean", "useExpectContinue", "true", "isUseExpectContinue", "setUseExpectContinue", "withUseExpectContinue"},
            {"boolean", "useThrottleRetries", "true", "useThrottledRetries", "setUseThrottleRetries", "withThrottledRetries"},
            {"Boolean", "preemptiveBasicProxyAuth", "null", "isPreemptiveBasicProxyAuth", "setPreemptiveBasicProxyAuth", "withPreemptiveBasicProxyAuth"}
    };

    private AwsClientConfigurationShim() {
    }

    static String source(String pkg) {
        StringBuilder s = new StringBuilder(ProjectShims.packageLine(pkg));
        s.append("import java.net.InetAddress;\n" +
                 "import java.net.URI;\n" +
                 "import java.time.Duration;\n" +
                 "import java.util.LinkedHashMap;\n" +
                 "import java.util.Map;\n" +
                 "import software.amazon.awssdk.core.Protocol;\n" +
                 "import software.amazon.awssdk.core.client.builder.SdkClientBuilder;\n" +
                 "import software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder;\n" +
                 "import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;\n" +
                 "import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;\n" +
                 "import software.amazon.awssdk.core.retry.RetryMode;\n" +
                 "import software.amazon.awssdk.core.retry.RetryPolicy;\n" +
                 "import software.amazon.awssdk.http.apache.ApacheHttpClient;\n" +
                 "import software.amazon.awssdk.http.apache.ProxyConfiguration;\n" +
                 "\n" +
                 "/**\n" +
                 " * The AWS SDK v1 {@code ClientConfiguration} this code passed around. SDK v2 splits it into an immutable\n" +
                 " * {@link ClientOverrideConfiguration} and HTTP client settings, so this keeps v1's accessors and defaults and\n" +
                 " * {@link #applyTo(Object, " + SHIM + ")} hands both parts to a v2 client builder. Async builders receive only the\n" +
                 " * override configuration: their HTTP settings belong to {@code NettyNioAsyncHttpClient}.\n" +
                 " */\n" +
                 "public class " + SHIM + " {\n\n");
        for (String[] p : PROPERTIES) {
            s.append("    private ").append(p[0]).append(' ').append(field(p[1])).append(" = ").append(p[2]).append(";\n");
        }
        for (String[] f : FLAGS) {
            s.append("    private ").append(f[0]).append(' ').append(f[1]).append(" = ").append(f[2]).append(";\n");
        }
        s.append("    private int[] socketBufferSizeHints = {0, 0};\n" +
                 "    private final Map<String, String> headers = new LinkedHashMap<>();\n\n" +
                 "    public " + SHIM + "() {\n" +
                 "    }\n\n" +
                 "    public " + SHIM + "(" + SHIM + " other) {\n");
        for (String[] p : PROPERTIES) {
            s.append("        this.").append(field(p[1])).append(" = other.").append(field(p[1])).append(";\n");
        }
        for (String[] f : FLAGS) {
            s.append("        this.").append(f[1]).append(" = other.").append(f[1]).append(";\n");
        }
        s.append("        this.socketBufferSizeHints = other.socketBufferSizeHints.clone();\n" +
                 "        this.headers.putAll(other.headers);\n" +
                 "    }\n\n");
        for (String[] p : PROPERTIES) {
            accessors(s, p[0], field(p[1]), "get" + p[1], "set" + p[1], "with" + p[1]);
        }
        for (String[] f : FLAGS) {
            accessors(s, f[0], f[1], f[3], f[4], f[5]);
        }
        s.append("    /**\n" +
                 "     * v1's deprecated alias of the user agent prefix.\n" +
                 "     */\n" +
                 "    public String getUserAgent() {\n" +
                 "        return userAgentPrefix;\n" +
                 "    }\n\n" +
                 "    public void setUserAgent(String userAgent) {\n" +
                 "        this.userAgentPrefix = userAgent;\n" +
                 "    }\n\n" +
                 "    public " + SHIM + " withUserAgent(String userAgent) {\n" +
                 "        this.userAgentPrefix = userAgent;\n" +
                 "        return this;\n" +
                 "    }\n\n" +
                 "    public int[] getSocketBufferSizeHints() {\n" +
                 "        return socketBufferSizeHints.clone();\n" +
                 "    }\n\n" +
                 "    public void setSocketBufferSizeHints(int socketSendBufferSizeHint, int socketReceiveBufferSizeHint) {\n" +
                 "        this.socketBufferSizeHints = new int[]{socketSendBufferSizeHint, socketReceiveBufferSizeHint};\n" +
                 "    }\n\n" +
                 "    public " + SHIM + " withSocketBufferSizeHints(int socketSendBufferSizeHint, int socketReceiveBufferSizeHint) {\n" +
                 "        setSocketBufferSizeHints(socketSendBufferSizeHint, socketReceiveBufferSizeHint);\n" +
                 "        return this;\n" +
                 "    }\n\n" +
                 "    public Map<String, String> getHeaders() {\n" +
                 "        return headers;\n" +
                 "    }\n\n" +
                 "    public void addHeader(String name, String value) {\n" +
                 "        headers.put(name, value);\n" +
                 "    }\n\n" +
                 "    public " + SHIM + " withHeader(String name, String value) {\n" +
                 "        addHeader(name, value);\n" +
                 "        return this;\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * Applies this configuration to a v2 client builder, as v1's {@code withClientConfiguration} did.\n" +
                 "     */\n" +
                 "    public static <B> B applyTo(B builder, " + SHIM + " configuration) {\n" +
                 "        if (configuration == null) {\n" +
                 "            return builder;\n" +
                 "        }\n" +
                 "        if (builder instanceof SdkClientBuilder) {\n" +
                 "            ((SdkClientBuilder<?, ?>) builder).overrideConfiguration(configuration.toOverrideConfiguration());\n" +
                 "        }\n" +
                 "        if (builder instanceof SdkSyncClientBuilder) {\n" +
                 "            ((SdkSyncClientBuilder<?, ?>) builder).httpClientBuilder(configuration.toHttpClientBuilder());\n" +
                 "        }\n" +
                 "        return builder;\n" +
                 "    }\n\n" +
                 "    public ClientOverrideConfiguration toOverrideConfiguration() {\n" +
                 "        ClientOverrideConfiguration.Builder builder = ClientOverrideConfiguration.builder();\n" +
                 "        RetryPolicy policy = retryPolicy;\n" +
                 "        if (maxErrorRetry >= 0) {\n" +
                 "            // In v1 the client's maxErrorRetry took precedence over the policy's own count\n" +
                 "            policy = (policy == null ? RetryPolicy.builder(RetryMode.LEGACY) : policy.toBuilder()).numRetries(maxErrorRetry).build();\n" +
                 "        }\n" +
                 "        builder.retryPolicy(policy == null ? RetryPolicy.forRetryMode(RetryMode.LEGACY) : policy);\n" +
                 "        headers.forEach(builder::putHeader);\n" +
                 "        if (userAgentPrefix != null) {\n" +
                 "            builder.putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_PREFIX, userAgentPrefix);\n" +
                 "        }\n" +
                 "        if (userAgentSuffix != null) {\n" +
                 "            builder.putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_SUFFIX, userAgentSuffix);\n" +
                 "        }\n" +
                 "        if (requestTimeout > 0) {\n" +
                 "            builder.apiCallAttemptTimeout(Duration.ofMillis(requestTimeout));\n" +
                 "        }\n" +
                 "        if (clientExecutionTimeout > 0) {\n" +
                 "            builder.apiCallTimeout(Duration.ofMillis(clientExecutionTimeout));\n" +
                 "        }\n" +
                 "        return builder.build();\n" +
                 "    }\n\n" +
                 "    public ApacheHttpClient.Builder toHttpClientBuilder() {\n" +
                 "        ApacheHttpClient.Builder builder = ApacheHttpClient.builder()\n" +
                 "                .connectionTimeout(Duration.ofMillis(connectionTimeout))\n" +
                 "                .socketTimeout(Duration.ofMillis(socketTimeout))\n" +
                 "                .maxConnections(maxConnections)\n" +
                 "                .connectionMaxIdleTime(Duration.ofMillis(connectionMaxIdleMillis))\n" +
                 "                .useIdleConnectionReaper(useReaper)\n" +
                 "                .tcpKeepAlive(useTcpKeepAlive)\n" +
                 "                .expectContinueEnabled(useExpectContinue);\n" +
                 "        if (connectionTTL > 0) {\n" +
                 "            builder.connectionTimeToLive(Duration.ofMillis(connectionTTL));\n" +
                 "        }\n" +
                 "        if (localAddress != null) {\n" +
                 "            builder.localAddress(localAddress);\n" +
                 "        }\n" +
                 "        if (proxyHost != null && !proxyHost.isEmpty()) {\n" +
                 "            String scheme = proxyProtocol == Protocol.HTTPS ? \"https\" : \"http\";\n" +
                 "            ProxyConfiguration.Builder proxy = ProxyConfiguration.builder()\n" +
                 "                    .endpoint(URI.create(scheme + \"://\" + proxyHost + (proxyPort > 0 ? \":\" + proxyPort : \"\")))\n" +
                 "                    .username(proxyUsername)\n" +
                 "                    .password(proxyPassword)\n" +
                 "                    .ntlmDomain(proxyDomain)\n" +
                 "                    .ntlmWorkstation(proxyWorkstation)\n" +
                 "                    .preemptiveBasicAuthenticationEnabled(preemptiveBasicProxyAuth);\n" +
                 "            if (nonProxyHosts != null) {\n" +
                 "                for (String host : nonProxyHosts.split(\"\\\\|\")) {\n" +
                 "                    proxy.addNonProxyHost(host.trim());\n" +
                 "                }\n" +
                 "            }\n" +
                 "            builder.proxyConfiguration(proxy.build());\n" +
                 "        }\n" +
                 "        return builder;\n" +
                 "    }\n" +
                 "}\n");
        return s.toString();
    }

    private static void accessors(StringBuilder s, String type, String field, String getter, String setter, String wither) {
        s.append("    public ").append(type).append(' ').append(getter).append("() {\n")
                .append("        return ").append(field).append(";\n")
                .append("    }\n\n")
                .append("    public void ").append(setter).append('(').append(type).append(' ').append(field).append(") {\n")
                .append("        this.").append(field).append(" = ").append(field).append(";\n")
                .append("    }\n\n")
                .append("    public ").append(SHIM).append(' ').append(wither).append('(').append(type).append(' ').append(field).append(") {\n")
                .append("        this.").append(field).append(" = ").append(field).append(";\n")
                .append("        return this;\n")
                .append("    }\n\n");
    }

    private static String field(String property) {
        if ("ConnectionTTL".equals(property)) {
            return "connectionTTL";
        }
        return Character.toLowerCase(property.charAt(0)) + property.substring(1);
    }
}

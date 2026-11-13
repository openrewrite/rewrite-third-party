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
 * Source of the base class that {@link MigrateAwsClientBuilderSubclasses} gives classes that extended v1's
 * {@code AwsClientBuilder}, typically test doubles whose settings are read back.
 */
final class RecordingAwsClientBuilderShim {

    static final String SHIM = "RecordingAwsClientBuilder";

    private RecordingAwsClientBuilderShim() {
    }

    static String source(String pkg) {
        return ProjectShims.packageLine(pkg) +
               "import java.net.URI;\n" +
               "import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;\n" +
               "import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;\n" +
               "import software.amazon.awssdk.http.SdkHttpClient;\n" +
               "import software.amazon.awssdk.regions.Region;\n" +
               "\n" +
               "/**\n" +
               " * An AWS SDK v2 client builder that records the settings it is given, for classes that extended v1's\n" +
               " * {@code AwsClientBuilder} and read its endpoint and region back.\n" +
               " */\n" +
               "public abstract class " + SHIM + " implements AwsSyncClientBuilder<" + SHIM + ", Object> {\n\n" +
               "    private URI endpoint;\n" +
               "    private Region region;\n" +
               "    private ClientOverrideConfiguration overrideConfiguration = ClientOverrideConfiguration.builder().build();\n" +
               "    private SdkHttpClient httpClient;\n" +
               "    private SdkHttpClient.Builder httpClientBuilder;\n" +
               "    private Boolean dualstackEnabled;\n" +
               "    private Boolean fipsEnabled;\n\n" +
               "    @Override\n" +
               "    public " + SHIM + " endpointOverride(URI endpointOverride) {\n" +
               "        this.endpoint = endpointOverride;\n" +
               "        return this;\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public " + SHIM + " region(Region region) {\n" +
               "        this.region = region;\n" +
               "        return this;\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public " + SHIM + " overrideConfiguration(ClientOverrideConfiguration overrideConfiguration) {\n" +
               "        this.overrideConfiguration = overrideConfiguration;\n" +
               "        return this;\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public ClientOverrideConfiguration overrideConfiguration() {\n" +
               "        return overrideConfiguration;\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public " + SHIM + " httpClient(SdkHttpClient httpClient) {\n" +
               "        this.httpClient = httpClient;\n" +
               "        return this;\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public " + SHIM + " httpClientBuilder(SdkHttpClient.Builder httpClientBuilder) {\n" +
               "        this.httpClientBuilder = httpClientBuilder;\n" +
               "        return this;\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public " + SHIM + " dualstackEnabled(Boolean dualstackEnabled) {\n" +
               "        this.dualstackEnabled = dualstackEnabled;\n" +
               "        return this;\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public " + SHIM + " fipsEnabled(Boolean fipsEnabled) {\n" +
               "        this.fipsEnabled = fipsEnabled;\n" +
               "        return this;\n" +
               "    }\n\n" +
               "    /**\n" +
               "     * v1 {@code AwsClientBuilder.getEndpoint()}: null until an endpoint is set.\n" +
               "     */\n" +
               "    public static EndpointConfiguration endpointOf(Object builder) {\n" +
               "        " + SHIM + " b = (" + SHIM + ") builder;\n" +
               "        return b.endpoint == null ? null : new EndpointConfiguration(b.endpoint.toString(), b.region == null ? null : b.region.id());\n" +
               "    }\n\n" +
               "    /**\n" +
               "     * v1 {@code AwsClientBuilder.getRegion()}.\n" +
               "     */\n" +
               "    public static String regionOf(Object builder) {\n" +
               "        " + SHIM + " b = (" + SHIM + ") builder;\n" +
               "        return b.region == null ? null : b.region.id();\n" +
               "    }\n\n" +
               "    public static final class EndpointConfiguration {\n" +
               "        private final String serviceEndpoint;\n" +
               "        private final String signingRegion;\n\n" +
               "        EndpointConfiguration(String serviceEndpoint, String signingRegion) {\n" +
               "            this.serviceEndpoint = serviceEndpoint;\n" +
               "            this.signingRegion = signingRegion;\n" +
               "        }\n\n" +
               "        public String getServiceEndpoint() {\n" +
               "            return serviceEndpoint;\n" +
               "        }\n\n" +
               "        public String getSigningRegion() {\n" +
               "            return signingRegion;\n" +
               "        }\n" +
               "    }\n" +
               "}\n";
    }
}

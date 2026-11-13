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
 * Source of the value class that {@link MigrateAwsEndpointConfigurationValues} generates for a v1
 * {@code AwsClientBuilder.EndpointConfiguration} that is kept as a value rather than passed to a client builder.
 */
final class AwsEndpointConfigurationShim {

    static final String SHIM = "AwsEndpointConfiguration";

    private AwsEndpointConfigurationShim() {
    }

    static String source(String pkg) {
        return ProjectShims.packageLine(pkg) +
               "/**\n" +
               " * The endpoint and signing region that AWS SDK v1's {@code AwsClientBuilder.EndpointConfiguration} held. SDK v2 has no\n" +
               " * such type: pass them to a client builder's {@code endpointOverride(URI.create(..))} and {@code region(Region.of(..))}.\n" +
               " */\n" +
               "public class " + SHIM + " {\n\n" +
               "    private final String serviceEndpoint;\n" +
               "    private final String signingRegion;\n\n" +
               "    public " + SHIM + "(String serviceEndpoint, String signingRegion) {\n" +
               "        this.serviceEndpoint = serviceEndpoint;\n" +
               "        this.signingRegion = signingRegion;\n" +
               "    }\n\n" +
               "    public String getServiceEndpoint() {\n" +
               "        return serviceEndpoint;\n" +
               "    }\n\n" +
               "    public String getSigningRegion() {\n" +
               "        return signingRegion;\n" +
               "    }\n" +
               "}\n";
    }
}

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
package org.openrewrite.aws.signing;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.srcMainJava;

class AwsRequestSigningChainedSmokeTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.aws.signing.MigrateAwsRequestSigningApacheInterceptor",
            "software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2")
          .parser(InterceptorStubs.java())
          // AWS's v2-migration leaves some credential types it introduces untyped
          .afterTypeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void inlineDefaultChain() {
        rewriteRun(
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.client.HttpClient;
                import org.apache.http.impl.client.HttpClients;

                class CortexClient {
                    HttpClient client(String region) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("aps");
                        signer.setRegionName(region);
                        return HttpClients.custom()
                                .addInterceptorLast(new AWSRequestSigningApacheInterceptor("aps", signer, new DefaultAWSCredentialsProviderChain()))
                                .build();
                    }
                }
                """,
              """
                package com.example;

                import org.apache.http.client.HttpClient;
                import org.apache.http.impl.client.HttpClients;
                import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

                class CortexClient {
                    HttpClient client(String region) {
                        return HttpClients.custom()
                                .addInterceptorLast(new AwsSigV4RequestInterceptor("aps", region, DefaultCredentialsProvider.builder()
                                        .build()))
                                .build();
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example"), spec -> spec.path("com/example/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void fieldProvider() {
        rewriteRun(
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class AWSClient {
                    static final AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();

                    HttpRequestInterceptor interceptor(String service, String region) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName(service);
                        signer.setRegionName(region);
                        return new AWSRequestSigningApacheInterceptor(service, signer, credentialsProvider);
                    }
                }
                """,
              """
                package com.example;

                import org.apache.http.HttpRequestInterceptor;
                import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
                import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

                class AWSClient {
                    static final AwsCredentialsProvider credentialsProvider = DefaultCredentialsProvider.builder()
                            .build();

                    HttpRequestInterceptor interceptor(String service, String region) {
                        return new AwsSigV4RequestInterceptor(service, region, credentialsProvider);
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example"), spec -> spec.path("com/example/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void staticCredentialsAndRegionsEnum() {
        rewriteRun(
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentials;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.auth.AWSStaticCredentialsProvider;
                import com.amazonaws.auth.BasicAWSCredentials;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import com.amazonaws.regions.Regions;
                import org.apache.http.HttpRequestInterceptor;

                class HttpService {
                    private static final Regions region = Regions.EU_CENTRAL_1;

                    static HttpRequestInterceptor interceptor(String id, String key) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        signer.setRegionName(region.getName());
                        AWSCredentials credentials = new BasicAWSCredentials(id, key);
                        AWSCredentialsProvider credProvider = new AWSStaticCredentialsProvider(credentials);
                        return new AWSRequestSigningApacheInterceptor("es", signer, credProvider);
                    }
                }
                """,
              """
                package com.example;

                import org.apache.http.HttpRequestInterceptor;
                import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
                import software.amazon.awssdk.auth.credentials.AwsCredentials;
                import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
                import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
                import software.amazon.awssdk.regions.Region;

                class HttpService {
                    private static final Region region = Region.EU_CENTRAL_1;

                    static HttpRequestInterceptor interceptor(String id, String key) {
                        AwsCredentials credentials = AwsBasicCredentials.create(id, key);
                        AwsCredentialsProvider credProvider = StaticCredentialsProvider.create(credentials);
                        return new AwsSigV4RequestInterceptor("es", region.id(), credProvider);
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example"), spec -> spec.path("com/example/AwsSigV4RequestInterceptor.java"))
          )
        );
    }
}

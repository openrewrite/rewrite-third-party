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
import org.openrewrite.aws.signing.table.AwsRequestSigningBehaviourChanges;
import org.openrewrite.aws.signing.table.AwsRequestSigningRemnants;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.*;
import static org.openrewrite.maven.Assertions.pomXml;

class ReplaceAwsRequestSigningApacheInterceptorTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ReplaceAwsRequestSigningApacheInterceptor(null))
          .parser(InterceptorStubs.java());
    }

    @DocumentExample
    @Test
    void localSignerAndInlineCredentials() {
        rewriteRun(
          srcMainJava(
            java(
              """
                package com.example.clients;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.client.HttpClient;
                import org.apache.http.impl.client.HttpClients;

                class CortexClient {
                    private static final String APS_SERVICE_NAME = "aps";
                    private final HttpClient httpClient;

                    CortexClient(String region) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName(APS_SERVICE_NAME);
                        signer.setRegionName(region);

                        this.httpClient = HttpClients.custom()
                                .addInterceptorLast(new AWSRequestSigningApacheInterceptor(
                                        APS_SERVICE_NAME, signer, new DefaultAWSCredentialsProviderChain()))
                                .build();
                    }
                }
                """,
              """
                package com.example.clients;

                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import org.apache.http.client.HttpClient;
                import org.apache.http.impl.client.HttpClients;

                class CortexClient {
                    private static final String APS_SERVICE_NAME = "aps";
                    private final HttpClient httpClient;

                    CortexClient(String region) {
                        this.httpClient = HttpClients.custom()
                                .addInterceptorLast(new AwsSigV4RequestInterceptor(
                                        APS_SERVICE_NAME, region, new DefaultAWSCredentialsProviderChain()))
                                .build();
                    }
                }
                """
            ),
            java(
              doesNotExist(),
              InterceptorStubs.shim("com.example.clients"),
              spec -> spec.path("com/example/clients/AwsSigV4RequestInterceptor.java")
            )
          )
        );
    }

    @Test
    void fieldCredentialsAndHoistedRegion() {
        rewriteRun(
          srcMainJava(
            java(
              """
                package com.example.es;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;
                import java.util.Map;

                class ElasticsearchBackendClient {
                    private static final String SERVICE_NAME = "es";
                    private static final AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();

                    HttpRequestInterceptor interceptor(Map<String, String> context) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName(SERVICE_NAME);
                        signer.setRegionName(context.get("es.aws.region"));
                        HttpRequestInterceptor interceptor = new AWSRequestSigningApacheInterceptor(SERVICE_NAME, signer,
                                credentialsProvider);
                        return interceptor;
                    }
                }
                """,
              """
                package com.example.es;

                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import org.apache.http.HttpRequestInterceptor;
                import java.util.Map;

                class ElasticsearchBackendClient {
                    private static final String SERVICE_NAME = "es";
                    private static final AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();

                    HttpRequestInterceptor interceptor(Map<String, String> context) {
                        String signingRegion = context.get("es.aws.region");
                        HttpRequestInterceptor interceptor = new AwsSigV4RequestInterceptor(SERVICE_NAME, signingRegion,
                                credentialsProvider);
                        return interceptor;
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example.es"), spec -> spec.path("com/example/es/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void getterRegionInlinedAndReturnTypeChanged() {
        rewriteRun(
          srcMainJava(
            java(
              """
                package com.example.config;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import com.amazonaws.regions.DefaultAwsRegionProviderChain;

                class Config {
                    private AWSRequestSigningApacheInterceptor awsInterceptor(String serviceName) {
                        AWS4Signer signer = new AWS4Signer();
                        DefaultAwsRegionProviderChain regionProviderChain = new DefaultAwsRegionProviderChain();
                        DefaultAWSCredentialsProviderChain credentialsProvider = new DefaultAWSCredentialsProviderChain();
                        signer.setServiceName(serviceName);
                        signer.setRegionName(regionProviderChain.getRegion());

                        return new AWSRequestSigningApacheInterceptor(serviceName, signer, credentialsProvider);
                    }
                }
                """,
              """
                package com.example.config;

                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import com.amazonaws.regions.DefaultAwsRegionProviderChain;

                class Config {
                    private AwsSigV4RequestInterceptor awsInterceptor(String serviceName) {
                        DefaultAwsRegionProviderChain regionProviderChain = new DefaultAwsRegionProviderChain();
                        DefaultAWSCredentialsProviderChain credentialsProvider = new DefaultAWSCredentialsProviderChain();
                        return new AwsSigV4RequestInterceptor(serviceName, regionProviderChain.getRegion(), credentialsProvider);
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example.config"), spec -> spec.path("com/example/config/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void signerUsedElsewhereIsKept() {
        rewriteRun(
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    AWS4Signer last;

                    HttpRequestInterceptor interceptor(AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        signer.setRegionName("eu-west-1");
                        last = signer;
                        return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    AWS4Signer last;

                    HttpRequestInterceptor interceptor(AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        signer.setRegionName("eu-west-1");
                        last = signer;
                        return new AwsSigV4RequestInterceptor("es", "eu-west-1", credentials);
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example"), spec -> spec.path("com/example/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void regionNotSetIsNull() {
        rewriteRun(
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    HttpRequestInterceptor interceptor(AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWSCredentialsProvider;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    HttpRequestInterceptor interceptor(AWSCredentialsProvider credentials) {
                        return new AwsSigV4RequestInterceptor("es", null, credentials);
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example"), spec -> spec.path("com/example/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void signerParameterIsUnmigratable() {
        rewriteRun(
          spec -> spec.dataTable(AwsRequestSigningRemnants.Row.class, rows -> assertThat(rows)
            .singleElement()
            .satisfies(r -> {
                assertThat(r.getKind()).isEqualTo("unmigratable site");
                assertThat(r.getDetail()).contains("not a local variable");
            })),
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    HttpRequestInterceptor interceptor(AWS4Signer signer, AWSCredentialsProvider credentials) {
                        return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    HttpRequestInterceptor interceptor(AWS4Signer signer, AWSCredentialsProvider credentials) {
                        return /*~~(Not migrated off awslabs' AWSRequestSigningApacheInterceptor: The signer `signer` is not a local variable of the enclosing block, so its service and region cannot be resolved)~~>*/new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void conditionallyConfiguredSignerIsUnmigratable() {
        rewriteRun(
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    HttpRequestInterceptor interceptor(boolean eu, AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        if (eu) {
                            signer.setRegionName("eu-west-1");
                        }
                        return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    HttpRequestInterceptor interceptor(boolean eu, AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        if (eu) {
                            signer.setRegionName("eu-west-1");
                        }
                        return /*~~(Not migrated off awslabs' AWSRequestSigningApacheInterceptor: The signer `signer` is configured conditionally or after the interceptor is created)~~>*/new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void signerConfiguredAfterCreationIsUnmigratable() {
        rewriteRun(
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    HttpRequestInterceptor interceptor(AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        HttpRequestInterceptor i = new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                        signer.setRegionName("eu-west-1");
                        return i;
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    HttpRequestInterceptor interceptor(AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        HttpRequestInterceptor i = /*~~(Not migrated off awslabs' AWSRequestSigningApacheInterceptor: The signer `signer` is configured conditionally or after the interceptor is created)~~>*/new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                        signer.setRegionName("eu-west-1");
                        return i;
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void anonymousSubclassIsUnmigratable() {
        rewriteRun(
          spec -> spec.dataTable(AwsRequestSigningRemnants.Row.class, rows -> assertThat(rows)
            .singleElement()
            .satisfies(r -> assertThat(r.getDetail()).contains("Anonymous subclass"))),
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    HttpRequestInterceptor interceptor(AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        return new AWSRequestSigningApacheInterceptor("es", signer, credentials) {
                        };
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class Clients {
                    HttpRequestInterceptor interceptor(AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        return /*~~(Not migrated off awslabs' AWSRequestSigningApacheInterceptor: Anonymous subclass of the interceptor)~~>*/new AWSRequestSigningApacheInterceptor("es", signer, credentials) {
                        };
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void subclassIsUnmigratable() {
        rewriteRun(
          spec -> spec.dataTable(AwsRequestSigningRemnants.Row.class, rows -> assertThat(rows)
            .singleElement()
            .satisfies(r -> assertThat(r.getDetail()).contains("Extends the interceptor"))),
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.auth.Signer;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                class MyInterceptor extends AWSRequestSigningApacheInterceptor {
                    MyInterceptor(Signer signer, AWSCredentialsProvider credentials) {
                        super("es", signer, credentials);
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.auth.Signer;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                class /*~~(Not migrated off awslabs' AWSRequestSigningApacheInterceptor: Extends the interceptor, so there is no construction site to replace)~~>*/MyInterceptor extends AWSRequestSigningApacheInterceptor {
                    MyInterceptor(Signer signer, AWSCredentialsProvider credentials) {
                        super("es", signer, credentials);
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void vendoredCopyDeletedWithEmptyPackageInfo() {
        rewriteRun(
          spec -> spec.parser(InterceptorStubs.javaWithoutStub()),
          srcMainJava(
            java(
              InterceptorStubs.INTERCEPTOR_JAVA,
              doesNotExist(),
              spec -> spec.path("com/amazonaws/http/AWSRequestSigningApacheInterceptor.java")
            ),
            java(
              """
                /** Taken from awslabs. */
                package com.amazonaws.http;
                """,
              doesNotExist(),
              spec -> spec.path("com/amazonaws/http/package-info.java")
            ),
            java(
              """
                package gov.example.service;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                class ESService {
                    private final AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();

                    HttpRequestInterceptor searchClient(String serviceName, String region) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName(serviceName);
                        signer.setRegionName(region);
                        HttpRequestInterceptor interceptor = new AWSRequestSigningApacheInterceptor(serviceName, signer, credentialsProvider);
                        return interceptor;
                    }
                }
                """,
              """
                package gov.example.service;

                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import org.apache.http.HttpRequestInterceptor;

                class ESService {
                    private final AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();

                    HttpRequestInterceptor searchClient(String serviceName, String region) {
                        HttpRequestInterceptor interceptor = new AwsSigV4RequestInterceptor(serviceName, region, credentialsProvider);
                        return interceptor;
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("gov.example.service"), spec -> spec.path("gov/example/service/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void packageInfoKeptWhenPackageHasOtherSources() {
        rewriteRun(
          spec -> spec.parser(InterceptorStubs.javaWithoutStub()),
          srcMainJava(
            java(InterceptorStubs.INTERCEPTOR_JAVA, doesNotExist(), spec -> spec.path("com/amazonaws/http/AWSRequestSigningApacheInterceptor.java")),
            java(
              """
                package com.amazonaws.http;
                """,
              spec -> spec.path("com/amazonaws/http/package-info.java")
            ),
            java(
              """
                package com.amazonaws.http;
                public class Other {
                }
                """,
              spec -> spec.path("com/amazonaws/http/Other.java")
            ),
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                class Clients {
                    Object interceptor(AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        signer.setRegionName("us-east-1");
                        return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWSCredentialsProvider;

                class Clients {
                    Object interceptor(AWSCredentialsProvider credentials) {
                        return new AwsSigV4RequestInterceptor("es", "us-east-1", credentials);
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example"), spec -> spec.path("com/example/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void vendoredCopyKeptWhenModuleHasUnmigratableSite() {
        rewriteRun(
          spec -> spec.parser(InterceptorStubs.javaWithoutStub()),
          srcMainJava(
            java(InterceptorStubs.INTERCEPTOR_JAVA, spec -> spec.path("com/amazonaws/http/AWSRequestSigningApacheInterceptor.java")),
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                class Clients {
                    Object interceptor(AWS4Signer signer, AWSCredentialsProvider credentials) {
                        return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                class Clients {
                    Object interceptor(AWS4Signer signer, AWSCredentialsProvider credentials) {
                        return /*~~(Not migrated off awslabs' AWSRequestSigningApacheInterceptor: The signer `signer` is not a local variable of the enclosing block, so its service and region cannot be resolved)~~>*/new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void classLiteralAndJavadocInTests() {
        rewriteRun(
          srcMainJava(
            java(
              """
                package com.example.interceptor;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;

                /**
                 * Builds an {@link AWSRequestSigningApacheInterceptor}.
                 */
                class SigningInterceptor {
                    HttpRequestInterceptor build(String service, String region, AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName(service);
                        signer.setRegionName(region);
                        return new AWSRequestSigningApacheInterceptor(service, signer, credentials);
                    }
                }
                """,
              """
                package com.example.interceptor;

                import com.amazonaws.auth.AWSCredentialsProvider;
                import org.apache.http.HttpRequestInterceptor;

                /**
                 * Builds an {@link AwsSigV4RequestInterceptor}.
                 */
                class SigningInterceptor {
                    HttpRequestInterceptor build(String service, String region, AWSCredentialsProvider credentials) {
                        return new AwsSigV4RequestInterceptor(service, region, credentials);
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example.interceptor"), spec -> spec.path("com/example/interceptor/AwsSigV4RequestInterceptor.java"))
          ),
          srcTestJava(
            java(
              """
                package com.example.interceptor;

                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                class SigningInterceptorTest {
                    boolean isSigning(Object interceptor) {
                        return AWSRequestSigningApacheInterceptor.class.equals(interceptor.getClass());
                    }
                }
                """,
              """
                package com.example.interceptor;

                class SigningInterceptorTest {
                    boolean isSigning(Object interceptor) {
                        return AwsSigV4RequestInterceptor.class.equals(interceptor.getClass());
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void presignedV2Copy() {
        rewriteRun(
          spec -> spec.parser(InterceptorStubs.javaWithoutStub())
            .dataTable(AwsRequestSigningBehaviourChanges.Row.class, rows -> assertThat(rows)
              .extracting(AwsRequestSigningBehaviourChanges.Row::getId)
              .containsExactly("F5", "V1", "V2", "V3", "V4")),
          srcMainJava(
            java(
              """
                package com.amazonaws.http;

                import org.apache.http.HttpRequest;
                import org.apache.http.HttpRequestInterceptor;
                import org.apache.http.protocol.HttpContext;
                import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
                import software.amazon.awssdk.auth.signer.internal.BaseAws4Signer;
                import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;

                public class AWSRequestSigningApacheInterceptor implements HttpRequestInterceptor {
                    public AWSRequestSigningApacheInterceptor(String service, BaseAws4Signer signer, AwsCredentialsProvider provider, Aws4PresignerParams params) {
                    }
                    @Override
                    public void process(HttpRequest request, HttpContext context) {
                    }
                }
                """,
              doesNotExist(),
              spec -> spec.path("com/amazonaws/http/AWSRequestSigningApacheInterceptor.java")
            ),
            java(
              """
                package com.example.aws2;

                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;
                import org.apache.http.HttpRequestInterceptor;
                import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
                import software.amazon.awssdk.auth.signer.Aws4Signer;
                import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
                import software.amazon.awssdk.regions.Region;

                abstract class ApacheSigningInterceptor {
                    public HttpRequestInterceptor build(String service, String region) throws Exception {
                        Aws4Signer signer = Aws4Signer.create();

                        Aws4PresignerParams.Builder presignParams = Aws4PresignerParams.builder();
                        presignParams.awsCredentials(credentials().resolveCredentials());
                        presignParams.signingName(service);
                        presignParams.signingRegion(Region.of(region));

                        return new AWSRequestSigningApacheInterceptor(service, signer, credentials(), presignParams.build());
                    }

                    abstract AwsCredentialsProvider credentials() throws Exception;
                }
                """,
              """
                package com.example.aws2;

                import org.apache.http.HttpRequestInterceptor;
                import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

                abstract class ApacheSigningInterceptor {
                    public HttpRequestInterceptor build(String service, String region) throws Exception {
                        return new AwsSigV4RequestInterceptor(service, region, credentials());
                    }

                    abstract AwsCredentialsProvider credentials() throws Exception;
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example.aws2"), spec -> spec.path("com/example/aws2/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void behaviourChangesForJitPackBuildWithPostBug() {
        rewriteRun(
          spec -> spec.dataTable(AwsRequestSigningBehaviourChanges.Row.class, rows -> assertThat(rows)
            .extracting(AwsRequestSigningBehaviourChanges.Row::getId)
            .containsExactly("F2", "F3", "F4", "V1", "V2", "V3", "V4")),
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                class Clients {
                    Object interceptor(AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        signer.setRegionName("us-east-1");
                        return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWSCredentialsProvider;

                class Clients {
                    Object interceptor(AWSCredentialsProvider credentials) {
                        return new AwsSigV4RequestInterceptor("es", "us-east-1", credentials);
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example"), spec -> spec.path("com/example/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void vendoredCopyWithPostBugReportsF1() {
        rewriteRun(
          spec -> spec.parser(InterceptorStubs.javaWithoutStub())
            .dataTable(AwsRequestSigningBehaviourChanges.Row.class, rows -> assertThat(rows)
              .extracting(AwsRequestSigningBehaviourChanges.Row::getId)
              .startsWith("F1", "F2")),
          srcMainJava(
            java(InterceptorStubs.INTERCEPTOR_JAVA, doesNotExist(), spec -> spec.path("com/amazonaws/http/AWSRequestSigningApacheInterceptor.java")),
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                class Clients {
                    Object interceptor(AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        signer.setRegionName("us-east-1");
                        return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWSCredentialsProvider;

                class Clients {
                    Object interceptor(AWSCredentialsProvider credentials) {
                        return new AwsSigV4RequestInterceptor("es", "us-east-1", credentials);
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example"), spec -> spec.path("com/example/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void oneShimPerModuleInCommonPackage() {
        rewriteRun(
          mavenProject("a",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>a</artifactId>
                    <version>1</version>
                </project>
                """
            ),
            srcMainJava(
              java(
                """
                  package com.example.a.one;

                  import com.amazonaws.auth.AWS4Signer;
                  import com.amazonaws.auth.AWSCredentialsProvider;
                  import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                  class One {
                      Object interceptor(AWSCredentialsProvider credentials) {
                          AWS4Signer signer = new AWS4Signer();
                          signer.setServiceName("es");
                          return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                      }
                  }
                  """,
                """
                  package com.example.a.one;

                  import com.amazonaws.auth.AWSCredentialsProvider;
                  import com.example.a.AwsSigV4RequestInterceptor;

                  class One {
                      Object interceptor(AWSCredentialsProvider credentials) {
                          return new AwsSigV4RequestInterceptor("es", null, credentials);
                      }
                  }
                  """
              ),
              java(
                """
                  package com.example.a.two;

                  import com.amazonaws.auth.AWS4Signer;
                  import com.amazonaws.auth.AWSCredentialsProvider;
                  import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                  class Two {
                      Object interceptor(AWSCredentialsProvider credentials) {
                          AWS4Signer signer = new AWS4Signer();
                          signer.setServiceName("es");
                          return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                      }
                  }
                  """,
                """
                  package com.example.a.two;

                  import com.amazonaws.auth.AWSCredentialsProvider;
                  import com.example.a.AwsSigV4RequestInterceptor;

                  class Two {
                      Object interceptor(AWSCredentialsProvider credentials) {
                          return new AwsSigV4RequestInterceptor("es", null, credentials);
                      }
                  }
                  """
              ),
              java(doesNotExist(), InterceptorStubs.shim("com.example.a"), spec -> spec.path("com/example/a/AwsSigV4RequestInterceptor.java"))
            )
          ),
          mavenProject("b",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>b</artifactId>
                    <version>1</version>
                </project>
                """
            ),
            srcMainJava(
              java(
                """
                  package com.example.b;

                  public class AwsSigV4RequestInterceptor {
                  }
                  """
              ),
              java(
                """
                  package com.example.b;

                  import com.amazonaws.auth.AWS4Signer;
                  import com.amazonaws.auth.AWSCredentialsProvider;
                  import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                  class B {
                      Object interceptor(AWSCredentialsProvider credentials) {
                          AWS4Signer signer = new AWS4Signer();
                          signer.setServiceName("es");
                          return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                      }
                  }
                  """,
                """
                  package com.example.b;

                  import com.amazonaws.auth.AWSCredentialsProvider;

                  class B {
                      Object interceptor(AWSCredentialsProvider credentials) {
                          return new AwsSigV4RequestInterceptor2("es", null, credentials);
                      }
                  }
                  """
              ),
              java(doesNotExist(), ReplaceAwsRequestSigningApacheInterceptor.render("com.example.b", "AwsSigV4RequestInterceptor2"),
                spec -> spec.path("com/example/b/AwsSigV4RequestInterceptor2.java"))
            )
          )
        );
    }

    @Test
    void existingShimIsReused() {
        rewriteRun(
          srcMainJava(
            java(InterceptorStubs.shim("com.example.signing"), spec -> spec.path("com/example/signing/AwsSigV4RequestInterceptor.java")),
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWS4Signer;
                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                class Clients {
                    Object interceptor(AWSCredentialsProvider credentials) {
                        AWS4Signer signer = new AWS4Signer();
                        signer.setServiceName("es");
                        return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWSCredentialsProvider;
                import com.example.signing.AwsSigV4RequestInterceptor;

                class Clients {
                    Object interceptor(AWSCredentialsProvider credentials) {
                        return new AwsSigV4RequestInterceptor("es", null, credentials);
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void inreachInterceptorBecomesTheGeneratedInterceptor() {
        rewriteRun(
          spec -> spec.parser(InterceptorStubs.javaWithInreach())
            .dataTable(AwsRequestSigningBehaviourChanges.Row.class, rows -> assertThat(rows)
              .anySatisfy(row -> assertThat(row.getId()).isEqualTo("I1"))),
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import com.google.common.base.Supplier;
                import java.time.LocalDateTime;
                import java.time.ZoneOffset;
                import org.apache.http.impl.client.HttpClientBuilder;
                import vc.inreach.aws.request.AWSSigner;
                import vc.inreach.aws.request.AWSSigningRequestInterceptor;

                class EsClient {
                    HttpClientBuilder configure(HttpClientBuilder builder, String region) {
                        Supplier<LocalDateTime> clock = () -> LocalDateTime.now(ZoneOffset.UTC);
                        AWSSigner signer = new AWSSigner(new DefaultAWSCredentialsProviderChain(), region, "es", clock);
                        return builder.addInterceptorLast(new AWSSigningRequestInterceptor(signer));
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
                import org.apache.http.impl.client.HttpClientBuilder;

                class EsClient {
                    HttpClientBuilder configure(HttpClientBuilder builder, String region) {
                        return builder.addInterceptorLast(new AwsSigV4RequestInterceptor("es", region, new DefaultAWSCredentialsProviderChain()));
                    }
                }
                """
            ),
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWSCredentialsProvider;
                import java.time.LocalDateTime;
                import vc.inreach.aws.request.AWSSigner;
                import vc.inreach.aws.request.AWSSigningRequestInterceptor;

                class Interceptors {
                    private final AWSSigningRequestInterceptor interceptor;

                    Interceptors(AWSCredentialsProvider credentials, String region) {
                        this.interceptor = new AWSSigningRequestInterceptor(new AWSSigner(credentials, region, "es", LocalDateTime::now));
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWSCredentialsProvider;

                class Interceptors {
                    private final AwsSigV4RequestInterceptor interceptor;

                    Interceptors(AWSCredentialsProvider credentials, String region) {
                        this.interceptor = new AwsSigV4RequestInterceptor("es", region, credentials);
                    }
                }
                """
            ),
            java(doesNotExist(), InterceptorStubs.shim("com.example"), spec -> spec.path("com/example/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void inreachSignerUsedElsewhereIsUnmigratable() {
        rewriteRun(
          spec -> spec.parser(InterceptorStubs.javaWithInreach()),
          srcMainJava(
            java(
              """
                package com.example;

                import com.amazonaws.auth.AWSCredentialsProvider;
                import java.time.LocalDateTime;
                import vc.inreach.aws.request.AWSSigner;
                import vc.inreach.aws.request.AWSSigningRequestInterceptor;

                class Interceptors {
                    AWSSigner last;

                    Object interceptor(AWSCredentialsProvider credentials) {
                        AWSSigner signer = new AWSSigner(credentials, "eu-west-1", "es", LocalDateTime::now);
                        last = signer;
                        return new AWSSigningRequestInterceptor(signer);
                    }
                }
                """,
              """
                package com.example;

                import com.amazonaws.auth.AWSCredentialsProvider;
                import java.time.LocalDateTime;
                import vc.inreach.aws.request.AWSSigner;
                import vc.inreach.aws.request.AWSSigningRequestInterceptor;

                class Interceptors {
                    AWSSigner last;

                    Object interceptor(AWSCredentialsProvider credentials) {
                        AWSSigner signer = new AWSSigner(credentials, "eu-west-1", "es", LocalDateTime::now);
                        last = signer;
                        return /*~~(Not migrated off vc.inreach's AWSSigningRequestInterceptor: The signer `signer` is used elsewhere)~~>*/new AWSSigningRequestInterceptor(signer);
                    }
                }
                """
            )
          )
        );
    }
}

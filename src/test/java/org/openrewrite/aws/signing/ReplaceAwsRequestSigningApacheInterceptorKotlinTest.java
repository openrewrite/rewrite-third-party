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

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.kotlin.Assertions.kotlin;

class ReplaceAwsRequestSigningApacheInterceptorKotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ReplaceAwsRequestSigningApacheInterceptor(null))
          .parser(InterceptorStubs.kotlin())
          .parser(InterceptorStubs.java());
    }

    @DocumentExample
    @Test
    void propertyAssignedSignerAndStaticCredentials() {
        rewriteRun(
          kotlin(
            """
              package com.ryulth.woom.config

              import com.amazonaws.auth.AWS4Signer
              import com.amazonaws.auth.AWSStaticCredentialsProvider
              import com.amazonaws.auth.BasicAWSCredentials
              import com.amazonaws.http.AWSRequestSigningApacheInterceptor
              import org.apache.http.HttpRequestInterceptor

              class ElasticsearchConfig(val accessKey: String, val secretKey: String, val region: String) {
                  companion object {
                      const val SERVICE_NAME = "es"
                  }

                  fun interceptor(): HttpRequestInterceptor {
                      val signer = AWS4Signer()
                      val credentialsProvider = AWSStaticCredentialsProvider(BasicAWSCredentials(accessKey, secretKey))
                      signer.serviceName = SERVICE_NAME
                      signer.regionName = region
                      val interceptor: HttpRequestInterceptor = AWSRequestSigningApacheInterceptor(SERVICE_NAME, signer, credentialsProvider)
                      return interceptor
                  }
              }
              """,
            """
              package com.ryulth.woom.config

              import org.apache.http.HttpRequestInterceptor
              import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
              import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider

              class ElasticsearchConfig(val accessKey: String, val secretKey: String, val region: String) {
                  companion object {
                      const val SERVICE_NAME = "es"
                  }

                  fun interceptor(): HttpRequestInterceptor {
                      val credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                      val interceptor: HttpRequestInterceptor = AwsSigV4RequestInterceptor(SERVICE_NAME, region, credentialsProvider)
                      return interceptor
                  }
              }
              """,
            spec -> spec.path("src/main/kotlin/com/ryulth/woom/config/ElasticsearchConfig.kt")
          ),
          java(doesNotExist(), InterceptorStubs.shim("com.ryulth.woom.config"),
            spec -> spec.path("src/main/java/com/ryulth/woom/config/AwsSigV4RequestInterceptor.java"))
        );
    }

    @Test
    void applyBlockSignerAndEndpointPrefixConstant() {
        rewriteRun(
          kotlin(
            """
              package dartzee.logging

              import com.amazonaws.auth.AWS4Signer
              import com.amazonaws.auth.AWSCredentials
              import com.amazonaws.auth.AWSStaticCredentialsProvider
              import com.amazonaws.http.AWSRequestSigningApacheInterceptor
              import com.amazonaws.services.elasticsearch.AWSElasticsearch
              import org.apache.http.HttpRequestInterceptor

              class ElasticsearchPoster(private val credentials: AWSCredentials?) {
                  fun createInterceptor(): HttpRequestInterceptor? {
                      try {
                          val signer =
                              AWS4Signer().apply {
                                  serviceName = AWSElasticsearch.ENDPOINT_PREFIX
                                  regionName = "eu-west-2"
                              }

                          val interceptor =
                              AWSRequestSigningApacheInterceptor(
                                  signer.serviceName,
                                  signer,
                                  AWSStaticCredentialsProvider(credentials),
                              )
                          return interceptor
                      } catch (t: Throwable) {
                          return null
                      }
                  }
              }
              """,
            """
              package dartzee.logging

              import com.amazonaws.auth.AWSCredentials
              import org.apache.http.HttpRequestInterceptor
              import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider

              class ElasticsearchPoster(private val credentials: AWSCredentials?) {
                  fun createInterceptor(): HttpRequestInterceptor? {
                      try {
                          val interceptor =
                              AwsSigV4RequestInterceptor(
                                  "es",
                                  "eu-west-2",
                                  StaticCredentialsProvider.create(credentials),
                              )
                          return interceptor
                      } catch (t: Throwable) {
                          return null
                      }
                  }
              }
              """,
            spec -> spec.path("src/main/kotlin/dartzee/logging/ElasticsearchPoster.kt")
          ),
          java(doesNotExist(), InterceptorStubs.shim("dartzee.logging"),
            spec -> spec.path("src/main/java/dartzee/logging/AwsSigV4RequestInterceptor.java"))
        );
    }

    @Test
    void hoistsRegionComputedWithArguments() {
        rewriteRun(
          kotlin(
            """
              package com.example

              import com.amazonaws.auth.AWS4Signer
              import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
              import com.amazonaws.http.AWSRequestSigningApacheInterceptor
              import org.apache.http.HttpRequestInterceptor

              class Config {
                  fun interceptor(): HttpRequestInterceptor {
                      val signer = AWS4Signer()
                      signer.serviceName = "es"
                      signer.regionName = System.getenv("AWS_REGION")
                      return AWSRequestSigningApacheInterceptor("es", signer, DefaultAWSCredentialsProviderChain.getInstance())
                  }
              }
              """,
            """
              package com.example

              import org.apache.http.HttpRequestInterceptor
              import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

              class Config {
                  fun interceptor(): HttpRequestInterceptor {
                      val signingRegion = System.getenv("AWS_REGION")
                      return AwsSigV4RequestInterceptor("es", signingRegion, DefaultCredentialsProvider.create())
                  }
              }
              """,
            spec -> spec.path("src/main/kotlin/com/example/Config.kt")
          ),
          java(doesNotExist(), InterceptorStubs.shim("com.example"),
            spec -> spec.path("src/main/java/com/example/AwsSigV4RequestInterceptor.java"))
        );
    }
}

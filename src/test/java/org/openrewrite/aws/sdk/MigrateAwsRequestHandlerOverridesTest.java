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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.kotlin.Assertions.kotlin;
import static org.openrewrite.test.SourceSpecs.text;

class MigrateAwsRequestHandlerOverridesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAwsRequestHandlerOverrides())
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(),
              "sqs-2",
              "sdk-core",
              "aws-core",
              "regions",
              "utils"))
          .parser(KotlinParser.builder()
            .classpathFromResources(new InMemoryExecutionContext(),
              "sqs-2",
              "sdk-core",
              "aws-core",
              "regions",
              "utils"));
    }

    @Test
    void createBecomesBuilderWithChecksumValidationDisabled() {
        rewriteRun(
          text(
            """
              com.amazonaws.services.sqs.QueueUrlHandler
              com.amazonaws.services.sqs.internal.SQSRequestHandler
              """,
            null,
            spec -> spec.path("src/main/resources/com/amazonaws/services/sqs/request.handlers")
          ),
          //language=java
          java(
            """
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;
              import software.amazon.awssdk.services.sqs.SqsClient;

              class Clients {
                  SqsClient sync = SqsClient.create();
                  SqsAsyncClient async = SqsAsyncClient.create();
              }
              """,
            """
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;
              import software.amazon.awssdk.services.sqs.SqsClient;

              class Clients {
                  SqsClient sync = SqsClient.builder().checksumValidationEnabled(false).build();
                  SqsAsyncClient async = SqsAsyncClient.builder().checksumValidationEnabled(false).build();
              }
              """,
            spec -> spec.path("src/main/java/Clients.java")
          )
        );
    }

    @Test
    void kotlinV1ClientsMigratedInTheSameRun() {
        rewriteRun(
          spec -> spec.recipes(new MigrateAwsSdkV1Kotlin(), new MigrateAwsRequestHandlerOverrides())
            .parser(KotlinParser.builder()
              .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sqs"))
            // MigrateAwsSdkV1Kotlin's v2 SQS client is unattributed, as in its own tests
            .typeValidationOptions(TypeValidation.none()),
          text(
            """
              com.amazonaws.services.sqs.QueueUrlHandler
              com.amazonaws.services.sqs.internal.SQSRequestHandler
              """,
            null,
            spec -> spec.path("src/main/resources/com/amazonaws/services/sqs/request.handlers")
          ),
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.services.sqs.AmazonSQS
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder

              class Clients {
                  fun sqs(): AmazonSQS = AmazonSQSClientBuilder.standard().withRegion("eu-west-1").build()

                  fun default(): AmazonSQS = AmazonSQSClientBuilder.defaultClient()
              }
              """,
            """
              import software.amazon.awssdk.regions.Region
              import software.amazon.awssdk.services.sqs.SqsClient

              class Clients {
                  fun sqs(): SqsClient = SqsClient.builder().checksumValidationEnabled(false).region(Region.of("eu-west-1")).build()

                  fun default(): SqsClient = SqsClient.builder().checksumValidationEnabled(false).build()
              }
              """,
            spec -> spec.path("src/main/kotlin/Clients.kt")
          )
        );
    }

    @Test
    void kotlinClientsInTheModuleAlsoDisableChecksumValidation() {
        rewriteRun(
          text(
            """
              com.amazonaws.services.sqs.QueueUrlHandler
              com.amazonaws.services.sqs.internal.SQSRequestHandler
              """,
            null,
            spec -> spec.path("src/main/resources/com/amazonaws/services/sqs/request.handlers")
          ),
          //language=kotlin
          kotlin(
            """
              import software.amazon.awssdk.regions.Region
              import software.amazon.awssdk.services.sqs.SqsAsyncClient
              import software.amazon.awssdk.services.sqs.SqsClient

              class Clients {
                  fun sync(): SqsClient = SqsClient.builder()
                      .region(Region.EU_WEST_1)
                      .build()

                  fun async(): SqsAsyncClient = SqsAsyncClient.create()
              }
              """,
            """
              import software.amazon.awssdk.regions.Region
              import software.amazon.awssdk.services.sqs.SqsAsyncClient
              import software.amazon.awssdk.services.sqs.SqsClient

              class Clients {
                  fun sync(): SqsClient = SqsClient.builder().checksumValidationEnabled(false)
                      .region(Region.EU_WEST_1)
                      .build()

                  fun async(): SqsAsyncClient = SqsAsyncClient.builder().checksumValidationEnabled(false).build()
              }
              """,
            spec -> spec.path("src/main/kotlin/Clients.kt")
          )
        );
    }

    @DocumentExample
    @Test
    void droppedSqsMd5HandlerDisablesChecksumValidationOnTheModulesClients() {
        rewriteRun(
          text(
            """
              com.amazonaws.services.sqs.QueueUrlHandler
              com.amazonaws.services.sqs.internal.SQSRequestHandler
              """,
            null,
            spec -> spec.path("order-system/src/main/java/com/amazonaws/services/sqs/request.handlers")
          ),
          //language=java
          java(
            """
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;
              import software.amazon.awssdk.services.sqs.SqsClient;

              class Clients {
                  SqsAsyncClient async() {
                      return SqsAsyncClient.builder()
                              .region(Region.EU_WEST_1)
                              .build();
                  }

                  SqsClient sync() {
                      return SqsClient.builder().build();
                  }

                  SqsClient explicit() {
                      return SqsClient.builder().checksumValidationEnabled(true).build();
                  }
              }
              """,
            """
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;
              import software.amazon.awssdk.services.sqs.SqsClient;

              class Clients {
                  SqsAsyncClient async() {
                      return SqsAsyncClient.builder().checksumValidationEnabled(false)
                              .region(Region.EU_WEST_1)
                              .build();
                  }

                  SqsClient sync() {
                      return SqsClient.builder().checksumValidationEnabled(false).build();
                  }

                  SqsClient explicit() {
                      return SqsClient.builder().checksumValidationEnabled(true).build();
                  }
              }
              """,
            spec -> spec.path("order-system/src/test/java/Clients.java")
          ),
          //language=java
          java(
            """
              import software.amazon.awssdk.services.sqs.SqsClient;

              class OtherModule {
                  SqsClient sync() {
                      return SqsClient.builder().build();
                  }
              }
              """,
            spec -> spec.path("crm-system/src/main/java/OtherModule.java")
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void keepsOverridesListingCustomHandlers() {
            rewriteRun(
              text(
                """
                  com.amazonaws.services.sqs.QueueUrlHandler
                  com.example.TracingHandler
                  """,
                spec -> spec.path("src/main/resources/com/amazonaws/services/sqs/request.handlers")
              ),
              //language=java
              java(
                """
                  import software.amazon.awssdk.services.sqs.SqsClient;

                  class Clients {
                      SqsClient sync() {
                          return SqsClient.builder().build();
                      }
                  }
                  """,
                spec -> spec.path("src/main/java/Clients.java")
              )
            );
        }

        @Test
        void keepsOverridesDroppingHandlersV2CannotTurnOff() {
            rewriteRun(
              text(
                """
                  com.amazonaws.services.sqs.QueueUrlHandler
                  com.amazonaws.services.sqs.MessageMD5ChecksumHandler
                  """,
                spec -> spec.path("src/main/resources/com/amazonaws/services/sqs/request.handlers")
              )
            );
        }
    }
}

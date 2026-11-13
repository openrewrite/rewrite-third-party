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
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class AwsAsyncClientCallsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new PrepareAwsAsyncClientCalls(null))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(),
              "aws-java-sdk-core",
              "aws-java-sdk-sqs",
              "mockito-core"));
    }

    @DocumentExample
    @Test
    void joinsBlockingCallsAfterV2Migration() {
        rewriteRun(
          spec -> spec.recipeFromResources("org.openrewrite.aws.sdk.AsyncClientAndS3ResidualsWithV2Migration")
            .typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
              import com.amazonaws.services.sqs.model.SendMessageRequest;
              import com.amazonaws.services.sqs.model.SendMessageResult;

              import java.util.concurrent.Future;

              class Producer {
                  void send(AmazonSQSAsync sqs) {
                      sqs.sendMessage(new SendMessageRequest().withQueueUrl("q").withMessageBody("b"));
                      int received = sqs.receiveMessage(new ReceiveMessageRequest().withQueueUrl("q")).getMessages().size();
                      Future<SendMessageResult> future = sqs.sendMessageAsync(new SendMessageRequest().withQueueUrl("q"));
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;
              import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
              import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
              import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

              import java.util.concurrent.CompletableFuture;

              class Producer {
                  void send(SqsAsyncClient sqs) {
                      sqs.sendMessage(SendMessageRequest.builder().queueUrl("q").messageBody("b")
                              .build()).join();
                      int received = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl("q")
                              .build()).join().messages().size();
                      CompletableFuture<SendMessageResponse> future = sqs.sendMessage(SendMessageRequest.builder().queueUrl("q")
                              .build());
                  }
              }
              """
          )
        );
    }

    @Test
    void renamesAsyncMethod() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.SendMessageRequest;
              import com.amazonaws.services.sqs.model.SendMessageResult;

              import java.util.concurrent.Future;

              class Producer {
                  Future<SendMessageResult> send(AmazonSQSAsync sqs, SendMessageRequest request) {
                      return sqs.sendMessageAsync(request);
                  }
              }
              """,
            """
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.SendMessageRequest;
              import com.amazonaws.services.sqs.model.SendMessageResult;

              import java.util.concurrent.Future;

              class Producer {
                  Future<SendMessageResult> send(AmazonSQSAsync sqs, SendMessageRequest request) {
                      return sqs.sendMessage(request);
                  }
              }
              """
          )
        );
    }

    @Test
    void marksAsyncHandler() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.handlers.AsyncHandler;
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.SendMessageRequest;
              import com.amazonaws.services.sqs.model.SendMessageResult;

              class Producer {
                  void send(AmazonSQSAsync sqs, SendMessageRequest request, AsyncHandler<SendMessageRequest, SendMessageResult> handler) {
                      sqs.sendMessageAsync(request, handler);
                  }
              }
              """,
            """
              import com.amazonaws.handlers.AsyncHandler;
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.SendMessageRequest;
              import com.amazonaws.services.sqs.model.SendMessageResult;

              class Producer {
                  void send(AmazonSQSAsync sqs, SendMessageRequest request, AsyncHandler<SendMessageRequest, SendMessageResult> handler) {
                      /*~~(AsyncHandler callbacks have no v2 equivalent: call sendMessage(request) and attach the callback with whenComplete)~~>*/sqs.sendMessageAsync(request, handler);
                  }
              }
              """
          )
        );
    }

    @Test
    void wrapsAsyncClientUsedAsSyncClientInBlockingView() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.AmazonSQSAsync;

              class Connection {
                  AmazonSQSAsync client;

                  AmazonSQS getSyncClient() {
                      return client;
                  }

                  void use(Helper helper) {
                      AmazonSQS sync = client;
                      helper.queueUrl("q", client);
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.AmazonSQSAsync;

              class Connection {
                  AmazonSQSAsync client;

                  AmazonSQS getSyncClient() {
                      return AwsSyncClients.blocking(client, AmazonSQS.class);
                  }

                  void use(Helper helper) {
                      AmazonSQS sync = AwsSyncClients.blocking(client, AmazonSQS.class);
                      helper.queueUrl("q", AwsSyncClients.blocking(client, AmazonSQS.class));
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/Connection.java")
          ),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.sqs.AmazonSQS;

              class Helper {
                  String queueUrl(String name, AmazonSQS sqs) {
                      return sqs.getQueueUrl(name).getQueueUrl();
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/Helper.java")
          ),
          java(
            doesNotExist(),
            AwsSyncClientsShim.source("com.example"),
            spec -> spec.path("src/main/java/com/example/AwsSyncClients.java")
          )
        );
    }

    @Test
    void wrapsAlreadyConvertedAsyncBuilderReturnedAsV1SyncClient() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(),
              "aws-java-sdk-core",
              "aws-java-sdk-sqs",
              "sqs-2",
              "sdk-core",
              "aws-core",
              "utils")),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.sqs.AmazonSQS;
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;

              class Config {
                  AmazonSQS sqs() {
                      return SqsAsyncClient.builder().build();
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.sqs.AmazonSQS;
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;

              class Config {
                  AmazonSQS sqs() {
                      return AwsSyncClients.blocking(SqsAsyncClient.builder().build(), AmazonSQS.class);
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/Config.java")
          ),
          java(
            doesNotExist(),
            AwsSyncClientsShim.source("com.example"),
            spec -> spec.path("src/main/java/com/example/AwsSyncClients.java")
          )
        );
    }

    @Test
    void blockingViewIsRetypedByV2Migration() {
        rewriteRun(
          spec -> spec.recipeFromResources("org.openrewrite.aws.sdk.AsyncClientAndS3ResidualsWithV2Migration")
            .typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.AmazonSQSAsync;

              class Connection {
                  AmazonSQSAsync client;

                  AmazonSQS getSyncClient() {
                      return client;
                  }
              }
              """,
            """
              package com.example;

              import software.amazon.awssdk.services.sqs.SqsAsyncClient;
              import software.amazon.awssdk.services.sqs.SqsClient;

              class Connection {
                  SqsAsyncClient client;

                  SqsClient getSyncClient() {
                      return AwsSyncClients.blocking(client, SqsClient.class);
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/Connection.java")
          ),
          java(
            doesNotExist(),
            AwsSyncClientsShim.source("com.example"),
            spec -> spec.path("src/main/java/com/example/AwsSyncClients.java")
          )
        );
    }

    @Test
    void leavesMockitoStubOfBlockingCallAndRenamesVerifiedAsyncCall() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.SendMessageRequest;
              import com.amazonaws.services.sqs.model.SendMessageResult;

              import static org.mockito.Mockito.*;

              class ProducerTest {
                  void test(AmazonSQSAsync sqs, SendMessageRequest request) {
                      when(sqs.sendMessage(request)).thenReturn(new SendMessageResult());
                      verify(sqs).sendMessageAsync(request);
                  }
              }
              """,
            """
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.SendMessageRequest;
              import com.amazonaws.services.sqs.model.SendMessageResult;

              import static org.mockito.Mockito.*;

              class ProducerTest {
                  void test(AmazonSQSAsync sqs, SendMessageRequest request) {
                      when(sqs.sendMessage(request)).thenReturn(new SendMessageResult());
                      verify(sqs).sendMessage(request);
                  }
              }
              """
          )
        );
    }

    @Test
    void leavesMockitoStubsAndVerificationsWithoutJoin() {
        rewriteRun(
          spec -> spec.recipeFromResources("org.openrewrite.aws.sdk.AsyncClientAndS3ResidualsWithV2Migration")
            .typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.SendMessageRequest;

              import static org.mockito.Mockito.*;

              class ProducerTest {
                  void test(AmazonSQSAsync sqs, SendMessageRequest request) {
                      verify(sqs).sendMessage(request);
                      verify(sqs).sendMessageAsync(request);
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;
              import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

              import static org.mockito.Mockito.*;

              class ProducerTest {
                  void test(SqsAsyncClient sqs, SendMessageRequest request) {
                      verify(sqs).sendMessage(request);
                      verify(sqs).sendMessage(request);
                  }
              }
              """
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void doesNotJoinNativeV2AsyncCalls() {
            rewriteRun(
              spec -> spec.recipe(new CompleteAwsAsyncClientCalls())
                .parser(JavaParser.fromJavaVersion()
                  .classpathFromResources(new InMemoryExecutionContext(),
                    "sqs-2",
                    "sdk-core",
                    "aws-core",
                    "utils")),
              //language=java
              java(
                """
                  import software.amazon.awssdk.services.sqs.SqsAsyncClient;
                  import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

                  class Producer {
                      void send(SqsAsyncClient sqs, SendMessageRequest request) {
                          sqs.sendMessage(request);
                          sqs.sendMessage(request).join();
                      }
                  }
                  """
              )
            );
        }
    }
}

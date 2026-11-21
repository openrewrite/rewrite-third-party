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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.maven.Assertions.pomXml;

class MigrateAwsSdkV1ToV2Test implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.aws.sdk.MigrateAwsSdkV1ToV2")
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sqs"));
    }

    @DocumentExample
    @Test
    void sqsClientWithEndpointAndOverload() {
        rewriteRun(
          mavenProject("app",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.amazonaws</groupId>
                            <artifactId>aws-java-sdk-sqs</artifactId>
                            <version>1.12.700</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
              spec -> spec.after(actual -> assertThat(actual)
                .contains("<artifactId>bom</artifactId>", "<artifactId>sqs</artifactId>")
                .containsPattern("<version>2\\.\\d+\\.\\d+</version>")
                .doesNotContain("com.amazonaws")
                .actual())
            ),
            srcMainJava(
              //language=java
              java(
                """
                  import com.amazonaws.client.builder.AwsClientBuilder;
                  import com.amazonaws.services.sqs.AmazonSQS;
                  import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

                  class Queues {
                      private final AmazonSQS sqs = AmazonSQSClientBuilder.standard()
                              .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration("http://localhost:4566", "us-east-1"))
                              .build();

                      void send(String queueUrl, String body) {
                          sqs.sendMessage(queueUrl, body);
                      }
                  }
                  """,
                """
                  import software.amazon.awssdk.regions.Region;
                  import software.amazon.awssdk.services.sqs.SqsClient;
                  import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

                  import java.net.URI;

                  class Queues {
                      private final SqsClient sqs = SqsClient.builder()
                              .endpointOverride(URI.create("http://localhost:4566"))
                              .region(Region.of("us-east-1"))
                              .build();

                      void send(String queueUrl, String body) {
                          sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body)
                                  .build());
                      }
                  }
                  """
              )
            )
          )
        );
    }

    @Test
    void webIdentityCredentialsInCommentedChain() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.auth.WebIdentityTokenCredentialsProvider;
              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

              class Queues {
                  AmazonSQS production() {
                      return AmazonSQSClientBuilder.standard()
                              // AWS_WEB_IDENTITY_TOKEN_FILE is set in the pod
                              .withCredentials(WebIdentityTokenCredentialsProvider.builder().build())
                              .build();
                  }
              }
              """,
            """
              import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
              import software.amazon.awssdk.services.sqs.SqsClient;

              class Queues {
                  SqsClient production() {
                      return SqsClient.builder()
                              // AWS_WEB_IDENTITY_TOKEN_FILE is set in the pod
                              .credentialsProvider(WebIdentityTokenFileCredentialsProvider.create())
                              .build();
                  }
              }
              """
          )
        );
    }

    @Test
    void endpointConfigurationImportGoesWithTheDocumentApiClient() {
        rewriteRun(
          // The v2 types aren't on the parser's classpath, and AWS's own credential rewrites leave identifiers untyped
          spec -> spec.parser(JavaParser.fromJavaVersion()
              .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-dynamodb"))
            .afterTypeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.auth.AWSStaticCredentialsProvider;
              import com.amazonaws.auth.BasicAWSCredentials;
              import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
              import com.amazonaws.services.dynamodbv2.document.DynamoDB;
              import com.amazonaws.services.dynamodbv2.document.Table;

              class ScriptRunner {
                  static AmazonDynamoDB amazonDynamoDB() {
                      return AmazonDynamoDBClientBuilder
                              .standard()
                              .withEndpointConfiguration(new EndpointConfiguration("http://localhost:8000", "us-east-1"))
                              .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("key", "secret")))
                              .build();
                  }

                  static DynamoDB dynamodb = new DynamoDB(amazonDynamoDB());

                  static void deleteTable(String name) {
                      Table table = dynamodb.getTable(name);
                      table.delete();
                  }
              }
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .doesNotContain("EndpointConfiguration", "com.amazonaws")
              .contains("endpointOverride(URI.create(\"http://localhost:8000\"))")
              .actual())
          ),
          java(
            doesNotExist(),
            """
              package com.example;
              """,
            spec -> spec.path("com/example/DynamoDbDocumentApi.java")
              .after(actual -> assertThat(actual).contains("public final class DynamoDbDocumentApi").actual())
          )
        );
    }
}

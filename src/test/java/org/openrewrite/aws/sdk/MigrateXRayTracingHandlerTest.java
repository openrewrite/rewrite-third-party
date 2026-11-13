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

import static org.openrewrite.java.Assertions.java;

class MigrateXRayTracingHandlerTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipes(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.AwsSdkJavaV1ToV2Safe"), new MigrateXRayTracingHandler())
          .parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(),
            "aws-java-sdk-core",
            "aws-java-sdk-dynamodb",
            "aws-xray-recorder-sdk-core",
            "aws-xray-recorder-sdk-aws-sdk-2",
            "aws-xray-recorder-sdk-aws-sdk-v2",
            "dynamodb-2",
            "regions",
            "sdk-core",
            "aws-core"))
          // AWS's v2-migration leaves the `Region` it adds untyped
          .afterTypeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void globalRecorderHandlerBecomesInterceptor() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
              import com.amazonaws.xray.AWSXRay;
              import com.amazonaws.xray.handlers.TracingHandler;

              class Config {
                  AmazonDynamoDB dynamo() {
                      return AmazonDynamoDBClientBuilder.standard()
                              .withRegion("eu-west-1")
                              .withRequestHandlers(new TracingHandler(AWSXRay.getGlobalRecorder()))
                              .build();
                  }
              }
              """,
            """
              import com.amazonaws.xray.interceptors.TracingInterceptor;
              import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

              class Config {
                  DynamoDbClient dynamo() {
                      return DynamoDbClient.builder()
                              .region(Region.of("eu-west-1"))
                              .overrideConfiguration(ClientOverrideConfiguration.builder().addExecutionInterceptor(new TracingInterceptor()).build())
                              .build();
                  }
              }
              """
          )
        );
    }

    @Test
    void mergedIntoExistingOverrideConfigurationBuiltInline() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.ClientConfiguration;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
              import com.amazonaws.xray.AWSXRay;
              import com.amazonaws.xray.handlers.TracingHandler;

              class Config {
                  AmazonDynamoDB dynamo() {
                      return AmazonDynamoDBClientBuilder.standard()
                              .withClientConfiguration(new ClientConfiguration().withMaxErrorRetry(5))
                              .withRequestHandlers(new TracingHandler(AWSXRay.getGlobalRecorder()))
                              .build();
                  }
              }
              """,
            """
              import com.amazonaws.xray.interceptors.TracingInterceptor;
              import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

              class Config {
                  DynamoDbClient dynamo() {
                      return DynamoDbClient.builder()
                              .overrideConfiguration(ClientOverrideConfiguration.builder().maxErrorRetry(5).addExecutionInterceptor(new TracingInterceptor()).build())
                              .build();
                  }
              }
              """
          )
        );
    }

    @Test
    void mergedIntoLaterOverrideConfigurationVariable() {
        rewriteRun(
          // The v1 `withRequestHandlers` v2-migration leaves on the v2 builder does not resolve
          spec -> spec.recipe(new MigrateXRayTracingHandler()).typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.xray.AWSXRayRecorder;
              import com.amazonaws.xray.handlers.TracingHandler;
              import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

              class Config {
                  DynamoDbClient dynamo(AWSXRayRecorder recorder, ClientOverrideConfiguration config) {
                      return DynamoDbClient.builder()
                              .withRequestHandlers(new TracingHandler(recorder))
                              .overrideConfiguration(config)
                              .build();
                  }
              }
              """,
            """
              import com.amazonaws.xray.AWSXRayRecorder;
              import com.amazonaws.xray.interceptors.TracingInterceptor;
              import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

              class Config {
                  DynamoDbClient dynamo(AWSXRayRecorder recorder, ClientOverrideConfiguration config) {
                      return DynamoDbClient.builder()
                              .overrideConfiguration(config.toBuilder().addExecutionInterceptor(new TracingInterceptor(recorder, null, null)).build())
                              .build();
                  }
              }
              """
          )
        );
    }

    @Test
    void mergedIntoOverrideConfigurationLambda() {
        rewriteRun(
          // The v1 `withRequestHandlers` v2-migration leaves on the v2 builder does not resolve
          spec -> spec.recipe(new MigrateXRayTracingHandler()).typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.xray.handlers.TracingHandler;
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

              import java.time.Duration;

              class Config {
                  DynamoDbClient dynamo() {
                      return DynamoDbClient.builder()
                              .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5)))
                              .withRequestHandlers(new TracingHandler())
                              .build();
                  }
              }
              """,
            """
              import com.amazonaws.xray.interceptors.TracingInterceptor;
              import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

              import java.time.Duration;

              class Config {
                  DynamoDbClient dynamo() {
                      return DynamoDbClient.builder()
                              .overrideConfiguration(ClientOverrideConfiguration.builder().applyMutation(c -> c.apiCallTimeout(Duration.ofSeconds(5))).addExecutionInterceptor(new TracingInterceptor()).build())
                              .build();
                  }
              }
              """
          )
        );
    }
}

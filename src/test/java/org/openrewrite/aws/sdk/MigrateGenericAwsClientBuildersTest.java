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

class MigrateGenericAwsClientBuildersTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateGenericAwsClientBuilders())
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sns"));
    }

    @DocumentExample
    @Test
    void endpointAndRegionOnGenericBuilder() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.client.builder.AwsClientBuilder;
              import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;

              class CustomEndpoint {
                  String endpoint;
                  String region;

                  <T extends AwsClientBuilder<?, ?>> T rebuild(T builder) {
                      builder.setEndpointConfiguration(new EndpointConfiguration(endpoint, region));
                      return builder;
                  }

                  <T extends AwsClientBuilder<?, ?>> T withRegion(T builder) {
                      builder.withRegion(region);
                      return builder;
                  }
              }
              """,
            """
              import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
              import software.amazon.awssdk.regions.Region;

              import java.net.URI;

              class CustomEndpoint {
                  String endpoint;
                  String region;

                  <T extends AwsClientBuilder<?, ?>> T rebuild(T builder) {
                      builder.endpointOverride(URI.create(endpoint)).region(Region.of(region));
                      return builder;
                  }

                  <T extends AwsClientBuilder<?, ?>> T withRegion(T builder) {
                      builder.region(Region.of(region));
                      return builder;
                  }
              }
              """
          )
        );
    }

    @Test
    void genericHelperInFileWithConcreteBuilderCalls() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.client.builder.AwsClientBuilder;
              import com.amazonaws.services.sns.AmazonSNS;
              import com.amazonaws.services.sns.AmazonSNSClientBuilder;

              class Mixed {
                  String region;

                  <T extends AwsClientBuilder<?, ?>> T withRegion(T builder) {
                      builder.setRegion(region);
                      return builder;
                  }

                  AmazonSNS sns() {
                      return AmazonSNSClientBuilder.standard().withRegion("us-east-1").build();
                  }

                  AmazonSNS configured() {
                      return withRegion(AmazonSNSClientBuilder.standard()).build();
                  }
              }
              """,
            """
              import com.amazonaws.services.sns.AmazonSNS;
              import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sns.SnsClient;

              class Mixed {
                  String region;

                  <T extends AwsClientBuilder<?, ?>> T withRegion(T builder) {
                      builder.region(Region.of(region));
                      return builder;
                  }

                  AmazonSNS sns() {
                      return SnsClient.builder().region(Region.of("us-east-1")).build();
                  }

                  AmazonSNS configured() {
                      return withRegion(SnsClient.builder()).build();
                  }
              }
              """
          )
        );
    }

    @Test
    void genericHelperInFileWithConcreteBuilderCallsEndsOnV2() {
        rewriteRun(
          spec -> spec.recipes(new MigrateGenericAwsClientBuilders(), new MigrateAwsClientBuildersToV2(),
              AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.AwsSdkJavaV1ToV2Safe"))
            // AWS's v2-migration leaves the types it adds untyped
            .afterTypeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.client.builder.AwsClientBuilder;
              import com.amazonaws.services.sns.AmazonSNS;
              import com.amazonaws.services.sns.AmazonSNSClientBuilder;

              class Mixed {
                  String region;

                  <T extends AwsClientBuilder<?, ?>> T withRegion(T builder) {
                      builder.setRegion(region);
                      return builder;
                  }

                  AmazonSNS sns() {
                      return AmazonSNSClientBuilder.standard().withRegion("us-east-1").build();
                  }

                  AmazonSNS configured() {
                      return withRegion(AmazonSNSClientBuilder.standard()).build();
                  }
              }
              """,
            """
              import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sns.SnsClient;

              class Mixed {
                  String region;

                  <T extends AwsClientBuilder<?, ?>> T withRegion(T builder) {
                      builder.region(Region.of(region));
                      return builder;
                  }

                  SnsClient sns() {
                      return SnsClient.builder().region(Region.of("us-east-1")).build();
                  }

                  SnsClient configured() {
                      return withRegion(SnsClient.builder()).build();
                  }
              }
              """
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void concreteBuilderCallsAreLeftToV2Migration() {
            rewriteRun(
              //language=java
              java(
                """
                  import com.amazonaws.auth.AWSCredentialsProvider;
                  import com.amazonaws.services.sns.AmazonSNSClientBuilder;

                  class SnsConnection {
                      AmazonSNSClientBuilder builder(AWSCredentialsProvider credentials) {
                          AmazonSNSClientBuilder builder = AmazonSNSClientBuilder.standard();
                          builder.withCredentials(credentials);
                          return builder;
                      }
                  }
                  """
              )
            );
        }
    }
}

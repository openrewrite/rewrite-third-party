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

import static org.openrewrite.java.Assertions.java;

class MigrateAwsEndpointConfigurationValuesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAwsEndpointConfigurationValues(null))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sqs"));
    }

    @DocumentExample
    @Test
    void beanHoldingEndpointAndRegion() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example.config;

              import com.amazonaws.client.builder.AwsClientBuilder;

              class DevConfig {
                  public AwsClientBuilder.EndpointConfiguration endpointConfiguration() {
                      return new AwsClientBuilder.EndpointConfiguration("http://localhost:4576", "ap-northeast-1");
                  }
              }
              """,
            """
              package com.example.config;

              class DevConfig {
                  public AwsEndpointConfiguration endpointConfiguration() {
                      return new AwsEndpointConfiguration("http://localhost:4576", "ap-northeast-1");
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            AwsEndpointConfigurationShim.source("com.example.config"),
            spec -> spec.path("com/example/config/AwsEndpointConfiguration.java")
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void inlineBuilderEndpointIsLeftForFolding() {
            rewriteRun(
              //language=java
              java(
                """
                  import com.amazonaws.client.builder.AwsClientBuilder;
                  import com.amazonaws.services.sqs.AmazonSQS;
                  import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

                  class Config {
                      AmazonSQS sqs() {
                          return AmazonSQSClientBuilder.standard()
                              .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration("http://localhost:4576", "us-east-1"))
                              .build();
                      }
                  }
                  """
              )
            );
        }
    }
}

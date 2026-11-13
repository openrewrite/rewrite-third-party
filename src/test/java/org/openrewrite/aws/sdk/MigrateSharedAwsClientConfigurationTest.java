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

class MigrateSharedAwsClientConfigurationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSharedAwsClientConfiguration(null))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sns"))
          // The generated class is attributed without the Apache HTTP client on the parser classpath
          .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void configurationBuiltInFactoryMethod() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.ClientConfiguration;

              class ClientConfigurationBuilder {
                  static ClientConfiguration build(int retries, String proxy) {
                      return new ClientConfiguration().withMaxErrorRetry(retries).withProxyHost(proxy).withGzip(true);
                  }
              }
              """,
            """
              package com.example;

              class ClientConfigurationBuilder {
                  static AwsClientConfiguration build(int retries, String proxy) {
                      return new AwsClientConfiguration().withMaxErrorRetry(retries).withProxyHost(proxy).withGzip(true);
                  }
              }
              """
          ),
          java(
            """
              package com.example;

              import com.amazonaws.ClientConfiguration;
              import com.amazonaws.services.sns.AmazonSNS;
              import com.amazonaws.services.sns.AmazonSNSClientBuilder;

              class SnsConnection {
                  AmazonSNS connect() {
                      ClientConfiguration cc = ClientConfigurationBuilder.build(3, "proxy");
                      return AmazonSNSClientBuilder.standard().withClientConfiguration(cc).build();
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.sns.AmazonSNS;
              import com.amazonaws.services.sns.AmazonSNSClientBuilder;

              class SnsConnection {
                  AmazonSNS connect() {
                      AwsClientConfiguration cc = ClientConfigurationBuilder.build(3, "proxy");
                      return AwsClientConfiguration.applyTo(AmazonSNSClientBuilder.standard(), cc).build();
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            AwsClientConfigurationShim.source("com.example"),
            spec -> spec.path("com/example/AwsClientConfiguration.java")
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void localConfigurationIsLeftForBuilderFolding() {
            rewriteRun(
              //language=java
              java(
                """
                  import com.amazonaws.ClientConfiguration;
                  import com.amazonaws.services.sns.AmazonSNS;
                  import com.amazonaws.services.sns.AmazonSNSClientBuilder;

                  class SnsConnection {
                      AmazonSNS connect() {
                          ClientConfiguration cc = new ClientConfiguration().withMaxErrorRetry(3);
                          return AmazonSNSClientBuilder.standard().withClientConfiguration(cc).build();
                      }
                  }
                  """
              )
            );
        }
    }
}

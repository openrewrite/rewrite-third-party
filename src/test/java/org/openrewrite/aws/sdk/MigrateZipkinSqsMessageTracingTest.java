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
import static org.openrewrite.java.Assertions.srcMainJava;

class MigrateZipkinSqsMessageTracingTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipes(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.AwsSdkJavaV1ToV2Safe"), new MigrateZipkinSqsMessageTracing(null))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sqs", "sqs-2", "regions",
              "sdk-core", "aws-core")
            .dependsOn(
              """
                package brave;
                public abstract class Tracing {
                    public static Tracing current() { return null; }
                }
                """,
              """
                package brave.instrumentation.aws.sqs;
                public class SqsMessageTracing {
                    public static SqsMessageTracing create(brave.Tracing tracing) { return null; }
                    public com.amazonaws.handlers.RequestHandler2 requestHandler() { return null; }
                }
                """
            ))
          // the generated class is parsed without Brave, and AWS's v2-migration leaves what it adds untyped
          .afterTypeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void requestHandlerBecomesGeneratedInterceptor() {
        rewriteRun(
          srcMainJava(
            //language=java
            java(
              """
                package com.acme.queue;

                import brave.Tracing;
                import brave.instrumentation.aws.sqs.SqsMessageTracing;
                import com.amazonaws.services.sqs.AmazonSQSAsync;
                import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder;

                class QueueConfig {
                    AmazonSQSAsync client() {
                        SqsMessageTracing sqsMessageTracing = SqsMessageTracing.create(Tracing.current());
                        return AmazonSQSAsyncClientBuilder.standard()
                                .withRegion("eu-west-1")
                                .withRequestHandlers(sqsMessageTracing.requestHandler())
                                .build();
                    }
                }
                """,
              """
                package com.acme.queue;

                import brave.Tracing;
                import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
                import software.amazon.awssdk.regions.Region;
                import software.amazon.awssdk.services.sqs.SqsAsyncClient;

                class QueueConfig {
                    SqsAsyncClient client() {
                        SqsMessageTracing sqsMessageTracing = SqsMessageTracing.create(Tracing.current());
                        return SqsAsyncClient.builder()
                                .region(Region.of("eu-west-1"))
                                .overrideConfiguration(ClientOverrideConfiguration.builder().addExecutionInterceptor(sqsMessageTracing.executionInterceptor()).build())
                                .build();
                    }
                }
                """
            ),
            java(
              null,
              spec -> spec.path("com/acme/queue/SqsMessageTracing.java")
                .after(actual -> assertThat(actual)
                  .startsWith("package com.acme.queue;")
                  .contains("public ExecutionInterceptor executionInterceptor()")
                  .contains("public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes executionAttributes)")
                  .actual())
            )
          )
        );
    }
}

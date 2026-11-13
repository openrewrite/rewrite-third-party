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

class MigrateAwsClientBuilderSubclassesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAwsClientBuilderSubclasses(null))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sqs"))
          // The generated base class is a v2 type the test classpath does not have
          .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void builderRecordsSettings() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.ClientConfigurationFactory;
              import com.amazonaws.client.builder.AwsClientBuilder;

              public class MockAwsClientBuilder extends AwsClientBuilder {

                  public MockAwsClientBuilder() {
                      super(new ClientConfigurationFactory());
                  }

                  @Override
                  public Object build() {
                      throw new UnsupportedOperationException();
                  }
              }
              """,
            """
              package com.example;

              public class MockAwsClientBuilder extends RecordingAwsClientBuilder {

                  @Override
                  public Object build() {
                      throw new UnsupportedOperationException();
                  }
              }
              """
          ),
          java(
            """
              package com.example;

              import com.amazonaws.client.builder.AwsClientBuilder;

              class EndpointTest {
                  String endpoint(AwsClientBuilder b) {
                      return b.getEndpoint().getServiceEndpoint() + b.getRegion();
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.client.builder.AwsClientBuilder;

              class EndpointTest {
                  String endpoint(AwsClientBuilder b) {
                      return RecordingAwsClientBuilder.endpointOf(b).getServiceEndpoint() + RecordingAwsClientBuilder.regionOf(b);
                  }
              }
              """
          ),
          java(
            """
              package com.example;

              import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

              class ServiceBuilderRegion {
                  String region() {
                      return AmazonSQSClientBuilder.standard().getRegion();
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            RecordingAwsClientBuilderShim.source("com.example"),
            spec -> spec.path("com/example/RecordingAwsClientBuilder.java")
          )
        );
    }
}

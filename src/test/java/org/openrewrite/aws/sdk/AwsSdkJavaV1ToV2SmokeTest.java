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

class AwsSdkJavaV1ToV2SmokeTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2")
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sqs"))
          // AWS's v2-migration leaves the `Region` it adds untyped
          .afterTypeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void sqsClientBuilder() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

              class Clients {
                  AmazonSQS sqs() {
                      return AmazonSQSClientBuilder.standard().withRegion("us-east-1").build();
                  }
              }
              """,
            """
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sqs.SqsClient;

              class Clients {
                  SqsClient sqs() {
                      return SqsClient.builder().region(Region.of("us-east-1")).build();
                  }
              }
              """
          )
        );
    }
}

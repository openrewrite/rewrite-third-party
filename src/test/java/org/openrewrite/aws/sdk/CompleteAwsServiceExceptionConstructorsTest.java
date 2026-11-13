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

class CompleteAwsServiceExceptionConstructorsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.MigrateAwsSdkV1ToV2Steps"))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-dynamodb"))
          // v2-migration's output is attributed against v1 types, without the v2 SDK on the classpath
          .afterTypeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void messageConstructorBecomesBuilder() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;

              class Tables {
                  void missing(String table) {
                      throw new ResourceNotFoundException("no table " + table);
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

              class Tables {
                  void missing(String table) {
                      throw ResourceNotFoundException.builder().message("no table " + table).build();
                  }
              }
              """
          )
        );
    }
}

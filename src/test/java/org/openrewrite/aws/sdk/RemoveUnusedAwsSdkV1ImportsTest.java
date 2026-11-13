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

class RemoveUnusedAwsSdkV1ImportsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveUnusedAwsSdkV1Imports())
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-s3"));
    }

    @DocumentExample
    @Test
    void removesUnusedImport() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.Response;
              import java.util.List;

              class Controller {
                  List<String> names;
              }
              """,
            """
              package com.example;

              import java.util.List;

              class Controller {
                  List<String> names;
              }
              """
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void keepsUsedImports() {
            rewriteRun(
              //language=java
              java(
                """
                  import com.amazonaws.services.s3.AmazonS3;

                  class Storage {
                      AmazonS3 s3;
                  }
                  """
              )
            );
        }
    }
}

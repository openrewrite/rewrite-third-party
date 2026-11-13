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

class MigrateDynamoDbTableUtilsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateDynamoDbTableUtils(null))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-dynamodb"))
          // The helper is attributed without the v2 DynamoDB client on the parser classpath
          .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void tableUtilsCallsUseGeneratedHelper() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
              import com.amazonaws.services.dynamodbv2.util.TableUtils;

              class Tables {
                  void create(AmazonDynamoDB dynamo, CreateTableRequest request) throws InterruptedException {
                      if (TableUtils.createTableIfNotExists(dynamo, request)) {
                          try {
                              TableUtils.waitUntilActive(dynamo, request.getTableName());
                          } catch (TableUtils.TableNeverTransitionedToStateException e) {
                              throw new IllegalStateException(e);
                          }
                      }
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;

              class Tables {
                  void create(AmazonDynamoDB dynamo, CreateTableRequest request) throws InterruptedException {
                      if (AwsTableUtils.createTableIfNotExists(dynamo, request)) {
                          try {
                              AwsTableUtils.waitUntilActive(dynamo, request.getTableName());
                          } catch (AwsTableUtils.TableNeverTransitionedToStateException e) {
                              throw new IllegalStateException(e);
                          }
                      }
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            AwsTableUtilsShim.source("com.example"),
            spec -> spec.path("com/example/AwsTableUtils.java")
          )
        );
    }
}

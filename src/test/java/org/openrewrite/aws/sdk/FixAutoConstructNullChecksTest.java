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

class FixAutoConstructNullChecksTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FixAutoConstructNullChecks())
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(),
              "dynamodb-2",
              "sqs-2",
              "sdk-core",
              "aws-core",
              "utils"));
    }

    @DocumentExample
    @Test
    void collectionNullChecks() {
        rewriteRun(
          //language=java
          java(
            """
              import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
              import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
              import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

              import java.util.Objects;

              class Pages {
                  boolean more(QueryResponse r) {
                      return r.lastEvaluatedKey() != null;
                  }

                  boolean empty(ReceiveMessageResponse r) {
                      return null == r.messages() || r.messages().isEmpty();
                  }

                  boolean noMap(AttributeValue v) {
                      return Objects.isNull(v.m());
                  }

                  boolean count(QueryResponse r) {
                      return r.count() == null;
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
              import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
              import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

              class Pages {
                  boolean more(QueryResponse r) {
                      return r.hasLastEvaluatedKey();
                  }

                  boolean empty(ReceiveMessageResponse r) {
                      return !r.hasMessages() || r.messages().isEmpty();
                  }

                  boolean noMap(AttributeValue v) {
                      return !v.hasM();
                  }

                  boolean count(QueryResponse r) {
                      return r.count() == null;
                  }
              }
              """
          )
        );
    }

    @Test
    void paginationLoopAfterV2Migration() {
        rewriteRun(
          spec -> spec.recipeFromResources("org.openrewrite.aws.sdk.AsyncClientAndS3ResidualsWithV2Migration")
            .parser(JavaParser.fromJavaVersion()
              .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-dynamodb"))
            .typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.model.AttributeValue;
              import com.amazonaws.services.dynamodbv2.model.ScanRequest;
              import com.amazonaws.services.dynamodbv2.model.ScanResult;

              import java.util.Map;

              class Scanner {
                  int count(AmazonDynamoDB dynamo) {
                      int count = 0;
                      Map<String, AttributeValue> start = null;
                      do {
                          ScanResult result = dynamo.scan(new ScanRequest().withTableName("t").withExclusiveStartKey(start));
                          count += result.getItems().size();
                          start = result.getLastEvaluatedKey();
                      } while (start != null);
                      return count;
                  }

                  boolean hasItems(ScanResult result) {
                      return result.getItems() != null;
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
              import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
              import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
              import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

              import java.util.Map;

              class Scanner {
                  int count(DynamoDbClient dynamo) {
                      int count = 0;
                      Map<String, AttributeValue> start = null;
                      do {
                          ScanResponse result = dynamo.scan(ScanRequest.builder().tableName("t").exclusiveStartKey(start)
                                  .build());
                          count += result.items().size();
                          start = result.hasLastEvaluatedKey() ? result.lastEvaluatedKey() : null;
                      } while (start != null);
                      return count;
                  }

                  boolean hasItems(ScanResponse result) {
                      return result.hasItems();
                  }
              }
              """
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void ignoresNonModelCollections() {
            rewriteRun(
              //language=java
              java(
                """
                  import java.util.List;

                  class Holder {
                      List<String> items() {
                          return null;
                      }

                      boolean none() {
                          return this.items() == null;
                      }
                  }
                  """
              )
            );
        }
    }
}

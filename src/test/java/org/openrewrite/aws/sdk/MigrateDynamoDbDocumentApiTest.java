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

class MigrateDynamoDbDocumentApiTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.MigrateAwsSdkV1ToV2"))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-dynamodb"))
          // v2-migration's output is attributed against v1 types, without the v2 SDK on the classpath
          .afterTypeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void documentApiMovesToGeneratedClass() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.document.DynamoDB;
              import com.amazonaws.services.dynamodbv2.document.Item;
              import com.amazonaws.services.dynamodbv2.document.Table;
              import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
              import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
              import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
              import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;

              class Drugs {
                  private final DynamoDB dynamoDB;

                  Drugs(AmazonDynamoDB client) {
                      dynamoDB = new DynamoDB(client);
                  }

                  void create() throws InterruptedException {
                      Table table = dynamoDB.createTable(new CreateTableRequest()
                          .withTableName("drugs")
                          .withKeySchema(new KeySchemaElement().withAttributeName("name").withKeyType("HASH")));
                      table.waitForActive();
                  }

                  String find(String name) {
                      Table table = dynamoDB.getTable("drugs");
                      table.putItem(new Item().withPrimaryKey("name", name).withNumber("doses", 2));
                      table.updateItem(new UpdateItemSpec().withPrimaryKey("name", name)
                          .withUpdateExpression("set doses = :d")
                          .withValueMap(new ValueMap().withNumber(":d", 3)));
                      Item item = table.getItem("name", name);
                      return item == null ? null : item.getString("name");
                  }
              }
              """,
            """
              package com.example;

              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
              import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
              import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;

              class Drugs {
                  private final DynamoDbDocumentApi.DynamoDB dynamoDB;

                  Drugs(DynamoDbClient client) {
                      dynamoDB = new DynamoDbDocumentApi.DynamoDB(client);
                  }

                  void create() throws InterruptedException {
                      DynamoDbDocumentApi.Table table = dynamoDB.createTable(CreateTableRequest.builder()
                              .tableName("drugs")
                              .keySchema(KeySchemaElement.builder().attributeName("name").keyType("HASH")
                                      .build())
                              .build());
                      table.waitForActive();
                  }

                  String find(String name) {
                      DynamoDbDocumentApi.Table table = dynamoDB.getTable("drugs");
                      table.putItem(new DynamoDbDocumentApi.Item().withPrimaryKey("name", name).withNumber("doses", 2));
                      table.updateItem(new DynamoDbDocumentApi.UpdateItemSpec().withPrimaryKey("name", name)
                          .withUpdateExpression("set doses = :d")
                          .withValueMap(new DynamoDbDocumentApi.ValueMap().withNumber(":d", 3)));
                      DynamoDbDocumentApi.Item item = table.getItem("name", name);
                      return item == null ? null : item.getString("name");
                  }
              }
              """
          ),
          java(
            null,
            """
              package com.example;
              """,
            spec -> spec.path("com/example/DynamoDbDocumentApi.java")
              .after(actual -> {
                  assertThat(actual).contains("public final class DynamoDbDocumentApi", "public static class Table");
                  return actual;
              })
          )
        );
    }

    @Test
    void legacyConditionsAndJsonMoveToGeneratedClass() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.dynamodbv2.document.AttributeUpdate;
              import com.amazonaws.services.dynamodbv2.document.Expected;
              import com.amazonaws.services.dynamodbv2.document.Item;
              import com.amazonaws.services.dynamodbv2.document.ItemCollection;
              import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
              import com.amazonaws.services.dynamodbv2.document.QueryFilter;
              import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
              import com.amazonaws.services.dynamodbv2.document.RangeKeyCondition;
              import com.amazonaws.services.dynamodbv2.document.ScanFilter;
              import com.amazonaws.services.dynamodbv2.document.ScanOutcome;
              import com.amazonaws.services.dynamodbv2.document.Table;
              import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;

              import java.util.Collections;

              class Orders {
                  void save(Table table, String json) {
                      table.putItem(Item.fromJSON(json), new Expected("id").notExist());
                  }

                  void count(Table table, String id) {
                      table.updateItem(new PrimaryKey("id", id), Collections.singletonList(new Expected("open").eq(true)),
                          new AttributeUpdate("count").addNumeric(1));
                  }

                  ItemCollection<QueryOutcome> recent(Table table, String id) {
                      return table.query(new QuerySpec().withHashKey("id", id)
                          .withRangeKeyCondition(new RangeKeyCondition("date").beginsWith("2024"))
                          .withQueryFilters(new QueryFilter("total").gt(10)));
                  }

                  ItemCollection<ScanOutcome> big(Table table) {
                      return table.scan(new ScanFilter("total").ge(100));
                  }

                  String print(Item item) {
                      return item.toJSONPretty() + item.toJSON();
                  }
              }
              """,
            """
              package com.example;

              import java.util.Collections;

              class Orders {
                  void save(DynamoDbDocumentApi.Table table, String json) {
                      table.putItem(DynamoDbDocumentApi.Item.fromJSON(json), new DynamoDbDocumentApi.Expected("id").notExist());
                  }

                  void count(DynamoDbDocumentApi.Table table, String id) {
                      table.updateItem(new DynamoDbDocumentApi.PrimaryKey("id", id), Collections.singletonList(new DynamoDbDocumentApi.Expected("open").eq(true)),
                          new DynamoDbDocumentApi.AttributeUpdate("count").addNumeric(1));
                  }

                  DynamoDbDocumentApi.ItemCollection<DynamoDbDocumentApi.QueryOutcome> recent(DynamoDbDocumentApi.Table table, String id) {
                      return table.query(new DynamoDbDocumentApi.QuerySpec().withHashKey("id", id)
                          .withRangeKeyCondition(new DynamoDbDocumentApi.RangeKeyCondition("date").beginsWith("2024"))
                          .withQueryFilters(new DynamoDbDocumentApi.QueryFilter("total").gt(10)));
                  }

                  DynamoDbDocumentApi.ItemCollection<DynamoDbDocumentApi.ScanOutcome> big(DynamoDbDocumentApi.Table table) {
                      return table.scan(new DynamoDbDocumentApi.ScanFilter("total").ge(100));
                  }

                  String print(DynamoDbDocumentApi.Item item) {
                      return item.toJSONPretty() + item.toJSON();
                  }
              }
              """
          ),
          java(
            null,
            """
              package com.example;
              """,
            spec -> spec.path("com/example/DynamoDbDocumentApi.java")
              .after(actual -> {
                  assertThat(actual).contains("public static class Expected extends Filter<Expected>",
                    "public static class RangeKeyCondition", "public static class AttributeUpdate",
                    "request.keyConditions(keyConditions)", "public static Item fromJSON(String json)");
                  return actual;
              })
          )
        );
    }
}

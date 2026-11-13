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

class MergeAwsModelMapEntryAddersTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.parser(JavaParser.fromJavaVersion()
          .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-dynamodb"));
    }

    @DocumentExample
    @Test
    void entriesOfEachMapBecomeOneMap() {
        rewriteRun(
          spec -> spec.recipe(new MergeAwsModelMapEntryAdders()),
          //language=java
          java(
            """
              import com.amazonaws.services.dynamodbv2.model.AttributeAction;
              import com.amazonaws.services.dynamodbv2.model.AttributeValue;
              import com.amazonaws.services.dynamodbv2.model.AttributeValueUpdate;
              import com.amazonaws.services.dynamodbv2.model.ReturnValue;
              import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;

              class Counter {
                  UpdateItemRequest next() {
                      return new UpdateItemRequest()
                          .withTableName("Counter")
                          .addKeyEntry("name", new AttributeValue().withS("HashId"))
                          .addKeyEntry("shard", new AttributeValue().withN("1"))
                          .addAttributeUpdatesEntry("value", new AttributeValueUpdate()
                              .withValue(new AttributeValue().withN("1"))
                              .withAction(AttributeAction.ADD))
                          .withReturnValues(ReturnValue.UPDATED_NEW);
                  }
              }
              """,
            """
              import com.amazonaws.services.dynamodbv2.model.AttributeAction;
              import com.amazonaws.services.dynamodbv2.model.AttributeValue;
              import com.amazonaws.services.dynamodbv2.model.AttributeValueUpdate;
              import com.amazonaws.services.dynamodbv2.model.ReturnValue;
              import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;

              import java.util.Map;

              class Counter {
                  UpdateItemRequest next() {
                      return new UpdateItemRequest()
                          .withTableName("Counter")
                          .withKey(Map.of("name", new AttributeValue().withS("HashId"), "shard", new AttributeValue().withN("1")))
                          .withAttributeUpdates(Map.of("value", new AttributeValueUpdate()
                              .withValue(new AttributeValue().withN("1"))
                              .withAction(AttributeAction.ADD)))
                          .withReturnValues(ReturnValue.UPDATED_NEW);
                  }
              }
              """
          )
        );
    }

    @Test
    void chainThatAlsoSetsTheMapIsLeftAlone() {
        rewriteRun(
          spec -> spec.recipe(new MergeAwsModelMapEntryAdders()),
          //language=java
          java(
            """
              import com.amazonaws.services.dynamodbv2.model.AttributeValue;
              import com.amazonaws.services.dynamodbv2.model.GetItemRequest;

              import java.util.Map;

              class Lookup {
                  GetItemRequest get(Map<String, AttributeValue> key) {
                      return new GetItemRequest().withKey(key).addKeyEntry("sort", new AttributeValue().withS("a"));
                  }
              }
              """
          )
        );
    }

    @Test
    void migratesToTheV2Builder() {
        rewriteRun(
          spec -> spec.recipe(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.MigrateAwsSdkV1ToV2Steps"))
            .afterTypeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.services.dynamodbv2.model.AttributeValue;
              import com.amazonaws.services.dynamodbv2.model.GetItemRequest;

              class Lookup {
                  GetItemRequest get() {
                      return new GetItemRequest()
                          .withTableName("Orders")
                          .addKeyEntry("id", new AttributeValue().withS("1"));
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
              import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

              import java.util.Map;

              class Lookup {
                  GetItemRequest get() {
                      return GetItemRequest.builder()
                              .tableName("Orders")
                              .key(Map.of("id",  AttributeValue.builder().s("1")
                                      .build()))
                              .build();
                  }
              }
              """
          )
        );
    }
}

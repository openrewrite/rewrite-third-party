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

class CompleteDynamoDbAttributeValuesTest implements RewriteTest {

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
    void nullAndBooleanAccessorsAndMethodReferences() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.dynamodbv2.model.AttributeValue;

              import java.util.List;
              import java.util.stream.Collectors;

              class Items {
                  AttributeValue absent() {
                      return new AttributeValue().withNULL(true);
                  }

                  void clear(AttributeValue v) {
                      v.setNULL(true);
                  }

                  boolean isAbsent(AttributeValue v) {
                      return Boolean.TRUE.equals(v.isNULL()) || Boolean.TRUE.equals(v.getNULL());
                  }

                  Boolean flag(AttributeValue v) {
                      return v.isBOOL();
                  }

                  List<String> names(List<AttributeValue> values) {
                      return values.stream().map(AttributeValue::getS).collect(Collectors.toList());
                  }

                  List<Boolean> flags(List<AttributeValue> values) {
                      return values.stream().map(AttributeValue::getBOOL).collect(Collectors.toList());
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

              import java.util.List;
              import java.util.stream.Collectors;

              class Items {
                  AttributeValue absent() {
                      return AttributeValue.builder().nul(true)
                              .build();
                  }

                  AttributeValue clear(AttributeValue v) {
                      v = v.toBuilder().nul(true).build();
                      return v;
                  }

                  boolean isAbsent(AttributeValue v) {
                      return Boolean.TRUE.equals(v.nul()) || Boolean.TRUE.equals(v.nul());
                  }

                  Boolean flag(AttributeValue v) {
                      return v.bool();
                  }

                  List<String> names(List<AttributeValue> values) {
                      return values.stream().map(AttributeValue::s).collect(Collectors.toList());
                  }

                  List<Boolean> flags(List<AttributeValue> values) {
                      return values.stream().map(AttributeValue::bool).collect(Collectors.toList());
                  }
              }
              """
          )
        );
    }

    @Test
    void binaryValuesAndSets() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.dynamodbv2.model.AttributeValue;

              import java.nio.ByteBuffer;
              import java.util.List;
              import java.util.Map;

              class Items {
                  AttributeValue one(ByteBuffer b) {
                      return new AttributeValue().withB(b);
                  }

                  AttributeValue many(List<ByteBuffer> bs) {
                      return new AttributeValue().withBS(bs);
                  }

                  AttributeValue varargs(ByteBuffer a, ByteBuffer b) {
                      return new AttributeValue().withBS(a, b);
                  }

                  void set(AttributeValue v, ByteBuffer b, List<ByteBuffer> bs) {
                      v.setB(b);
                      v.setBS(bs);
                  }

                  AttributeValue built(ByteBuffer b) {
                      AttributeValue v = new AttributeValue();
                      v.setB(b);
                      return v;
                  }

                  byte[] bytes(Map<String, AttributeValue> item) {
                      return item.get("k").getB().array();
                  }

                  List<ByteBuffer> all(AttributeValue v) {
                      return v.getBS();
                  }
              }
              """,
            """
              import software.amazon.awssdk.core.SdkBytes;
              import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

              import java.nio.ByteBuffer;
              import java.util.List;
              import java.util.Map;
              import java.util.stream.Collectors;

              class Items {
                  AttributeValue one(ByteBuffer b) {
                      return AttributeValue.builder().b(SdkBytes.fromByteBuffer(b))
                              .build();
                  }

                  AttributeValue many(List<ByteBuffer> bs) {
                      return AttributeValue.builder().bs(bs.stream().map(SdkBytes::fromByteBuffer).collect(Collectors.toList()))
                              .build();
                  }

                  AttributeValue varargs(ByteBuffer a, ByteBuffer b) {
                      return AttributeValue.builder().bs(SdkBytes.fromByteBuffer(a), SdkBytes.fromByteBuffer(b))
                              .build();
                  }

                  AttributeValue set(AttributeValue v, ByteBuffer b, List<ByteBuffer> bs) {
                      v = v.toBuilder().b(SdkBytes.fromByteBuffer(b)).build();
                      v = v.toBuilder().bs(bs.stream().map(SdkBytes::fromByteBuffer).collect(Collectors.toList())).build();
                      return v;
                  }

                  AttributeValue built(ByteBuffer b) {
                      AttributeValue v = AttributeValue.builder().b(SdkBytes.fromByteBuffer(b))
                              .build();
                      return v;
                  }

                  byte[] bytes(Map<String, AttributeValue> item) {
                      return item.get("k").b().asByteBuffer().array();
                  }

                  List<ByteBuffer> all(AttributeValue v) {
                      return v.bs().stream().map(SdkBytes::asByteBuffer).collect(Collectors.toList());
                  }
              }
              """
          )
        );
    }
}

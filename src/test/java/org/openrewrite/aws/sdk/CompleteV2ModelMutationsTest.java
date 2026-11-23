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
import org.openrewrite.aws.sdk.table.AwsSdkV1Remnants;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.java.Assertions.java;

class CompleteV2ModelMutationsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new CompleteV2ModelMutations())
          .parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(),
            "s3-2",
            "sdk-core",
            "aws-core",
            "regions",
            "auth",
            "utils",
            "http-client-spi",
            "identity-spi"))
          // The input holds the builder-only calls v2-migration left on immutable models, which do not resolve
          .typeValidationOptions(TypeValidation.none())
          .afterTypeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void witherStatementsAndExpressions() {
        rewriteRun(
          //language=java
          java(
            """
              import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
              import software.amazon.awssdk.services.s3.model.StorageClass;

              class Listing {
                  private ListObjectsV2Request request = ListObjectsV2Request.builder().bucket("b").build();

                  ListObjectsV2Request next(String token) {
                      request.continuationToken(token);
                      this.request.maxKeys(10);
                      return request.prefix("logs/");
                  }

                  StorageClass storageClass(String value) {
                      return StorageClass.fromValue(value);
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
              import software.amazon.awssdk.services.s3.model.StorageClass;

              class Listing {
                  private ListObjectsV2Request request = ListObjectsV2Request.builder().bucket("b").build();

                  ListObjectsV2Request next(String token) {
                      request = request.toBuilder().continuationToken(token).build();
                      this.request = this.request.toBuilder().maxKeys(10).build();
                      return request.toBuilder().prefix("logs/").build();
                  }

                  StorageClass storageClass(String value) {
                      return StorageClass.fromValue(value);
                  }
              }
              """
          )
        );
    }

    @Test
    void privateMethodRebuildingParameterReturnsIt() {
        rewriteRun(
          //language=java
          java(
            """
              import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

              class Listing {
                  ListObjectsV2Request request(String bucket) {
                      ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).build();
                      limit(request, 10);
                      return request;
                  }

                  private void limit(ListObjectsV2Request request, int max) {
                      request.maxKeys(max);
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

              class Listing {
                  ListObjectsV2Request request(String bucket) {
                      ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).build();
                      request = limit(request, 10);
                      return request;
                  }

                  private ListObjectsV2Request limit(ListObjectsV2Request request, int max) {
                      request = request.toBuilder().maxKeys(max).build();
                      return request;
                  }
              }
              """
          )
        );
    }

    @Test
    void rebuildsAfterADeclarationJoinItsBuilder() {
        rewriteRun(
          //language=java
          java(
            """
              import software.amazon.awssdk.services.s3.model.S3Object;

              import java.util.Date;

              class Summaries {
                  static S3Object createSummary(String bucket, String key) {
                      S3Object sbase = S3Object.builder()
                          .build();
                      sbase.bucket(bucket);
                      sbase.key(key);
                      sbase.size(0);
                      sbase.lastModified(new Date().toInstant());
                      return sbase;
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.s3.model.S3Object;

              import java.util.Date;

              class Summaries {
                  static S3Object createSummary(String bucket, String key) {
                      // v1's S3ObjectSummary held its bucket; v2's S3Object does not, the listing request names it
                      S3Object sbase = S3Object.builder().key(key).size(0L).lastModified(new Date().toInstant())
                          .build();
                      return sbase;
                  }
              }
              """
          )
        );
    }

    @Test
    void publicMethodRebuildingParameterReturnsItToCallersInOtherFiles() {
        rewriteRun(
          spec -> spec.recipes(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.AwsSdkJavaV1ToV2Safe"), new CompleteV2ModelMutations())
            .parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(),
              "aws-java-sdk-core", "aws-java-sdk-s3")),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.s3.model.ListObjectsV2Request;

              public class Paging {
                  public void limit(ListObjectsV2Request request, int max) {
                      request.setMaxKeys(max);
                  }
              }
              """,
            spec -> spec.after(actual -> assertThat(actual).contains("public ListObjectsV2Request limit(ListObjectsV2Request request, int max) {")
                       .contains("request = request.toBuilder().maxKeys(max).build();")
                       .contains("return request;").actual())
          ),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.s3.model.ListObjectsV2Request;

              class Lister {
                  ListObjectsV2Request request(Paging paging, String bucket) {
                      ListObjectsV2Request request = new ListObjectsV2Request().withBucketName(bucket);
                      paging.limit(request, 10);
                      return request;
                  }
              }
              """,
            spec -> spec.after(actual -> assertThat(actual).contains("request = paging.limit(request, 10);").actual())
          )
        );
    }

    @Test
    void chainedSettersOnOneModelRebuildItOnce() {
        rewriteRun(
          //language=java
          java(
            """
              import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

              class Chained {
                  ListObjectsV2Request page(ListObjectsV2Request request, String token) {
                      request.continuationToken(token).maxKeys(10);
                      return request;
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

              class Chained {
                  ListObjectsV2Request page(ListObjectsV2Request request, String token) {
                      request = request.toBuilder().continuationToken(token).maxKeys(10).build();
                      return request;
                  }
              }
              """
          )
        );
    }

    @Test
    void rebuildsThatCannotReachTheMutatedObjectAreMarked() {
        rewriteRun(
          spec -> spec.dataTable(AwsSdkV1Remnants.Row.class, rows -> assertThat(rows)
            .extracting(AwsSdkV1Remnants.Row::getKind, AwsSdkV1Remnants.Row::getReason)
            .containsOnly(tuple("code", AwsSdkV1Remnants.UNMIGRATABLE))
            .hasSize(3)),
          //language=java
          java(
            """
              import software.amazon.awssdk.services.s3.model.Tag;

              import java.util.List;

              class Tags {
                  void loop(List<Tag> tags) {
                      for (Tag tag : tags) {
                          tag = tag.toBuilder().value("v").build();
                      }
                  }

                  void lambda(List<Tag> tags) {
                      tags.forEach(tag -> {
                          tag = tag.toBuilder().key("k").value("v").build();
                      });
                  }

                  public void parameter(Tag tag) {
                      tag = tag.toBuilder().value("v").build();
                  }

                  Tag local(Tag source) {
                      Tag tag = source;
                      tag = tag.toBuilder().value("v").build();
                      return tag;
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.s3.model.Tag;

              import java.util.List;

              class Tags {
                  void loop(List<Tag> tags) {
                      for (Tag tag : tags) {
                          /*~~(v2 models are immutable, and rebuilding `tag`, a for-each variable, does not reach the object v1 changed in place: rebuild the object that holds it instead)~~>*/tag.value("v");
                      }
                  }

                  void lambda(List<Tag> tags) {
                      tags.forEach(tag -> {
                          /*~~(v2 models are immutable, and rebuilding `tag`, a lambda parameter, does not reach the object v1 changed in place: rebuild the object that holds it instead)~~>*/tag.key("k").value("v");
                      });
                  }

                  public void parameter(Tag tag) {
                      /*~~(v2 models are immutable, and rebuilding `tag`, a parameter, whose caller keeps the object v1 changed in place, does not reach the object v1 changed in place: rebuild the object that holds it instead)~~>*/tag.value("v");
                  }

                  Tag local(Tag source) {
                      Tag tag = source;
                      tag = tag.toBuilder().value("v").build();
                      return tag;
                  }
              }
              """
          )
        );
    }
}

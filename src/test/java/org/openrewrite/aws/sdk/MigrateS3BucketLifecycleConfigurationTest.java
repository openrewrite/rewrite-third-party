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

class MigrateS3BucketLifecycleConfigurationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateS3BucketLifecycleConfiguration(null))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-s3"))
          // Helper calls keep v1 parameter types for v2-migration to retype
          .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void fetchedRulesEditedInPlaceAndSaved() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.BucketLifecycleConfiguration;
              import com.amazonaws.services.s3.model.BucketLifecycleConfiguration.Rule;
              import com.amazonaws.services.s3.model.lifecycle.LifecycleFilter;
              import com.amazonaws.services.s3.model.lifecycle.LifecyclePrefixPredicate;

              import java.util.ArrayList;
              import java.util.Optional;

              class Lifecycle {
                  void expire(AmazonS3 s3, String bucket, String prefix, int days) {
                      BucketLifecycleConfiguration cfg = s3.getBucketLifecycleConfiguration(bucket);
                      if (cfg == null) {
                          cfg = new BucketLifecycleConfiguration().withRules(new ArrayList<>());
                      }
                      Optional<Rule> old = cfg.getRules().stream().filter(r -> r.getId().startsWith(prefix)).findFirst();
                      if (old.isPresent() && old.get().getExpirationInDays() != days) {
                          cfg.getRules().remove(old.get());
                      }
                      cfg.getRules().add(rule(prefix, days));
                      s3.setBucketLifecycleConfiguration(bucket, cfg);
                  }

                  private BucketLifecycleConfiguration.Rule rule(String prefix, int days) {
                      return new BucketLifecycleConfiguration.Rule()
                          .withId(prefix)
                          .withFilter(new LifecycleFilter(new LifecyclePrefixPredicate(prefix)))
                          .withExpirationInDays(days)
                          .withStatus(BucketLifecycleConfiguration.ENABLED);
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;
              import com.example.BucketLifecycleConfiguration.Rule;
              import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;

              import java.util.ArrayList;
              import java.util.Optional;

              class Lifecycle {
                  void expire(AmazonS3 s3, String bucket, String prefix, int days) {
                      BucketLifecycleConfiguration cfg = BucketLifecycleConfiguration.get(s3, bucket);
                      if (cfg == null) {
                          cfg = new BucketLifecycleConfiguration().withRules(new ArrayList<>());
                      }
                      Optional<Rule> old = cfg.getRules().stream().filter(r -> r.getId().startsWith(prefix)).findFirst();
                      if (old.isPresent() && old.get().getExpirationInDays() != days) {
                          cfg.getRules().remove(old.get());
                      }
                      cfg.getRules().add(rule(prefix, days));
                      BucketLifecycleConfiguration.set(s3, bucket, cfg);
                  }

                  private BucketLifecycleConfiguration.Rule rule(String prefix, int days) {
                      return new BucketLifecycleConfiguration.Rule()
                          .withId(prefix)
                          .withFilter(LifecycleRuleFilter.builder().prefix(prefix).build())
                          .withExpirationInDays(days)
                          .withStatus(BucketLifecycleConfiguration.ENABLED);
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            S3BucketLifecycleShim.source("com.example"),
            spec -> spec.path("com/example/BucketLifecycleConfiguration.java")
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void transitionsAreLeftToV2Migration() {
            rewriteRun(
              //language=java
              java(
                """
                  import com.amazonaws.services.s3.AmazonS3;
                  import com.amazonaws.services.s3.model.BucketLifecycleConfiguration;
                  import com.amazonaws.services.s3.model.StorageClass;

                  import java.util.Collections;

                  class Lifecycle {
                      void archive(AmazonS3 s3, String bucket) {
                          BucketLifecycleConfiguration.Rule rule = new BucketLifecycleConfiguration.Rule()
                              .withId("archive")
                              .addTransition(new BucketLifecycleConfiguration.Transition().withDays(30).withStorageClass(StorageClass.Glacier))
                              .withStatus(BucketLifecycleConfiguration.ENABLED);
                          s3.setBucketLifecycleConfiguration(bucket, new BucketLifecycleConfiguration(Collections.singletonList(rule)));
                      }
                  }
                  """
              )
            );
        }
    }
}

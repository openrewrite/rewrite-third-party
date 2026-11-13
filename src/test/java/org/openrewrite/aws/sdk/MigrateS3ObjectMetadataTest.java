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

class MigrateS3ObjectMetadataTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateS3ObjectMetadata(null))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-s3"))
          // Helper calls keep v1 parameter types for v2-migration to retype
          .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void metadataFilledByOtherClassesAndUploadedFromStream() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.s3.model.ObjectMetadata;

              interface MetadataRule {
                  void apply(ObjectMetadata meta);
              }
              """,
            """
              package com.example;

              interface MetadataRule {
                  void apply(ObjectMetadata meta);
              }
              """
          ),
          java(
            """
              package com.example;

              import com.amazonaws.services.s3.model.CannedAccessControlList;
              import com.amazonaws.services.s3.model.ObjectMetadata;
              import com.amazonaws.services.s3.model.PutObjectRequest;
              import com.amazonaws.services.s3.transfer.TransferManager;
              import com.amazonaws.services.s3.transfer.Upload;

              import java.io.InputStream;
              import java.util.List;

              class Uploader {
                  void upload(TransferManager tm, InputStream in, long size, List<MetadataRule> rules) throws Exception {
                      ObjectMetadata s3meta = new ObjectMetadata();
                      s3meta.setContentLength(size);
                      for (MetadataRule rule : rules) {
                          rule.apply(s3meta);
                      }
                      PutObjectRequest request = new PutObjectRequest("bucket", "key", in, s3meta);
                      request.setCannedAcl(CannedAccessControlList.BucketOwnerFullControl);
                      Upload upload = tm.upload(request);
                      upload.waitForCompletion();
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.s3.model.CannedAccessControlList;
              import com.amazonaws.services.s3.model.PutObjectRequest;
              import com.amazonaws.services.s3.transfer.TransferManager;
              import com.amazonaws.services.s3.transfer.Upload;

              import java.io.InputStream;
              import java.util.List;

              class Uploader {
                  void upload(TransferManager tm, InputStream in, long size, List<MetadataRule> rules) throws Exception {
                      ObjectMetadata s3meta = new ObjectMetadata();
                      s3meta.setContentLength(size);
                      for (MetadataRule rule : rules) {
                          rule.apply(s3meta);
                      }
                      PutObjectRequest request = ObjectMetadata.putObjectRequest("bucket", "key", s3meta);
                      request = ObjectMetadata.withCannedAcl(request, CannedAccessControlList.BucketOwnerFullControl);
                      Upload upload = ObjectMetadata.upload(tm, request, in, s3meta);
                      upload.waitForCompletion();
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            S3ObjectMetadataShim.source("com.example", true),
            spec -> spec.path("com/example/ObjectMetadata.java")
          )
        );
    }

    @Test
    void copyKeepsSourceMetadataWithOverrides() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.CopyObjectRequest;
              import com.amazonaws.services.s3.model.ObjectMetadata;
              import com.amazonaws.services.s3.model.ObjectTagging;

              import java.util.ArrayList;

              class Copier {
                  CopyObjectRequest copy(AmazonS3 s3, String bucket, String src, String dst) {
                      return new CopyObjectRequest(bucket, src, bucket, dst)
                          .withNewObjectMetadata(metadata(s3, bucket, src))
                          .withNewObjectTagging(new ObjectTagging(new ArrayList<>()));
                  }

                  private ObjectMetadata metadata(AmazonS3 s3, String bucket, String key) {
                      ObjectMetadata result = s3.getObjectMetadata(bucket, key).clone();
                      result.setHeader("x-amz-storage-class", "GLACIER");
                      return result;
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.CopyObjectRequest;
              import com.amazonaws.services.s3.model.ObjectTagging;

              import java.util.ArrayList;

              class Copier {
                  CopyObjectRequest copy(AmazonS3 s3, String bucket, String src, String dst) {
                      return ObjectMetadata.withNewObjectMetadata(new CopyObjectRequest(bucket, src, bucket, dst)
                              .withNewObjectTagging(new ObjectTagging(new ArrayList<>())), metadata(s3, bucket, src));
                  }

                  private ObjectMetadata metadata(AmazonS3 s3, String bucket, String key) {
                      ObjectMetadata result = ObjectMetadata.getObjectMetadata(s3, bucket, key).clone();
                      result.setHeader("x-amz-storage-class", "GLACIER");
                      return result;
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            S3ObjectMetadataShim.source("com.example", false),
            spec -> spec.path("com/example/ObjectMetadata.java")
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void localMetadataIsLeftToV2Migration() {
            rewriteRun(
              //language=java
              java(
                """
                  import com.amazonaws.services.s3.AmazonS3;
                  import com.amazonaws.services.s3.model.ObjectMetadata;

                  import java.io.InputStream;

                  class Uploader {
                      void upload(AmazonS3 s3, InputStream in, long size) {
                          ObjectMetadata meta = new ObjectMetadata();
                          meta.setContentLength(size);
                          meta.setContentType("text/plain");
                          s3.putObject("bucket", "key", in, meta);
                      }
                  }
                  """
              )
            );
        }
    }
}

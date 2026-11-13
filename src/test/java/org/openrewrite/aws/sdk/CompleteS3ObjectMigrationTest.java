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

class CompleteS3ObjectMigrationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new CompleteS3ObjectMigration())
          .parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(),
            "s3-2",
            "s3-transfer-manager",
            "sdk-core",
            "aws-core",
            "regions",
            "auth",
            "utils",
            "http-client-spi",
            "identity-spi"))
          // The input holds the v1 method names v2-migration left behind, which do not resolve
          .typeValidationOptions(TypeValidation.none())
          .afterTypeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void transferAndResponseGetters() {
        rewriteRun(
          //language=java
          java(
            """
              import software.amazon.awssdk.core.ResponseInputStream;
              import software.amazon.awssdk.services.s3.model.GetObjectResponse;
              import software.amazon.awssdk.transfer.s3.model.FileDownload;
              import software.amazon.awssdk.transfer.s3.model.Upload;

              import java.util.Map;

              class Transfers {
                  Map<String, String> download(FileDownload download) throws Exception {
                      download.waitForCompletion();
                      return download.getObjectMetadata().userMetadata();
                  }

                  void upload(Upload upload) throws Exception {
                      while (!upload.isDone()) {
                          System.out.println(upload.progress().snapshot().ratioTransferred() / 1);
                      }
                      upload.waitForCompletion();
                  }

                  Map<String, String> get(ResponseInputStream<GetObjectResponse> response) {
                      return response.response().userMetadata();
                  }

                  boolean copied(software.amazon.awssdk.services.s3.model.CopyObjectResponse r) {
                      return java.util.Objects.nonNull(/*AWS SDK for Java v2 migration: NOTE: V2's eTag() preserves surrounding quotes in the response, whereas V1's getETag() strips them - https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/migration-s3-client.html#V1s-ObjectMetadata-using-V1s-getETag*/
              r.eTag().replaceAll("^\\"|\\"$", ""));
                  }
              }
              """,
            """
              import software.amazon.awssdk.core.ResponseInputStream;
              import software.amazon.awssdk.services.s3.model.GetObjectResponse;
              import software.amazon.awssdk.transfer.s3.model.FileDownload;
              import software.amazon.awssdk.transfer.s3.model.Upload;

              import java.util.Map;

              class Transfers {
                  Map<String, String> download(FileDownload download) throws Exception {
                      download.completionFuture().join();
                      return download.completionFuture().join().response().metadata();
                  }

                  void upload(Upload upload) throws Exception {
                      while (!upload.completionFuture().isDone()) {
                          System.out.println((upload.progress().snapshot().ratioTransferred().orElse(0.0) * 100) / 1);
                      }
                      upload.completionFuture().join();
                  }

                  Map<String, String> get(ResponseInputStream<GetObjectResponse> response) {
                      return response.response().metadata();
                  }

                  boolean copied(software.amazon.awssdk.services.s3.model.CopyObjectResponse r) {
                      return java.util.Objects.nonNull(r.eTag().replaceAll("^\\"|\\"$", ""));
                  }
              }
              """
          )
        );
    }

    @Test
    void accessControlPolicyGrants() {
        rewriteRun(
          //language=java
          java(
            """
              import software.amazon.awssdk.services.s3.model.AccessControlPolicy;
              import software.amazon.awssdk.services.s3.model.Grant;
              import software.amazon.awssdk.services.s3.model.Grantee;

              import java.util.List;

              class Acls {
                  Grantee grantee(String id) {
                      return Grantee.builder().id(id)
                          .build();
                  }

                  AccessControlPolicy create(List<Grant> grants) {
                      AccessControlPolicy objectAcl = AccessControlPolicy.builder()
                          .build();
                      for (Grant grant : grants) {
                          objectAcl.grantAllPermissions(grant);
                      }
                      return objectAcl;
                  }

                  List<Grant> grants(AccessControlPolicy acl) {
                      return acl.grantsAsList();
                  }

                  String id(Grantee grantee) {
                      return grantee.identifier();
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.s3.model.AccessControlPolicy;
              import software.amazon.awssdk.services.s3.model.Grant;
              import software.amazon.awssdk.services.s3.model.Grantee;
              import software.amazon.awssdk.services.s3.model.Type;

              import java.util.List;
              import java.util.stream.Collectors;
              import java.util.stream.Stream;

              class Acls {
                  Grantee grantee(String id) {
                      return Grantee.builder().type(Type.CANONICAL_USER).id(id)
                          .build();
                  }

                  AccessControlPolicy create(List<Grant> grants) {
                      AccessControlPolicy objectAcl = AccessControlPolicy.builder()
                          .build();
                      for (Grant grant : grants) {
                          objectAcl = objectAcl.toBuilder().grants(Stream.concat(objectAcl.grants().stream(), Stream.of(grant)).collect(Collectors.toList())).build();
                      }
                      return objectAcl;
                  }

                  List<Grant> grants(AccessControlPolicy acl) {
                      return acl.grants();
                  }

                  String id(Grantee grantee) {
                      return grantee.id();
                  }
              }
              """
          )
        );
    }

    @Test
    void setObjectTaggingBecomesPutObjectTagging() {
        rewriteRun(
          //language=java
          java(
            """
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;

              class Tagger {
                  void tag(S3Client s3, PutObjectTaggingRequest request) {
                      s3.setObjectTagging(request);
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;

              class Tagger {
                  void tag(S3Client s3, PutObjectTaggingRequest request) {
                      s3.putObjectTagging(request);
                  }
              }
              """
          )
        );
    }
    @Test
    void intContentLengthIsWidened() {
        rewriteRun(
          //language=java
          java(
            """
              import software.amazon.awssdk.core.sync.RequestBody;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.PutObjectRequest;

              import java.io.ByteArrayInputStream;

              class Uploads {
                  void upload(S3Client s3, byte[] bytes) {
                      s3.putObject(PutObjectRequest.builder().bucket("b").key("k").contentLength(bytes.length)
                              .build(), RequestBody.fromInputStream(new ByteArrayInputStream(bytes), bytes.length));
                  }
              }
              """,
            """
              import software.amazon.awssdk.core.sync.RequestBody;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.PutObjectRequest;

              import java.io.ByteArrayInputStream;

              class Uploads {
                  void upload(S3Client s3, byte[] bytes) {
                      s3.putObject(PutObjectRequest.builder().bucket("b").key("k").contentLength((long) bytes.length)
                              .build(), RequestBody.fromInputStream(new ByteArrayInputStream(bytes), bytes.length));
                  }
              }
              """
          )
        );
    }

    @Test
    void intContentLengthFromLocalMetadataIsWidened() {
        rewriteRun(
          spec -> spec.recipe(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.MigrateAwsSdkV1ToV2Steps"))
            .parser(JavaParser.fromJavaVersion()
              .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-s3"))
            // v2-migration's output is attributed against v1 types, without the v2 SDK on the classpath
            .afterTypeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.ObjectMetadata;

              import java.io.ByteArrayInputStream;

              class Uploads {
                  void upload(AmazonS3 s3, byte[] bytes) {
                      ObjectMetadata metadata = new ObjectMetadata();
                      metadata.setContentLength(bytes.length);
                      s3.putObject("b", "k", new ByteArrayInputStream(bytes), metadata);
                  }
              }
              """,
            """
              import software.amazon.awssdk.core.sync.RequestBody;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
              import software.amazon.awssdk.services.s3.model.PutObjectRequest;

              import java.io.ByteArrayInputStream;

              class Uploads {
                  void upload(S3Client s3, byte[] bytes) {
                      HeadObjectResponse metadata = HeadObjectResponse.builder()
                              .build();
                      s3.putObject(PutObjectRequest.builder().bucket("b").key("k").contentLength((long) bytes.length)
                              .build(), RequestBody.fromInputStream(new ByteArrayInputStream(bytes), bytes.length));
                  }
              }
              """
          )
        );
    }
}

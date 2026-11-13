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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.openrewrite.java.Assertions.java;

class MigrateS3ResidualsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateS3Residuals(null))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(),
              "aws-java-sdk-core",
              "aws-java-sdk-s3",
              "mockito-core"));
    }

    @DocumentExample
    @Test
    void doesObjectExist() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;

              class Files {
                  boolean exists(AmazonS3 s3, String key) {
                      return s3.doesObjectExist("bucket", key) && s3.doesBucketExistV2("bucket");
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;

              class Files {
                  boolean exists(AmazonS3 s3, String key) {
                      return S3Compat.doesObjectExist(s3, "bucket", key) && S3Compat.doesBucketExistV2(s3, "bucket");
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            //language=java
            """
              package com.example;

              import software.amazon.awssdk.awscore.exception.AwsServiceException;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.S3Exception;

              /**
               * v1 {@code AmazonS3} behaviour that the AWS SDK for Java v2 has no single method for.
               */
              public final class S3Compat {

                  /**
                   * v1 {@code AmazonS3.doesObjectExist}: a HEAD of the object, where only a 404 means "absent".
                   */
                  public static boolean doesObjectExist(S3Client s3, String bucketName, String objectName) {
                      try {
                          s3.headObject(r -> r.bucket(bucketName).key(objectName));
                          return true;
                      } catch (S3Exception e) {
                          if (e.statusCode() == 404) {
                              return false;
                          }
                          throw e;
                      }
                  }

                  /**
                   * v1 {@code AmazonS3.doesBucketExistV2}: a redirect or AccessDenied means the bucket exists in another region or account.
                   */
                  public static boolean doesBucketExistV2(S3Client s3, String bucketName) {
                      if (bucketName == null || bucketName.isEmpty()) {
                          throw new IllegalArgumentException("bucketName must not be empty");
                      }
                      try {
                          s3.getBucketAcl(r -> r.bucket(bucketName));
                          return true;
                      } catch (AwsServiceException e) {
                          if (e.statusCode() == 301 || (e.awsErrorDetails() != null && "AccessDenied".equals(e.awsErrorDetails().errorCode()))) {
                              return true;
                          }
                          if (e.statusCode() == 404) {
                              return false;
                          }
                          throw e;
                      }
                  }

                  private S3Compat() {
                  }
              }
              """,
            spec -> spec.path("com/example/S3Compat.java")
          )
        );
    }

    @Test
    void presignLocalRequest() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.HttpMethod;
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.Headers;
              import com.amazonaws.services.s3.model.CannedAccessControlList;
              import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;

              import java.net.URL;
              import java.util.Date;

              class Urls {
                  String upload(AmazonS3 s3, String key, Date expiration) {
                      GeneratePresignedUrlRequest request =
                              new GeneratePresignedUrlRequest("bucket", key)
                                      .withMethod(HttpMethod.PUT)
                                      .withExpiration(expiration);
                      request.addRequestParameter(Headers.S3_CANNED_ACL, CannedAccessControlList.PublicRead.toString());
                      URL url = s3.generatePresignedUrl(request);
                      return url.toString();
                  }

                  URL download(AmazonS3 s3, String key) {
                      GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest("bucket", key);
                      request.setMethod(HttpMethod.GET);
                      return s3.generatePresignedUrl(request);
                  }

                  URL inline(AmazonS3 s3, String key, Date expiration) {
                      return s3.generatePresignedUrl(new GeneratePresignedUrlRequest("bucket", key)
                              .withMethod(HttpMethod.PUT)
                              .withExpiration(expiration));
                  }

                  URL variable(AmazonS3 s3, String key, HttpMethod method, Date expiration) {
                      GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest("bucket", key).withMethod(method).withExpiration(expiration);
                      return s3.generatePresignedUrl(request);
                  }

                  URL elsewhere(AmazonS3 s3, GeneratePresignedUrlRequest request) {
                      return s3.generatePresignedUrl(request);
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;
              import software.amazon.awssdk.http.SdkHttpMethod;
              import software.amazon.awssdk.services.s3.model.ObjectCannedACL;

              import java.net.URL;
              import java.time.Duration;
              import java.time.Instant;
              import java.util.Date;

              class Urls {
                  String upload(AmazonS3 s3, String key, Date expiration) {
                      URL url = S3PresignerHolder.presigner(s3)
                              .presignPutObject(presignRequest -> presignRequest
                                      .signatureDuration(Duration.between(Instant.now(), expiration.toInstant()))
                                      .putObjectRequest(objectRequest -> objectRequest.bucket("bucket").key(key).overrideConfiguration(override -> override.putRawQueryParameter("x-amz-acl", ObjectCannedACL.PUBLIC_READ.toString()))))
                              .url();
                      return url.toString();
                  }

                  URL download(AmazonS3 s3, String key) {
                      return S3PresignerHolder.presigner(s3)
                              .presignGetObject(presignRequest -> presignRequest
                                      .signatureDuration(Duration.ofMinutes(15))
                                      .getObjectRequest(objectRequest -> objectRequest.bucket("bucket").key(key)))
                              .url();
                  }

                  URL inline(AmazonS3 s3, String key, Date expiration) {
                      return S3PresignerHolder.presigner(s3)
                              .presignPutObject(presignRequest -> presignRequest
                                      .signatureDuration(Duration.between(Instant.now(), expiration.toInstant()))
                                      .putObjectRequest(objectRequest -> objectRequest.bucket("bucket").key(key)))
                              .url();
                  }

                  URL variable(AmazonS3 s3, String key, SdkHttpMethod method, Date expiration) {
                      return S3PresignerHolder.presign(s3, method, "bucket", key, Duration.between(Instant.now(), expiration.toInstant()));
                  }

                  URL elsewhere(AmazonS3 s3, GeneratePresignedUrlRequest request) {
                      return S3PresignerHolder.presign(s3, request);
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            S3Shims.presignerHolder("com.example", new HashSet<>(List.of(S3Shims.PRESIGNER, S3Shims.PRESIGN, S3Shims.PRESIGN_REQUEST))),
            spec -> spec.path("com/example/S3PresignerHolder.java")
          ),
          java(
            doesNotExist(),
            S3Shims.presignRequest("com.example"),
            spec -> spec.path("com/example/GeneratePresignedUrlRequest.java")
          )
        );
    }

    @Test
    void presignHeadAndPost() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.HttpMethod;
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;

              import java.net.URL;

              class Urls {
                  URL head(AmazonS3 s3, String key) {
                      return s3.generatePresignedUrl(new GeneratePresignedUrlRequest("bucket", key).withMethod(HttpMethod.HEAD));
                  }

                  URL startUpload(AmazonS3 s3, String key) {
                      return s3.generatePresignedUrl(new GeneratePresignedUrlRequest("bucket", key, HttpMethod.POST));
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;
              import software.amazon.awssdk.http.SdkHttpMethod;

              import java.net.URL;
              import java.time.Duration;

              class Urls {
                  URL head(AmazonS3 s3, String key) {
                      return S3PresignerHolder.presigner(s3)
                              .presignHeadObject(presignRequest -> presignRequest
                                      .signatureDuration(Duration.ofMinutes(15))
                                      .headObjectRequest(objectRequest -> objectRequest.bucket("bucket").key(key)))
                              .url();
                  }

                  URL startUpload(AmazonS3 s3, String key) {
                      return S3PresignerHolder.presign(s3, new GeneratePresignedUrlRequest("bucket", key, SdkHttpMethod.POST));
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            S3Shims.presignerHolder("com.example", new HashSet<>(List.of(S3Shims.PRESIGNER, S3Shims.PRESIGN_REQUEST))),
            spec -> spec.path("com/example/S3PresignerHolder.java")
          ),
          java(
            doesNotExist(),
            S3Shims.presignRequest("com.example"),
            spec -> spec.path("com/example/GeneratePresignedUrlRequest.java")
          )
        );
    }

    @Test
    void presignRequestBuiltInHelperMethod() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.HttpMethod;
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.Headers;
              import com.amazonaws.services.s3.model.CannedAccessControlList;
              import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;

              import java.util.Date;

              class PresignedUrlService {
                  AmazonS3 amazonS3;

                  String getPresignedUrl(String bucket, String fileName) {
                      GeneratePresignedUrlRequest generatePresignedUrlRequest = request(bucket, fileName);
                      return amazonS3.generatePresignedUrl(generatePresignedUrlRequest).toString();
                  }

                  private GeneratePresignedUrlRequest request(String bucket, String fileName) {
                      GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, fileName)
                              .withMethod(HttpMethod.PUT)
                              .withExpiration(new Date());
                      request.addRequestParameter(Headers.S3_CANNED_ACL, CannedAccessControlList.PublicRead.toString());
                      return request;
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;
              import software.amazon.awssdk.http.SdkHttpMethod;
              import software.amazon.awssdk.services.s3.model.ObjectCannedACL;

              import java.util.Date;

              class PresignedUrlService {
                  AmazonS3 amazonS3;

                  String getPresignedUrl(String bucket, String fileName) {
                      GeneratePresignedUrlRequest generatePresignedUrlRequest = request(bucket, fileName);
                      return S3PresignerHolder.presign(amazonS3, generatePresignedUrlRequest).toString();
                  }

                  private GeneratePresignedUrlRequest request(String bucket, String fileName) {
                      GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, fileName)
                              .withMethod(SdkHttpMethod.PUT)
                              .withExpiration(new Date());
                      request.addRequestParameter("x-amz-acl", ObjectCannedACL.PUBLIC_READ.toString());
                      return request;
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/PresignedUrlService.java")
          ),
          java(
            doesNotExist(),
            S3Shims.presignerHolder("com.example", new HashSet<>(List.of(S3Shims.PRESIGNER, S3Shims.PRESIGN_REQUEST))),
            spec -> spec.path("src/main/java/com/example/S3PresignerHolder.java")
          ),
          java(
            doesNotExist(),
            S3Shims.presignRequest("com.example"),
            spec -> spec.path("src/main/java/com/example/GeneratePresignedUrlRequest.java")
          )
        );
    }

    @Test
    void cannedAcl() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.CannedAccessControlList;
              import com.amazonaws.services.s3.model.PutObjectRequest;

              import java.io.File;

              class Uploader {
                  void upload(AmazonS3 s3, File file) {
                      s3.putObject(new PutObjectRequest("bucket", "key", file).withCannedAcl(CannedAccessControlList.PublicRead));
                      s3.setBucketAcl("bucket", CannedAccessControlList.Private);
                  }
              }
              """,
            """
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.PutObjectRequest;
              import software.amazon.awssdk.services.s3.model.BucketCannedACL;
              import software.amazon.awssdk.services.s3.model.ObjectCannedACL;

              import java.io.File;

              class Uploader {
                  void upload(AmazonS3 s3, File file) {
                      s3.putObject(new PutObjectRequest("bucket", "key", file).withAcl(ObjectCannedACL.PUBLIC_READ));
                      s3.setBucketAcl("bucket", BucketCannedACL.PRIVATE);
                  }
              }
              """
          )
        );
    }

    @Test
    void cannedAclWithoutV2ConstantPassedAsString() {
        rewriteRun(
          spec -> spec.recipes(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.AsyncClientAndS3ResidualsWithV2Migration"),
              new CompleteS3ObjectMigration())
            .typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.CannedAccessControlList;
              import com.amazonaws.services.s3.model.CreateBucketRequest;
              import com.amazonaws.services.s3.model.PutObjectRequest;

              import java.io.File;

              class Logs {
                  void setUp(AmazonS3 s3, File file) {
                      s3.createBucket(new CreateBucketRequest("logs").withCannedAcl(CannedAccessControlList.LogDeliveryWrite));
                      s3.setBucketAcl("logs", CannedAccessControlList.LogDeliveryWrite);
                      s3.putObject(new PutObjectRequest("logs", "key", file).withCannedAcl(CannedAccessControlList.LogDeliveryWrite));
                  }
              }
              """,
            """
              import software.amazon.awssdk.core.sync.RequestBody;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
              import software.amazon.awssdk.services.s3.model.PutObjectRequest;

              import java.io.File;

              class Logs {
                  void setUp(S3Client s3, File file) {
                      s3.createBucket(CreateBucketRequest.builder().bucket("logs").acl("log-delivery-write")
                              .build());
                      s3.putBucketAcl(r -> r.bucket("logs").acl("log-delivery-write"));
                      s3.putObject(PutObjectRequest.builder().bucket("logs").key("key").acl("log-delivery-write")
                              .build(), RequestBody.fromFile(file));
                  }
              }
              """
          )
        );
    }

    @Test
    void listNextBatchLoop() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.ObjectListing;
              import com.amazonaws.services.s3.model.S3ObjectSummary;

              class Lister {
                  int count(AmazonS3 s3) {
                      ObjectListing listing = null;
                      int count = 0;
                      listing = s3.listObjects("bucket");
                      do {
                          for (S3ObjectSummary summary : listing.getObjectSummaries()) {
                              count += summary.getKey().length();
                          }
                          listing = s3.listNextBatchOfObjects(listing);
                      } while (listing.isTruncated());
                      return count;
                  }
              }
              """,
            """
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.S3ObjectSummary;
              import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

              class Lister {
                  int count(AmazonS3 s3) {
                      int count = 0;
                      for (S3ObjectSummary summary : s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket("bucket").build()).contents()) {
                          count += summary.getKey().length();
                      }
                      return count;
                  }
              }
              """
          )
        );
    }

    @Test
    void transferManager() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.transfer.TransferManager;
              import com.amazonaws.services.s3.transfer.TransferManagerBuilder;

              import java.util.concurrent.ExecutorService;

              class Transfers {
                  TransferManager built(AmazonS3 s3) {
                      return TransferManagerBuilder.standard().withS3Client(s3).build();
                  }

                  TransferManager constructed(AmazonS3 s3, ExecutorService executor) {
                      return new TransferManager(s3, executor);
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.transfer.TransferManager;
              import software.amazon.awssdk.transfer.s3.S3TransferManager;

              import java.util.concurrent.ExecutorService;

              class Transfers {
                  TransferManager built(AmazonS3 s3) {
                      return S3TransferManager.builder().s3Client(S3Compat.asyncClient(s3)).build();
                  }

                  TransferManager constructed(AmazonS3 s3, ExecutorService executor) {
                      return S3TransferManager.builder().s3Client(S3Compat.asyncClient(s3)).executor(executor).build();
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            //language=java
            """
              package com.example;

              import java.util.Map;
              import java.util.WeakHashMap;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.S3AsyncClient;
              import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
              import software.amazon.awssdk.services.s3.S3ServiceClientConfiguration;

              /**
               * v1 {@code AmazonS3} behaviour that the AWS SDK for Java v2 has no single method for.
               */
              public final class S3Compat {

                  private static final Map<S3Client, S3AsyncClient> ASYNC_CLIENTS = new WeakHashMap<>();

                  /**
                   * The v2 transfer manager needs an async client. One is built per sync client, with its region, credentials and
                   * endpoint, and reused so that creating a transfer manager per call does not leak clients.
                   */
                  public static synchronized S3AsyncClient asyncClient(S3Client s3) {
                      return ASYNC_CLIENTS.computeIfAbsent(s3, c -> {
                          S3ServiceClientConfiguration config = c.serviceClientConfiguration();
                          S3AsyncClientBuilder builder = S3AsyncClient.builder()
                                  .multipartEnabled(true)
                                  .region(config.region())
                                  .credentialsProvider(config.credentialsProvider());
                          config.endpointOverride().ifPresent(builder::endpointOverride);
                          return builder.build();
                      });
                  }

                  private S3Compat() {
                  }
              }
              """,
            spec -> spec.path("com/example/S3Compat.java")
          )
        );
    }

    @Test
    void chainedWithV2Migration() {
        rewriteRun(
          // No v2 enum constants such as ObjectCannedACL.PUBLIC_READ: v2-migration's EnumCasingToV2 returns an equal copy of
          // each, which rewriteRun rejects. cannedAcl() covers the ACL mapping on its own.
          spec -> spec.recipeFromResources("org.openrewrite.aws.sdk.AsyncClientAndS3ResidualsWithV2Migration")
            .typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.HttpMethod;
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.Headers;
              import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
              import com.amazonaws.services.s3.model.ObjectListing;
              import com.amazonaws.services.s3.model.S3ObjectSummary;
              import com.amazonaws.services.s3.transfer.TransferManager;
              import com.amazonaws.services.s3.transfer.TransferManagerBuilder;

              import java.net.URL;
              import java.util.Date;

              class S3Service {
                  AmazonS3 s3;

                  boolean exists(String key) {
                      return s3.doesObjectExist("bucket", key);
                  }

                  String presign(String key, Date expiration) {
                      GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest("bucket", key)
                              .withMethod(HttpMethod.PUT)
                              .withExpiration(expiration);
                      request.addRequestParameter(Headers.S3_TAGGING, "team=core");
                      URL url = s3.generatePresignedUrl(request);
                      return url.toString();
                  }

                  URL presign(String key, HttpMethod method, Date expiration) {
                      return s3.generatePresignedUrl(new GeneratePresignedUrlRequest("bucket", key).withMethod(method).withExpiration(expiration));
                  }

                  int count() {
                      int count = 0;
                      ObjectListing listing = s3.listObjects("bucket", "prefix/");
                      do {
                          for (S3ObjectSummary summary : listing.getObjectSummaries()) {
                              count += summary.getKey().length();
                          }
                          listing = s3.listNextBatchOfObjects(listing);
                      } while (listing.isTruncated());
                      return count;
                  }

                  TransferManager transferManager() {
                      return TransferManagerBuilder.standard().withS3Client(s3).build();
                  }
              }
              """,
            """
              package com.example;

              import software.amazon.awssdk.http.SdkHttpMethod;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
              import software.amazon.awssdk.services.s3.model.S3Object;
              import software.amazon.awssdk.transfer.s3.S3TransferManager;

              import java.net.URL;
              import java.time.Duration;
              import java.time.Instant;
              import java.util.Date;

              class S3Service {
                  S3Client s3;

                  boolean exists(String key) {
                      return S3Compat.doesObjectExist(s3, "bucket", key);
                  }

                  String presign(String key, Date expiration) {
                      URL url = S3PresignerHolder.presigner(s3)
                              .presignPutObject(presignRequest -> presignRequest
                                      .signatureDuration(Duration.between(Instant.now(), expiration.toInstant()))
                                      .putObjectRequest(objectRequest -> objectRequest.bucket("bucket").key(key).overrideConfiguration(override -> override.putRawQueryParameter("x-amz-tagging", "team=core"))))
                              .url();
                      return url.toString();
                  }

                  URL presign(String key, SdkHttpMethod method, Date expiration) {
                      return S3PresignerHolder.presign(s3, method, "bucket", key, Duration.between(Instant.now(), expiration.toInstant()));
                  }

                  int count() {
                      int count = 0;
                      for (S3Object summary : s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket("bucket").prefix("prefix/").build()).contents()) {
                          count += summary.key().length();
                      }
                      return count;
                  }

                  S3TransferManager transferManager() {
                      return S3TransferManager.builder().s3Client(S3Compat.asyncClient(s3)).build();
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            //language=java
            """
              package com.example;

              import java.util.Map;
              import java.util.WeakHashMap;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.S3AsyncClient;
              import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
              import software.amazon.awssdk.services.s3.S3ServiceClientConfiguration;
              import software.amazon.awssdk.services.s3.model.S3Exception;

              /**
               * v1 {@code AmazonS3} behaviour that the AWS SDK for Java v2 has no single method for.
               */
              public final class S3Compat {

                  private static final Map<S3Client, S3AsyncClient> ASYNC_CLIENTS = new WeakHashMap<>();

                  /**
                   * v1 {@code AmazonS3.doesObjectExist}: a HEAD of the object, where only a 404 means "absent".
                   */
                  public static boolean doesObjectExist(S3Client s3, String bucketName, String objectName) {
                      try {
                          s3.headObject(r -> r.bucket(bucketName).key(objectName));
                          return true;
                      } catch (S3Exception e) {
                          if (e.statusCode() == 404) {
                              return false;
                          }
                          throw e;
                      }
                  }

                  /**
                   * The v2 transfer manager needs an async client. One is built per sync client, with its region, credentials and
                   * endpoint, and reused so that creating a transfer manager per call does not leak clients.
                   */
                  public static synchronized S3AsyncClient asyncClient(S3Client s3) {
                      return ASYNC_CLIENTS.computeIfAbsent(s3, c -> {
                          S3ServiceClientConfiguration config = c.serviceClientConfiguration();
                          S3AsyncClientBuilder builder = S3AsyncClient.builder()
                                  .multipartEnabled(true)
                                  .region(config.region())
                                  .credentialsProvider(config.credentialsProvider());
                          config.endpointOverride().ifPresent(builder::endpointOverride);
                          return builder.build();
                      });
                  }

                  private S3Compat() {
                  }
              }
              """,
            spec -> spec.path("com/example/S3Compat.java")
          ),
          java(
            doesNotExist(),
            S3Shims.presignerHolder("com.example", Set.of(S3Shims.PRESIGNER, S3Shims.PRESIGN)),
            spec -> spec.path("com/example/S3PresignerHolder.java")
          )
        );
    }

    @Test
    void getObjectToFileCreatesDirectoriesAndReplacesFile() {
        rewriteRun(
          spec -> spec.typeValidationOptions(org.openrewrite.test.TypeValidation.none()),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.GetObjectRequest;

              import java.io.File;

              class Downloads {
                  void download(AmazonS3 s3, String key, File target) {
                      s3.getObject(new GetObjectRequest("bucket", key), target);
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.model.GetObjectRequest;

              import java.io.File;

              class Downloads {
                  void download(AmazonS3 s3, String key, File target) {
                      s3.getObject(new GetObjectRequest("bucket", key), S3Compat.prepareDownload(target));
                  }
              }
              """
          ),
          java(
            doesNotExist(),
            S3Shims.s3Compat("com.example", Set.of(S3Shims.PREPARE_DOWNLOAD))
          )
        );
    }
}

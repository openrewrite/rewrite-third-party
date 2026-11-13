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

class CompleteV2MockitoStubsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new CompleteV2MockitoStubs())
          .parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(),
            "mockito-core",
            "s3-2",
            "s3-transfer-manager",
            "sqs-2",
            "sdk-core",
            "aws-core",
            "regions",
            "auth",
            "utils",
            "http-client-spi",
            "identity-spi"))
          // The input holds the v1 calls v2-migration left in stubs, which do not resolve
          .typeValidationOptions(TypeValidation.none())
          .afterTypeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void s3Stubs() {
        rewriteRun(
          //language=java
          java(
            """
              import org.mockito.Mockito;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
              import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
              import software.amazon.awssdk.transfer.s3.model.Upload;

              import static org.mockito.ArgumentMatchers.anyString;

              class S3Test {
                  void test(S3Client client, Upload upload, CopyObjectResponse result) {
                      Mockito.when(client.copyObject(CopyObjectRequest.builder().sourceBucket(anyString()).sourceKey(anyString())
                              .build()))
                          .thenReturn(result);
                      Mockito.when(client.doesObjectExist(anyString(), anyString())).thenReturn(false).thenReturn(true);
                      Mockito.when(upload.isDone()).thenReturn(false, true);
                      Mockito.doAnswer((i) -> {
                          return null;
                      }).when(upload).waitForCompletion();
                  }
              }
              """,
            """
              import org.mockito.ArgumentMatchers;
              import org.mockito.Mockito;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.*;
              import software.amazon.awssdk.transfer.s3.model.CompletedUpload;
              import software.amazon.awssdk.transfer.s3.model.Upload;

              import java.util.concurrent.CompletableFuture;
              import java.util.function.Consumer;

              import static org.mockito.ArgumentMatchers.anyString;

              class S3Test {
                  void test(S3Client client, Upload upload, CopyObjectResponse result) {
                      Mockito.when(client.copyObject(ArgumentMatchers.any(CopyObjectRequest.class)))
                          .thenReturn(result);
                      Mockito.when(client.headObject(ArgumentMatchers.<Consumer<HeadObjectRequest.Builder>>any())).thenThrow(S3Exception.builder().statusCode(404).build()).thenReturn(HeadObjectResponse.builder().build());
                      Mockito.when(upload.completionFuture()).thenReturn(CompletableFuture.completedFuture(CompletedUpload.builder().response(PutObjectResponse.builder().build()).build()));
                  }
              }
              """
          )
        );
    }

    @Test
    void asyncClientStubReturnsCompletedFuture() {
        rewriteRun(
          //language=java
          java(
            """
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;
              import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
              import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;

              import static org.mockito.Mockito.*;

              class ProducerTest {
                  void test(SqsAsyncClient sqs, GetQueueUrlResponse response) {
                      when(sqs.getQueueUrl((GetQueueUrlRequest) any())).thenReturn(response);
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;
              import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
              import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;

              import java.util.concurrent.CompletableFuture;

              import static org.mockito.Mockito.*;

              class ProducerTest {
                  void test(SqsAsyncClient sqs, GetQueueUrlResponse response) {
                      when(sqs.getQueueUrl((GetQueueUrlRequest) any())).thenReturn(CompletableFuture.completedFuture(response));
                  }
              }
              """
          )
        );
    }

    @Test
    void mockedS3ObjectsAndDownloadMetadata() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import java.util.Map;

              public class ObjectMetadata {
                  public long getContentLength() {
                      return 0;
                  }

                  public Map<String, String> getUserMetadata() {
                      return null;
                  }
              }
              """
          ),
          //language=java
          java(
            """
              package com.example;

              import java.io.ByteArrayInputStream;
              import java.util.HashMap;
              import java.util.Map;
              import org.mockito.Mockito;
              import software.amazon.awssdk.core.ResponseInputStream;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.GetObjectRequest;
              import software.amazon.awssdk.services.s3.model.GetObjectResponse;
              import software.amazon.awssdk.services.s3.model.Owner;
              import software.amazon.awssdk.services.s3.model.S3Object;
              import software.amazon.awssdk.services.s3.model.S3ObjectInputStream;
              import software.amazon.awssdk.transfer.s3.model.FileDownload;

              import static org.mockito.ArgumentMatchers.any;

              class GetTest {
                  void get(S3Client client) {
                      ResponseInputStream<GetObjectResponse> result = Mockito.mock(S3Object.class);
                      ObjectMetadata metadata = Mockito.mock(ObjectMetadata.class);
                      S3ObjectInputStream resultStream = new S3ObjectInputStream(new ByteArrayInputStream("Hello".getBytes()), null);
                      Mockito.when(metadata.getContentLength()).thenReturn(100L);
                      Mockito.when(result.response()).thenReturn(metadata);
                      Mockito.when(result).thenReturn(resultStream);
                      Mockito.when(client.getObject(any(GetObjectRequest.class))).thenReturn(result);
                      Mockito.when(client.listBuckets().owner()).thenReturn(Owner.builder().id("234").build());
                  }

                  void download(FileDownload download) {
                      ObjectMetadata metadata = Mockito.mock(ObjectMetadata.class);
                      Map<String, String> userMetadata = new HashMap<>();
                      Mockito.when(download.isDone()).thenReturn(false, true);
                      Mockito.when(download.getObjectMetadata()).thenReturn(metadata);
                      Mockito.when(metadata.getUserMetadata()).thenReturn(userMetadata);
                  }
              }
              """,
            """
              package com.example;

              import java.io.ByteArrayInputStream;
              import java.io.InputStream;
              import java.util.HashMap;
              import java.util.Map;
              import java.util.concurrent.CompletableFuture;

              import org.mockito.Mockito;
              import software.amazon.awssdk.core.ResponseInputStream;
              import software.amazon.awssdk.http.AbortableInputStream;
              import software.amazon.awssdk.services.s3.S3Client;
              import software.amazon.awssdk.services.s3.model.*;
              import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload;
              import software.amazon.awssdk.transfer.s3.model.FileDownload;

              import static org.mockito.ArgumentMatchers.any;

              class GetTest {
                  void get(S3Client client) {
                      ObjectMetadata metadata = Mockito.mock(ObjectMetadata.class);
                      InputStream resultStream = new ByteArrayInputStream("Hello".getBytes());
                      Mockito.when(metadata.getContentLength()).thenReturn(100L);
                      ResponseInputStream<GetObjectResponse> result = new ResponseInputStream<>(ObjectMetadata.getObjectResponse(metadata), AbortableInputStream.create(resultStream));
                      Mockito.when(client.getObject(any(GetObjectRequest.class))).thenReturn(result);
                      Mockito.when(client.listBuckets()).thenReturn(ListBucketsResponse.builder().owner(Owner.builder().id("234").build()).build());
                  }

                  void download(FileDownload download) {
                      ObjectMetadata metadata = Mockito.mock(ObjectMetadata.class);
                      Map<String, String> userMetadata = new HashMap<>();
                      Mockito.when(download.completionFuture()).thenAnswer(invocation -> CompletableFuture.completedFuture(CompletedFileDownload.builder().response(ObjectMetadata.getObjectResponse(metadata)).build()));
                      Mockito.when(metadata.getUserMetadata()).thenReturn(userMetadata);
                  }
              }
              """
          )
        );
    }
}

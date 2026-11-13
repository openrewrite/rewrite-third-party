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
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class MigrateCloudFrontSignersAndSqsBufferedClientTest implements RewriteTest {

    @DocumentExample
    @Test
    void cloudFrontCookieSigner() {
        rewriteRun(
          spec -> spec.recipe(new MigrateCloudFrontSigners(null))
            .parser(JavaParser.fromJavaVersion()
              .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-cloudfront"))
            .typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              package com.example;

              import java.io.File;
              import java.io.IOException;
              import java.security.spec.InvalidKeySpecException;
              import java.util.Date;

              import com.amazonaws.services.cloudfront.CloudFrontCookieSigner;
              import com.amazonaws.services.cloudfront.CloudFrontCookieSigner.CookiesForCustomPolicy;
              import com.amazonaws.services.cloudfront.util.SignerUtils.Protocol;

              public class CloudFrontSignatureHelper {
                  private com.amazonaws.Protocol protocol = com.amazonaws.Protocol.HTTPS;

                  public CookiesForCustomPolicy getSignedCookies(String domain, String key, String path, String keyPairId,
                                                                 Date expiresOn, Date activeFrom, String ip) {
                      Protocol signerUtilsProtocol = Protocol.valueOf(protocol.toString());
                      CookiesForCustomPolicy cookies = null;
                      try {
                          cookies = CloudFrontCookieSigner.getCookiesForCustomPolicy(
                                  signerUtilsProtocol, domain, new File(key), path, keyPairId, expiresOn, activeFrom, ip);
                      } catch (IOException | InvalidKeySpecException e) {
                          throw new IllegalStateException(e);
                      }
                      return cookies;
                  }
              }
              """,
            """
              package com.example;

              import java.io.File;
              import java.io.IOException;
              import java.security.spec.InvalidKeySpecException;
              import java.util.Date;

              import com.example.CloudFrontCookieSigner.CookiesForCustomPolicy;

              public class CloudFrontSignatureHelper {
                  private com.amazonaws.Protocol protocol = com.amazonaws.Protocol.HTTPS;

                  public CookiesForCustomPolicy getSignedCookies(String domain, String key, String path, String keyPairId,
                                                                 Date expiresOn, Date activeFrom, String ip) {
                      CloudFrontSignerUtils.Protocol signerUtilsProtocol = CloudFrontSignerUtils.Protocol.valueOf(protocol.toString());
                      CookiesForCustomPolicy cookies = null;
                      try {
                          cookies = CloudFrontCookieSigner.getCookiesForCustomPolicy(
                                  signerUtilsProtocol, domain, new File(key), path, keyPairId, expiresOn, activeFrom, ip);
                      } catch (IOException | InvalidKeySpecException e) {
                          throw new IllegalStateException(e);
                      }
                      return cookies;
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/CloudFrontSignatureHelper.java")
          ),
          java(
            doesNotExist(),
            MigrateCloudFrontSigners.source(MigrateCloudFrontSigners.SIGNER_UTILS, "com.example"),
            spec -> spec.path("src/main/java/com/example/CloudFrontSignerUtils.java")
          ),
          java(
            doesNotExist(),
            MigrateCloudFrontSigners.source(MigrateCloudFrontSigners.COOKIE_SIGNER, "com.example"),
            spec -> spec.path("src/main/java/com/example/CloudFrontCookieSigner.java")
          )
        );
    }

    @Test
    void cloudFrontUrlSignerAndCannedCookies() {
        rewriteRun(
          spec -> spec.recipe(new MigrateCloudFrontSigners(null))
            .parser(JavaParser.fromJavaVersion()
              .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-cloudfront")),
          //language=java
          java(
            """
              package com.example;

              import java.io.File;
              import java.security.PrivateKey;
              import java.util.Date;
              import java.util.Map;

              import com.amazonaws.services.cloudfront.CloudFrontCookieSigner;
              import com.amazonaws.services.cloudfront.CloudFrontUrlSigner;
              import com.amazonaws.services.cloudfront.util.SignerUtils;

              class Signing {
                  String url(File keyFile, String keyPairId, Date expires) throws Exception {
                      PrivateKey key = SignerUtils.loadPrivateKey(keyFile);
                      String policy = CloudFrontUrlSigner.buildCustomPolicyForSignedUrl("https://d.example.com/*", expires, null, null);
                      String custom = CloudFrontUrlSigner.getSignedURLWithCustomPolicy("https://d.example.com/a", keyPairId, key, policy);
                      return CloudFrontUrlSigner.getSignedURLWithCannedPolicy(
                              SignerUtils.Protocol.https, "d.example.com", keyFile, "a", keyPairId, expires) + custom;
                  }

                  Map.Entry<String, String> cookie(PrivateKey key, String keyPairId, Date expires) {
                      CloudFrontCookieSigner.CookiesForCannedPolicy cookies =
                              CloudFrontCookieSigner.getCookiesForCannedPolicy("https://d.example.com/*", keyPairId, key, expires);
                      return cookies.getExpires();
                  }
              }
              """,
            """
              package com.example;

              import java.io.File;
              import java.security.PrivateKey;
              import java.util.Date;
              import java.util.Map;

              class Signing {
                  String url(File keyFile, String keyPairId, Date expires) throws Exception {
                      PrivateKey key = CloudFrontSignerUtils.loadPrivateKey(keyFile);
                      String policy = CloudFrontUrlSigner.buildCustomPolicyForSignedUrl("https://d.example.com/*", expires, null, null);
                      String custom = CloudFrontUrlSigner.getSignedURLWithCustomPolicy("https://d.example.com/a", keyPairId, key, policy);
                      return CloudFrontUrlSigner.getSignedURLWithCannedPolicy(
                              CloudFrontSignerUtils.Protocol.https, "d.example.com", keyFile, "a", keyPairId, expires) + custom;
                  }

                  Map.Entry<String, String> cookie(PrivateKey key, String keyPairId, Date expires) {
                      CloudFrontCookieSigner.CookiesForCannedPolicy cookies =
                              CloudFrontCookieSigner.getCookiesForCannedPolicy("https://d.example.com/*", keyPairId, key, expires);
                      return cookies.getExpires();
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/Signing.java")
          ),
          java(
            doesNotExist(),
            MigrateCloudFrontSigners.source(MigrateCloudFrontSigners.SIGNER_UTILS, "com.example"),
            spec -> spec.path("src/main/java/com/example/CloudFrontSignerUtils.java")
          ),
          java(
            doesNotExist(),
            MigrateCloudFrontSigners.source(MigrateCloudFrontSigners.COOKIE_SIGNER, "com.example"),
            spec -> spec.path("src/main/java/com/example/CloudFrontCookieSigner.java")
          ),
          java(
            doesNotExist(),
            MigrateCloudFrontSigners.source(MigrateCloudFrontSigners.URL_SIGNER, "com.example"),
            spec -> spec.path("src/main/java/com/example/CloudFrontUrlSigner.java")
          )
        );
    }

    @Test
    void bufferedClient() {
        rewriteRun(
          spec -> spec.recipe(new MigrateSqsBufferedClient(null))
            .parser(JavaParser.fromJavaVersion()
              .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sqs"))
            .typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.buffered.AmazonSQSBufferedAsyncClient;

              class BufferedSQSClientFactory {
                  AmazonSQSAsync createClient(AmazonSQSAsync realClient) {
                      return new AmazonSQSBufferedAsyncClient(realClient);
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.sqs.AmazonSQSAsync;

              class BufferedSQSClientFactory {
                  AmazonSQSAsync createClient(AmazonSQSAsync realClient) {
                      return BufferedSqsAsyncClient.wrap(realClient);
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/BufferedSQSClientFactory.java")
          ),
          java(
            """
              package com.example.mail;

              import com.amazonaws.services.sqs.buffered.AmazonSQSBufferedAsyncClient;
              import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
              import com.amazonaws.services.sqs.model.ReceiveMessageResult;

              class MailMessageBounceAndComplaintListener {
                  private AmazonSQSBufferedAsyncClient sqsClient;

                  ReceiveMessageResult receive(ReceiveMessageRequest request) {
                      return sqsClient.receiveMessage(request);
                  }
              }
              """,
            """
              package com.example.mail;

              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
              import com.amazonaws.services.sqs.model.ReceiveMessageResult;

              class MailMessageBounceAndComplaintListener {
                  private AmazonSQSAsync sqsClient;

                  ReceiveMessageResult receive(ReceiveMessageRequest request) {
                      return sqsClient.receiveMessage(request);
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/mail/MailMessageBounceAndComplaintListener.java")
          ),
          java(
            doesNotExist(),
            MigrateSqsBufferedClient.source("com.example"),
            spec -> spec.path("src/main/java/com/example/BufferedSqsAsyncClient.java")
          )
        );
    }

    @Test
    void bufferedClientWithQueueBufferConfig() {
        rewriteRun(
          spec -> spec.recipe(new MigrateSqsBufferedClient(null))
            .parser(JavaParser.fromJavaVersion()
              .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sqs"))
            .typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.buffered.AmazonSQSBufferedAsyncClient;
              import com.amazonaws.services.sqs.buffered.QueueBufferConfig;

              class BufferedSQSClientFactory {
                  int maxBatchSize = new QueueBufferConfig().getMaxBatchSize();

                  AmazonSQSAsync createClient(AmazonSQSAsync realClient) {
                      QueueBufferConfig config = new QueueBufferConfig()
                        .withMaxBatchSize(maxBatchSize)
                        .withLongPoll(true);
                      return new AmazonSQSBufferedAsyncClient(realClient, config);
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.services.sqs.AmazonSQSAsync;

              class BufferedSQSClientFactory {
                  int maxBatchSize = new BufferedSqsAsyncClient.QueueBufferConfig().getMaxBatchSize();

                  AmazonSQSAsync createClient(AmazonSQSAsync realClient) {
                      BufferedSqsAsyncClient.QueueBufferConfig config = new BufferedSqsAsyncClient.QueueBufferConfig()
                        .withMaxBatchSize(maxBatchSize)
                        .withLongPoll(true);
                      return BufferedSqsAsyncClient.wrap(realClient, config);
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/BufferedSQSClientFactory.java")
          ),
          java(
            doesNotExist(),
            MigrateSqsBufferedClient.source("com.example"),
            spec -> spec.path("src/main/java/com/example/BufferedSqsAsyncClient.java")
          )
        );
    }
}

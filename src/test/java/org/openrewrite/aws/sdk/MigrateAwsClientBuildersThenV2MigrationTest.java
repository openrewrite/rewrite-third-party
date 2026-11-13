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
import org.openrewrite.config.Environment;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class MigrateAwsClientBuildersThenV2MigrationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipes(new MigrateAwsClientBuildersToV2(),
            Environment.builder().scanRuntimeClasspath("software.amazon.awssdk.v2migration").build()
              .activateRecipes("software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2"))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(),
              "aws-java-sdk-core",
              "aws-java-sdk-sqs",
              "aws-java-sdk-sns",
              "aws-java-sdk-s3",
              "aws-java-sdk-dynamodb"))
          .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void endpointConfiguration() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.auth.AWSStaticCredentialsProvider;
              import com.amazonaws.auth.BasicAWSCredentials;
              import com.amazonaws.client.builder.AwsClientBuilder;
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder;

              class SQSConfig {
                  String sqsUrl;
                  String region;

                  AmazonSQSAsync amazonSQSAsync(String accessKeyId, String secretAccessKey) {
                      return AmazonSQSAsyncClientBuilder
                              .standard()
                              .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(sqsUrl, region))
                              .withCredentials(
                                      new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKeyId, secretAccessKey)))
                              .build();
                  }
              }
              """,
            """
              import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
              import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;

              import java.net.URI;

              class SQSConfig {
                  String sqsUrl;
                  String region;

                  SqsAsyncClient amazonSQSAsync(String accessKeyId, String secretAccessKey) {
                      return SqsAsyncClient
                              .builder()
                              .endpointOverride(URI.create(sqsUrl))
                              .region(Region.of(region))
                              .credentialsProvider(
                                      StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                              .build();
                  }
              }
              """
          )
        );
    }

    @Test
    void requestHandler() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.AmazonWebServiceRequest;
              import com.amazonaws.handlers.RequestHandler2;
              import com.amazonaws.services.sns.model.PublishRequest;

              import java.util.UUID;

              public class SnsFifoMessageGroupIdHandler extends RequestHandler2 {

                  @Override
                  public AmazonWebServiceRequest beforeExecution(AmazonWebServiceRequest request) {
                      AmazonWebServiceRequest amazonWebServiceRequest = super.beforeExecution(request);
                      if (amazonWebServiceRequest instanceof PublishRequest) {
                          PublishRequest publishRequest = (PublishRequest) amazonWebServiceRequest;
                          if (publishRequest.getTopicArn().contains(".fifo")) {
                              publishRequest.setMessageGroupId(publishRequest.getTopicArn());
                              publishRequest.setMessageDeduplicationId(UUID.randomUUID().toString());
                          }
                      }
                      return amazonWebServiceRequest;
                  }
              }
              """,
            """
              import software.amazon.awssdk.core.SdkRequest;
              import software.amazon.awssdk.core.interceptor.Context;
              import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
              import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
              import software.amazon.awssdk.services.sns.model.PublishRequest;

              import java.util.UUID;

              public class SnsFifoMessageGroupIdHandler implements ExecutionInterceptor {

                  @Override
                  public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
                      SdkRequest request = context.request();
                      SdkRequest amazonWebServiceRequest = request;
                      if (amazonWebServiceRequest instanceof PublishRequest) {
                          PublishRequest publishRequest = (PublishRequest) amazonWebServiceRequest;
                          if (publishRequest.topicArn().contains(".fifo")) {
                              publishRequest = publishRequest.toBuilder().messageGroupId(publishRequest.topicArn()).build();
                              amazonWebServiceRequest = publishRequest;
                              publishRequest = publishRequest.toBuilder().messageDeduplicationId(UUID.randomUUID().toString()).build();
                              amazonWebServiceRequest = publishRequest;
                          }
                      }
                      return amazonWebServiceRequest;
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import com.amazonaws.ClientConfiguration;
              import com.amazonaws.auth.AWSStaticCredentialsProvider;
              import com.amazonaws.auth.BasicAWSCredentials;
              import com.amazonaws.regions.Regions;
              import com.amazonaws.services.sns.AmazonSNSAsync;
              import com.amazonaws.services.sns.AmazonSNSAsyncClientBuilder;

              public class SnsFifoConfig {
                  public AmazonSNSAsync amazonSNS(SnsFifoMessageGroupIdHandler snsFifoMessageGroupIdHandler) {
                      ClientConfiguration configuration = new ClientConfiguration();
                      configuration.setMaxConnections(10);
                      configuration.setMaxErrorRetry(2);
                      AmazonSNSAsyncClientBuilder amazonSNSAsyncClientBuilder = AmazonSNSAsyncClientBuilder
                              .standard()
                              .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("a", "b")))
                              .withRegion(Regions.US_EAST_1)
                              .withClientConfiguration(configuration)
                              .withRequestHandlers(snsFifoMessageGroupIdHandler);
                      return amazonSNSAsyncClientBuilder.build();
                  }
              }
              """,
            """
              import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
              import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
              import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sns.SnsAsyncClient;
              import software.amazon.awssdk.services.sns.SnsAsyncClientBuilder;

              public class SnsFifoConfig {
                  public SnsAsyncClient amazonSNS(SnsFifoMessageGroupIdHandler snsFifoMessageGroupIdHandler) {
                      SnsAsyncClientBuilder amazonSNSAsyncClientBuilder = SnsAsyncClient
                              .builder()
                              .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("a", "b")))
                              .region(Region.US_EAST_1)
                              .httpClientBuilder(NettyNioAsyncHttpClient.builder().maxConcurrency(10))
                              .overrideConfiguration(o -> o.retryStrategy(b -> b.maxAttempts(3)).addExecutionInterceptor(snsFifoMessageGroupIdHandler));
                      return amazonSNSAsyncClientBuilder.build();
                  }
              }
              """
          )
        );
    }
}

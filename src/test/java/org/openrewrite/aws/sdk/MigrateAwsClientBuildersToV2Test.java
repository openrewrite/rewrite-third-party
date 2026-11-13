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

class MigrateAwsClientBuildersToV2Test implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAwsClientBuildersToV2())
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(),
              "aws-java-sdk-core",
              "aws-java-sdk-sqs",
              "aws-java-sdk-sns",
              "aws-java-sdk-s3",
              "aws-java-sdk-dynamodb",
              "aws-xray-recorder-sdk-core",
              "aws-xray-recorder-sdk-aws-sdk"))
          // v1 types stay referenced until v2-migration retypes them, so the output is intentionally mixed
          .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void endpointConfigurationWithCredentials() {
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
              import com.amazonaws.auth.AWSStaticCredentialsProvider;
              import com.amazonaws.auth.BasicAWSCredentials;
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sqs.SqsAsyncClient;

              import java.net.URI;

              class SQSConfig {
                  String sqsUrl;
                  String region;

                  AmazonSQSAsync amazonSQSAsync(String accessKeyId, String secretAccessKey) {
                      return SqsAsyncClient
                              .builder()
                              .endpointOverride(URI.create(sqsUrl))
                              .region(Region.of(region))
                              .credentialsProvider(
                                      new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKeyId, secretAccessKey)))
                              .build();
                  }
              }
              """
          )
        );
    }

    @Test
    void endpointConfigurationInLocalVariable() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.client.builder.AwsClientBuilder;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;

              class DynamoDBConfig {
                  String amazonDynamoDBEndpoint;
                  String region;

                  AmazonDynamoDB amazonDynamoDB() {
                      AwsClientBuilder.EndpointConfiguration endpointConfiguration = new AwsClientBuilder.EndpointConfiguration(amazonDynamoDBEndpoint, region);

                      return AmazonDynamoDBClientBuilder.standard()
                              .withEndpointConfiguration(endpointConfiguration)
                              .build();
                  }
              }
              """,
            """
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

              import java.net.URI;

              class DynamoDBConfig {
                  String amazonDynamoDBEndpoint;
                  String region;

                  AmazonDynamoDB amazonDynamoDB() {

                      return DynamoDbClient.builder()
                              .endpointOverride(URI.create(amazonDynamoDBEndpoint))
                              .region(Region.of(region))
                              .build();
                  }
              }
              """
          )
        );
    }

    @Test
    void literalEndpointWithoutSchemeAndNullRegion() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;

              class DynamoDBClientFactory {
                  static AmazonDynamoDB create() {
                      return AmazonDynamoDBClientBuilder.standard().withEndpointConfiguration(
                              new EndpointConfiguration("localhost:8000", null)).build();
                  }
              }
              """,
            """
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

              import java.net.URI;

              class DynamoDBClientFactory {
                  static AmazonDynamoDB create() {
                      return /*~~(v1 derived the signing region from the endpoint; v2 reads it from the environment. Set `.region(..)` if the environment has none)~~>*/DynamoDbClient.builder().endpointOverride(URI.create("https://localhost:8000")).build();
                  }
              }
              """
          )
        );
    }

    @Test
    void pathStyleAccessAndRegionsConstant() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.auth.AWSStaticCredentialsProvider;
              import com.amazonaws.auth.AnonymousAWSCredentials;
              import com.amazonaws.client.builder.AwsClientBuilder;
              import com.amazonaws.regions.Regions;
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.AmazonS3ClientBuilder;

              class TestAWSConfiguration {
                  AmazonS3 amazonS3() {
                      return AmazonS3ClientBuilder
                              .standard()
                              .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration("http://localhost:8001", "ap-northeast-1"))
                              .withCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials()))
                              .withPathStyleAccessEnabled(true)
                              .build();
                  }

                  AmazonS3 plain() {
                      return AmazonS3ClientBuilder.standard().withRegion(Regions.GovCloud).withPathStyleAccessEnabled(true).build();
                  }
              }
              """,
            """
              import com.amazonaws.auth.AWSStaticCredentialsProvider;
              import com.amazonaws.auth.AnonymousAWSCredentials;
              import com.amazonaws.services.s3.AmazonS3;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.s3.S3Client;

              import java.net.URI;

              class TestAWSConfiguration {
                  AmazonS3 amazonS3() {
                      return S3Client
                              .builder()
                              .endpointOverride(URI.create("http://localhost:8001"))
                              .region(Region.of("ap-northeast-1"))
                              .credentialsProvider(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials()))
                              .forcePathStyle(true)
                              .build();
                  }

                  AmazonS3 plain() {
                      return S3Client.builder().region(Region.US_GOV_WEST_1).forcePathStyle(true).build();
                  }
              }
              """
          )
        );
    }

    @Test
    void proxyClientConfigurationIsFolded() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.ClientConfiguration;
              import com.amazonaws.Protocol;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;

              class DynamoDBTask {
                  String region, proxyHost, proxyPort, proxyUsername, proxyPassword;
                  AmazonDynamoDB dynamoDB;

                  void execute() {
                      if (proxyHost != null) {
                          ClientConfiguration clientConfiguration = new ClientConfiguration();
                          clientConfiguration.setProtocol(Protocol.HTTPS);
                          clientConfiguration.setProxyHost(proxyHost);
                          clientConfiguration.setProxyPort(Integer.parseInt(proxyPort));
                          clientConfiguration.setProxyUsername(proxyUsername);
                          clientConfiguration.setProxyPassword(proxyPassword);
                          clientConfiguration.setMaxErrorRetry(5);
                          dynamoDB = AmazonDynamoDBClientBuilder.standard()
                                  .withClientConfiguration(clientConfiguration)
                                  .withRegion(region).build();
                      }
                  }
              }
              """,
            """
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import software.amazon.awssdk.http.apache.ApacheHttpClient;
              import software.amazon.awssdk.http.apache.ProxyConfiguration;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

              import java.net.URI;

              class DynamoDBTask {
                  String region, proxyHost, proxyPort, proxyUsername, proxyPassword;
                  AmazonDynamoDB dynamoDB;

                  void execute() {
                      if (proxyHost != null) {
                          dynamoDB = DynamoDbClient.builder()
                                  .httpClientBuilder(ApacheHttpClient.builder().proxyConfiguration(ProxyConfiguration.builder().endpoint(URI.create("http://" + proxyHost + ":" + Integer.parseInt(proxyPort))).username(proxyUsername).password(proxyPassword).build()))
                                  .overrideConfiguration(o -> o.retryStrategy(b -> b.maxAttempts(6)))
                                  .region(Region.of(region)).build();
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void unfoldableClientConfigurationIsMarked() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.ClientConfiguration;

              class ClientConfigurationBuilder {
                  ClientConfiguration configure(ClientConfiguration cc, String str) {
                      return cc.withMaxErrorRetry(Integer.parseInt(str));
                  }
              }
              """,
            """
              import com.amazonaws.ClientConfiguration;

              class ClientConfigurationBuilder {
                  ClientConfiguration configure(ClientConfiguration cc, String str) {
                      return /*~~(AWS SDK v1 ClientConfiguration setting not migrated: build the client from a local `new ClientConfiguration()` so it can be folded into the v2 builder)~~>*/cc.withMaxErrorRetry(Integer.parseInt(str));
                  }
              }
              """
          )
        );
    }

    @Test
    void legacyConstructorWithCredentialsProvider() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.auth.AWSCredentialsProvider;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;

              class DynamoDbConfig {
                  AWSCredentialsProvider credentialsProvider;
                  String amazonDynamoDBEndpoint;

                  AmazonDynamoDB amazonDynamoDB() {
                      AmazonDynamoDB amazonDynamoDB = new AmazonDynamoDBClient(credentialsProvider);
                      if (!"N/A".equals(amazonDynamoDBEndpoint)) {
                          amazonDynamoDB.setEndpoint(amazonDynamoDBEndpoint);
                      }
                      return amazonDynamoDB;
                  }
              }
              """,
            """
              import com.amazonaws.auth.AWSCredentialsProvider;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

              class DynamoDbConfig {
                  AWSCredentialsProvider credentialsProvider;
                  String amazonDynamoDBEndpoint;

                  AmazonDynamoDB amazonDynamoDB() {
                      AmazonDynamoDB amazonDynamoDB = DynamoDbClient.builder().region(Region.US_EAST_1).credentialsProvider(credentialsProvider).build();
                      if (!"N/A".equals(amazonDynamoDBEndpoint)) {
                          /*~~(v2 clients are immutable: move this endpoint or region into the client builder)~~>*/amazonDynamoDB.setEndpoint(amazonDynamoDBEndpoint);
                      }
                      return amazonDynamoDB;
                  }
              }
              """
          )
        );
    }

    @Test
    void s3ClientWithClientConfiguration() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.ClientConfiguration;
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.services.s3.AmazonS3Client;

              class WallRideAutoConfiguration {
                  public AmazonS3 amazonS3() {
                      ClientConfiguration configuration = new ClientConfiguration();
                      configuration.setMaxConnections(1000);
                      return new AmazonS3Client(configuration);
                  }
              }
              """,
            """
              import com.amazonaws.services.s3.AmazonS3;
              import software.amazon.awssdk.http.apache.ApacheHttpClient;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.s3.S3Client;

              class WallRideAutoConfiguration {
                  public AmazonS3 amazonS3() {
                      return S3Client.builder().region(Region.US_EAST_1).crossRegionAccessEnabled(true).httpClientBuilder(ApacheHttpClient.builder().maxConnections(1000)).build();
                  }
              }
              """
          )
        );
    }

    @Test
    void requestHandlerBecomesExecutionInterceptor() {
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
              import com.amazonaws.services.sns.model.PublishRequest;
              import software.amazon.awssdk.core.SdkRequest;
              import software.amazon.awssdk.core.interceptor.Context;
              import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
              import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

              import java.util.UUID;

              public class SnsFifoMessageGroupIdHandler implements ExecutionInterceptor {

                  @Override
                  public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
                      SdkRequest request = context.request();
                      SdkRequest amazonWebServiceRequest = request;
                      if (amazonWebServiceRequest instanceof PublishRequest) {
                          PublishRequest publishRequest = (PublishRequest) amazonWebServiceRequest;
                          if (publishRequest.getTopicArn().contains(".fifo")) {
                              publishRequest.setMessageGroupId(publishRequest.getTopicArn());
                              amazonWebServiceRequest = publishRequest;
                              publishRequest.setMessageDeduplicationId(UUID.randomUUID().toString());
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
              import com.amazonaws.auth.AWSStaticCredentialsProvider;
              import com.amazonaws.auth.BasicAWSCredentials;
              import com.amazonaws.regions.Regions;
              import com.amazonaws.services.sns.AmazonSNSAsync;
              import com.amazonaws.services.sns.AmazonSNSAsyncClientBuilder;

              public class SnsFifoConfig {
                  public AmazonSNSAsync amazonSNS(SnsFifoMessageGroupIdHandler snsFifoMessageGroupIdHandler) {
                      AmazonSNSAsyncClientBuilder amazonSNSAsyncClientBuilder = AmazonSNSAsyncClientBuilder
                              .standard()
                              .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("a", "b")))
                              .withRegion(Regions.US_EAST_1)
                              .withRequestHandlers(snsFifoMessageGroupIdHandler);
                      return amazonSNSAsyncClientBuilder.build();
                  }
              }
              """,
            """
              import com.amazonaws.auth.AWSStaticCredentialsProvider;
              import com.amazonaws.auth.BasicAWSCredentials;
              import com.amazonaws.services.sns.AmazonSNSAsync;
              import com.amazonaws.services.sns.AmazonSNSAsyncClientBuilder;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sns.SnsAsyncClient;

              public class SnsFifoConfig {
                  public AmazonSNSAsync amazonSNS(SnsFifoMessageGroupIdHandler snsFifoMessageGroupIdHandler) {
                      AmazonSNSAsyncClientBuilder amazonSNSAsyncClientBuilder = SnsAsyncClient
                              .builder()
                              .credentialsProvider(new AWSStaticCredentialsProvider(new BasicAWSCredentials("a", "b")))
                              .region(Region.US_EAST_1)
                              .overrideConfiguration(o -> o.addExecutionInterceptor(snsFifoMessageGroupIdHandler));
                      return amazonSNSAsyncClientBuilder.build();
                  }
              }
              """
          )
        );
    }

    @Test
    void unknownRequestHandlerIsMarked() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.handlers.RequestHandler2;
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder;

              class QueueConfig {
                  AmazonSQSAsync client(RequestHandler2 tracing) {
                      return AmazonSQSAsyncClientBuilder.standard()
                              .withRequestHandlers(tracing)
                              .build();
                  }
              }
              """,
            """
              import com.amazonaws.handlers.RequestHandler2;
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder;

              class QueueConfig {
                  AmazonSQSAsync client(RequestHandler2 tracing) {
                      return /*~~(AWS SDK v1 client not migrated: request handler `RequestHandler2` has no ExecutionInterceptor equivalent)~~>*/AmazonSQSAsyncClientBuilder.standard()
                              .withRequestHandlers(tracing)
                              .build();
                  }
              }
              """
          )
        );
    }

    @Test
    void xRayHandlerIsCarriedThroughConvertedChain() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.client.builder.AwsClientBuilder;
              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
              import com.amazonaws.xray.AWSXRay;
              import com.amazonaws.xray.handlers.TracingHandler;

              class SQSConfig {
                  AmazonSQS sqs() {
                      return AmazonSQSClientBuilder.standard()
                              .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration("http://localhost:4566", "us-east-1"))
                              .withRequestHandlers(new TracingHandler(AWSXRay.getGlobalRecorder()))
                              .build();
                  }
              }
              """,
            """
              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.xray.AWSXRay;
              import com.amazonaws.xray.handlers.TracingHandler;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sqs.SqsClient;

              import java.net.URI;

              class SQSConfig {
                  AmazonSQS sqs() {
                      return SqsClient.builder()
                              .endpointOverride(URI.create("http://localhost:4566"))
                              .region(Region.of("us-east-1"))
                              .withRequestHandlers(new TracingHandler(AWSXRay.getGlobalRecorder()))
                              .build();
                  }
              }
              """
          )
        );
    }

    @Test
    void commentsInChainAreKeptOnce() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.client.builder.AwsClientBuilder;
              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

              class SQSConfig {
                  AmazonSQS sqs(String endpoint, String region) {
                      return AmazonSQSClientBuilder.standard()
                              // default credentials chain, so only the endpoint is set
                              // for LocalStack
                              .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, region))
                              /* built once per context */
                              .build();
                  }
              }
              """,
            """
              import com.amazonaws.services.sqs.AmazonSQS;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sqs.SqsClient;

              import java.net.URI;

              class SQSConfig {
                  AmazonSQS sqs(String endpoint, String region) {
                      return SqsClient.builder()
                              // default credentials chain, so only the endpoint is set
                              // for LocalStack
                              .endpointOverride(URI.create(endpoint))
                              .region(Region.of(region))
                              /* built once per context */
                              .build();
                  }
              }
              """
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void chainsWithoutGapsAreLeftForV2Migration() {
            rewriteRun(
              //language=java
              java(
                """
                  import com.amazonaws.ClientConfiguration;
                  import com.amazonaws.services.sqs.AmazonSQS;
                  import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

                  class Clients {
                      AmazonSQS sqs() {
                          return AmazonSQSClientBuilder.standard()
                                  .withClientConfiguration(new ClientConfiguration().withConnectionTimeout(1000))
                                  .withRegion("eu-west-1")
                                  .build();
                      }
                  }
                  """
              )
            );
        }

        @Test
        void xRayHandlerAloneIsLeftForV2MigrationAndXRayStep() {
            rewriteRun(
              //language=java
              java(
                """
                  import com.amazonaws.services.sqs.AmazonSQS;
                  import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
                  import com.amazonaws.xray.AWSXRay;
                  import com.amazonaws.xray.handlers.TracingHandler;

                  class SQSConfig {
                      AmazonSQS sqs() {
                          return AmazonSQSClientBuilder.standard()
                                  .withRequestHandlers(new TracingHandler(AWSXRay.getGlobalRecorder()))
                                  .build();
                      }
                  }
                  """
              )
            );
        }
    }
}

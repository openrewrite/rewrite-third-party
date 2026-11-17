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

class ExpandAwsRequestConstructorsAndOverloadsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ExpandAwsRequestConstructorsAndOverloads())
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(),
              "aws-java-sdk-core",
              "aws-java-sdk-sqs",
              "aws-java-sdk-sns",
              "aws-java-sdk-dynamodb"));
    }

    @DocumentExample
    @Test
    void sqsConstructorsAndOverloads() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.DeleteMessageBatchRequestEntry;
              import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
              import com.amazonaws.services.sqs.model.ReceiveMessageResult;

              import java.util.List;

              class Listener {
                  private AmazonSQSAsync sqsClient;

                  ReceiveMessageResult receive(String queueUrl) {
                      final ReceiveMessageRequest request = new ReceiveMessageRequest(queueUrl);
                      request.setMaxNumberOfMessages(10);
                      return sqsClient.receiveMessage(request);
                  }

                  void acknowledge(String queueUrl, List<DeleteMessageBatchRequestEntry> entries) {
                      sqsClient.deleteMessageBatch(queueUrl, entries);
                  }

                  String url(String queueName) {
                      return sqsClient.getQueueUrl(queueName).getQueueUrl();
                  }
              }
              """,
            """
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.*;

              import java.util.List;

              class Listener {
                  private AmazonSQSAsync sqsClient;

                  ReceiveMessageResult receive(String queueUrl) {
                      final ReceiveMessageRequest request = new ReceiveMessageRequest().withQueueUrl(queueUrl);
                      request.setMaxNumberOfMessages(10);
                      return sqsClient.receiveMessage(request);
                  }

                  void acknowledge(String queueUrl, List<DeleteMessageBatchRequestEntry> entries) {
                      sqsClient.deleteMessageBatch(new DeleteMessageBatchRequest().withQueueUrl(queueUrl).withEntries(entries));
                  }

                  String url(String queueName) {
                      return sqsClient.getQueueUrl(new GetQueueUrlRequest().withQueueName(queueName)).getQueueUrl();
                  }
              }
              """
          )
        );
    }

    @Test
    void sendMessageConstructorKeepsWithers() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.model.SendMessageRequest;

              class SqsProducer {
                  AmazonSQS amazonSqs;

                  void produce(String url, String body, String groupId) {
                      SendMessageRequest request = new SendMessageRequest(url,
                              body)
                              .withMessageGroupId(groupId);
                      amazonSqs.sendMessage(request);
                  }
              }
              """,
            """
              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.model.SendMessageRequest;

              class SqsProducer {
                  AmazonSQS amazonSqs;

                  void produce(String url, String body, String groupId) {
                      SendMessageRequest request = new SendMessageRequest().withQueueUrl(url).withMessageBody(body)
                              .withMessageGroupId(groupId);
                      amazonSqs.sendMessage(request);
                  }
              }
              """
          )
        );
    }

    @Test
    void asyncOverloadWithHandler() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.handlers.AsyncHandler;
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.SendMessageRequest;
              import com.amazonaws.services.sqs.model.SendMessageResult;

              class Sender {
                  void send(AmazonSQSAsync sqs, String url, String body, AsyncHandler<SendMessageRequest, SendMessageResult> handler) {
                      sqs.sendMessageAsync(url, body, handler);
                  }
              }
              """,
            """
              import com.amazonaws.handlers.AsyncHandler;
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.model.SendMessageRequest;
              import com.amazonaws.services.sqs.model.SendMessageResult;

              class Sender {
                  void send(AmazonSQSAsync sqs, String url, String body, AsyncHandler<SendMessageRequest, SendMessageResult> handler) {
                      sqs.sendMessageAsync(new SendMessageRequest().withQueueUrl(url).withMessageBody(body), handler);
                  }
              }
              """
          )
        );
    }

    @Test
    void snsAndDynamoDb() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.model.*;
              import com.amazonaws.services.sns.AmazonSNS;
              import com.amazonaws.services.sns.model.PublishRequest;

              import java.util.Collections;
              import java.util.Map;

              class Setup {
                  void run(AmazonSNS sns, AmazonDynamoDB ddb, String arn, String msg, Map<String, AttributeValue> key) {
                      sns.publish(new PublishRequest(arn, msg).withMessageGroupId("g"));
                      sns.publish(arn, msg, "subject");
                      ddb.describeTable("users");
                      ddb.getItem("users", key);
                      CreateTableRequest create = new CreateTableRequest("users",
                              Collections.singletonList(new KeySchemaElement("id", KeyType.HASH)))
                              .withProvisionedThroughput(new ProvisionedThroughput(5L, 5L));
                      key.put("id", new AttributeValue("1"));
                  }
              }
              """,
            """
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.dynamodbv2.model.*;
              import com.amazonaws.services.sns.AmazonSNS;
              import com.amazonaws.services.sns.model.PublishRequest;

              import java.util.Collections;
              import java.util.Map;

              class Setup {
                  void run(AmazonSNS sns, AmazonDynamoDB ddb, String arn, String msg, Map<String, AttributeValue> key) {
                      sns.publish(new PublishRequest().withTopicArn(arn).withMessage(msg).withMessageGroupId("g"));
                      sns.publish(new PublishRequest().withTopicArn(arn).withMessage(msg).withSubject("subject"));
                      ddb.describeTable(new DescribeTableRequest().withTableName("users"));
                      ddb.getItem(new GetItemRequest().withTableName("users").withKey(key));
                      CreateTableRequest create = new CreateTableRequest().withTableName("users").withKeySchema(Collections.singletonList(new KeySchemaElement().withAttributeName("id").withKeyType(KeyType.HASH)))
                              .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(5L).withWriteCapacityUnits(5L));
                      key.put("id", new AttributeValue().withS("1"));
                  }
              }
              """
          )
        );
    }

    @Test
    void kinesisOverloads() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-kinesis")),
          //language=java
          java(
            """
              import com.amazonaws.services.kinesis.AmazonKinesis;
              import com.amazonaws.services.kinesis.model.Shard;

              import java.util.List;

              class LocalstackTest {
                  List<Shard> shards(AmazonKinesis client, String streamName) {
                      return client.describeStream(streamName).getStreamDescription().getShards();
                  }
              }
              """,
            """
              import com.amazonaws.services.kinesis.AmazonKinesis;
              import com.amazonaws.services.kinesis.model.DescribeStreamRequest;
              import com.amazonaws.services.kinesis.model.Shard;

              import java.util.List;

              class LocalstackTest {
                  List<Shard> shards(AmazonKinesis client, String streamName) {
                      return client.describeStream(new DescribeStreamRequest().withStreamName(streamName)).getStreamDescription().getShards();
                  }
              }
              """
          )
        );
    }

    @Test
    void receiverlessOverloadInheritedFromTheSdkIsExpanded() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AbstractAmazonSQS;

              class Notifier extends AbstractAmazonSQS {
                  void notify(String url, String body) {
                      sendMessage(url, body);
                  }
              }
              """,
            """
              import com.amazonaws.services.sqs.AbstractAmazonSQS;
              import com.amazonaws.services.sqs.model.SendMessageRequest;

              class Notifier extends AbstractAmazonSQS {
                  void notify(String url, String body) {
                      sendMessage(new SendMessageRequest().withQueueUrl(url).withMessageBody(body));
                  }
              }
              """
          )
        );
    }

    @Test
    void callsToTheProjectsOwnOverrideOfAnOverloadAreKept() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AbstractAmazonSQS;
              import com.amazonaws.services.sqs.model.SendMessageResult;

              class Notifier extends AbstractAmazonSQS {
                  @Override
                  public SendMessageResult sendMessage(String url, String body) {
                      return new SendMessageResult();
                  }

                  void notify(String url, String body) {
                      sendMessage(url, body);
                  }
              }
              """
          )
        );
    }

    @Test
    void unknownOverloadIsMarked() {
        rewriteRun(
          spec -> spec.typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQS;

              class Client {
                  void configure(AmazonSQS sqs) {
                      sqs.setEndpoint("http://localhost:4566");
                  }
              }
              """,
            """
              import com.amazonaws.services.sqs.AmazonSQS;

              class Client {
                  void configure(AmazonSQS sqs) {
                      /*~~(%s)~~>*/sqs.setEndpoint("http://localhost:4566");
                  }
              }
              """.formatted(ExpandAwsRequestConstructorsAndOverloads.UNKNOWN_OVERLOAD)
          )
        );
    }

    @Test
    void convertedByV2Migration() {
        rewriteRun(
          spec -> spec.recipes(new ExpandAwsRequestConstructorsAndOverloads(),
              org.openrewrite.config.Environment.builder().scanRuntimeClasspath("software.amazon.awssdk.v2migration").build()
                .activateRecipes("software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2"))
            .typeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQS;

              class Sender {
                  void send(AmazonSQS sqs, String url, String body) {
                      sqs.sendMessage(url, body);
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.sqs.SqsClient;
              import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

              class Sender {
                  void send(SqsClient sqs, String url, String body) {
                      sqs.sendMessage(SendMessageRequest.builder().queueUrl(url).messageBody(body)
                              .build());
                  }
              }
              """
          )
        );
    }

    @Test
    void s3TagConstructor() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(),
            "aws-java-sdk-core", "aws-java-sdk-s3")),
          //language=java
          java(
            """
              import com.amazonaws.services.s3.model.Tag;

              class Tags {
                  Tag tag() {
                      return new Tag("hello", "world");
                  }
              }
              """,
            """
              import com.amazonaws.services.s3.model.Tag;

              class Tags {
                  Tag tag() {
                      return new Tag().withKey("hello").withValue("world");
                  }
              }
              """
          )
        );
    }
}

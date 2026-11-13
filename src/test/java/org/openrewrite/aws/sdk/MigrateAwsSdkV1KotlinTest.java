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
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.kotlin.Assertions.kotlin;

class MigrateAwsSdkV1KotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAwsSdkV1Kotlin())
          .parser(KotlinParser.builder()
            .classpathFromResources(new InMemoryExecutionContext(),
              "aws-java-sdk-core", "aws-java-sdk-sqs", "aws-java-sdk-sns", "aws-java-sdk-s3", "aws-java-sdk-dynamodb",
              "aws-java-sdk-cloudwatch"))
          // Only S3's v2 client is on the template classpath, so other v2 clients are unattributed
          .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void credentialsAndS3Builder() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              package dartzee.utils

              import com.amazonaws.auth.AWSStaticCredentialsProvider
              import com.amazonaws.auth.BasicAWSCredentials
              import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
              import com.amazonaws.services.s3.AmazonS3
              import com.amazonaws.services.s3.AmazonS3ClientBuilder

              object AwsUtils {
                  fun readCredentials(lines: List<String>) =
                      try {
                          BasicAWSCredentials(lines[0], lines[1])
                      } catch (t: Throwable) {
                          null
                      }

                  fun makeS3Client(lines: List<String>): AmazonS3 {
                      val credentials = readCredentials(lines)
                      val credProvider =
                          if (credentials != null) AWSStaticCredentialsProvider(credentials)
                          else EnvironmentVariableCredentialsProvider()
                      return AmazonS3ClientBuilder.standard()
                          .withRegion("eu-west-2")
                          .withCredentials(credProvider)
                          .build()
                  }
              }
              """,
            """
              package dartzee.utils

              import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
              import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider
              import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
              import software.amazon.awssdk.regions.Region
              import software.amazon.awssdk.services.s3.S3Client

              object AwsUtils {
                  fun readCredentials(lines: List<String>) =
                      try {
                          AwsBasicCredentials.create(lines[0], lines[1])
                      } catch (t: Throwable) {
                          null
                      }

                  fun makeS3Client(lines: List<String>): S3Client {
                      val credentials = readCredentials(lines)
                      val credProvider =
                          if (credentials != null) StaticCredentialsProvider.create(credentials)
                          else EnvironmentVariableCredentialsProvider.create()
                      return S3Client.builder()
                          .region(Region.of("eu-west-2"))
                          .credentialsProvider(credProvider)
                          .build()
                  }
              }
              """
          )
        );
    }

    @Test
    void endpointConfiguration() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
              import com.amazonaws.client.builder.AwsClientBuilder
              import com.amazonaws.services.sqs.AmazonSQSAsync
              import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder

              class AnthonyAwsSQSConfiguration {
                  fun amazonSQSAsync(): AmazonSQSAsync {
                      return AmazonSQSAsyncClientBuilder.standard()
                              .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(
                                      "http://localhost:4566/000000000000/anthony-queue",
                                      "us-west-1"
                              ))
                              .withCredentials(DefaultAWSCredentialsProviderChain())
                              .build()
                  }
              }
              """,
            """
              import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
              import software.amazon.awssdk.regions.Region
              import software.amazon.awssdk.services.sqs.SqsAsyncClient

              import java.net.URI

              class AnthonyAwsSQSConfiguration {
                  fun amazonSQSAsync(): SqsAsyncClient {
                      return SqsAsyncClient.builder()
                          .endpointOverride(URI.create("http://localhost:4566/000000000000/anthony-queue"))
                          .region(Region.of("us-west-1"))
                          .credentialsProvider(DefaultCredentialsProvider.create())
                          .build()
                  }
              }
              """
          )
        );
    }

    @Test
    void legacyConstructorDefaultClientAndUnmigratedCalls() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
              import com.amazonaws.services.cloudwatch.AmazonCloudWatch
              import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder
              import com.amazonaws.services.s3.AmazonS3Client

              class S3MetricCollector {
                  private val cloudWatch: AmazonCloudWatch = AmazonCloudWatchClientBuilder.defaultClient()

                  fun handle(bucketName: String, key: String) {
                      val s3Client = AmazonS3Client(DefaultAWSCredentialsProviderChain())
                      val inputStream = s3Client.getObject(bucketName, key).objectContent
                      s3Client.deleteBucket(bucketName)
                  }
              }
              """,
            """
              import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
              import software.amazon.awssdk.regions.Region
              import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
              import software.amazon.awssdk.services.s3.S3Client
              import software.amazon.awssdk.services.s3.model.GetObjectRequest

              class S3MetricCollector {
                  private val cloudWatch: CloudWatchClient = CloudWatchClient.create()

                  fun handle(bucketName: String, key: String) {
                      val s3Client = S3Client.builder().region(Region.US_EAST_1).crossRegionAccessEnabled(true).credentialsProvider(DefaultCredentialsProvider.create()).build()
                      val inputStream = s3Client.getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build())
                      /*~~(AWS SDK v1 call not migrated in Kotlin)~~>*/s3Client.deleteBucket(bucketName)
                  }
              }
              """
          )
        );
    }

    @Test
    void anonymousCredentials() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.auth.AWSStaticCredentialsProvider
              import com.amazonaws.auth.AnonymousAWSCredentials
              import com.amazonaws.client.builder.AwsClientBuilder
              import com.amazonaws.services.sqs.AmazonSQS
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder

              class JmsConfig {
                fun awsSqsClientLocalstack(serviceEndpoint: String, region: String): AmazonSQS =
                  AmazonSQSClientBuilder.standard()
                    .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(serviceEndpoint, region))
                    .withCredentials(AWSStaticCredentialsProvider(AnonymousAWSCredentials()))
                    .build()
              }
              """,
            """
              import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
              import software.amazon.awssdk.regions.Region
              import software.amazon.awssdk.services.sqs.SqsClient

              import java.net.URI

              class JmsConfig {
                fun awsSqsClientLocalstack(serviceEndpoint: String, region: String): SqsClient =
                  SqsClient.builder()
                      .endpointOverride(URI.create(serviceEndpoint))
                      .region(Region.of(region))
                      .credentialsProvider(AnonymousCredentialsProvider.create())
                      .build()
              }
              """
          )
        );
    }

    @Test
    void projectTypeNamedLikeTheV2ClientAndCommentedOutCall() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              package com.example.sns

              interface SnsClient {
                  fun sendNotification(message: String)
              }
              """
          ),
          //language=kotlin
          kotlin(
            """
              package com.example.sns

              import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
              import com.amazonaws.services.sns.AmazonSNS
              import com.amazonaws.services.sns.AmazonSNSClientBuilder

              class SnsClientBean(private val awsRegion: String) : SnsClient {
                  private val awsSnsClient: AmazonSNS = AmazonSNSClientBuilder
                      .standard()
                      .withRegion(awsRegion)
                      .withCredentials(DefaultAWSCredentialsProviderChain())
                      //.withClientConfiguration(config)
                      .build()

                  override fun sendNotification(message: String) {
                  }
              }
              """,
            """
              package com.example.sns

              import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
              import software.amazon.awssdk.regions.Region

              class SnsClientBean(private val awsRegion: String) : SnsClient {
                  private val awsSnsClient: software.amazon.awssdk.services.sns.SnsClient = software.amazon.awssdk.services.sns.SnsClient
                      .builder()
                      .region(Region.of(awsRegion))
                      .credentialsProvider(DefaultCredentialsProvider.create())
                      //.withClientConfiguration(config)
                      .build()

                  override fun sendNotification(message: String) {
                  }
              }
              """
          )
        );
    }

    @Test
    void lowLevelDynamoDbClientDoesNotBlockS3Calls() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
              import com.amazonaws.services.s3.AmazonS3
              import java.io.File

              class Archiver(private val dynamo: AmazonDynamoDB, private val s3: AmazonS3) {
                  fun archive(table: String, bucket: String, file: File) {
                      println(dynamo.describeTable(table).table.tableStatus)
                      s3.putObject(bucket, file.name, file)
                  }
              }
              """,
            """
              import software.amazon.awssdk.core.sync.RequestBody
              import software.amazon.awssdk.services.dynamodb.DynamoDbClient
              import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest
              import software.amazon.awssdk.services.s3.S3Client
              import software.amazon.awssdk.services.s3.model.PutObjectRequest

              import java.io.File

              class Archiver(private val dynamo: DynamoDbClient, private val s3: S3Client) {
                  fun archive(table: String, bucket: String, file: File) {
                      println(dynamo.describeTable(DescribeTableRequest.builder().tableName(table).build()).table().tableStatusAsString())
                      s3.putObject(PutObjectRequest.builder().bucket(bucket).key(file.name).build(), RequestBody.fromFile(file))
                  }
              }
              """
          )
        );
    }
    @Test
    void s3CallsAndResultProperties() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              package dartzee.sync

              import com.amazonaws.services.s3.AmazonS3
              import com.amazonaws.services.s3.model.GetObjectRequest
              import java.io.File
              import java.util.*

              class AmazonS3RemoteDatabaseStore(private val s3Client: AmazonS3, private val bucketName: String) {
                  fun databaseExists(remoteName: String) =
                      s3Client.doesObjectExist(bucketName, key(remoteName))

                  fun fetchDatabase(remoteName: String, downloadPath: File): Date {
                      val request = GetObjectRequest(bucketName, key(remoteName))
                      val s3Obj = s3Client.getObject(request, downloadPath)
                      println("Last modified remotely: ${s3Obj.lastModified}")
                      return s3Obj.lastModified
                  }

                  fun pushDatabase(remoteName: String, zip: File, lastModified: Date) {
                      val metadata = s3Client.getObjectMetadata(bucketName, key(remoteName))
                      if (metadata.lastModified > lastModified) {
                          throw ConcurrentModificationException("modified")
                      }
                      s3Client.putObject(bucketName, key(remoteName), zip)
                      s3Client.copyObject(
                          bucketName,
                          key(remoteName),
                          bucketName,
                          "$remoteName/backups/x.zip",
                      )
                  }

                  fun backups(remoteName: String): List<String> {
                      val objects = s3Client.listObjects(bucketName, "$remoteName/backups/").objectSummaries
                      return objects.map { it.key }
                  }

                  fun read(remoteName: String): Pair<String, Date> {
                      val obj = s3Client.getObject(bucketName, key(remoteName))
                      val text = obj.objectContent.bufferedReader().readText()
                      return Pair(text + s3Client.getObjectAsString(bucketName, "readme"), obj.objectMetadata.lastModified)
                  }

                  private fun key(remoteName: String) = "$remoteName/current.zip"
              }
              """,
            """
              package dartzee.sync

              import software.amazon.awssdk.core.sync.RequestBody
              import software.amazon.awssdk.services.s3.S3Client
              import software.amazon.awssdk.services.s3.model.*

              import java.io.File
              import java.nio.file.Files
              import java.util.*

              class AmazonS3RemoteDatabaseStore(private val s3Client: S3Client, private val bucketName: String) {
                  fun databaseExists(remoteName: String) =
                      try {
                          s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key(remoteName)).build())
                          true
                      } catch (e: S3Exception) {
                          if (e.statusCode() == 404) false else throw e
                      }

                  fun fetchDatabase(remoteName: String, downloadPath: File): Date {
                      val request = GetObjectRequest.builder().bucket(bucketName).key(key(remoteName)).build()
                      val s3Obj = s3Client.getObject(request, downloadPath.toPath().also { Files.createDirectories(it.toAbsolutePath().parent); Files.deleteIfExists(it) })
                      println("Last modified remotely: ${Date.from(s3Obj.lastModified())}")
                      return Date.from(s3Obj.lastModified())
                  }

                  fun pushDatabase(remoteName: String, zip: File, lastModified: Date) {
                      val metadata = s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key(remoteName)).build())
                      if (Date.from(metadata.lastModified()) > lastModified) {
                          throw ConcurrentModificationException("modified")
                      }
                      s3Client.putObject(PutObjectRequest.builder().bucket(bucketName).key(key(remoteName)).build(), RequestBody.fromFile(zip))
                      s3Client.copyObject(CopyObjectRequest.builder().sourceBucket(bucketName).sourceKey(key(remoteName)).destinationBucket(bucketName).destinationKey("$remoteName/backups/x.zip").build())
                  }

                  fun backups(remoteName: String): List<String> {
                      val objects = s3Client.listObjects(ListObjectsRequest.builder().bucket(bucketName).prefix("$remoteName/backups/").build()).contents()
                      return objects.map { it.key() }
                  }

                  fun read(remoteName: String): Pair<String, Date> {
                      val obj = s3Client.getObject(GetObjectRequest.builder().bucket(bucketName).key(key(remoteName)).build())
                      val text = obj.bufferedReader().readText()
                      return Pair(text + s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucketName).key("readme").build()).asUtf8String(), Date.from(obj.response().lastModified()))
                  }

                  private fun key(remoteName: String) = "$remoteName/current.zip"
              }
              """
          )
        );
    }
    @Test
    void s3PresignAndPutWithCannedAclInProjectClassNamedS3Client() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              package waffle.guam.community.service.storage

              import com.amazonaws.HttpMethod
              import com.amazonaws.services.s3.AmazonS3Client
              import com.amazonaws.services.s3.model.CannedAccessControlList
              import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest
              import com.amazonaws.services.s3.model.PutObjectRequest
              import java.io.File
              import java.sql.Date
              import java.time.Instant

              class S3Client(
                  private val s3Client: AmazonS3Client,
                  private val bucketName: String,
              ) {
                  fun upload(objectPath: String, file: File) {
                      val request = PutObjectRequest(bucketName, objectPath, file)
                      s3Client.putObject(request.withCannedAcl(CannedAccessControlList.PublicRead))
                  }

                  fun getPresignedUrl(path: String): String {
                      val expirationDate = Date.from(Instant.now().plusSeconds(60))
                      val request = GeneratePresignedUrlRequest(bucketName, path, HttpMethod.PUT)
                          .withExpiration(expirationDate)
                      return s3Client.generatePresignedUrl(request).toExternalForm()
                  }
              }
              """,
            """
              package waffle.guam.community.service.storage

              import software.amazon.awssdk.core.sync.RequestBody
              import software.amazon.awssdk.services.s3.model.ObjectCannedACL
              import software.amazon.awssdk.services.s3.model.PutObjectRequest
              import software.amazon.awssdk.services.s3.presigner.S3Presigner
              import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest

              import java.io.File
              import java.sql.Date
              import java.time.Duration
              import java.time.Instant

              class S3Client(
                  private val s3Client: software.amazon.awssdk.services.s3.S3Client,
                  private val bucketName: String,
              ) {
                  fun upload(objectPath: String, file: File) {
                      s3Client.putObject(PutObjectRequest.builder().bucket(bucketName).key(objectPath).acl(ObjectCannedACL.PUBLIC_READ).build(), RequestBody.fromFile(file))
                  }

                  fun getPresignedUrl(path: String): String {
                      val expirationDate = Date.from(Instant.now().plusSeconds(60))
                      return S3Presigner.builder().s3Client(s3Client).build().presignPutObject(PutObjectPresignRequest.builder().signatureDuration(Duration.between(Instant.now(), expirationDate.toInstant())).putObjectRequest(PutObjectRequest.builder().bucket(bucketName).key(path).build()).build()).url().toExternalForm()
                  }
              }
              """
          )
        );
    }

    @Test
    void putWithMemberImportedCannedAcl() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              package waffle.guam.user.infra.aws

              import com.amazonaws.services.s3.AmazonS3Client
              import com.amazonaws.services.s3.model.CannedAccessControlList.PublicRead
              import com.amazonaws.services.s3.model.PutObjectRequest
              import java.io.File

              class ImageClientS3Impl(private val s3Client: AmazonS3Client, private val bucket: String) {
                  fun upload(file: File): String {
                      s3Client.putObject(
                          PutObjectRequest(bucket, file.path, file).withCannedAcl(PublicRead)
                      )
                      return file.path
                  }
              }
              """,
            """
              package waffle.guam.user.infra.aws

              import software.amazon.awssdk.core.sync.RequestBody
              import software.amazon.awssdk.services.s3.S3Client
              import software.amazon.awssdk.services.s3.model.ObjectCannedACL
              import software.amazon.awssdk.services.s3.model.PutObjectRequest

              import java.io.File

              class ImageClientS3Impl(private val s3Client: S3Client, private val bucket: String) {
                  fun upload(file: File): String {
                      s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(file.path).acl(ObjectCannedACL.PUBLIC_READ).build(), RequestBody.fromFile(file))
                      return file.path
                  }
              }
              """
          )
        );
    }

    @Test
    void snsPublishRequestAndUnusedV1Imports() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              package com.example.sns

              interface SnsClient {
                  fun sendNotification(topic: String, message: String)
              }
              """
          ),
          //language=kotlin
          kotlin(
            """
              package com.example.sns

              import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
              import com.amazonaws.client.builder.AwsClientBuilder
              import com.amazonaws.services.sns.AmazonSNSClientBuilder
              import com.amazonaws.services.sns.model.PublishRequest

              class SnsClientLocalBean(
                  private val awsRegion: String,
                  private val serviceEndpoint: String,
              ) : SnsClient {
                  private val snsClient = AmazonSNSClientBuilder
                      .standard()
                      .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(serviceEndpoint, awsRegion))
                      .build()

                  override fun sendNotification(topic: String, message: String) {
                      val publishRequest = PublishRequest()
                          .withTopicArn(topic)
                          .withMessage(message)

                      snsClient.publish(publishRequest)
                  }
              }
              """,
            """
              package com.example.sns

              import software.amazon.awssdk.regions.Region
              import software.amazon.awssdk.services.sns.model.PublishRequest

              import java.net.URI

              class SnsClientLocalBean(
                  private val awsRegion: String,
                  private val serviceEndpoint: String,
              ) : SnsClient {
                  private val snsClient = software.amazon.awssdk.services.sns.SnsClient
                      .builder()
                      .endpointOverride(URI.create(serviceEndpoint))
                      .region(Region.of(awsRegion))
                      .build()

                  override fun sendNotification(topic: String, message: String) {
                      val publishRequest = PublishRequest.builder().topicArn(topic).message(message).build()

                      snsClient.publish(publishRequest)
                  }
              }
              """
          )
        );
    }

    @Test
    void sqsConvenienceOverloadsAndResults() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.services.sqs.AmazonSQS
              import com.amazonaws.services.sqs.model.SendMessageRequest

              class Queue(private val sqs: AmazonSQS, private val url: String) {
                  fun send(body: String): String = sqs.sendMessage(url, body).messageId

                  fun sendDelayed(body: String) {
                      sqs.sendMessage(SendMessageRequest(url, body).withDelaySeconds(5))
                  }

                  fun drain() {
                      for (message in sqs.receiveMessage(url).messages) {
                          println(message.body)
                          sqs.deleteMessage(url, message.receiptHandle)
                      }
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.sqs.SqsClient
              import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
              import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
              import software.amazon.awssdk.services.sqs.model.SendMessageRequest

              class Queue(private val sqs: SqsClient, private val url: String) {
                  fun send(body: String): String = sqs.sendMessage(SendMessageRequest.builder().queueUrl(url).messageBody(body).build()).messageId()

                  fun sendDelayed(body: String) {
                      sqs.sendMessage(SendMessageRequest.builder().queueUrl(url).messageBody(body).delaySeconds(5).build())
                  }

                  fun drain() {
                      for (message in sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(url).build()).messages()) {
                          println(message.body())
                          sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(url).receiptHandle(message.receiptHandle()).build())
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void requestSettingWithoutV2EquivalentIsMarked() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.services.sns.AmazonSNS
              import com.amazonaws.services.sns.model.MessageAttributeValue
              import com.amazonaws.services.sns.model.PublishRequest

              class Publisher(private val sns: AmazonSNS) {
                  fun publish(topic: String, attributes: Map<String, MessageAttributeValue>) {
                      sns.publish(PublishRequest(topic, "hello").withMessageAttributes(attributes))
                  }
              }
              """,
            """
              import com.amazonaws.services.sns.model.MessageAttributeValue
              import com.amazonaws.services.sns.model.PublishRequest
              import software.amazon.awssdk.services.sns.SnsClient

              class Publisher(private val sns: SnsClient) {
                  fun publish(topic: String, attributes: Map<String, MessageAttributeValue>) {
                      /*~~(AWS SDK v1 call not migrated in Kotlin)~~>*/sns.publish(/*~~(AWS SDK v1 request not migrated in Kotlin: it has a setting with no v2 builder equivalent)~~>*/PublishRequest(topic, "hello").withMessageAttributes(attributes))
                  }
              }
              """
          )
        );
    }

    @Test
    void builderRetypedEarlierInTheRun() {
        rewriteRun(
          // The messaging migration retypes async SNS beans to sync before this recipe runs
          spec -> spec.recipes(
            new org.openrewrite.java.ChangeType("com.amazonaws.services.sns.AmazonSNSAsync", "com.amazonaws.services.sns.AmazonSNS", true),
            new org.openrewrite.java.ChangeType("com.amazonaws.services.sns.AmazonSNSAsyncClientBuilder", "com.amazonaws.services.sns.AmazonSNSClientBuilder", true),
            new MigrateAwsSdkV1Kotlin()),
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.auth.AWSStaticCredentialsProvider
              import com.amazonaws.auth.BasicAWSCredentials
              import com.amazonaws.client.builder.AwsClientBuilder
              import com.amazonaws.services.sns.AmazonSNSAsync
              import com.amazonaws.services.sns.AmazonSNSAsyncClientBuilder

              class SNSConfig(private val region: String?, private val endpoint: String?, private val key: String?, private val secret: String?) {
                  fun amazonSNSAsync(): AmazonSNSAsync {
                      return if (endpoint === "defaultEndPoint") {
                          AmazonSNSAsyncClientBuilder.standard().withRegion(region)
                              .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(key, secret)))
                              .build()
                      } else AmazonSNSAsyncClientBuilder.standard()
                          .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(endpoint, region))
                          .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(key, secret)))
                          .build()
                  }
              }
              """,
            """
              import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
              import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
              import software.amazon.awssdk.regions.Region
              import software.amazon.awssdk.services.sns.SnsClient

              import java.net.URI

              class SNSConfig(private val region: String?, private val endpoint: String?, private val key: String?, private val secret: String?) {
                  fun amazonSNSAsync(): SnsClient {
                      return if (endpoint === "defaultEndPoint") {
                          SnsClient.builder().region(Region.of(region))
                              .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                              .build()
                      } else SnsClient.builder()
                          .endpointOverride(URI.create(endpoint))
                          .region(Region.of(region))
                          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                          .build()
                  }
              }
              """
          )
        );
    }
    @Test
    void stringUtils() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.util.StringUtils

              class Endpoints(private val endpoint: String?) {
                  fun custom() = !StringUtils.isNullOrEmpty(endpoint)

                  fun hasEndpoint() = StringUtils.hasValue(endpoint)

                  fun normalized() = StringUtils.lowerCase(endpoint)
              }
              """,
            """
              import software.amazon.awssdk.utils.StringUtils

              class Endpoints(private val endpoint: String?) {
                  fun custom() = !StringUtils.isEmpty(endpoint)

                  fun hasEndpoint() = !StringUtils.isEmpty(endpoint)

                  fun normalized() = StringUtils.lowerCase(endpoint)
              }
              """
          )
        );
    }

    @Test
    void webIdentityCredentialsInCommentedChain() {
        rewriteRun(
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.auth.WebIdentityTokenCredentialsProvider
              import com.amazonaws.services.sqs.AmazonSQS
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder

              class SqsConfig {
                  fun production(): AmazonSQS {
                      return AmazonSQSClientBuilder.standard()
                          // AWS_WEB_IDENTITY_TOKEN_FILE is present
                          .withCredentials(WebIdentityTokenCredentialsProvider.builder().build())
                          .build()
                  }

                  fun configured(role: String, tokenFile: String) =
                      WebIdentityTokenCredentialsProvider.builder()
                          .roleArn(role)
                          // the projected service account token
                          .webIdentityTokenFile(tokenFile)
                          .build()
              }
              """,
            """
              import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider
              import software.amazon.awssdk.services.sqs.SqsClient

              import java.nio.file.Paths

              class SqsConfig {
                  fun production(): SqsClient {
                      return SqsClient.builder()
                          // AWS_WEB_IDENTITY_TOKEN_FILE is present
                          .credentialsProvider(WebIdentityTokenFileCredentialsProvider.create())
                          .build()
                  }

                  fun configured(role: String, tokenFile: String) =
                      WebIdentityTokenFileCredentialsProvider.builder()
                          .roleArn(role)
                          // the projected service account token
                          .webIdentityTokenFile(Paths.get(tokenFile))
                          .build()
              }
              """
          )
        );
    }

    @Test
    void containerInstanceProfileAndAssumeRoleCredentials() {
        rewriteRun(
          spec -> spec.parser(KotlinParser.builder()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sts")),
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.auth.AWSCredentialsProvider
              import com.amazonaws.auth.ContainerCredentialsProvider
              import com.amazonaws.auth.EC2ContainerCredentialsProviderWrapper
              import com.amazonaws.auth.InstanceProfileCredentialsProvider
              import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider

              class Providers {
                  val container: AWSCredentialsProvider = ContainerCredentialsProvider()
                  val instance = InstanceProfileCredentialsProvider.getInstance()
                  val wrapper: EC2ContainerCredentialsProviderWrapper = EC2ContainerCredentialsProviderWrapper()

                  fun assumed(role: String) = STSAssumeRoleSessionCredentialsProvider(role, "app")

                  fun built(role: String, base: AWSCredentialsProvider) =
                      STSAssumeRoleSessionCredentialsProvider.Builder(role, "app").withLongLivedCredentialsProvider(base).build()
              }
              """,
            """
              import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
              import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain
              import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider
              import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider
              import software.amazon.awssdk.regions.Region
              import software.amazon.awssdk.services.sts.StsClient
              import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider
              import software.amazon.awssdk.services.sts.model.AssumeRoleRequest

              class Providers {
                  val container: AwsCredentialsProvider = ContainerCredentialsProvider.create()
                  val instance = InstanceProfileCredentialsProvider.create()
                  val wrapper: AwsCredentialsProviderChain = AwsCredentialsProviderChain.of(ContainerCredentialsProvider.create(), InstanceProfileCredentialsProvider.create())

                  fun assumed(role: String) = StsAssumeRoleCredentialsProvider.builder().stsClient(StsClient.builder().region(Region.AWS_GLOBAL).build()).refreshRequest(AssumeRoleRequest.builder().roleArn(role).roleSessionName("app").build()).build()

                  fun built(role: String, base: AwsCredentialsProvider) =
                      StsAssumeRoleCredentialsProvider.builder().stsClient(StsClient.builder().region(Region.AWS_GLOBAL).credentialsProvider(base).build()).refreshRequest(AssumeRoleRequest.builder().roleArn(role).roleSessionName("app").build()).build()
              }
              """
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void dynamoDbMapperFilesAreLeftToTheDynamoDbMigration() {
            rewriteRun(
              //language=kotlin
              kotlin(
                """
                  import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
                  import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
                  import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper

                  class DynamoDBConfig {
                      fun mapper(): DynamoDBMapper = DynamoDBMapper(AmazonDynamoDBClientBuilder.standard()
                          .withCredentials(DefaultAWSCredentialsProviderChain())
                          .build())
                  }
                  """
              )
            );
        }
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

class MigrateKinesisClientLibraryToV2Test implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateKinesisClientLibraryToV2(null))
          .parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(),
            "aws-java-sdk-core",
            "aws-java-sdk-kinesis",
            "amazon-kinesis-client-1",
            "amazon-kinesis-client-2"));
    }

    @DocumentExample
    @Test
    void portsRecordProcessorFactoryAndWorker() {
        rewriteRun(
          spec -> spec.afterRecipe(run -> assertThat(run.getChangeset().getAllResults())
            .anySatisfy(r -> {
                assertThat(r.getBefore()).isNull();
                assertThat(r.getAfter().getSourcePath()).hasToString("src/main/java/com/example/KclWorker.java");
                assertThat(r.getAfter().printAll()).contains("new Scheduler(").contains("new PollingConfig(");
            })
            .anySatisfy(r -> {
                assertThat(r.getBefore()).isNull();
                assertThat(r.getAfter().printAll()).contains("public interface KclRecordProcessor extends ShardRecordProcessor");
            })),
          //language=xml
          pomXml(
            """
              <project>
                  <groupId>com.example</groupId>
                  <artifactId>consumer</artifactId>
                  <version>1.0</version>
                  <dependencies>
                      <dependency>
                          <groupId>com.amazonaws</groupId>
                          <artifactId>amazon-kinesis-client</artifactId>
                          <version>1.15.2</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            spec -> spec.after(actual -> assertThat(actual).contains("<groupId>software.amazon.kinesis</groupId>").containsPattern("<version>2\\.7\\.\\d+</version>").actual())
          ),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
              import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
              import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessor;
              import com.amazonaws.services.kinesis.clientlibrary.lib.worker.ShutdownReason;
              import com.amazonaws.services.kinesis.clientlibrary.types.InitializationInput;
              import com.amazonaws.services.kinesis.clientlibrary.types.ProcessRecordsInput;
              import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownInput;
              import com.amazonaws.services.kinesis.model.Record;

              class Processor implements IRecordProcessor {
                  private String shardId;

                  @Override
                  public void initialize(InitializationInput input) {
                      shardId = input.getShardId();
                  }

                  @Override
                  public void processRecords(ProcessRecordsInput input) {
                      for (Record record : input.getRecords()) {
                          System.out.println(record.getPartitionKey() + " " + record.getData().remaining());
                          try {
                              input.getCheckpointer().checkpoint(record);
                          } catch (InvalidStateException | ShutdownException e) {
                              throw new IllegalStateException(e);
                          }
                      }
                  }

                  @Override
                  public void shutdown(ShutdownInput input) {
                      if (input.getShutdownReason() == ShutdownReason.TERMINATE) {
                          try {
                              input.getCheckpointer().checkpoint();
                          } catch (InvalidStateException | ShutdownException e) {
                              throw new IllegalStateException(e);
                          }
                      }
                  }
              }
              """,
            """
              package com.example;

              import software.amazon.kinesis.exceptions.InvalidStateException;
              import software.amazon.kinesis.exceptions.ShutdownException;
              import software.amazon.kinesis.lifecycle.ShutdownReason;
              import software.amazon.kinesis.lifecycle.events.InitializationInput;
              import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
              import software.amazon.kinesis.retrieval.KinesisClientRecord;

              class Processor implements KclRecordProcessor {
                  private String shardId;

                  @Override
                  public void initialize(InitializationInput input) {
                      shardId = input.shardId();
                  }

                  @Override
                  public void processRecords(ProcessRecordsInput input) {
                      for (KinesisClientRecord record : input.records()) {
                          System.out.println(record.partitionKey() + " " + record.data().remaining());
                          try {
                              input.checkpointer().checkpoint(record.sequenceNumber(), record.subSequenceNumber());
                          } catch (InvalidStateException | ShutdownException e) {
                              throw new IllegalStateException(e);
                          }
                      }
                  }

                  @Override
                  public void shutdown(KclRecordProcessor.ShutdownInput input) {
                      if (input.getShutdownReason() == ShutdownReason.SHARD_END) {
                          try {
                              input.getCheckpointer().checkpoint();
                          } catch (InvalidStateException | ShutdownException e) {
                              throw new IllegalStateException(e);
                          }
                      }
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/Processor.java")
          ),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
              import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessor;
              import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessorFactory;
              import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;
              import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
              import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;

              class Consumer {
                  Worker worker(String stream) {
                      KinesisClientLibConfiguration config = new KinesisClientLibConfiguration("app", stream,
                              new DefaultAWSCredentialsProviderChain(), "worker-1")
                              .withRegionName("eu-west-1")
                              .withInitialPositionInStream(InitialPositionInStream.TRIM_HORIZON);
                      IRecordProcessorFactory factory = new IRecordProcessorFactory() {
                          @Override
                          public IRecordProcessor createProcessor() {
                              return new Processor();
                          }
                      };
                      return new Worker.Builder().recordProcessorFactory(factory).config(config).build();
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
              import software.amazon.kinesis.common.InitialPositionInStream;
              import software.amazon.kinesis.processor.ShardRecordProcessorFactory;

              class Consumer {
                  KclWorker worker(String stream) {
                      KclWorker.Configuration config = new KclWorker.Configuration("app", stream,
                              new DefaultAWSCredentialsProviderChain(), "worker-1")
                              .withRegionName("eu-west-1")
                              .withInitialPositionInStream(InitialPositionInStream.TRIM_HORIZON);
                      ShardRecordProcessorFactory factory = new ShardRecordProcessorFactory() {
                          @Override
                          public KclRecordProcessor shardRecordProcessor() {
                              return new Processor();
                          }
                      };
                      return new KclWorker.Builder().recordProcessorFactory(factory).config(config).build();
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/Consumer.java")
          )
        );
    }

    @Test
    void portsOriginalProcessorInterfaceAndWorkerConstructor() {
        rewriteRun(
          spec -> spec.afterRecipe(run -> assertThat(run.getChangeset().getAllResults())
            .filteredOn(r -> r.getBefore() == null)
            .extracting(r -> r.getAfter().getSourcePath().toString())
            .containsExactlyInAnyOrder("src/main/java/com/example/KclWorker.java", "src/main/java/com/example/KclRecordProcessor.java")),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
              import com.amazonaws.services.kinesis.clientlibrary.exceptions.ShutdownException;
              import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessor;
              import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
              import com.amazonaws.services.kinesis.clientlibrary.lib.worker.ShutdownReason;
              import com.amazonaws.services.kinesis.clientlibrary.types.UserRecord;
              import com.amazonaws.services.kinesis.model.Record;

              import java.util.Date;
              import java.util.List;

              class OldStyle implements IRecordProcessor {
                  @Override
                  public void initialize(String shardId) {
                  }

                  @Override
                  public void processRecords(List<Record> records, IRecordProcessorCheckpointer checkpointer) {
                      for (UserRecord record : UserRecord.deaggregate(records)) {
                          Date arrived = record.getApproximateArrivalTimestamp();
                          System.out.println(arrived + " " + record.getSubSequenceNumber());
                      }
                  }

                  @Override
                  public void shutdown(IRecordProcessorCheckpointer checkpointer, ShutdownReason reason) {
                      switch (reason) {
                          case TERMINATE:
                              try {
                                  checkpointer.checkpoint();
                              } catch (InvalidStateException | ShutdownException e) {
                                  throw new IllegalStateException(e);
                              }
                              break;
                          case ZOMBIE:
                          default:
                              break;
                      }
                  }
              }
              """,
            """
              package com.example;

              import software.amazon.kinesis.exceptions.InvalidStateException;
              import software.amazon.kinesis.exceptions.ShutdownException;
              import software.amazon.kinesis.lifecycle.ShutdownReason;
              import software.amazon.kinesis.processor.RecordProcessorCheckpointer;
              import software.amazon.kinesis.retrieval.KinesisClientRecord;

              import java.util.Date;
              import java.util.List;

              class OldStyle implements KclRecordProcessor.Legacy {
                  @Override
                  public void initialize(String shardId) {
                  }

                  @Override
                  public void processRecords(List<KinesisClientRecord> records, RecordProcessorCheckpointer checkpointer) {
                      for (KinesisClientRecord record : records) {
                          Date arrived = Date.from(record.approximateArrivalTimestamp());
                          System.out.println(arrived + " " + record.subSequenceNumber());
                      }
                  }

                  @Override
                  public void shutdown(RecordProcessorCheckpointer checkpointer, ShutdownReason reason) {
                      switch (reason) {
                          case SHARD_END:
                              try {
                                  checkpointer.checkpoint();
                              } catch (InvalidStateException | ShutdownException e) {
                                  throw new IllegalStateException(e);
                              }
                              break;
                          case LEASE_LOST:
                          default:
                              break;
                      }
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/OldStyle.java")
          ),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.auth.AWSCredentialsProvider;
              import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
              import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
              import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;

              import java.util.concurrent.ExecutorService;

              class OldConsumer {
                  Worker worker(AWSCredentialsProvider credentials, ExecutorService executor) {
                      KinesisClientLibConfiguration config = new KinesisClientLibConfiguration("app", "stream",
                              credentials, credentials, credentials, "worker-1")
                              .withMaxLeasesForWorker(4)
                              .withTaskBackoffTimeMillis(1000L);
                      IRecordProcessorFactory factory = OldStyle::new;
                      return new Worker(factory, config, executor);
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.auth.AWSCredentialsProvider;
              import software.amazon.kinesis.processor.ShardRecordProcessorFactory;

              import java.util.concurrent.ExecutorService;

              class OldConsumer {
                  KclWorker worker(AWSCredentialsProvider credentials, ExecutorService executor) {
                      KclWorker.Configuration config = new KclWorker.Configuration("app", "stream",
                              credentials, credentials, credentials, "worker-1")
                              .withMaxLeasesForWorker(4)
                              .withTaskBackoffTimeMillis(1000L);
                      ShardRecordProcessorFactory factory = OldStyle::new;
                      return new KclWorker(factory, config, executor);
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/OldConsumer.java")
          )
        );
    }

    @Test
    void portsWorkerBuilderClientsAndMetrics() {
        rewriteRun(
          spec -> spec.afterRecipe(run -> assertThat(run.getChangeset().getAllResults())
            .filteredOn(r -> r.getBefore() == null)
            .extracting(r -> r.getAfter().getSourcePath().toString())
            .containsExactlyInAnyOrder("src/main/java/com/example/KclWorker.java", "src/main/java/com/example/KclRecordProcessor.java"))
            .parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(),
              "aws-java-sdk-core", "aws-java-sdk-kinesis", "aws-java-sdk-dynamodb", "amazon-kinesis-client-1")),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
              import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessor;
              import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IShutdownNotificationAware;
              import com.amazonaws.services.kinesis.clientlibrary.types.InitializationInput;
              import com.amazonaws.services.kinesis.clientlibrary.types.ProcessRecordsInput;
              import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownInput;

              class Aware implements IRecordProcessor, IShutdownNotificationAware {
                  @Override
                  public void initialize(InitializationInput input) {
                  }

                  @Override
                  public void processRecords(ProcessRecordsInput input) {
                  }

                  @Override
                  public void shutdownRequested(IRecordProcessorCheckpointer checkpointer) {
                  }

                  @Override
                  public void shutdown(ShutdownInput input) {
                  }
              }
              """,
            """
              package com.example;

              import software.amazon.kinesis.lifecycle.events.InitializationInput;
              import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
              import software.amazon.kinesis.processor.RecordProcessorCheckpointer;

              class Aware implements KclRecordProcessor, KclRecordProcessor.ShutdownNotificationAware {
                  @Override
                  public void initialize(InitializationInput input) {
                  }

                  @Override
                  public void processRecords(ProcessRecordsInput input) {
                  }

                  @Override
                  public void shutdownRequested(RecordProcessorCheckpointer checkpointer) {
                  }

                  @Override
                  public void shutdown(KclRecordProcessor.ShutdownInput input) {
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/Aware.java")
          ),
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.kinesis.AmazonKinesis;
              import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessorFactory;
              import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;
              import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
              import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;
              import com.amazonaws.services.kinesis.metrics.impl.NullMetricsFactory;
              import com.amazonaws.services.kinesis.metrics.interfaces.MetricsLevel;

              import java.util.Date;
              import java.util.concurrent.Future;

              class Consumer {
                  Future<Boolean> start(AmazonKinesis kinesis, AmazonDynamoDB dynamo, Date from) {
                      KinesisClientLibConfiguration config = new KinesisClientLibConfiguration("app", "stream",
                              new DefaultAWSCredentialsProviderChain(), "worker-1")
                              .withInitialPositionInStream(InitialPositionInStream.AT_TIMESTAMP)
                              .withTimestampAtInitialPositionInStream(from)
                              .withMetricsLevel(MetricsLevel.NONE);
                      IRecordProcessorFactory factory = Aware::new;
                      Worker worker = new Worker.Builder()
                              .recordProcessorFactory(factory)
                              .config(config)
                              .kinesisClient(kinesis)
                              .dynamoDBClient(dynamo)
                              .metricsFactory(new NullMetricsFactory())
                              .build();
                      new Thread(worker).start();
                      return worker.startGracefulShutdown();
                  }
              }
              """,
            """
              package com.example;

              import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
              import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
              import com.amazonaws.services.kinesis.AmazonKinesis;
              import software.amazon.kinesis.common.InitialPositionInStream;
              import software.amazon.kinesis.metrics.MetricsLevel;
              import software.amazon.kinesis.metrics.NullMetricsFactory;
              import software.amazon.kinesis.processor.ShardRecordProcessorFactory;

              import java.util.Date;
              import java.util.concurrent.Future;

              class Consumer {
                  Future<Boolean> start(AmazonKinesis kinesis, AmazonDynamoDB dynamo, Date from) {
                      KclWorker.Configuration config = new KclWorker.Configuration("app", "stream",
                              new DefaultAWSCredentialsProviderChain(), "worker-1")
                              .withInitialPositionInStream(InitialPositionInStream.AT_TIMESTAMP)
                              .withTimestampAtInitialPositionInStream(from)
                              .withMetricsLevel(MetricsLevel.NONE);
                      ShardRecordProcessorFactory factory = Aware::new;
                      KclWorker worker = new KclWorker.Builder()
                              .recordProcessorFactory(factory)
                              .config(config)
                              .kinesisClient(kinesis)
                              .dynamoDBClient(dynamo)
                              .metricsFactory(new NullMetricsFactory())
                              .build();
                      new Thread(worker).start();
                      return worker.startGracefulShutdown();
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/Consumer.java")
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void leavesProjectsImplementingKclInternals() {
            rewriteRun(
              //language=java
              java(
                """
                  import com.amazonaws.services.kinesis.metrics.interfaces.IMetricsFactory;
                  import com.amazonaws.services.kinesis.metrics.interfaces.IMetricsScope;
                  import com.amazonaws.services.kinesis.metrics.impl.NullMetricsScope;

                  class Metrics implements IMetricsFactory {
                      @Override
                      public IMetricsScope createMetrics() {
                          return new NullMetricsScope();
                      }
                  }
                  """
              )
            );
        }
    }
}

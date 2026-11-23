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
package org.openrewrite.azure.sdk;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateAzureEventHubsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.azure.sdk.MigrateAzureSdkLegacyToCurrent")
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "azure-eventhubs-3"));
    }

    @DocumentExample
    @Test
    void sendWithConnectionStringBuilder() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.eventhubs.ConnectionStringBuilder;
              import com.microsoft.azure.eventhubs.EventData;
              import com.microsoft.azure.eventhubs.EventHubClient;
              import com.microsoft.azure.eventhubs.EventHubException;

              import java.io.IOException;
              import java.nio.charset.StandardCharsets;
              import java.util.concurrent.Executors;
              import java.util.concurrent.ScheduledExecutorService;

              class Sender {
                  void send(String namespace, String hub, String keyName, String key, String body) throws EventHubException, IOException {
                      final ConnectionStringBuilder connStr = new ConnectionStringBuilder()
                              .setNamespaceName(namespace)
                              .setEventHubName(hub)
                              .setSasKeyName(keyName)
                              .setSasKey(key);
                      final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(4);
                      final EventHubClient ehClient = EventHubClient.createFromConnectionStringSync(connStr.toString(), executorService);
                      try {
                          EventData sendEvent = EventData.create(body.getBytes(StandardCharsets.UTF_8));
                          ehClient.sendSync(sendEvent);
                          ehClient.sendSync(sendEvent, "partition-key");
                      } finally {
                          ehClient.closeSync();
                          executorService.shutdown();
                      }
                  }
              }
              """,
            """
              import com.azure.core.amqp.exception.AmqpException;
              import com.azure.messaging.eventhubs.EventData;
              import com.azure.messaging.eventhubs.EventHubClientBuilder;
              import com.azure.messaging.eventhubs.EventHubProducerClient;
              import com.azure.messaging.eventhubs.models.SendOptions;

              import java.io.IOException;
              import java.nio.charset.StandardCharsets;
              import java.util.Collections;
              import java.util.concurrent.Executors;
              import java.util.concurrent.ScheduledExecutorService;

              class Sender {
                  void send(String namespace, String hub, String keyName, String key, String body) throws AmqpException, IOException {
                      final String connStr = "Endpoint=sb://" + namespace + ".servicebus.windows.net/;EntityPath=" + hub + ";SharedAccessKeyName=" + keyName + ";SharedAccessKey=" + key;
                      final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(4);
                      final EventHubProducerClient ehClient = new EventHubClientBuilder().connectionString(connStr).buildProducerClient();
                      try {
                          EventData sendEvent = new EventData(body.getBytes(StandardCharsets.UTF_8));
                          ehClient.send(Collections.singletonList(sendEvent));
                          ehClient.send(Collections.singletonList(sendEvent), new SendOptions().setPartitionKey("partition-key"));
                      } finally {
                          ehClient.close();
                          executorService.shutdown();
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void eventProperties() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.eventhubs.EventData;

              import java.time.Instant;

              class Events {
                  String describe(EventData event) {
                      String offset = event.getSystemProperties().getOffset();
                      long sequenceNumber = event.getSystemProperties().getSequenceNumber();
                      Instant enqueued = event.getSystemProperties().getEnqueuedTime();
                      return offset + sequenceNumber + enqueued + event.getProperties().get("type") + new String(event.getBytes());
                  }
              }
              """,
            """
              import com.azure.messaging.eventhubs.EventData;

              import java.time.Instant;

              class Events {
                  String describe(EventData event) {
                      String offset = event.getOffsetString();
                      long sequenceNumber = event.getSequenceNumber();
                      Instant enqueued = event.getEnqueuedTime();
                      return offset + sequenceNumber + enqueued + event.getProperties().get("type") + new String(event.getBody());
                  }
              }
              """
          )
        );
    }

    @Test
    void batchAndAsyncSendWhoseFutureIsIgnored() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.eventhubs.EventData;
              import com.microsoft.azure.eventhubs.EventDataBatch;
              import com.microsoft.azure.eventhubs.EventHubClient;
              import com.microsoft.azure.eventhubs.EventHubException;

              import java.nio.ByteBuffer;
              import java.util.List;

              class Batches {
                  int send(EventHubClient client, List<byte[]> bodies) throws EventHubException {
                      EventDataBatch batch = client.createBatch();
                      for (byte[] body : bodies) {
                          batch.tryAdd(EventData.create(body));
                      }
                      client.send(batch);
                      client.send(EventData.create(ByteBuffer.wrap(bodies.get(0))));
                      client.close();
                      return batch.getSize();
                  }
              }
              """,
            """
              import com.azure.core.amqp.exception.AmqpException;
              import com.azure.messaging.eventhubs.EventData;
              import com.azure.messaging.eventhubs.EventDataBatch;
              import com.azure.messaging.eventhubs.EventHubProducerClient;

              import java.nio.ByteBuffer;
              import java.util.Collections;
              import java.util.List;

              class Batches {
                  int send(EventHubProducerClient client, List<byte[]> bodies) throws AmqpException {
                      EventDataBatch batch = client.createBatch();
                      for (byte[] body : bodies) {
                          batch.tryAdd(new EventData(body));
                      }
                      client.send(batch);
                      client.send(Collections.singletonList(new EventData(ByteBuffer.wrap(bodies.get(0)))));
                      client.close();
                      return batch.getCount();
                  }
              }
              """
          )
        );
    }

    @Test
    void leavesCodeOnALibraryBuiltOnTheLegacySdkToAPerson() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "azure-eventhubs-3")
            //language=java
            .dependsOn(
              """
                package org.apache.spark.eventhubs;

                import com.microsoft.azure.eventhubs.EventData;

                import java.util.List;

                public class EventHubsUtils {
                    public static List<EventData> receive(String connectionString) {
                        return null;
                    }
                }
                """
            )),
          //language=java
          java(
            """
              import com.microsoft.azure.eventhubs.ConnectionStringBuilder;
              import com.microsoft.azure.eventhubs.EventData;
              import org.apache.spark.eventhubs.EventHubsUtils;

              import java.util.List;

              class Batches {
                  int size(String namespace, String hub) {
                      String connectionString = new ConnectionStringBuilder().setNamespaceName(namespace).setEventHubName(hub).toString();
                      List<EventData> events = EventHubsUtils.receive(connectionString);
                      return events.size();
                  }
              }
              """,
            """
              import com.microsoft.azure.eventhubs.ConnectionStringBuilder;
              import com.microsoft.azure.eventhubs.EventData;
              import org.apache.spark.eventhubs.EventHubsUtils;

              import java.util.List;

              class Batches {
                  int size(String namespace, String hub) {
                      String connectionString = /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.eventhubs.ConnectionStringBuilder.toString` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/eventhubs/azure-messaging-eventhubs/migration-guide.md)~~>*//*~~(Legacy Azure SDK: migrate `com.microsoft.azure.eventhubs.ConnectionStringBuilder.setEventHubName` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/eventhubs/azure-messaging-eventhubs/migration-guide.md)~~>*//*~~(Legacy Azure SDK: migrate `com.microsoft.azure.eventhubs.ConnectionStringBuilder.setNamespaceName` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/eventhubs/azure-messaging-eventhubs/migration-guide.md)~~>*//*~~(Legacy Azure SDK: migrate `com.microsoft.azure.eventhubs.ConnectionStringBuilder` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/eventhubs/azure-messaging-eventhubs/migration-guide.md)~~>*/new ConnectionStringBuilder().setNamespaceName(namespace).setEventHubName(hub).toString();
                      List<EventData> events = /*~~(Legacy Azure SDK: migrate `org.apache.spark.eventhubs.EventHubsUtils.receive` by hand, see https://github.com/Azure/azure-event-hubs-spark)~~>*/EventHubsUtils.receive(connectionString);
                      return events.size();
                  }
              }
              """
          )
        );
    }
}

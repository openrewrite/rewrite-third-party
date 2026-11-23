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
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.java.Assertions.mavenProject;

class MigrateAzureServiceBusTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.azure.sdk.MigrateAzureSdkLegacyToCurrent")
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "azure-servicebus-3"));
    }

    @DocumentExample
    @Test
    void sendToAQueue() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.servicebus.IMessage;
              import com.microsoft.azure.servicebus.Message;
              import com.microsoft.azure.servicebus.QueueClient;
              import com.microsoft.azure.servicebus.ReceiveMode;
              import com.microsoft.azure.servicebus.primitives.ConnectionStringBuilder;
              import com.microsoft.azure.servicebus.primitives.ServiceBusException;

              import java.time.Duration;

              class Sender {
                  QueueClient client(String namespace, String queue, String keyName, String key) {
                      ConnectionStringBuilder builder = new ConnectionStringBuilder(namespace, queue, keyName, key);
                      try {
                          return new QueueClient(builder, ReceiveMode.PEEKLOCK);
                      } catch (InterruptedException | ServiceBusException e) {
                          throw new RuntimeException("Failed to create the queue client", e);
                      }
                  }

                  void send(QueueClient client, String body) throws InterruptedException, ServiceBusException {
                      IMessage message = new Message(body, "application/json");
                      message.setLabel("order");
                      message.setTimeToLive(Duration.ofMinutes(5));
                      message.getProperties().put("tenant", "acme");
                      client.send(message);
                      client.sendAsync(new Message(body.getBytes()));
                      client.close();
                  }
              }
              """,
            """
              import com.azure.messaging.servicebus.ServiceBusClientBuilder;
              import com.azure.messaging.servicebus.ServiceBusException;
              import com.azure.messaging.servicebus.ServiceBusMessage;
              import com.azure.messaging.servicebus.ServiceBusSenderClient;

              import java.time.Duration;

              class Sender {
                  ServiceBusSenderClient client(String namespace, String queue, String keyName, String key) {
                      String builder = "Endpoint=sb://" + namespace + ".servicebus.windows.net/;SharedAccessKeyName=" + keyName + ";SharedAccessKey=" + key + ";EntityPath=" + queue;
                      try {
                          return new ServiceBusClientBuilder().connectionString(builder).sender().buildClient();
                      } catch (ServiceBusException e) {
                          throw new RuntimeException("Failed to create the queue client", e);
                      }
                  }

                  void send(ServiceBusSenderClient client, String body) throws InterruptedException, ServiceBusException {
                      ServiceBusMessage message = new ServiceBusMessage(body).setContentType("application/json");
                      message.setSubject("order");
                      message.setTimeToLive(Duration.ofMinutes(5));
                      message.getApplicationProperties().put("tenant", "acme");
                      client.sendMessage(message);
                      client.sendMessage(new ServiceBusMessage(body.getBytes()));
                      client.close();
                  }
              }
              """
          )
        );
    }

    @Test
    void sendToATopicAndSchedule() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.servicebus.ClientFactory;
              import com.microsoft.azure.servicebus.IMessageSender;
              import com.microsoft.azure.servicebus.Message;
              import com.microsoft.azure.servicebus.TopicClient;
              import com.microsoft.azure.servicebus.primitives.ConnectionStringBuilder;

              import java.time.Instant;
              import java.util.List;

              class Publisher {
                  void publish(String connectionString, String topic, List<Message> messages, Instant at) throws Exception {
                      TopicClient client = new TopicClient(new ConnectionStringBuilder(connectionString, topic));
                      client.sendBatch(messages);
                      long sequenceNumber = client.scheduleMessage(messages.get(0), at);
                      client.cancelScheduledMessage(sequenceNumber);
                      client.closeAsync();

                      IMessageSender sender = ClientFactory.createMessageSenderFromConnectionString(connectionString);
                      Message message = new Message("id-1", "hello", "text/plain");
                      message.setScheduledEnqueueTimeUtc(at);
                      sender.send(message);
                      System.out.println(sender.getEntityPath() + new String(message.getBody()) + message.getLabel());
                  }
              }
              """,
            """
              import com.azure.messaging.servicebus.ServiceBusClientBuilder;
              import com.azure.messaging.servicebus.ServiceBusMessage;
              import com.azure.messaging.servicebus.ServiceBusSenderClient;

              import java.time.Instant;
              import java.time.OffsetDateTime;
              import java.time.ZoneOffset;
              import java.util.List;

              class Publisher {
                  void publish(String connectionString, String topic, List<ServiceBusMessage> messages, Instant at) throws Exception {
                      ServiceBusSenderClient client = new ServiceBusClientBuilder().connectionString(connectionString.replaceFirst(";?$", ";EntityPath=") + topic).sender().buildClient();
                      client.sendMessages(messages);
                      long sequenceNumber = client.scheduleMessage(messages.get(0), OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
                      client.cancelScheduledMessage(sequenceNumber);
                      client.close();

                      ServiceBusSenderClient sender = new ServiceBusClientBuilder().connectionString(connectionString).sender().buildClient();
                      ServiceBusMessage message = new ServiceBusMessage("hello").setMessageId("id-1").setContentType("text/plain");
                      message.setScheduledEnqueueTime(OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
                      sender.sendMessage(message);
                      System.out.println(sender.getEntityPath() + new String(message.getBody().toBytes()) + message.getSubject());
                  }
              }
              """
          )
        );
    }

    @Test
    void marksAFutureThatIsRead() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.servicebus.IMessage;
              import com.microsoft.azure.servicebus.Message;
              import com.microsoft.azure.servicebus.QueueClient;

              import java.util.concurrent.CompletableFuture;

              class Producer {
                  CompletableFuture<Void> send(QueueClient client, String body) {
                      IMessage message = new Message(body);
                      return client.sendAsync(message);
                  }
              }
              """,
            """
              import com.microsoft.azure.servicebus.IMessage;
              import com.microsoft.azure.servicebus.Message;
              import com.microsoft.azure.servicebus.QueueClient;

              import java.util.concurrent.CompletableFuture;

              class Producer {
                  CompletableFuture<Void> send(QueueClient client, String body) {
                      IMessage message = new Message(body);
                      return /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.servicebus.QueueClient.sendAsync` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/servicebus/azure-messaging-servicebus/migration-guide.md)~~>*/client.sendAsync(message);
                  }
              }
              """
          )
        );
    }

    @Test
    void leavesAClassThatImplementsALegacyTypeToAPerson() {
        rewriteRun(
          mavenProject("app",
            srcMainJava(
              //language=java
              java(
                """
                  import com.microsoft.azure.servicebus.IMessage;
                  import com.microsoft.azure.servicebus.IMessageSender;
                  import com.microsoft.azure.servicebus.Message;

                  import java.util.ArrayList;
                  import java.util.List;

                  abstract class RecordingSender implements IMessageSender {
                      final List<IMessage> sent = new ArrayList<>();

                      @Override
                      public void send(IMessage message) {
                          sent.add(message);
                      }

                      void sendText(String body) {
                          send(new Message(body));
                      }
                  }
                  """,
                """
                  import com.microsoft.azure.servicebus.IMessage;
                  import com.microsoft.azure.servicebus.IMessageSender;
                  import com.microsoft.azure.servicebus.Message;

                  import java.util.ArrayList;
                  import java.util.List;

                  abstract class RecordingSender implements /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.servicebus.IMessageSender as a supertype` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/servicebus/azure-messaging-servicebus/migration-guide.md)~~>*/IMessageSender {
                      final List<IMessage> sent = new ArrayList<>();

                      @Override
                      public void send(IMessage message) {
                          sent.add(message);
                      }

                      void sendText(String body) {
                          send(/*~~(Legacy Azure SDK: migrate `com.microsoft.azure.servicebus.Message` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/servicebus/azure-messaging-servicebus/migration-guide.md)~~>*/new Message(body));
                      }
                  }
                  """
              )
            ),
            // The fake keeps using the legacy artifact, and nothing else needs a current one
            //language=xml
            pomXml(
              """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.microsoft.azure</groupId>
                      <artifactId>azure-servicebus</artifactId>
                      <version>3.6.7</version>
                    </dependency>
                  </dependencies>
                </project>
                """
            )
          )
        );
    }
}

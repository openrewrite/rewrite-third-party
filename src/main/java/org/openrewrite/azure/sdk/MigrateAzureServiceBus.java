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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;

import static org.openrewrite.azure.sdk.CallRewrite.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAzureServiceBus extends Recipe {

    static final String SB = "com.microsoft.azure.servicebus.";
    private static final String V7 = "com.azure.messaging.servicebus.";
    private static final String CSB = SB + "primitives.ConnectionStringBuilder";
    private static final String MESSAGE = SB + "IMessage";
    private static final String SENDER = SB + "IMessageSender";
    private static final String BUILD_SENDER = "new " + V7 + "ServiceBusClientBuilder().connectionString($0).sender().buildClient()";
    private static final String UTC = "java.time.OffsetDateTime.ofInstant($N, java.time.ZoneOffset.UTC)";

    @Getter
    final String displayName = "Migrate Azure Service Bus v3 to v7";

    @Getter
    final String description = "Rewrites sending messages with the legacy `com.microsoft.azure:azure-servicebus` client to " +
                               "`com.azure:azure-messaging-servicebus` 7.x: a `QueueClient`, `TopicClient` or " +
                               "`IMessageSender` becomes a `ServiceBusSenderClient`, a `Message` a `ServiceBusMessage`, " +
                               "and a `ConnectionStringBuilder` the connection string it would have built, with the entity " +
                               "path in it. Sending stays synchronous, and an asynchronous send whose future is ignored " +
                               "becomes a synchronous one. Receiving, message handlers and the other calls that change shape " +
                               "in v7 are left for `FindAzureSdkLegacyRemnants` to report.";

    static final List<CallRewrite> RULES = new ArrayList<>();

    static {
        List<CallRewrite> r = RULES;

        // Connection strings, which v7 reads the entity path from when a sender is built without a queue or topic name
        r.add(call(CSB + " <constructor>(String)", "$0"));
        r.add(call(CSB + " <constructor>(String, String)", "$0.replaceFirst(\";?$\", \";EntityPath=\") + $1"));
        r.add(call(CSB + " <constructor>(String, String, String, String)",
                "\"Endpoint=sb://\" + $0 + \".servicebus.windows.net/;SharedAccessKeyName=\" + $2 + \";SharedAccessKey=\" + $3 + \";EntityPath=\" + $1"));
        r.add(call(CSB + " <constructor>(java.net.URI, String, String, String)",
                "\"Endpoint=\" + $0 + \";SharedAccessKeyName=\" + $2 + \";SharedAccessKey=\" + $3 + \";EntityPath=\" + $1"));
        r.add(call(CSB + " <constructor>(String, String, String)",
                "\"Endpoint=sb://\" + $0 + \".servicebus.windows.net/;SharedAccessSignature=\" + $2 + \";EntityPath=\" + $1"));
        r.add(call(CSB + " <constructor>(java.net.URI, String, String)",
                "\"Endpoint=\" + $0 + \";SharedAccessSignature=\" + $2 + \";EntityPath=\" + $1"));
        r.add(call(CSB + " toString()", "$r"));

        // Senders
        r.add(call(SB + "QueueClient <constructor>(" + CSB + ", " + SB + "ReceiveMode)", BUILD_SENDER));
        r.add(call(SB + "TopicClient <constructor>(" + CSB + ")", BUILD_SENDER));
        r.add(call(SB + "ClientFactory createMessageSenderFromConnectionString(String)", BUILD_SENDER));
        r.add(call(SB + "ClientFactory createMessageSenderFromConnectionStringBuilder(" + CSB + ")", BUILD_SENDER));
        r.add(call(SENDER + " send(" + MESSAGE + ")", "$r.sendMessage($0)"));
        r.add(call(SENDER + " sendBatch(java.util.Collection)", "$r.sendMessages($0)"));
        r.add(call(SENDER + " sendAsync(" + MESSAGE + ")", "$r.sendMessage($0)").when(MigrateAzureServiceBus::futureIgnored));
        r.add(call(SENDER + " sendBatchAsync(java.util.Collection)", "$r.sendMessages($0)").when(MigrateAzureServiceBus::futureIgnored));
        r.add(call(SENDER + " scheduleMessage(" + MESSAGE + ", java.time.Instant)", "$r.scheduleMessage($0, " + utc(1) + ")"));
        r.add(call(SENDER + " cancelScheduledMessage(long)", "$r.cancelScheduledMessage($0)"));
        r.add(call(SB + ".* close()", "$r.close()").when(MigrateAzureServiceBus::onSender));
        r.add(call(SB + ".* closeAsync()", "$r.close()").when(all(MigrateAzureServiceBus::onSender, MigrateAzureServiceBus::futureIgnored)));
        r.add(call(SB + ".* getEntityPath()", "$r.getEntityPath()").when(MigrateAzureServiceBus::onSender));

        // Messages
        String message = "new " + V7 + "ServiceBusMessage";
        r.add(call(SB + "Message <constructor>(String)", message + "($0)"));
        r.add(call(SB + "Message <constructor>(byte[])", message + "($0)"));
        r.add(call(SB + "Message <constructor>(String, String)", message + "($0).setContentType($1)"));
        r.add(call(SB + "Message <constructor>(byte[], String)", message + "($0).setContentType($1)"));
        r.add(call(SB + "Message <constructor>(String, String, String)", message + "($1).setMessageId($0).setContentType($2)"));
        r.add(call(SB + "Message <constructor>(String, byte[], String)", message + "($1).setMessageId($0).setContentType($2)"));
        for (String property : new String[]{"MessageId", "ContentType", "SessionId", "CorrelationId", "To", "ReplyTo",
                "ReplyToSessionId", "PartitionKey", "TimeToLive"}) {
            r.add(call(MESSAGE + " get" + property + "()", "$r.get" + property + "()"));
            r.add(call(MESSAGE + " set" + property + "(..)", "$r.set" + property + "($0)"));
        }
        r.add(call(MESSAGE + " getLabel()", "$r.getSubject()"));
        r.add(call(MESSAGE + " setLabel(String)", "$r.setSubject($0)"));
        r.add(call(MESSAGE + " getProperties()", "$r.getApplicationProperties()"));
        r.add(call(MESSAGE + " setProperties(java.util.Map)", "$r.getApplicationProperties().putAll($0)"));
        r.add(call(MESSAGE + " getBody()", "$r.getBody().toBytes()"));
        r.add(call(MESSAGE + " getScheduledEnqueueTimeUtc()", "$r.getScheduledEnqueueTime().toInstant()"));
        r.add(call(MESSAGE + " setScheduledEnqueueTimeUtc(java.time.Instant)", "$r.setScheduledEnqueueTime(" + utc(0) + ")"));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>(SB + "*", false), new AzureCallRewriter(RULES));
    }

    private static String utc(int arg) {
        return UTC.replace("$N", "$" + arg);
    }

    /**
     * A future nobody reads, as a statement of its own, is the same as the synchronous call v7 makes.
     */
    private static boolean futureIgnored(Call call) {
        return call.cursor.getParentTreeCursor().getValue() instanceof J.Block;
    }

    /**
     * {@code close()} and {@code getEntityPath()} are declared for receivers too, which are left as they are.
     */
    private static boolean onSender(Call call) {
        return call.receiver != null && TypeUtils.isAssignableTo(SENDER, call.receiver.getType());
    }
}

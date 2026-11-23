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
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

import static org.openrewrite.azure.sdk.CallRewrite.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAzureEventHubs extends Recipe {

    static final String EH = "com.microsoft.azure.eventhubs.";
    private static final String V5 = "com.azure.messaging.eventhubs.";
    private static final String CSB = EH + "ConnectionStringBuilder";

    @Getter
    final String displayName = "Migrate Azure Event Hubs v3 to v5";

    @Getter
    final String description = "Rewrites sending events with the legacy `com.microsoft.azure:azure-eventhubs` client to " +
                               "`com.azure:azure-messaging-eventhubs` 5.x: an `EventHubClient` becomes an " +
                               "`EventHubProducerClient`, `EventData.create(..)` an `EventData`, and a " +
                               "`ConnectionStringBuilder` the connection string it would have built. Sending stays " +
                               "synchronous, and an asynchronous send whose future is ignored becomes a synchronous " +
                               "one. Receivers, the event processor host and the other calls that change shape in v5 " +
                               "are left for `FindAzureSdkLegacyRemnants` to report.";

    /**
     * The setters of a {@code ConnectionStringBuilder}, in the order its {@code toString()} writes their keys.
     */
    private static final Map<String, String> CONNECTION_STRING_KEYS = new LinkedHashMap<>();

    static {
        CONNECTION_STRING_KEYS.put("setEndpoint", "Endpoint");
        CONNECTION_STRING_KEYS.put("setNamespaceName", "Endpoint");
        CONNECTION_STRING_KEYS.put("setEventHubName", "EntityPath");
        CONNECTION_STRING_KEYS.put("setSasKeyName", "SharedAccessKeyName");
        CONNECTION_STRING_KEYS.put("setSasKey", "SharedAccessKey");
        CONNECTION_STRING_KEYS.put("setSharedAccessSignature", "SharedAccessSignature");
    }

    static final List<CallRewrite> RULES = Arrays.asList(
            // Connection strings
            call(CSB + " <constructor>(String)", "$0"),
            custom(CSB + " set*(..)", MigrateAzureEventHubs::connectionString),
            call(CSB + " toString()", "$r"),

            // Clients
            custom(EH + "EventHubClient createFromConnectionStringSync(String, ..)", MigrateAzureEventHubs::producer),
            custom(EH + "EventHubClient createSync(String, ..)", MigrateAzureEventHubs::producer),
            call(EH + "EventHubClient sendSync(" + EH + "EventData)", "$r.send(java.util.Collections.singletonList($0))"),
            call(EH + "EventHubClient sendSync(" + EH + "EventData, String)",
                    "$r.send(java.util.Collections.singletonList($0), new " + V5 + "models.SendOptions().setPartitionKey($1))"),
            call(EH + "EventHubClient sendSync(java.lang.Iterable, String)",
                    "$r.send($0, new " + V5 + "models.SendOptions().setPartitionKey($1))"),
            call(EH + "EventHubClient sendSync(..)", "$r.send($0)"),
            call(EH + "EventHubClient send(" + EH + "EventData)", "$r.send(java.util.Collections.singletonList($0))")
                    .when(MigrateAzureEventHubs::futureIgnored),
            call(EH + "EventHubClient send(" + EH + "EventData, String)",
                    "$r.send(java.util.Collections.singletonList($0), new " + V5 + "models.SendOptions().setPartitionKey($1))")
                    .when(MigrateAzureEventHubs::futureIgnored),
            call(EH + "EventHubClient send(java.lang.Iterable, String)",
                    "$r.send($0, new " + V5 + "models.SendOptions().setPartitionKey($1))")
                    .when(MigrateAzureEventHubs::futureIgnored),
            call(EH + "EventHubClient send(..)", "$r.send($0)").when(MigrateAzureEventHubs::futureIgnored),
            call(EH + "EventHubClient closeSync()", "$r.close()"),
            call(EH + "EventHubClient close()", "$r.close()").when(MigrateAzureEventHubs::futureIgnored),
            call(EH + "EventHubClient createBatch()", "$r.createBatch()"),
            call(EH + "EventHubClient getEventHubName()", "$r.getEventHubName()"),
            call(EH + "EventDataBatch tryAdd(" + EH + "EventData)", "$r.tryAdd($0)"),
            call(EH + "EventDataBatch getSize()", "$r.getCount()"),

            // Events
            call(EH + "EventData create(byte[])", "new " + V5 + "EventData($0)"),
            call(EH + "EventData create(java.nio.ByteBuffer)", "new " + V5 + "EventData($0)"),
            call(EH + "EventData create(byte[], int, int)",
                    "new " + V5 + "EventData(java.util.Arrays.copyOfRange($0, $1, $1 + $2))")
                    .when(call -> call.arg(1) instanceof J.Identifier || call.arg(1) instanceof J.Literal),
            call(EH + "EventData getBytes()", "$r.getBody()"),
            call(EH + "EventData getProperties()", "$r.getProperties()"),
            call(EH + "EventData getSystemProperties()", "$r.getSystemProperties()"),
            systemProperty("getOffset", "getOffsetString"),
            systemProperty("getSequenceNumber", "getSequenceNumber"),
            systemProperty("getEnqueuedTime", "getEnqueuedTime"),
            systemProperty("getPartitionKey", "getPartitionKey")
    );

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>(EH + "*", false), new AzureCallRewriter(RULES));
    }

    /**
     * A future nobody reads, as a statement of its own, is the same as the synchronous call v5 makes.
     */
    private static boolean futureIgnored(Call call) {
        return call.cursor.getParentTreeCursor().getValue() instanceof J.Block;
    }

    /**
     * A client created from a connection string, with at most the executor v5 has no use for.
     */
    private static @Nullable J producer(Call call, AzureCallRewriter rewriter, ExecutionContext ctx) {
        if (call.args.size() > 2 || (call.args.size() == 2 &&
                                     !TypeUtils.isAssignableTo("java.util.concurrent.ScheduledExecutorService", call.arg(1).getType()))) {
            return null;
        }
        return rewriter.template("new " + V5 + "EventHubClientBuilder().connectionString($0).buildProducerClient()", call, ctx);
    }

    /**
     * {@code event.getSystemProperties().getOffset()} and the like are properties of the event itself in v5.
     */
    private static CallRewrite systemProperty(String legacy, String current) {
        return custom(EH + "EventData.SystemProperties " + legacy + "()", (call, rewriter, ctx) -> {
            if (!(call.receiver instanceof J.MethodInvocation)) {
                return null;
            }
            J.MethodInvocation systemProperties = (J.MethodInvocation) call.receiver;
            if (!"getSystemProperties".equals(systemProperties.getSimpleName()) || systemProperties.getSelect() == null) {
                return null;
            }
            return MigrateAzureStorageBlob.apply(rewriter, call,
                    "#{event:any(" + V5 + "EventData)}." + current + "()",
                    Collections.singletonList(systemProperties.getSelect()), ctx);
        });
    }

    /**
     * {@code new ConnectionStringBuilder().setNamespaceName(ns).setEventHubName(hub)...}, as one chain, becomes the
     * connection string its {@code toString()} would have returned. The setters inside the chain wait for the last.
     */
    private static @Nullable J connectionString(Call call, AzureCallRewriter rewriter, ExecutionContext ctx) {
        Object parent = call.cursor.getParentTreeCursor().getValue();
        if (parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() == call.tree &&
            isConnectionStringSetter((J.MethodInvocation) parent)) {
            return null;
        }

        Map<String, List<Expression>> set = new HashMap<>();
        Expression e = (Expression) call.tree;
        while (e instanceof J.MethodInvocation && isConnectionStringSetter((J.MethodInvocation) e)) {
            J.MethodInvocation setter = (J.MethodInvocation) e;
            String key = CONNECTION_STRING_KEYS.get(setter.getSimpleName());
            if (key == null) {
                return null;
            }
            // The last call wins, and this walks the chain from its end
            set.putIfAbsent(key, setter.getArguments());
            e = setter.getSelect();
        }
        if (!(e instanceof J.NewClass) || !noArguments((J.NewClass) e) || !set.containsKey("Endpoint")) {
            return null;
        }

        StringBuilder code = new StringBuilder();
        List<Expression> params = new ArrayList<>();
        String separator = "";
        for (String key : new LinkedHashSet<>(CONNECTION_STRING_KEYS.values())) {
            List<Expression> args = set.get(key);
            if (args == null) {
                continue;
            }
            code.append(code.length() == 0 ? "\"" : " + \"").append(separator).append(key).append('=');
            if ("Endpoint".equals(key)) {
                if (args.size() == 1 && TypeUtils.isOfClassType(args.get(0).getType(), "java.lang.String")) {
                    // setNamespaceName(namespace)
                    code.append("sb://\" + #{p").append(params.size()).append(":any(java.lang.String)} + \".servicebus.windows.net/");
                    params.add(args.get(0));
                } else if (args.size() == 1) {
                    // setEndpoint(URI)
                    code.append("\" + #{p").append(params.size()).append(":any(java.net.URI)} + \"");
                    params.add(args.get(0));
                } else {
                    // setEndpoint(namespace, domain)
                    code.append("sb://\" + #{p").append(params.size()).append(":any(java.lang.String)} + \".\" + #{p")
                            .append(params.size() + 1).append(":any(java.lang.String)} + \"");
                    params.add(args.get(0));
                    params.add(args.get(1));
                }
                code.append('"');
            } else {
                code.append("\" + #{p").append(params.size()).append(":any(java.lang.String)}");
                params.add(args.get(0));
            }
            separator = ";";
        }
        // Drop the empty literal after a URI endpoint, and join the literals that meet
        String template = code.toString().replace(" + \"\"", "").replace("\" + \"", "");
        return MigrateAzureStorageBlob.apply(rewriter, call, template, params, ctx);
    }

    private static boolean isConnectionStringSetter(J.MethodInvocation m) {
        JavaType.Method type = m.getMethodType();
        return type != null && TypeUtils.isOfClassType(type.getDeclaringType(), CSB) &&
               m.getSimpleName().startsWith("set");
    }

    private static boolean noArguments(J.NewClass n) {
        return n.getArguments().isEmpty() || (n.getArguments().size() == 1 && n.getArguments().get(0) instanceof J.Empty);
    }
}

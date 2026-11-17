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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.util.Collection;
import java.util.Collections;

/**
 * v2 replaces the buffered client with {@code SqsAsyncClient.batchManager()}, which only offers the four buffered operations.
 * A generated {@code BufferedSqsAsyncClient.wrap(client)} returns a full {@code SqsAsyncClient} that routes those four through
 * the batch manager and everything else to the client, as v1's buffered client did.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateSqsBufferedClient extends ScanningRecipe<ProjectShims> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    static final String V1_BUFFERED = "com.amazonaws.services.sqs.buffered.AmazonSQSBufferedAsyncClient";
    static final String SHIM = "BufferedSqsAsyncClient";
    static final String V1_CONFIG = "com.amazonaws.services.sqs.buffered.QueueBufferConfig";
    static final String CONFIG = "QueueBufferConfig";
    private static final MethodMatcher NEW_BUFFERED = new MethodMatcher(V1_BUFFERED + " <constructor>(..)");

    // type, property, default, v1 getter prefix; each also gets setX and withX
    private static final String[][] CONFIG_PROPERTIES = {
            {"long", "MaxBatchOpenMs", "200", "get"},
            {"boolean", "LongPoll", "true", "is"},
            {"int", "MaxInflightOutboundBatches", "5", "get"},
            {"int", "MaxInflightReceiveBatches", "10", "get"},
            {"int", "MaxDoneReceiveBatches", "10", "get"},
            {"int", "MaxBatchSize", "10", "get"},
            {"long", "MaxBatchSizeBytes", "262144", "get"},
            {"int", "VisibilityTimeoutSeconds", "-1", "get"},
            {"int", "LongPollWaitTimeoutSeconds", "20", "get"},
            {"int", "MinReceiveWaitTimeMs", "50", "get"},
            {"boolean", "DeleteInBackground", "false", "is"},
            {"boolean", "AdaptivePrefetching", "false", "is"},
            {"boolean", "FlushOnShutdown", "false", "is"},
            {"List<String>", "ReceiveAttributeNames", "new ArrayList<>()", "get"},
            {"List<String>", "ReceiveMessageAttributeNames", "new ArrayList<>()", "get"}
    };

    @Getter
    final String displayName = "Migrate AWS SDK v1 `AmazonSQSBufferedAsyncClient`";

    @Getter
    final String description = "Replaces `new AmazonSQSBufferedAsyncClient(client)` with a generated `BufferedSqsAsyncClient.wrap(client)`, " +
                               "a v2 `SqsAsyncClient` whose send, delete, change-visibility and receive calls go through v2's batch manager " +
                               "(`client.batchManager()`). Other references to the buffered type become `AmazonSQSAsync`, which v2-migration " +
                               "turns into `SqsAsyncClient`. `QueueBufferConfig` becomes the generated class's nested `QueueBufferConfig`, " +
                               "with v1's accessors and defaults, whose batch size, send frequency, visibility timeout, minimum receive wait " +
                               "and attribute names configure the batch manager.";

    @Override
    public ProjectShims getInitialValue(ExecutionContext ctx) {
        return new ProjectShims();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(ProjectShims acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    acc.recordExisting((SourceFile) tree);
                }
                if (tree instanceof J.CompilationUnit) {
                    boolean[] constructs = {false};
                    new JavaVisitor<Integer>() {
                        @Override
                        public J visitNewClass(J.NewClass newClass, Integer p) {
                            constructs[0] |= NEW_BUFFERED.matches(newClass) || TypeUtils.isOfClassType(newClass.getType(), V1_CONFIG);
                            return super.visitNewClass(newClass, p);
                        }
                    }.visit(tree, 0);
                    if (constructs[0]) {
                        acc.require((J.CompilationUnit) tree);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(ProjectShims acc, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        return acc.generate(SHIM, MigrateSqsBufferedClient::source, ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(ProjectShims acc) {
        return JavaOnly.check(new UsesType<>("com.amazonaws.services.sqs.buffered.*", false), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                J c = super.visitCompilationUnit(cu, ctx);
                doAfterVisit(new ChangeType(V1_BUFFERED, "com.amazonaws.services.sqs.AmazonSQSAsync", true).getVisitor());
                String shim = acc.fqn(cu, SHIM);
                if (shim != null) {
                    doAfterVisit(new ChangeType(V1_CONFIG, shim + "$" + CONFIG, true).getVisitor());
                }
                return c;
            }

            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J j = super.visitNewClass(newClass, ctx);
                if (!(j instanceof J.NewClass) || !NEW_BUFFERED.matches((J.NewClass) j)) {
                    return j;
                }
                J.NewClass n = (J.NewClass) j;
                String shim = acc.fqn(getCursor().firstEnclosingOrThrow(J.CompilationUnit.class), SHIM);
                if (shim == null) {
                    return n;
                }
                boolean withConfig = n.getArguments().size() == 2 && TypeUtils.isOfClassType(n.getArguments().get(1).getType(), V1_CONFIG);
                if (n.getArguments().size() > 1 && !withConfig) {
                    return n;
                }
                String simple = shim.substring(shim.lastIndexOf('.') + 1);
                String pkg = shim.contains(".") ? shim.substring(0, shim.lastIndexOf('.')) : "";
                maybeAddImport(shim);
                return JavaTemplate.builder(simple + ".wrap(#{any()}" + (withConfig ? ", #{any()}" : "") + ")")
                        .imports(shim)
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(
                                "package software.amazon.awssdk.services.sqs; public interface SqsAsyncClient {}",
                                ProjectShims.packageLine(pkg) + "public final class " + SHIM + " {\n" +
                                "    public static software.amazon.awssdk.services.sqs.SqsAsyncClient wrap(software.amazon.awssdk.services.sqs.SqsAsyncClient c) { return c; }\n" +
                                "    public static software.amazon.awssdk.services.sqs.SqsAsyncClient wrap(software.amazon.awssdk.services.sqs.SqsAsyncClient c, Object config) { return c; }\n" +
                                "}"))
                        .build()
                        .apply(getCursor(), n.getCoordinates().replace(), n.getArguments().toArray());
            }
        });
    }

    static String source(String pkg) {
        StringBuilder s = new StringBuilder(ProjectShims.packageLine(pkg));
        s.append("import software.amazon.awssdk.services.sqs.SqsAsyncClient;\n" +
                 "import software.amazon.awssdk.services.sqs.batchmanager.BatchOverrideConfiguration;\n" +
                 "import software.amazon.awssdk.services.sqs.batchmanager.SqsAsyncBatchManager;\n" +
                 "import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;\n" +
                 "import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;\n" +
                 "import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;\n" +
                 "import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;\n" +
                 "import software.amazon.awssdk.services.sqs.model.SendMessageRequest;\n" +
                 "\n" +
                 "import java.lang.reflect.InvocationTargetException;\n" +
                 "import java.lang.reflect.Proxy;\n" +
                 "import java.time.Duration;\n" +
                 "import java.util.ArrayList;\n" +
                 "import java.util.List;\n" +
                 "import java.util.concurrent.Executors;\n" +
                 "import java.util.concurrent.ScheduledExecutorService;\n" +
                 "\n" +
                 "/**\n" +
                 " * Replacement for AWS SDK v1's {@code AmazonSQSBufferedAsyncClient}: an {@code SqsAsyncClient} that buffers sends, deletes,\n" +
                 " * visibility changes and receives through SDK v2's batch manager and passes every other call to the wrapped client.\n" +
                 " */\n" +
                 "public final class " + SHIM + " {\n" +
                 "\n" +
                 "    private " + SHIM + "() {\n" +
                 "    }\n" +
                 "\n" +
                 "    public static SqsAsyncClient wrap(SqsAsyncClient client) {\n" +
                 "        return wrap(client, client.batchManager(), null);\n" +
                 "    }\n" +
                 "\n" +
                 "    public static SqsAsyncClient wrap(SqsAsyncClient client, " + CONFIG + " config) {\n" +
                 "        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {\n" +
                 "            Thread t = new Thread(r, \"sqs-batch-manager\");\n" +
                 "            t.setDaemon(true);\n" +
                 "            return t;\n" +
                 "        });\n" +
                 "        SqsAsyncBatchManager batchManager = SqsAsyncBatchManager.builder()\n" +
                 "                .client(client)\n" +
                 "                .scheduledExecutor(executor)\n" +
                 "                .overrideConfiguration(config.toBatchOverrideConfiguration())\n" +
                 "                .build();\n" +
                 "        return wrap(client, batchManager, executor);\n" +
                 "    }\n" +
                 "\n" +
                 "    private static SqsAsyncClient wrap(SqsAsyncClient client, SqsAsyncBatchManager batchManager, ScheduledExecutorService executor) {\n" +
                 "        return (SqsAsyncClient) Proxy.newProxyInstance(SqsAsyncClient.class.getClassLoader(),\n" +
                 "                new Class<?>[]{SqsAsyncClient.class}, (proxy, method, args) -> {\n" +
                 "                    Object request = args != null && args.length == 1 ? args[0] : null;\n" +
                 "                    if (request instanceof SendMessageRequest) {\n" +
                 "                        return batchManager.sendMessage((SendMessageRequest) request);\n" +
                 "                    }\n" +
                 "                    if (request instanceof DeleteMessageRequest) {\n" +
                 "                        return batchManager.deleteMessage((DeleteMessageRequest) request);\n" +
                 "                    }\n" +
                 "                    if (request instanceof ChangeMessageVisibilityRequest) {\n" +
                 "                        return batchManager.changeMessageVisibility((ChangeMessageVisibilityRequest) request);\n" +
                 "                    }\n" +
                 "                    if (request instanceof ReceiveMessageRequest) {\n" +
                 "                        return batchManager.receiveMessage((ReceiveMessageRequest) request);\n" +
                 "                    }\n" +
                 "                    if (\"close\".equals(method.getName()) && args == null) {\n" +
                 "                        batchManager.close();\n" +
                 "                        if (executor != null) {\n" +
                 "                            executor.shutdown();\n" +
                 "                        }\n" +
                 "                    }\n" +
                 "                    try {\n" +
                 "                        return method.invoke(client, args);\n" +
                 "                    } catch (InvocationTargetException e) {\n" +
                 "                        throw e.getCause();\n" +
                 "                    }\n" +
                 "                });\n" +
                 "    }\n" +
                 "\n" +
                 "    /**\n" +
                 "     * AWS SDK v1's {@code QueueBufferConfig} with its defaults. The batch manager has no settings for long polling,\n" +
                 "     * in-flight batch limits, prefetching or the batch byte limit, so those are kept but have no effect.\n" +
                 "     */\n" +
                 "    public static class " + CONFIG + " {\n\n");
        for (String[] p : CONFIG_PROPERTIES) {
            s.append("        private ").append(p[0]).append(' ').append(field(p[1])).append(" = ").append(p[2]).append(";\n");
        }
        s.append("\n        public " + CONFIG + "() {\n" +
                 "        }\n\n");
        for (String[] p : CONFIG_PROPERTIES) {
            String f = field(p[1]);
            s.append("        public ").append(p[0]).append(' ').append(p[3]).append(p[1]).append("() {\n")
                    .append("            return ").append(f).append(";\n")
                    .append("        }\n\n")
                    .append("        public void set").append(p[1]).append('(').append(p[0]).append(' ').append(f).append(") {\n")
                    .append("            this.").append(f).append(" = ").append(f).append(";\n")
                    .append("        }\n\n")
                    .append("        public ").append(CONFIG).append(" with").append(p[1]).append('(').append(p[0]).append(' ').append(f).append(") {\n")
                    .append("            this.").append(f).append(" = ").append(f).append(";\n")
                    .append("            return this;\n")
                    .append("        }\n\n");
        }
        s.append("        BatchOverrideConfiguration toBatchOverrideConfiguration() {\n" +
                 "            BatchOverrideConfiguration.Builder builder = BatchOverrideConfiguration.builder()\n" +
                 "                    .maxBatchSize(maxBatchSize)\n" +
                 "                    .sendRequestFrequency(Duration.ofMillis(maxBatchOpenMs))\n" +
                 "                    .receiveMessageMinWaitDuration(Duration.ofMillis(minReceiveWaitTimeMs))\n" +
                 "                    .receiveMessageAttributeNames(receiveMessageAttributeNames);\n" +
                 "            if (visibilityTimeoutSeconds > 0) {\n" +
                 "                builder.receiveMessageVisibilityTimeout(Duration.ofSeconds(visibilityTimeoutSeconds));\n" +
                 "            }\n" +
                 "            List<MessageSystemAttributeName> systemAttributes = new ArrayList<>();\n" +
                 "            for (String name : receiveAttributeNames) {\n" +
                 "                systemAttributes.add(MessageSystemAttributeName.fromValue(name));\n" +
                 "            }\n" +
                 "            return builder.receiveMessageSystemAttributeNames(systemAttributes).build();\n" +
                 "        }\n" +
                 "    }\n" +
                 "}\n");
        return s.toString();
    }

    private static String field(String property) {
        return Character.toLowerCase(property.charAt(0)) + property.substring(1);
    }
}

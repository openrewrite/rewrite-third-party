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
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.*;
import org.openrewrite.java.dependencies.ChangeDependency;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateKinesisClientLibraryToV2 extends ScanningRecipe<MigrateKinesisClientLibraryToV2.Accumulator> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    static final String KCL = "com.amazonaws.services.kinesis.clientlibrary.";
    private static final String METRICS = "com.amazonaws.services.kinesis.metrics.";
    private static final String RECORD = "com.amazonaws.services.kinesis.model.Record";
    private static final String V2_RECORD = "software.amazon.kinesis.retrieval.KinesisClientRecord";
    private static final String V2_CHECKPOINTER = "software.amazon.kinesis.processor.RecordProcessorCheckpointer";
    static final String PROCESSOR_SHIM = "KclRecordProcessor";
    static final String WORKER_SHIM = "KclWorker";
    static final String KCL_V2_VERSION = "2.7.x";
    private static final Function<ExecutionContext, JavaParser.Builder<?, ?>> KCL_V2_PARSER = ctx -> JavaParser.fromJavaVersion()
            .classpathFromResources(ctx, "amazon-kinesis-client-2", "kinesis-2", "sdk-core-2", "utils-2");
    // Matched after the type change, since v2's checkpointer takes an SDK `Record` rather than the `KinesisClientRecord` processors get
    private static final MethodMatcher CHECKPOINT_RECORD = new MethodMatcher(V2_CHECKPOINTER + " *(" + V2_RECORD + ")");
    private static final MethodMatcher ARRIVAL_TIMESTAMP = new MethodMatcher(V2_RECORD + " getApproximateArrivalTimestamp()");
    // KCL 2.x hands processors records that are already deaggregated
    private static final MethodMatcher DEAGGREGATE = new MethodMatcher(KCL + "types.UserRecord deaggregate(java.util.List)");
    private static final Set<String> IMPLEMENTABLE = new HashSet<>(Arrays.asList(
            KCL + "interfaces.IRecordProcessor", KCL + "interfaces.IRecordProcessorFactory",
            KCL + "interfaces.v2.IRecordProcessor", KCL + "interfaces.v2.IRecordProcessorFactory",
            KCL + "interfaces.v2.IShutdownNotificationAware"));
    // Parameters after the factory and configuration of the v1 `Worker` constructors that `KclWorker` mirrors
    private static final Set<List<String>> WORKER_CONSTRUCTORS = new HashSet<>(Arrays.asList(
            emptyList(),
            singletonList("java.util.concurrent.ExecutorService"),
            singletonList(METRICS + "interfaces.IMetricsFactory"),
            Arrays.asList(METRICS + "interfaces.IMetricsFactory", "java.util.concurrent.ExecutorService"),
            Arrays.asList("kinesis", "dynamodb", "cloudwatch"),
            Arrays.asList("kinesis", "dynamodb", "cloudwatch", "java.util.concurrent.ExecutorService"),
            Arrays.asList("kinesis", "dynamodb", METRICS + "interfaces.IMetricsFactory", "java.util.concurrent.ExecutorService")));

    private static final Map<String, String> TYPES = new LinkedHashMap<>();
    private static final Map<String, Set<String>> METHODS = new HashMap<>();
    private static final String[][] RENAMES = {
            {KCL + "types.InitializationInput getShardId()", "shardId"},
            {KCL + "types.InitializationInput getExtendedSequenceNumber()", "extendedSequenceNumber"},
            {KCL + "types.InitializationInput getPendingCheckpointSequenceNumber()", "pendingCheckpointSequenceNumber"},
            {KCL + "types.ProcessRecordsInput getRecords()", "records"},
            {KCL + "types.ProcessRecordsInput getCheckpointer()", "checkpointer"},
            {KCL + "types.ProcessRecordsInput getMillisBehindLatest()", "millisBehindLatest"},
            {KCL + "types.ProcessRecordsInput getCacheEntryTime()", "cacheEntryTime"},
            {KCL + "types.ProcessRecordsInput getCacheExitTime()", "cacheExitTime"},
            {RECORD + " getData()", "data"},
            {RECORD + " getPartitionKey()", "partitionKey"},
            {RECORD + " getSequenceNumber()", "sequenceNumber"},
            {KCL + "types.UserRecord getSubSequenceNumber()", "subSequenceNumber"},
            {KCL + "types.UserRecord getExplicitHashKey()", "explicitHashKey"},
            {KCL + "types.ExtendedSequenceNumber getSequenceNumber()", "sequenceNumber"},
            {KCL + "types.ExtendedSequenceNumber getSubSequenceNumber()", "subSequenceNumber"},
            {KCL + "interfaces.IPreparedCheckpointer getPendingCheckpoint()", "pendingCheckpoint"},
            {KCL + "interfaces.v2.IRecordProcessorFactory createProcessor()", "shardRecordProcessor"},
            {KCL + "interfaces.IRecordProcessorFactory createProcessor()", "shardRecordProcessor"},
    };

    static {
        TYPES.put(KCL + "interfaces.v2.IRecordProcessorFactory", "software.amazon.kinesis.processor.ShardRecordProcessorFactory");
        TYPES.put(KCL + "interfaces.IRecordProcessorFactory", "software.amazon.kinesis.processor.ShardRecordProcessorFactory");
        TYPES.put(KCL + "types.UserRecord", V2_RECORD);
        TYPES.put(METRICS + "interfaces.IMetricsFactory", "software.amazon.kinesis.metrics.MetricsFactory");
        TYPES.put(METRICS + "impl.NullMetricsFactory", "software.amazon.kinesis.metrics.NullMetricsFactory");
        TYPES.put(METRICS + "impl.LogMetricsFactory", "software.amazon.kinesis.metrics.LogMetricsFactory");
        TYPES.put(METRICS + "interfaces.MetricsLevel", "software.amazon.kinesis.metrics.MetricsLevel");
        TYPES.put(KCL + "interfaces.IRecordProcessorCheckpointer", V2_CHECKPOINTER);
        TYPES.put(KCL + "interfaces.IPreparedCheckpointer", "software.amazon.kinesis.processor.PreparedCheckpointer");
        TYPES.put(KCL + "types.InitializationInput", "software.amazon.kinesis.lifecycle.events.InitializationInput");
        TYPES.put(KCL + "types.ProcessRecordsInput", "software.amazon.kinesis.lifecycle.events.ProcessRecordsInput");
        TYPES.put(KCL + "types.ExtendedSequenceNumber", "software.amazon.kinesis.retrieval.kpl.ExtendedSequenceNumber");
        TYPES.put(KCL + "lib.worker.InitialPositionInStream", "software.amazon.kinesis.common.InitialPositionInStream");
        TYPES.put(KCL + "lib.worker.ShutdownReason", "software.amazon.kinesis.lifecycle.ShutdownReason");
        TYPES.put(RECORD, V2_RECORD);

        methods("interfaces.v2.IRecordProcessor", "initialize", "processRecords", "shutdown");
        methods("interfaces.v2.IRecordProcessorFactory", "createProcessor");
        methods("interfaces.IRecordProcessor", "initialize", "processRecords", "shutdown");
        methods("interfaces.IRecordProcessorFactory", "createProcessor");
        methods("interfaces.v2.IShutdownNotificationAware", "shutdownRequested");
        methods("types.UserRecord", "deaggregate", "getSubSequenceNumber", "getExplicitHashKey");
        methods("interfaces.IRecordProcessorCheckpointer", "checkpoint", "prepareCheckpoint");
        methods("interfaces.IPreparedCheckpointer", "checkpoint", "getPendingCheckpoint");
        methods("types.InitializationInput", "getShardId", "getExtendedSequenceNumber", "getPendingCheckpointSequenceNumber");
        methods("types.ProcessRecordsInput", "getRecords", "getCheckpointer", "getMillisBehindLatest", "getCacheEntryTime",
                "getCacheExitTime", "isAtShardEnd");
        methods("types.ShutdownInput", "getShutdownReason", "getCheckpointer");
        methods("types.ExtendedSequenceNumber", "<constructor>", "getSequenceNumber", "getSubSequenceNumber", "isShardEnd",
                "compareTo", "equals", "hashCode", "toString");
        methods("lib.worker.Worker", "<constructor>", "run", "shutdown", "startGracefulShutdown", "requestShutdown",
                "hasGracefulShutdownStarted");
        methods("lib.worker.Worker$Builder", "<constructor>", "recordProcessorFactory", "config", "kinesisClient",
                "dynamoDBClient", "cloudWatchClient", "metricsFactory", "execService", "build");
        methods("lib.worker.KinesisClientLibConfiguration", "<constructor>", "getApplicationName", "getStreamName",
                "getWorkerIdentifier", "getRegionName", "getTableName", "getKinesisEndpoint", "getDynamoDBEndpoint",
                "getInitialPositionInStream", "getKinesisCredentialsProvider", "withRegionName", "withKinesisEndpoint",
                "withDynamoDBEndpoint", "withTableName", "withInitialPositionInStream",
                "withTimestampAtInitialPositionInStream", "withMaxRecords", "withIdleTimeBetweenReadsInMillis",
                "withFailoverTimeMillis", "withCallProcessRecordsEvenForEmptyRecordList", "withParentShardPollIntervalMillis",
                "withSkipShardSyncAtStartupIfLeasesExist", "withShardSyncIntervalMillis",
                "withCleanupLeasesUponShardCompletion", "withMaxLeasesForWorker", "withMaxLeasesToStealAtOneTime",
                "withInitialLeaseTableReadCapacity", "withInitialLeaseTableWriteCapacity", "withTaskBackoffTimeMillis",
                "withMetricsLevel", "withMetricsBufferTimeMillis", "withMetricsMaxQueueSize");
        methods("lib.worker.InitialPositionInStream", "values", "valueOf", "name", "ordinal", "equals", "toString");
        methods("lib.worker.ShutdownReason", "values", "valueOf", "name", "ordinal", "equals", "toString");
        METHODS.put(METRICS + "interfaces.IMetricsFactory", emptySet());
        METHODS.put(METRICS + "impl.NullMetricsFactory", singleton("<constructor>"));
        METHODS.put(METRICS + "impl.LogMetricsFactory", singleton("<constructor>"));
        METHODS.put(METRICS + "interfaces.MetricsLevel", new HashSet<>(Arrays.asList("values", "valueOf", "fromName",
                "getName", "getValue", "name", "ordinal", "equals", "toString")));
    }

    private static void methods(String type, String... names) {
        METHODS.put(KCL + type, new HashSet<>(Arrays.asList(names)));
    }

    @Getter
    final String displayName = "Port Kinesis Client Library 1.x consumers to KCL 2.x";

    @Getter
    final String description = "Moves record processors, their factories, checkpointers and the `Worker` of Kinesis Client Library " +
            "1.x to KCL 2.x (`software.amazon.kinesis:amazon-kinesis-client`). A generated `KclRecordProcessor` keeps both of " +
            "v1's processor interfaces and their shutdown callbacks over v2's lease-lost, shard-ended and shutdown-requested " +
            "callbacks, and a generated `KclWorker` builds a `Scheduler` from v1's configuration, clients, metrics factory and " +
            "executor with polling retrieval, as in v1. Projects that " +
            "use KCL 1.x internals with no KCL 2.x counterpart (lease managers, Kinesis proxies, custom metrics scopes, the " +
            "MultiLang daemon) or Kotlin are left for `MarkKinesisClientLibraryV1` to mark.";

    public static class Accumulator implements AwsMigrationScope.Limitable {
        final ProjectShims shims = new ProjectShims();

        @Override
        public void limitTo(AwsMigrationScope scope) {
            shims.limitTo(scope);
        }
        final Map<Path, String> kclSources = new ConcurrentHashMap<>();
        final Set<String> excludedProjects = ConcurrentHashMap.newKeySet();
        final Set<Path> portedSources = ConcurrentHashMap.newKeySet();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    static boolean isKcl(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq != null && fq.getFullyQualifiedName().startsWith(KCL);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    acc.shims.recordExisting((SourceFile) tree);
                }
                if (!(tree instanceof J.CompilationUnit) || !MarkKinesisClientLibraryV1.usesKcl((J.CompilationUnit) tree)) {
                    return tree;
                }
                J.CompilationUnit cu = (J.CompilationUnit) tree;
                String project = ProjectShims.projectName(cu);
                acc.kclSources.put(cu.getSourcePath(), project);
                if (!cu.getSourcePath().toString().endsWith(".java")) {
                    acc.excludedProjects.add(project);
                    return tree;
                }
                if (!portable(cu)) {
                    acc.excludedProjects.add(project);
                }
                acc.shims.require(cu);
                return tree;
            }
        };
    }

    private static boolean supported(String fqn) {
        return fqn.startsWith(KCL + "exceptions.") || METHODS.containsKey(fqn) || TYPES.containsKey(fqn);
    }

    private static boolean portable(J.CompilationUnit cu) {
        for (JavaType type : cu.getTypesInUse().getTypesInUse()) {
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
            if (fq != null && AwsSdkV2Coordinates.isKclV1Type(fq.getFullyQualifiedName()) && !supported(fq.getFullyQualifiedName())) {
                return false;
            }
        }
        for (JavaType.Method m : cu.getTypesInUse().getUsedMethods()) {
            String declaring = m.getDeclaringType().getFullyQualifiedName();
            String name = m.isConstructor() ? "<constructor>" : m.getName();
            if (AwsSdkV2Coordinates.isKclV1Type(declaring) && !declaring.startsWith(KCL + "exceptions.") &&
                !METHODS.getOrDefault(declaring, emptySet()).contains(name)) {
                return false;
            }
            if (RECORD.equals(declaring) && !"getData".equals(name) && !"getPartitionKey".equals(name) &&
                !"getSequenceNumber".equals(name) && !"getApproximateArrivalTimestamp".equals(name)) {
                return false;
            }
            if ((KCL + "types.UserRecord").equals(declaring) && "deaggregate".equals(name) && m.getParameterTypes().size() != 1 ||
                (KCL + "types.UserRecord").equals(declaring) && m.isConstructor()) {
                return false;
            }
            if (m.isConstructor() && declaring.equals(KCL + "lib.worker.KinesisClientLibConfiguration") &&
                m.getParameterTypes().size() != 4 && m.getParameterTypes().size() != 6) {
                return false;
            }
            if (m.isConstructor() && declaring.equals(KCL + "lib.worker.Worker") && !WORKER_CONSTRUCTORS.contains(workerExtras(m))) {
                return false;
            }
        }
        // Implementing other KCL 1.x interfaces (metrics scopes, lease managers, proxies) has no KCL 2.x counterpart
        return !new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, AtomicBoolean unportable) {
                JavaType.FullyQualified t = classDecl.getType();
                if (t != null) {
                    List<JavaType.FullyQualified> supertypes = new ArrayList<>(t.getInterfaces());
                    if (t.getSupertype() != null) {
                        supertypes.add(t.getSupertype());
                    }
                    for (JavaType.FullyQualified st : supertypes) {
                        if (AwsSdkV2Coordinates.isKclV1Type(st.getFullyQualifiedName()) && !IMPLEMENTABLE.contains(st.getFullyQualifiedName())) {
                            unportable.set(true);
                        }
                    }
                }
                return super.visitClassDeclaration(classDecl, unportable);
            }
        }.reduce(cu, new AtomicBoolean()).get();
    }

    private static List<String> workerExtras(JavaType.Method constructor) {
        List<JavaType> params = constructor.getParameterTypes();
        if (params.size() < 2 || params.get(0) instanceof JavaType.FullyQualified &&
                                 !((JavaType.FullyQualified) params.get(0)).getFullyQualifiedName().endsWith("IRecordProcessorFactory")) {
            return singletonList("unsupported");
        }
        List<String> extras = new ArrayList<>();
        for (JavaType p : params.subList(2, params.size())) {
            String fqn = p instanceof JavaType.FullyQualified ? ((JavaType.FullyQualified) p).getFullyQualifiedName() : String.valueOf(p);
            if (fqn.startsWith("com.amazonaws.services.kinesis.AmazonKinesis")) {
                extras.add("kinesis");
            } else if (fqn.startsWith("com.amazonaws.services.dynamodbv2.AmazonDynamoDB")) {
                extras.add("dynamodb");
            } else if (fqn.startsWith("com.amazonaws.services.cloudwatch.AmazonCloudWatch")) {
                extras.add("cloudwatch");
            } else {
                extras.add(fqn);
            }
        }
        return extras;
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        Set<String> ported = new HashSet<>(acc.kclSources.values());
        ported.removeAll(acc.excludedProjects);
        if (ported.isEmpty()) {
            return emptyList();
        }
        for (Map.Entry<Path, String> e : acc.kclSources.entrySet()) {
            if (ported.contains(e.getValue())) {
                acc.portedSources.add(e.getKey());
            }
        }
        acc.shims.retainProjects(ported);
        List<SourceFile> generated = new ArrayList<>(acc.shims.generate(PROCESSOR_SHIM, pkg -> template(PROCESSOR_SHIM, pkg), ctx));
        generated.addAll(acc.shims.generate(WORKER_SHIM, pkg -> template(WORKER_SHIM, pkg), ctx));
        return generated;
    }

    static String template(String name, String pkg) {
        try (InputStream in = MigrateKinesisClientLibraryToV2.class.getResourceAsStream("/META-INF/rewrite/aws-sdk/" + name + ".java.template")) {
            return StringUtils.readFully(Objects.requireNonNull(in), StandardCharsets.UTF_8)
                    .replace("__PACKAGE__", ProjectShims.packageLine(pkg));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (acc.portedSources.isEmpty() || !(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile s = (SourceFile) tree;
                if (BuildFiles.isBuildFile(s)) {
                    Path module = BuildFiles.moduleDir(s);
                    for (Path p : acc.portedSources) {
                        if (module.toString().isEmpty() || p.startsWith(module)) {
                            return new ChangeDependency("com.amazonaws", "amazon-kinesis-client", "software.amazon.kinesis",
                                    null, KCL_V2_VERSION, null, null, null).getVisitor().visit(s, ctx);
                        }
                    }
                    return s;
                }
                if (s instanceof J.CompilationUnit && acc.portedSources.contains(s.getSourcePath())) {
                    return port((J.CompilationUnit) s, acc, ctx);
                }
                return s;
            }
        };
    }

    private static J.CompilationUnit port(J.CompilationUnit cu, Accumulator acc, ExecutionContext ctx) {
        String processor = acc.shims.fqn(cu, PROCESSOR_SHIM);
        String worker = acc.shims.fqn(cu, WORKER_SHIM);
        if (processor == null || worker == null) {
            return cu;
        }
        J.CompilationUnit c = (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (DEAGGREGATE.matches(m)) {
                    return m.getArguments().get(0).withPrefix(m.getPrefix());
                }
                return m;
            }

            @Override
            public J visitIdentifier(J.Identifier ident, ExecutionContext ctx) {
                // Case labels must stay unqualified, which ReplaceConstantWithAnotherConstant does not do before Java 21
                JavaType.Variable field = ident.getFieldType();
                if (field != null && getCursor().getParentTreeCursor().getValue() instanceof J.Case &&
                    TypeUtils.isOfClassType(field.getOwner(), KCL + "lib.worker.ShutdownReason")) {
                    String renamed = "ZOMBIE".equals(ident.getSimpleName()) ? "LEASE_LOST" :
                            "TERMINATE".equals(ident.getSimpleName()) ? "SHARD_END" : null;
                    if (renamed != null) {
                        return ident.withSimpleName(renamed).withFieldType(field.withName(renamed));
                    }
                }
                return ident;
            }
        }.visitNonNull(cu, ctx);
        c = (J.CompilationUnit) new ReplaceConstantWithAnotherConstant(KCL + "lib.worker.ShutdownReason.ZOMBIE",
                "software.amazon.kinesis.lifecycle.ShutdownReason.LEASE_LOST").getVisitor().visitNonNull(c, ctx);
        c = (J.CompilationUnit) new ReplaceConstantWithAnotherConstant(KCL + "lib.worker.ShutdownReason.TERMINATE",
                "software.amazon.kinesis.lifecycle.ShutdownReason.SHARD_END").getVisitor().visitNonNull(c, ctx);
        for (String[] rename : RENAMES) {
            c = (J.CompilationUnit) new ChangeMethodName(rename[0], rename[1], true, null).getVisitor().visitNonNull(c, ctx);
        }
        Map<String, String> types = new LinkedHashMap<>(TYPES);
        types.put(KCL + "interfaces.v2.IRecordProcessor", processor);
        types.put(KCL + "interfaces.IRecordProcessor", processor + "$Legacy");
        types.put(KCL + "interfaces.v2.IShutdownNotificationAware", processor + "$ShutdownNotificationAware");
        types.put(KCL + "types.ShutdownInput", processor + "$ShutdownInput");
        types.put(KCL + "lib.worker.Worker$Builder", worker + "$Builder");
        types.put(KCL + "lib.worker.KinesisClientLibConfiguration", worker + "$Configuration");
        types.put(KCL + "lib.worker.Worker", worker);
        for (Map.Entry<String, String> e : types.entrySet()) {
            c = (J.CompilationUnit) new ChangeType(e.getKey(), e.getValue(), true).getVisitor().visitNonNull(c, ctx);
        }
        c = (J.CompilationUnit) new ChangePackage(KCL + "exceptions", "software.amazon.kinesis.exceptions", false)
                .getVisitor().visitNonNull(c, ctx);
        return (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (CHECKPOINT_RECORD.matches(m) && m.getSelect() != null) {
                    Expression record = m.getArguments().get(0);
                    return JavaTemplate.builder("#{any(" + V2_CHECKPOINTER + ")}." + m.getSimpleName() +
                                                "(#{any(" + V2_RECORD + ")}.sequenceNumber(), #{any(" + V2_RECORD + ")}.subSequenceNumber())")
                            .javaParser(KCL_V2_PARSER.apply(ctx))
                            .build()
                            .apply(getCursor(), m.getCoordinates().replace(), m.getSelect(), record, record);
                }
                if (ARRIVAL_TIMESTAMP.matches(m) && m.getSelect() != null) {
                    maybeAddImport("java.util.Date");
                    return JavaTemplate.builder("Date.from(#{any(" + V2_RECORD + ")}.approximateArrivalTimestamp())")
                            .imports("java.util.Date")
                            .javaParser(KCL_V2_PARSER.apply(ctx))
                            .build()
                            .apply(getCursor(), m.getCoordinates().replace(), m.getSelect());
                }
                return m;
            }
        }.visitNonNull(c, ctx);
    }
}

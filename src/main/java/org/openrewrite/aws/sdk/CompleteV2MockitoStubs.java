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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.SearchResult;

import java.util.*;
import java.util.regex.Pattern;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

/**
 * v2-migration rewrites calls inside Mockito stubs as if they ran: argument matchers end up inside request builders, where
 * Mockito rejects them, and v1-only methods stay stubbed although the code under test now calls something else.
 */
public class CompleteV2MockitoStubs extends Recipe {

    private static final String V2_TRANSFER_MODEL = "software.amazon.awssdk.transfer.s3.model.";
    private static final Pattern V2_ASYNC_CLIENT = Pattern.compile("software\\.amazon\\.awssdk\\.services\\.[a-z0-9]+\\.[A-Za-z0-9]+AsyncClient");
    private static final Set<String> MATCHERS = new HashSet<>(Arrays.asList("any", "anyString", "anyInt", "anyLong", "anyBoolean",
            "anyList", "anyMap", "anyCollection", "anySet", "eq", "isA", "notNull", "isNull", "argThat", "same", "nullable", "contains",
            "startsWith", "endsWith", "matches", "refEq"));

    @Getter
    final String displayName = "Complete Mockito stubs of AWS SDK v2 clients";

    @Getter
    final String description = "Runs after `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. A request builder full of argument " +
            "matchers inside a stub becomes `any(Request.class)`; stubs of `doesObjectExist`, `doesBucketExistV2` and `doesBucketExist` " +
            "stub the HEAD or ACL call the generated `S3Compat` makes, with `true` as a response and `false` as a 404; a value a v2 " +
            "async client call is stubbed to return is wrapped in `CompletableFuture.completedFuture`; and stubs of a transfer's " +
            "`waitForCompletion()` or `isDone()` complete its `completionFuture()`.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(new UsesType<>("org.mockito..*", true), new JavaVisitor<ExecutionContext>() {

            @Override
            public @Nullable J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J planned = plannedStatement(method, ctx);
                if (planned != KEEP) {
                    return planned;
                }
                J j = super.visitMethodInvocation(method, ctx);
                if (!(j instanceof J.MethodInvocation)) {
                    return j;
                }
                J.MethodInvocation m = (J.MethodInvocation) j;
                if ("build".equals(m.getSimpleName()) && isStubArgument(getCursor())) {
                    String request = matcherFilledBuilder(m);
                    if (request != null) {
                        maybeAddImport("org.mockito.ArgumentMatchers");
                        return template("ArgumentMatchers.any(" + request + ".class)", ctx, "org.mockito.ArgumentMatchers")
                                .apply(getCursor(), method.getCoordinates().replace());
                    }
                }
                if (isChainTop(getCursor())) {
                    J replaced = completeWhenChain(m, method, ctx);
                    if (replaced != null) {
                        return replaced;
                    }
                }
                if ("waitForCompletion".equals(m.getSimpleName()) && m.getSelect() instanceof J.MethodInvocation &&
                    "when".equals(((J.MethodInvocation) m.getSelect()).getSimpleName()) &&
                    isMockito(((J.MethodInvocation) m.getSelect()).getMethodType()) &&
                    ((J.MethodInvocation) m.getSelect()).getArguments().size() == 1) {
                    Expression transfer = ((J.MethodInvocation) m.getSelect()).getArguments().get(0);
                    String completed = completed(transfer);
                    if (completed != null) {
                        return completeTransfer(transfer, completed, method, ctx);
                    }
                }
                return m;
            }

            @Override
            public @Nullable J visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                J.VariableDeclarations v = (J.VariableDeclarations) super.visitVariableDeclarations(multiVariable, ctx);
                if (v.getVariables().size() != 1) {
                    return v;
                }
                J.VariableDeclarations.NamedVariable var = v.getVariables().get(0);
                Plan plan = getCursor().getNearestMessage(PLAN);
                if (plan != null && plan.contents.containsKey(var.getSimpleName()) && var.getInitializer() != null &&
                    getCursor().getParentTreeCursor().getValue() instanceof J.Block) {
                    // v2 returns content and metadata as one stream, built where the content stub was
                    Expression md = plan.streamMetadata.get(var.getSimpleName());
                    String response = md == null ? "GetObjectResponse.builder().build()" : shimName(md) + ".getObjectResponse(#{any()})";
                    maybeAddImport("software.amazon.awssdk.http.AbortableInputStream");
                    maybeAddImport("software.amazon.awssdk.services.s3.model.GetObjectResponse");
                    JavaTemplate t = template("new ResponseInputStream<>(" + response + ", AbortableInputStream.create(#{any(java.io.InputStream)}))", ctx,
                            "software.amazon.awssdk.core.ResponseInputStream", "software.amazon.awssdk.http.AbortableInputStream",
                            "software.amazon.awssdk.services.s3.model.GetObjectResponse");
                    Expression content = plan.contents.get(var.getSimpleName());
                    Cursor init = new Cursor(new Cursor(getCursor(), var), var.getInitializer());
                    Expression built = md == null ? t.apply(init, var.getInitializer().getCoordinates().replace(), content) :
                            t.apply(init, var.getInitializer().getCoordinates().replace(), md, content);
                    return v.withVariables(singletonList(var.withInitializer(built)))
                            .withMarkers(v.getMarkers().add(new SearchResult(Tree.randomId(), MOVE_FROM + var.getSimpleName())));
                }
                // v1's S3ObjectInputStream wrapped a stream for tests; v2 has no such type
                if (v.getTypeExpression() != null && v.getTypeExpression().printTrimmed(getCursor()).endsWith("S3ObjectInputStream") &&
                    var.getInitializer() instanceof J.NewClass && !((J.NewClass) var.getInitializer()).getArguments().isEmpty() &&
                    !(((J.NewClass) var.getInitializer()).getArguments().get(0) instanceof J.Empty)) {
                    maybeRemoveImport("software.amazon.awssdk.services.s3.model.S3ObjectInputStream");
                    maybeAddImport("java.io.InputStream");
                    maybeRemoveImport("com.amazonaws.services.s3.model.S3ObjectInputStream");
                    return JavaTemplate.builder("InputStream " + var.getSimpleName() + " = #{any(java.io.InputStream)};")
                            .imports("java.io.InputStream")
                            .build()
                            .apply(getCursor(), v.getCoordinates().replace(), ((J.NewClass) var.getInitializer()).getArguments().get(0));
                }
                return v;
            }

            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                getCursor().putMessage(PLAN, Plan.of(block));
                J.Block b = (J.Block) super.visitBlock(block, ctx);
                b = moveStreamDeclarations(b);
                // isDone() and waitForCompletion() stubs of one transfer both become the same completionFuture() stub
                Set<String> futures = new HashSet<>();
                return b.withStatements(ListUtils.map(b.getStatements(), st -> {
                    if (st instanceof J.MethodInvocation && ("thenReturn".equals(((J.MethodInvocation) st).getSimpleName()) ||
                                                             "thenAnswer".equals(((J.MethodInvocation) st).getSimpleName())) &&
                        ((J.MethodInvocation) st).getSelect() instanceof J.MethodInvocation) {
                        J.MethodInvocation when = (J.MethodInvocation) ((J.MethodInvocation) st).getSelect();
                        if ("when".equals(when.getSimpleName()) && when.getArguments().size() == 1 &&
                            when.getArguments().get(0) instanceof J.MethodInvocation &&
                            "completionFuture".equals(((J.MethodInvocation) when.getArguments().get(0)).getSimpleName()) &&
                            ((J.MethodInvocation) when.getArguments().get(0)).getSelect() instanceof J.Identifier &&
                            !futures.add(((J.Identifier) ((J.MethodInvocation) when.getArguments().get(0)).getSelect()).getSimpleName())) {
                            return null;
                        }
                    }
                    return st;
                }));
            }

            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J j = super.visitNewClass(newClass, ctx);
                // v1's TransferProgress was a class tests built; v2's is an interface
                if (j instanceof J.NewClass && TypeUtils.isOfClassType(((J.NewClass) j).getType(),
                        "software.amazon.awssdk.transfer.s3.progress.TransferProgress") &&
                    ((J.NewClass) j).getArguments().stream().allMatch(J.Empty.class::isInstance)) {
                    maybeAddImport("org.mockito.Mockito");
                    return template("Mockito.mock(TransferProgress.class)", ctx, "org.mockito.Mockito",
                            "software.amazon.awssdk.transfer.s3.progress.TransferProgress")
                            .apply(getCursor(), newClass.getCoordinates().replace());
                }
                return j;
            }

            private @Nullable J completeWhenChain(J.MethodInvocation top, J.MethodInvocation original, ExecutionContext ctx) {
                List<J.MethodInvocation> links = new ArrayList<>();
                Expression e = top;
                while (e instanceof J.MethodInvocation && !"when".equals(((J.MethodInvocation) e).getSimpleName())) {
                    links.add(0, (J.MethodInvocation) e);
                    e = ((J.MethodInvocation) e).getSelect();
                }
                if (!(e instanceof J.MethodInvocation) || links.isEmpty() || !isMockito(((J.MethodInvocation) e).getMethodType()) ||
                    ((J.MethodInvocation) e).getArguments().size() != 1 ||
                    !(((J.MethodInvocation) e).getArguments().get(0) instanceof J.MethodInvocation)) {
                    return null;
                }
                J.MethodInvocation stubbed = (J.MethodInvocation) ((J.MethodInvocation) e).getArguments().get(0);
                Expression client = stubbed.getSelect();
                if (client == null) {
                    return null;
                }
                JavaType.FullyQualified clientType = TypeUtils.asFullyQualified(client.getType());
                String clientFqn = clientType == null ? "" : clientType.getFullyQualifiedName();
                String name = stubbed.getSimpleName();
                if ("software.amazon.awssdk.services.s3.S3Client".equals(clientFqn) &&
                    ("doesObjectExist".equals(name) || "doesBucketExistV2".equals(name) || "doesBucketExist".equals(name))) {
                    return existenceStub(client, name, links, original, ctx);
                }
                if ("software.amazon.awssdk.services.s3.S3Client".equals(clientFqn) && "headObject".equals(name) &&
                    links.size() == 1 && "thenReturn".equals(links.get(0).getSimpleName()) && links.get(0).getArguments().size() == 1 &&
                    isMetadataShim(links.get(0).getArguments().get(0))) {
                    // The generated ObjectMetadata stands for a HEAD response in v1 stubs of getObjectMetadata
                    Expression md = links.get(0).getArguments().get(0);
                    J.MethodInvocation link = links.get(0);
                    J.MethodInvocation converted = LstFactory.noArgCall(md, "toHeadObjectResponse", TypeUtils.asFullyQualified(md.getType()),
                            JavaType.ShallowClass.build("software.amazon.awssdk.services.s3.model.HeadObjectResponse"));
                    return top == link ? link.withArguments(singletonList(converted)) : null;
                }
                if (client instanceof J.MethodInvocation && stubbed.getArguments().stream().allMatch(J.Empty.class::isInstance) &&
                    links.size() == 1 && top == links.get(0) && "thenReturn".equals(top.getSimpleName()) && top.getArguments().size() == 1) {
                    String response = v2Response((J.MethodInvocation) client);
                    if (response != null) {
                        // Mockito cannot stub a getter of a call's result; stub the call with a response holding the value
                        maybeAddImport(response);
                        String simple = response.substring(response.lastIndexOf('.') + 1);
                        return template("Mockito.when(#{any()}).thenReturn(" + simple + ".builder()." + name + "(#{any()}).build())", ctx,
                                "org.mockito.Mockito", response)
                                .apply(getCursor(), original.getCoordinates().replace(), client, top.getArguments().get(0));
                    }
                }
                if (isMockitoStub(links) && clientType != null && V2_ASYNC_CLIENT.matcher(clientFqn).matches()) {
                    return asyncStub(top, links, original, ctx);
                }
                if ("isDone".equals(name) && completed(client) != null && links.stream().allMatch(l -> "thenReturn".equals(l.getSimpleName()))) {
                    return completeTransfer(client, Objects.requireNonNull(completed(client)), original, ctx);
                }
                return null;
            }

            private @Nullable J existenceStub(Expression client, String name, List<J.MethodInvocation> links,
                                              J.MethodInvocation original, ExecutionContext ctx) {
                List<Boolean> outcomes = new ArrayList<>();
                for (J.MethodInvocation link : links) {
                    if (!"thenReturn".equals(link.getSimpleName())) {
                        return null;
                    }
                    for (Expression arg : link.getArguments()) {
                        if (!(arg instanceof J.Literal) || !(((J.Literal) arg).getValue() instanceof Boolean)) {
                            return null;
                        }
                        outcomes.add((Boolean) ((J.Literal) arg).getValue());
                    }
                }
                String call = "doesObjectExist".equals(name) ? "headObject" : "doesBucketExist".equals(name) ? "headBucket" : "getBucketAcl";
                String model = "doesObjectExist".equals(name) ? "HeadObject" : "doesBucketExist".equals(name) ? "HeadBucket" : "GetBucketAcl";
                StringBuilder stub = new StringBuilder("Mockito.when(#{any()}." + call + "(ArgumentMatchers.<Consumer<" + model +
                                                       "Request.Builder>>any()))");
                for (Boolean exists : outcomes) {
                    // S3Compat treats a 404 as absent
                    stub.append(exists ? ".thenReturn(" + model + "Response.builder().build())" :
                            ".thenThrow(S3Exception.builder().statusCode(404).build())");
                }
                String pkg = "software.amazon.awssdk.services.s3.model.";
                for (String i : Arrays.asList("org.mockito.Mockito", "org.mockito.ArgumentMatchers", "java.util.function.Consumer",
                        pkg + model + "Request", pkg + model + "Response", pkg + "S3Exception")) {
                    maybeAddImport(i);
                }
                return template(stub.toString(), ctx, "org.mockito.Mockito", "org.mockito.ArgumentMatchers", "java.util.function.Consumer",
                        pkg + model + "Request", pkg + model + "Response", pkg + "S3Exception")
                        .apply(getCursor(), original.getCoordinates().replace(), client);
            }

            private @Nullable J asyncStub(J.MethodInvocation top, List<J.MethodInvocation> links, J.MethodInvocation original,
                                          ExecutionContext ctx) {
                boolean changed = false;
                Map<J.MethodInvocation, J.MethodInvocation> wrapped = new IdentityHashMap<>();
                for (J.MethodInvocation link : links) {
                    if (!"thenReturn".equals(link.getSimpleName()) || link.getArguments().stream().anyMatch(CompleteV2MockitoStubs::isFuture)) {
                        continue;
                    }
                    changed = true;
                    wrapped.put(link, link);
                }
                if (!changed) {
                    return null;
                }
                maybeAddImport("java.util.concurrent.CompletableFuture");
                Expression rebuilt = rewrap(top, wrapped, ctx);
                return rebuilt.withPrefix(original.getPrefix());
            }

            private Expression rewrap(Expression e, Map<J.MethodInvocation, J.MethodInvocation> wrapped, ExecutionContext ctx) {
                if (!(e instanceof J.MethodInvocation) || "when".equals(((J.MethodInvocation) e).getSimpleName())) {
                    return e;
                }
                J.MethodInvocation link = (J.MethodInvocation) e;
                link = link.withSelect(rewrap(Objects.requireNonNull(link.getSelect()), wrapped, ctx));
                if (wrapped.containsKey(e)) {
                    link = link.withArguments(link.getArguments().stream().map(a -> {
                        J.MethodInvocation future = (J.MethodInvocation) template("CompletableFuture.completedFuture(#{any()})", ctx,
                                "java.util.concurrent.CompletableFuture")
                                .apply(new Cursor(getCursor(), a), a.getCoordinates().replace(), a);
                        return (Expression) future.withPrefix(a.getPrefix());
                    }).collect(toList()));
                }
                return link;
            }

            /**
             * Statements of a block that the block's {@link Plan} rewrites, or {@link #KEEP}.
             */
            private @Nullable J plannedStatement(J.MethodInvocation m, ExecutionContext ctx) {
                Plan plan = getCursor().getNearestMessage(PLAN);
                if (plan == null || !(getCursor().getParentTreeCursor().getValue() instanceof J.Block)) {
                    return KEEP;
                }
                Stub stub = Stub.of(m);
                if (stub == null) {
                    return KEEP;
                }
                if (stub.target instanceof J.MethodInvocation && "getObjectMetadata".equals(((J.MethodInvocation) stub.target).getSimpleName()) &&
                    plan.downloadMetadata.containsKey(stub.targetName)) {
                    Expression transfer = Objects.requireNonNull(((J.MethodInvocation) stub.target).getSelect());
                    String completed = completed(transfer);
                    return completed == null ? KEEP : completeTransfer(transfer, completed, m, ctx);
                }
                if (stub.target instanceof J.MethodInvocation && "response".equals(((J.MethodInvocation) stub.target).getSimpleName()) &&
                    plan.contents.containsKey(stub.targetName)) {
                    return null;
                }
                if (stub.target instanceof J.Identifier && plan.contents.containsKey(stub.targetName)) {
                    // The stream's declaration moves here
                    return m.withMarkers(m.getMarkers().add(new SearchResult(Tree.randomId(), MOVE_HERE + stub.targetName)));
                }
                return KEEP;
            }

            private J completeTransfer(Expression transfer, String completed, J.MethodInvocation original, ExecutionContext ctx) {
                Plan plan = getCursor().getNearestMessage(PLAN);
                Expression md = plan == null || !(transfer instanceof J.Identifier) ? null :
                        plan.downloadMetadata.get(((J.Identifier) transfer).getSimpleName());
                if (md != null && "CompletedFileDownload".equals(completed)) {
                    // Answered lazily, since the metadata's own stubs usually follow
                    maybeAddImport("org.mockito.Mockito");
                    maybeAddImport("java.util.concurrent.CompletableFuture");
                    maybeAddImport(V2_TRANSFER_MODEL + completed);
                    return template("Mockito.when(#{any()}.completionFuture()).thenAnswer(invocation -> CompletableFuture.completedFuture(" +
                                    completed + ".builder().response(" + shimName(md) + ".getObjectResponse(#{any()})).build()))", ctx,
                            "org.mockito.Mockito", "java.util.concurrent.CompletableFuture", V2_TRANSFER_MODEL + completed)
                            .apply(getCursor(), original.getCoordinates().replace(), transfer, md);
                }
                String response = completed.endsWith("FileDownload") || completed.endsWith("Download") ? "GetObjectResponse" :
                        completed.endsWith("Copy") ? "CopyObjectResponse" : "PutObjectResponse";
                maybeAddImport("org.mockito.Mockito");
                maybeAddImport("java.util.concurrent.CompletableFuture");
                maybeAddImport(V2_TRANSFER_MODEL + completed);
                maybeAddImport("software.amazon.awssdk.services.s3.model." + response);
                return template("Mockito.when(#{any()}.completionFuture()).thenReturn(CompletableFuture.completedFuture(" + completed +
                                ".builder().response(" + response + ".builder().build()).build()))", ctx,
                        "org.mockito.Mockito", "java.util.concurrent.CompletableFuture", V2_TRANSFER_MODEL + completed,
                        "software.amazon.awssdk.services.s3.model." + response)
                        .apply(getCursor(), original.getCoordinates().replace(), transfer);
            }
        });
    }

    private static final String PLAN = "s3StubPlan";
    private static final String MOVE_FROM = "s3StubMoveFrom:";
    private static final String MOVE_HERE = "s3StubMoveHere:";

    private static @Nullable String moveMarker(Statement st, String prefix) {
        for (SearchResult r : st.getMarkers().findAll(SearchResult.class)) {
            if (r.getDescription() != null && r.getDescription().startsWith(prefix)) {
                return r.getDescription().substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * Moves each stream declaration marked {@link #MOVE_FROM} to the content stub marked {@link #MOVE_HERE}, which it replaces.
     */
    private static J.Block moveStreamDeclarations(J.Block b) {
        Map<String, Statement> declarations = new HashMap<>();
        for (Statement st : b.getStatements()) {
            String name = moveMarker(st, MOVE_FROM);
            if (name != null) {
                declarations.put(name, st);
            }
        }
        Set<String> targets = new HashSet<>();
        for (Statement st : b.getStatements()) {
            String name = moveMarker(st, MOVE_HERE);
            if (name != null) {
                targets.add(name);
            }
        }
        return b.withStatements(ListUtils.map(b.getStatements(), st -> {
            String from = moveMarker(st, MOVE_FROM);
            if (from != null) {
                return targets.contains(from) ? null : withoutMoveMarkers(st);
            }
            String name = moveMarker(st, MOVE_HERE);
            if (name != null) {
                return declarations.containsKey(name) ? withoutMoveMarkers(declarations.get(name)).withPrefix(st.getPrefix()) :
                        withoutMoveMarkers(st);
            }
            return st;
        }));
    }

    private static Statement withoutMoveMarkers(Statement st) {
        List<Marker> kept = new ArrayList<>();
        for (Marker mk : st.getMarkers().getMarkers()) {
            if (!(mk instanceof SearchResult) || ((SearchResult) mk).getDescription() == null ||
                !((SearchResult) mk).getDescription().startsWith(MOVE_FROM) && !((SearchResult) mk).getDescription().startsWith(MOVE_HERE)) {
                kept.add(mk);
            }
        }
        return st.withMarkers(st.getMarkers().withMarkers(kept));
    }

    private static final J KEEP = new J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY);

    private static String shimName(Expression md) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(md.getType());
        return type == null ? "ObjectMetadata" : type.getClassName();
    }

    /**
     * A {@code Mockito.when(target).thenReturn(value)} statement.
     */
    private static final class Stub {
        final Expression target;
        final String targetName;
        final Expression value;

        private Stub(Expression target, String targetName, Expression value) {
            this.target = target;
            this.targetName = targetName;
            this.value = value;
        }

        static @Nullable Stub of(Statement st) {
            if (!(st instanceof J.MethodInvocation) || !"thenReturn".equals(((J.MethodInvocation) st).getSimpleName()) ||
                ((J.MethodInvocation) st).getArguments().size() != 1 || !(((J.MethodInvocation) st).getSelect() instanceof J.MethodInvocation)) {
                return null;
            }
            J.MethodInvocation when = (J.MethodInvocation) ((J.MethodInvocation) st).getSelect();
            if (!"when".equals(when.getSimpleName()) || when.getArguments().size() != 1) {
                return null;
            }
            Expression target = when.getArguments().get(0);
            Expression named = target instanceof J.MethodInvocation ? ((J.MethodInvocation) target).getSelect() : target;
            if (!(named instanceof J.Identifier)) {
                return null;
            }
            return new Stub(target, ((J.Identifier) named).getSimpleName(), ((J.MethodInvocation) st).getArguments().get(0));
        }
    }

    /**
     * What a block's stubs say about mocked S3 objects and downloads: v1 tests mocked {@code S3Object} and stubbed its
     * content and metadata, which v2 returns as one {@code ResponseInputStream}, and stubbed a download's metadata, which v2
     * returns with the download's completion.
     */
    private static final class Plan {
        final Map<String, Expression> contents = new HashMap<>();
        final Map<String, Expression> streamMetadata = new HashMap<>();
        final Map<String, Expression> downloadMetadata = new HashMap<>();

        static Plan of(J.Block block) {
            Plan plan = new Plan();
            Set<String> mockedStreams = new HashSet<>();
            for (Statement st : block.getStatements()) {
                if (st instanceof J.VariableDeclarations && ((J.VariableDeclarations) st).getVariables().size() == 1 &&
                    TypeUtils.isOfClassType(((J.VariableDeclarations) st).getType(), "software.amazon.awssdk.core.ResponseInputStream")) {
                    Expression init = ((J.VariableDeclarations) st).getVariables().get(0).getInitializer();
                    if (init instanceof J.MethodInvocation && "mock".equals(((J.MethodInvocation) init).getSimpleName())) {
                        mockedStreams.add(((J.VariableDeclarations) st).getVariables().get(0).getSimpleName());
                    }
                }
                Stub stub = Stub.of(st);
                if (stub == null) {
                    continue;
                }
                if (stub.target instanceof J.Identifier && mockedStreams.contains(stub.targetName)) {
                    plan.contents.put(stub.targetName, stub.value);
                } else if (stub.target instanceof J.MethodInvocation && isMetadataShim(stub.value)) {
                    String name = ((J.MethodInvocation) stub.target).getSimpleName();
                    if ("response".equals(name) && mockedStreams.contains(stub.targetName)) {
                        plan.streamMetadata.put(stub.targetName, stub.value);
                    } else if ("getObjectMetadata".equals(name) && completed(Objects.requireNonNull(((J.MethodInvocation) stub.target).getSelect())) != null) {
                        plan.downloadMetadata.put(stub.targetName, stub.value);
                    }
                }
            }
            plan.streamMetadata.keySet().retainAll(plan.contents.keySet());
            return plan;
        }
    }

    private static JavaTemplate template(String code, ExecutionContext ctx, String... imports) {
        return JavaTemplate.builder(code)
                .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "mockito-core", "s3-2", "s3-transfer-manager",
                        "sdk-core", "aws-core", "utils", "http-client-spi"))
                .imports(imports)
                .build();
    }

    /**
     * The completed-transfer type whose future a stubbed v2 transfer returns.
     */
    private static @Nullable String completed(Expression transfer) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(transfer.getType());
        if (type == null || !type.getFullyQualifiedName().startsWith(V2_TRANSFER_MODEL)) {
            return null;
        }
        String simple = type.getClassName();
        switch (simple) {
            case "Upload":
            case "FileUpload":
            case "Download":
            case "FileDownload":
            case "Copy":
                return "Completed" + simple;
            default:
                return null;
        }
    }

    private static final Pattern V2_RESPONSE = Pattern.compile("software\\.amazon\\.awssdk\\.services\\.[a-z0-9]+\\.model\\.[A-Za-z0-9]+Response");
    private static final Pattern V2_CLIENT = Pattern.compile("(software\\.amazon\\.awssdk\\.services\\.[a-z0-9]+)\\.[A-Za-z0-9]+Client");

    /**
     * The v2 response a client call returns, from its type or, when v2-migration left it untyped, from its name.
     */
    private static @Nullable String v2Response(J.MethodInvocation call) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(call.getType());
        if (type != null && V2_RESPONSE.matcher(type.getFullyQualifiedName()).matches()) {
            return type.getFullyQualifiedName();
        }
        JavaType.FullyQualified client = call.getSelect() == null ? null : TypeUtils.asFullyQualified(call.getSelect().getType());
        if (type == null && client != null) {
            java.util.regex.Matcher m = V2_CLIENT.matcher(client.getFullyQualifiedName());
            if (m.matches() && !client.getFullyQualifiedName().endsWith("AsyncClient")) {
                return m.group(1) + ".model." + Character.toUpperCase(call.getSimpleName().charAt(0)) + call.getSimpleName().substring(1) + "Response";
            }
        }
        return null;
    }

    private static boolean isMetadataShim(Expression e) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(e.getType());
        return type != null && "ObjectMetadata".equals(type.getClassName()) && !type.getFullyQualifiedName().startsWith("com.amazonaws.");
    }

    private static boolean isFuture(Expression e) {
        return TypeUtils.isAssignableTo("java.util.concurrent.Future", e.getType()) ||
               e instanceof J.MethodInvocation && "completedFuture".equals(((J.MethodInvocation) e).getSimpleName());
    }

    private static boolean isMockitoStub(List<J.MethodInvocation> links) {
        return links.stream().allMatch(l -> l.getSimpleName().startsWith("then"));
    }

    private static boolean isChainTop(Cursor cursor) {
        Object parent = cursor.getParentTreeCursor().getValue();
        return !(parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() == cursor.getValue());
    }

    /**
     * A request built with matchers among its values, inside a call that Mockito stubs or verifies.
     */
    private static boolean isStubArgument(Cursor cursor) {
        Cursor call = cursor.getParentTreeCursor();
        return call.getValue() instanceof J.MethodInvocation &&
               ((J.MethodInvocation) call.getValue()).getArguments().contains(cursor.getValue()) &&
               V2MigrationScope.isMockitoStubbedOrVerified(call);
    }

    private static @Nullable String matcherFilledBuilder(J.MethodInvocation build) {
        boolean matcher = false;
        Expression e = build.getSelect();
        while (e instanceof J.MethodInvocation) {
            J.MethodInvocation link = (J.MethodInvocation) e;
            if ("builder".equals(link.getSimpleName()) && link.getSelect() instanceof J.Identifier) {
                return matcher ? ((J.Identifier) link.getSelect()).getSimpleName() : null;
            }
            for (Expression arg : link.getArguments()) {
                if (isMatcher(arg)) {
                    matcher = true;
                }
            }
            e = link.getSelect();
        }
        return null;
    }

    private static boolean isMatcher(Expression arg) {
        Expression a = arg instanceof J.TypeCast ? ((J.TypeCast) arg).getExpression() : arg;
        return a instanceof J.MethodInvocation && MATCHERS.contains(((J.MethodInvocation) a).getSimpleName()) &&
               (((J.MethodInvocation) a).getMethodType() == null || isMockito(((J.MethodInvocation) a).getMethodType()));
    }

    private static boolean isMockito(JavaType.@Nullable Method method) {
        return method != null && method.getDeclaringType().getFullyQualifiedName().startsWith("org.mockito.");
    }
}

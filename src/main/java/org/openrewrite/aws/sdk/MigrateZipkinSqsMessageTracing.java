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
import org.openrewrite.java.ChangeMethodName;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

import static java.util.Collections.singletonList;

// Runs after v2-migration because the interceptor can only be attached to a v2 builder
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateZipkinSqsMessageTracing extends ScanningRecipe<ProjectShims> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    static final String V1_TRACING = "brave.instrumentation.aws.sqs.SqsMessageTracing";
    static final String SHIM = "SqsMessageTracing";
    // the v1 type as it is once renamed, for the template
    private static final String STUB = "package brave.instrumentation.aws.sqs; public class SqsMessageTracing { " +
                                       "public software.amazon.awssdk.core.interceptor.ExecutionInterceptor executionInterceptor() { return null; } }";

    @Getter
    final String displayName = "Migrate zipkin-aws `SqsMessageTracing` to AWS SDK v2";

    @Getter
    final String description = "zipkin-aws' `SqsMessageTracing` puts the trace context on sent SQS messages through an AWS SDK " +
                               "v1 request handler, and zipkin-aws has no AWS SDK v2 counterpart. It becomes a generated " +
                               "`SqsMessageTracing` of the same shape whose `executionInterceptor()` does the same on a v2 client, " +
                               "and `withRequestHandlers(tracing.requestHandler())` becomes that interceptor added to the " +
                               "builder's `ClientOverrideConfiguration`.";

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
                if (tree instanceof J.CompilationUnit && new UsesType<>(V1_TRACING, false).visit(tree, ctx) != tree) {
                    acc.require((J.CompilationUnit) tree);
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
        return acc.generate(SHIM, MigrateZipkinSqsMessageTracing::source, ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(ProjectShims acc) {
        return JavaOnly.check(new UsesType<>(V1_TRACING, false), new RequestHandlerInterceptors() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);
                String shim = acc.fqn(cu, SHIM);
                if (shim != null) {
                    doAfterVisit(new ChangeMethodName(V1_TRACING + " requestHandler()", "executionInterceptor", null, null).getVisitor());
                    doAfterVisit(new ChangeType(V1_TRACING, shim, true).getVisitor());
                }
                return c;
            }

            @Override
            RequestHandlerInterceptors.@Nullable Interceptor interceptor(Expression handler) {
                if (!(handler instanceof J.MethodInvocation) || !"requestHandler".equals(((J.MethodInvocation) handler).getSimpleName()) ||
                    ((J.MethodInvocation) handler).getSelect() == null ||
                    !TypeUtils.isOfClassType(((J.MethodInvocation) handler).getSelect().getType(), V1_TRACING) ||
                    acc.fqn(getCursor().firstEnclosingOrThrow(J.CompilationUnit.class), SHIM) == null) {
                    return null;
                }
                return new Interceptor("#{any(" + V1_TRACING + ")}.executionInterceptor()",
                        singletonList(((J.MethodInvocation) handler).getSelect()), new String[0], STUB);
            }

            @Override
            void cleanUpImports() {
            }
        });
    }

    static String source(String pkg) {
        try (InputStream in = MigrateZipkinSqsMessageTracing.class.getResourceAsStream("/META-INF/rewrite/aws-sdk/" + SHIM + ".java.template")) {
            return StringUtils.readFully(Objects.requireNonNull(in), StandardCharsets.UTF_8)
                    .replace("__PACKAGE__", ProjectShims.packageLine(pkg));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

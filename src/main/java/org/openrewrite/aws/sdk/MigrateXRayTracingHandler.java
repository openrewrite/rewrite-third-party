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
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.ArrayList;
import java.util.List;

// Runs after v2-migration because the interceptor can only be attached to a v2 builder
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateXRayTracingHandler extends Recipe {

    private static final String TRACING_HANDLER = "com.amazonaws.xray.handlers.TracingHandler";
    private static final String TRACING_INTERCEPTOR = "com.amazonaws.xray.interceptors.TracingInterceptor";
    private static final String OVERRIDE_CONFIG = "software.amazon.awssdk.core.client.config.ClientOverrideConfiguration";
    private static final MethodMatcher GLOBAL_RECORDER = new MethodMatcher("com.amazonaws.xray.AWSXRay getGlobalRecorder()");

    private static final String[] STUBS = {
            "package software.amazon.awssdk.core.interceptor; public interface ExecutionInterceptor {}",
            "package software.amazon.awssdk.core.client.config; " +
            "public final class ClientOverrideConfiguration { " +
            "  public static Builder builder() { return null; } " +
            "  public Builder toBuilder() { return null; } " +
            "  public interface Builder { " +
            "    Builder addExecutionInterceptor(software.amazon.awssdk.core.interceptor.ExecutionInterceptor i); " +
            "    Builder applyMutation(java.util.function.Consumer<Builder> mutator); " +
            "    ClientOverrideConfiguration build(); " +
            "  } " +
            "}",
            "package com.amazonaws.xray; public class AWSXRayRecorder {}",
            "package com.amazonaws.xray.interceptors; " +
            "public class TracingInterceptor implements software.amazon.awssdk.core.interceptor.ExecutionInterceptor { " +
            "  public TracingInterceptor() {} " +
            "  public TracingInterceptor(com.amazonaws.xray.AWSXRayRecorder r, String accountId, java.net.URL whitelist) {} " +
            "}"
    };

    @Getter
    final String displayName = "Migrate X-Ray `TracingHandler` to `TracingInterceptor`";

    @Getter
    final String description = "Replaces `withRequestHandlers(new TracingHandler(..))` on an AWS SDK v2 client builder with a " +
            "`TracingInterceptor` added to the builder's `ClientOverrideConfiguration`: a new one when the builder has none, " +
            "otherwise the existing one, since a second `overrideConfiguration` would replace the first. " +
            "`TracingHandler(AWSXRay.getGlobalRecorder())` becomes `TracingInterceptor()`, which uses the same global recorder.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(new UsesType<>(TRACING_HANDLER, false), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (isLastOverrideConfiguration(m)) {
                    J.MethodInvocation handlerCall = findTracingHandlerCall(m.getSelect());
                    Interceptor interceptor = handlerCall == null ? null : Interceptor.of(handlerCall);
                    if (interceptor == null) {
                        return m;
                    }
                    // The handler call comes before this overrideConfiguration in the chain: drop it and merge here
                    return mergeInto(m, removeLink(m.getSelect(), handlerCall), interceptor, getCursor(), ctx);
                }
                if (!isTracingHandlerCall(m) || isStillV1(m.getSelect().getType()) || laterOverrideConfiguration(getCursor())) {
                    return m;
                }
                Interceptor interceptor = Interceptor.of(m);
                if (interceptor == null) {
                    return m;
                }
                J.MethodInvocation existing = lastOverrideConfiguration(m.getSelect());
                if (existing != null) {
                    Cursor cursor = getCursor();
                    J merged = new JavaIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext ctx) {
                            J.MethodInvocation inner = super.visitMethodInvocation(mi, ctx);
                            return inner.getId().equals(existing.getId()) ?
                                    mergeInto(inner, inner.getSelect(), interceptor, getCursor(), ctx) : inner;
                        }
                    }.visitNonNull(m.getSelect(), ctx, cursor);
                    return (J.MethodInvocation) merged.withPrefix(m.getPrefix());
                }
                cleanUpImports();
                List<Object> params = new ArrayList<>();
                params.add(m.getSelect());
                params.addAll(interceptor.args);
                J.MethodInvocation replaced = JavaTemplate.builder("#{any()}.overrideConfiguration(ClientOverrideConfiguration.builder()" +
                                ".addExecutionInterceptor(" + interceptor.code + ").build())")
                        .imports(TRACING_INTERCEPTOR, OVERRIDE_CONFIG)
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(STUBS))
                        .build()
                        .apply(getCursor(), m.getCoordinates().replace(), params.toArray());
                return keepSelectPadding(replaced, m);
            }

            private J.MethodInvocation mergeInto(J.MethodInvocation overrideCall, @Nullable Expression select, Interceptor interceptor,
                                                 Cursor cursor, ExecutionContext ctx) {
                Expression config = overrideCall.getArguments().get(0);
                String merged;
                List<Object> params = new ArrayList<>();
                params.add(select);
                if (config instanceof J.MethodInvocation && "build".equals(((J.MethodInvocation) config).getSimpleName()) &&
                    ((J.MethodInvocation) config).getSelect() != null &&
                    TypeUtils.isOfClassType(((J.MethodInvocation) config).getSelect().getType(), OVERRIDE_CONFIG + "$Builder")) {
                    merged = "#{any()}.addExecutionInterceptor(" + interceptor.code + ").build()";
                    params.add(((J.MethodInvocation) config).getSelect());
                } else if (config instanceof J.Lambda) {
                    merged = "ClientOverrideConfiguration.builder().applyMutation(#{any()}).addExecutionInterceptor(" + interceptor.code + ").build()";
                    params.add(config);
                } else {
                    merged = "#{any(" + OVERRIDE_CONFIG + ")}.toBuilder().addExecutionInterceptor(" + interceptor.code + ").build()";
                    params.add(config);
                }
                params.addAll(interceptor.args);
                cleanUpImports();
                J.MethodInvocation replaced = JavaTemplate.builder("#{any()}.overrideConfiguration(" + merged + ")")
                        .imports(TRACING_INTERCEPTOR, OVERRIDE_CONFIG)
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(STUBS))
                        .build()
                        .apply(cursor, overrideCall.getCoordinates().replace(), params.toArray());
                return keepSelectPadding(replaced, overrideCall);
            }

            private void cleanUpImports() {
                maybeRemoveImport(TRACING_HANDLER);
                maybeRemoveImport("com.amazonaws.xray.AWSXRay");
                maybeAddImport(TRACING_INTERCEPTOR);
                maybeAddImport(OVERRIDE_CONFIG);
            }

            private boolean isLastOverrideConfiguration(J.MethodInvocation m) {
                // By name, as builders v2-migration just produced may not be typed yet
                return "overrideConfiguration".equals(m.getSimpleName()) && m.getArguments().size() == 1 &&
                       !laterOverrideConfiguration(getCursor());
            }

            // Walks up the chain this call is the receiver of
            private boolean laterOverrideConfiguration(Cursor cursor) {
                Object current = cursor.getValue();
                Cursor parent = cursor.getParentTreeCursor();
                while (parent.getValue() instanceof J.MethodInvocation && ((J.MethodInvocation) parent.getValue()).getSelect() == current) {
                    J.MethodInvocation p = parent.getValue();
                    if ("overrideConfiguration".equals(p.getSimpleName())) {
                        return true;
                    }
                    current = p;
                    parent = parent.getParentTreeCursor();
                }
                return false;
            }

            private J.@Nullable MethodInvocation lastOverrideConfiguration(@Nullable Expression select) {
                for (Expression e = select; e instanceof J.MethodInvocation; e = ((J.MethodInvocation) e).getSelect()) {
                    if ("overrideConfiguration".equals(((J.MethodInvocation) e).getSimpleName()) &&
                        ((J.MethodInvocation) e).getArguments().size() == 1) {
                        return (J.MethodInvocation) e;
                    }
                }
                return null;
            }

            private J.@Nullable MethodInvocation findTracingHandlerCall(@Nullable Expression select) {
                for (Expression e = select; e instanceof J.MethodInvocation; e = ((J.MethodInvocation) e).getSelect()) {
                    if (isTracingHandlerCall((J.MethodInvocation) e)) {
                        return (J.MethodInvocation) e;
                    }
                }
                return null;
            }

            private boolean isTracingHandlerCall(J.MethodInvocation m) {
                return ("withRequestHandlers".equals(m.getSimpleName()) || "requestHandlers".equals(m.getSimpleName())) &&
                       m.getSelect() != null && m.getArguments().size() == 1 &&
                       m.getArguments().get(0) instanceof J.NewClass &&
                       TypeUtils.isOfClassType(((J.NewClass) m.getArguments().get(0)).getType(), TRACING_HANDLER);
            }

            private Expression removeLink(@Nullable Expression select, J.MethodInvocation link) {
                if (!(select instanceof J.MethodInvocation)) {
                    return select;
                }
                J.MethodInvocation mi = (J.MethodInvocation) select;
                if (mi == link) {
                    return mi.getSelect().withPrefix(mi.getPrefix());
                }
                return mi.withSelect(removeLink(mi.getSelect(), link));
            }

            private boolean isStillV1(@Nullable JavaType type) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                return fq != null && fq.getFullyQualifiedName().startsWith("com.amazonaws.");
            }
        });
    }

    // Keeps the line break before the call in a builder chain
    private static J.MethodInvocation keepSelectPadding(J.MethodInvocation replaced, J.MethodInvocation original) {
        JRightPadded<Expression> select = replaced.getPadding().getSelect();
        return select == null || original.getPadding().getSelect() == null ? replaced :
                replaced.getPadding().withSelect(select.withAfter(original.getPadding().getSelect().getAfter()));
    }

    private static final class Interceptor {
        final String code;
        final List<Expression> args;

        private Interceptor(String code, List<Expression> args) {
            this.code = code;
            this.args = args;
        }

        static @Nullable Interceptor of(J.MethodInvocation handlerCall) {
            List<Expression> args = ((J.NewClass) handlerCall.getArguments().get(0)).getArguments();
            if (args.isEmpty() || args.get(0) instanceof J.Empty ||
                args.size() == 1 && args.get(0) instanceof J.MethodInvocation && GLOBAL_RECORDER.matches((J.MethodInvocation) args.get(0))) {
                return new Interceptor("new TracingInterceptor()", new ArrayList<>());
            } else if (args.size() == 1) {
                return new Interceptor("new TracingInterceptor(#{any(com.amazonaws.xray.AWSXRayRecorder)}, null, null)", args);
            } else if (args.size() == 3) {
                return new Interceptor("new TracingInterceptor(#{any(com.amazonaws.xray.AWSXRayRecorder)}, #{any(String)}, #{any(java.net.URL)})", args);
            }
            return null;
        }
    }
}

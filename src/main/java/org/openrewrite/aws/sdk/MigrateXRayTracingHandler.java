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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;

// Runs after v2-migration because the interceptor can only be attached to a v2 builder
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateXRayTracingHandler extends Recipe {

    private static final String TRACING_HANDLER = "com.amazonaws.xray.handlers.TracingHandler";
    private static final String TRACING_INTERCEPTOR = "com.amazonaws.xray.interceptors.TracingInterceptor";
    private static final MethodMatcher GLOBAL_RECORDER = new MethodMatcher("com.amazonaws.xray.AWSXRay getGlobalRecorder()");

    private static final String[] STUBS = {
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
        return JavaOnly.check(new UsesType<>(TRACING_HANDLER, false), new RequestHandlerInterceptors() {
            @Override
            RequestHandlerInterceptors.@Nullable Interceptor interceptor(Expression handler) {
                if (!(handler instanceof J.NewClass) || !TypeUtils.isOfClassType(handler.getType(), TRACING_HANDLER)) {
                    return null;
                }
                List<Expression> args = ((J.NewClass) handler).getArguments();
                String[] imports = {TRACING_INTERCEPTOR};
                if (args.isEmpty() || args.get(0) instanceof J.Empty ||
                    args.size() == 1 && args.get(0) instanceof J.MethodInvocation && GLOBAL_RECORDER.matches((J.MethodInvocation) args.get(0))) {
                    return new Interceptor("new TracingInterceptor()", new ArrayList<>(), imports, STUBS);
                } else if (args.size() == 1) {
                    return new Interceptor("new TracingInterceptor(#{any(com.amazonaws.xray.AWSXRayRecorder)}, null, null)",
                            args, imports, STUBS);
                } else if (args.size() == 3) {
                    return new Interceptor("new TracingInterceptor(#{any(com.amazonaws.xray.AWSXRayRecorder)}, #{any(String)}, #{any(java.net.URL)})",
                            args, imports, STUBS);
                }
                return null;
            }

            @Override
            void cleanUpImports() {
                maybeRemoveImport(TRACING_HANDLER);
                maybeRemoveImport("com.amazonaws.xray.AWSXRay");
                maybeAddImport(TRACING_INTERCEPTOR);
            }
        });
    }
}

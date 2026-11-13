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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.regex.Pattern;

public class CompleteAwsAsyncClientCalls extends Recipe {

    private static final Pattern V2_ASYNC_CLIENT = Pattern.compile("software\\.amazon\\.awssdk\\.services\\.[a-z0-9]+\\.[A-Za-z0-9]+AsyncClient");
    private static final Pattern V2_RESPONSE = Pattern.compile("software\\.amazon\\.awssdk\\.services\\.[a-z0-9.]+\\.model\\.[A-Za-z0-9]+Response");

    @Getter
    final String displayName = "Complete AWS SDK v2 async client calls that blocked in v1";

    @Getter
    final String description = "Runs after `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`, which retypes a v1 async client to the " +
            "v2 async client but keeps each call's v1 return type. A call on a v2 `*AsyncClient` that still returns a response was a " +
            "blocking call in v1: it gets `.join()`, so it still blocks and throws instead of becoming a fire-and-forget future whose " +
            "failure is lost. A call that still returns `Future` was an `xxxAsync(..)` call renamed by `PrepareAwsAsyncClientCalls`: its " +
            "type and the variable holding it become `CompletableFuture`. Calls that Mockito stubs or verifies are left alone.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>("software.amazon.awssdk.services..*", true), new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                J.VariableDeclarations mv = super.visitVariableDeclarations(multiVariable, ctx);
                if (mv.getVariables().size() != 1 || !(mv.getTypeExpression() instanceof J.ParameterizedType)) {
                    return mv;
                }
                Expression init = mv.getVariables().get(0).getInitializer();
                J.ParameterizedType pt = (J.ParameterizedType) mv.getTypeExpression();
                if (init == null || !(init.unwrap() instanceof J.MethodInvocation) || !(pt.getClazz() instanceof J.Identifier) ||
                    !TypeUtils.isOfClassType(pt.getClazz().getType(), "java.util.concurrent.Future") ||
                    !TypeUtils.isOfClassType(init.unwrap().getType(), "java.util.concurrent.CompletableFuture") ||
                    !isV2AsyncClient(((J.MethodInvocation) init.unwrap()).getSelect())) {
                    return mv;
                }
                JavaType.Parameterized cf = LstFactory.completableFutureOf(
                        pt.getTypeParameters() == null || pt.getTypeParameters().isEmpty() ? null : pt.getTypeParameters().get(0).getType());
                maybeRemoveImport("java.util.concurrent.Future");
                maybeAddImport("java.util.concurrent.CompletableFuture");
                return mv.withTypeExpression(pt
                                .withClazz(((J.Identifier) pt.getClazz()).withSimpleName("CompletableFuture").withType(cf.getType()))
                                .withType(cf))
                        .withVariables(ListUtils.map(mv.getVariables(), v -> v.withType(cf).withName(v.getName().withType(cf))));
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                JavaType.Method mt = m.getMethodType();
                if (mt == null || !isV2AsyncClient(m.getSelect()) || V2MigrationScope.isMockitoStubbedOrVerified(getCursor())) {
                    return m;
                }
                if (TypeUtils.isOfClassType(mt.getReturnType(), "java.util.concurrent.Future") &&
                    mt.getReturnType() instanceof JavaType.Parameterized) {
                    JavaType.Method retyped = mt.withReturnType(LstFactory.completableFutureOf(
                            ((JavaType.Parameterized) mt.getReturnType()).getTypeParameters().get(0)));
                    return m.withMethodType(retyped).withName(m.getName().withType(retyped));
                }
                JavaType.FullyQualified response = TypeUtils.asFullyQualified(mt.getReturnType());
                if (response == null || !V2_RESPONSE.matcher(response.getFullyQualifiedName()).matches()) {
                    return m;
                }
                JavaType.Parameterized cf = LstFactory.completableFutureOf(response);
                JavaType.Method retyped = mt.withReturnType(cf);
                m = m.withMethodType(retyped).withName(m.getName().withType(retyped));
                return LstFactory.noArgCall(m, "join", cf.getType(), response);
            }
        });
    }

    private static boolean isV2AsyncClient(@Nullable Expression select) {
        JavaType.FullyQualified type = select == null ? null : TypeUtils.asFullyQualified(select.getType());
        return type != null && V2_ASYNC_CLIENT.matcher(type.getFullyQualifiedName()).matches();
    }
}

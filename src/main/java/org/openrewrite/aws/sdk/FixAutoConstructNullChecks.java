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
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.regex.Pattern;

import static org.openrewrite.Tree.randomId;

public class FixAutoConstructNullChecks extends Recipe {

    private static final Pattern V2_MODEL = Pattern.compile("software\\.amazon\\.awssdk\\.services\\.[a-z0-9]+\\.model\\..+");
    private static final MethodMatcher IS_NULL = new MethodMatcher("java.util.Objects isNull(Object)");
    private static final MethodMatcher NON_NULL = new MethodMatcher("java.util.Objects nonNull(Object)");

    @Getter
    final String displayName = "Replace null checks on AWS SDK v2 auto-construct collections with `hasX()`";

    @Getter
    final String description = "Runs after `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. v1 model getters returned `null` for " +
            "a list or map the service did not send, while v2 returns an empty auto-construct collection. A migrated " +
            "`result.getLastEvaluatedKey() == null` therefore never becomes true in v2, which can turn a pagination loop into an " +
            "infinite one. This rewrites `x.m() == null` to `!x.hasM()` and `x.m() != null` to `x.hasM()` on v2 model types, and " +
            "stores `x.hasM() ? x.m() : null` where the getter's value is kept in a variable that is later compared to null.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>("software.amazon.awssdk.services..*", true), new JavaVisitor<ExecutionContext>() {

            @Override
            public J visitBinary(J.Binary binary, ExecutionContext ctx) {
                J j = super.visitBinary(binary, ctx);
                if (!(j instanceof J.Binary)) {
                    return j;
                }
                J.Binary b = (J.Binary) j;
                if (b.getOperator() != J.Binary.Type.Equal && b.getOperator() != J.Binary.Type.NotEqual) {
                    return b;
                }
                Expression getter = isNull(b.getRight()) ? b.getLeft() : isNull(b.getLeft()) ? b.getRight() : null;
                J.MethodInvocation has = getter == null ? null : hasCall(getter.unwrap());
                if (has == null) {
                    return b;
                }
                return b.getOperator() == J.Binary.Type.Equal ? not(has.withPrefix(Space.EMPTY), b.getPrefix()) : has.withPrefix(b.getPrefix());
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J j = super.visitAssignment(assignment, ctx);
                if (j instanceof J.Assignment && ((J.Assignment) j).getVariable() instanceof J.Identifier) {
                    J.Assignment a = (J.Assignment) j;
                    Expression restored = restoreNull(a.getAssignment(), ((J.Identifier) a.getVariable()).getSimpleName());
                    return restored == null ? a : a.withAssignment(restored);
                }
                return j;
            }

            @Override
            public J visitVariable(J.VariableDeclarations.NamedVariable variable, ExecutionContext ctx) {
                J.VariableDeclarations.NamedVariable v = (J.VariableDeclarations.NamedVariable) super.visitVariable(variable, ctx);
                Expression restored = v.getInitializer() == null ? null : restoreNull(v.getInitializer(), v.getSimpleName());
                return restored == null ? v : v.withInitializer(restored);
            }

            /**
             * A getter stored in a variable that the method later compares to null, such as the classic
             * {@code start = result.lastEvaluatedKey(); } ... {@code while (start != null)}, gets v1's null back.
             */
            private @Nullable Expression restoreNull(Expression value, String variable) {
                J.MethodDeclaration method = getCursor().firstEnclosing(J.MethodDeclaration.class);
                Expression getter = value.unwrap();
                if (method == null || method.getBody() == null || !(getter instanceof J.MethodInvocation) ||
                    !(((J.MethodInvocation) getter).getSelect() instanceof J.Identifier) || !isNullChecked(method.getBody(), variable)) {
                    return null;
                }
                J.MethodInvocation has = hasCall(getter);
                if (has == null) {
                    return null;
                }
                return new J.Ternary(randomId(), value.getPrefix(), Markers.EMPTY,
                        has.withPrefix(Space.EMPTY),
                        JLeftPadded.build((Expression) getter.withPrefix(Space.SINGLE_SPACE)).withBefore(Space.SINGLE_SPACE),
                        JLeftPadded.build((Expression) new J.Literal(randomId(), Space.SINGLE_SPACE, Markers.EMPTY, null, "null", null,
                                JavaType.Primitive.Null)).withBefore(Space.SINGLE_SPACE),
                        getter.getType());
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J j = super.visitMethodInvocation(method, ctx);
                if (!(j instanceof J.MethodInvocation)) {
                    return j;
                }
                J.MethodInvocation m = (J.MethodInvocation) j;
                boolean isNull = IS_NULL.matches(m);
                if (!isNull && !NON_NULL.matches(m)) {
                    return m;
                }
                J.MethodInvocation has = hasCall(m.getArguments().get(0).unwrap());
                if (has == null) {
                    return m;
                }
                maybeRemoveImport("java.util.Objects");
                return isNull ? not(has.withPrefix(Space.EMPTY), m.getPrefix()) : has.withPrefix(m.getPrefix());
            }
        });
    }

    private static boolean isNullChecked(J tree, String variable) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Binary visitBinary(J.Binary binary, Integer p) {
                if ((binary.getOperator() == J.Binary.Type.Equal || binary.getOperator() == J.Binary.Type.NotEqual) &&
                    (isNull(binary.getLeft()) && isVariable(binary.getRight()) || isNull(binary.getRight()) && isVariable(binary.getLeft()))) {
                    found[0] = true;
                }
                return super.visitBinary(binary, p);
            }

            private boolean isVariable(Expression e) {
                return e.unwrap() instanceof J.Identifier && ((J.Identifier) e.unwrap()).getSimpleName().equals(variable);
            }
        }.visit(tree, 0);
        return found[0];
    }

    private static boolean isNull(Expression e) {
        Expression u = e.unwrap();
        return J.Literal.isLiteralValue(u, null) && "null".equals(((J.Literal) u).getValueSource());
    }

    /**
     * Every list or map member of a generated v2 model has a {@code hasX()} that tells "absent" from "empty".
     */
    private static J.@Nullable MethodInvocation hasCall(Expression e) {
        if (!(e instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation getter = (J.MethodInvocation) e;
        JavaType.Method mt = getter.getMethodType();
        if (getter.getSelect() == null || mt == null || !getter.getArguments().stream().allMatch(J.Empty.class::isInstance) ||
            !V2_MODEL.matcher(mt.getDeclaringType().getFullyQualifiedName()).matches() ||
            !(TypeUtils.isOfClassType(mt.getReturnType(), "java.util.List") || TypeUtils.isOfClassType(mt.getReturnType(), "java.util.Map"))) {
            return null;
        }
        String name = getter.getSimpleName();
        if (name.startsWith("get") || name.startsWith("has")) {
            return null;
        }
        String hasName = "has" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return LstFactory.noArgCall(getter.getSelect(), hasName, mt.getDeclaringType(), JavaType.Primitive.Boolean)
                .withPrefix(getter.getPrefix());
    }

    private static J.Unary not(Expression e, Space prefix) {
        return new J.Unary(randomId(), prefix, Markers.EMPTY, JLeftPadded.build(J.Unary.Type.Not), e, JavaType.Primitive.Boolean);
    }
}

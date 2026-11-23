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

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.List;

/**
 * One way a legacy Azure SDK call is rewritten. A template names the call's receiver {@code $r} and its arguments
 * {@code $0}, {@code $1}, ..., is written with fully qualified names, and is compiled against the current Azure SDK,
 * each placeholder typed as the parameter it fills will be once legacy types are retyped. An identical call on the
 * current type is a rule too, so that every call left typed as legacy is one no rule supports.
 */
final class CallRewrite {

    /**
     * A replacement built in code, for what a template cannot say.
     */
    interface Builder {
        /**
         * @return the replacement, or {@code null} when this call is not one the rule can rewrite
         */
        @Nullable J build(Call call, AzureCallRewriter rewriter, ExecutionContext ctx);
    }

    /**
     * Whether the rule applies to a matched call.
     */
    interface Condition {
        boolean test(Call call);
    }

    final MethodMatcher matcher;
    final @Nullable String template;
    final @Nullable Builder builder;
    final @Nullable Condition condition;
    final boolean deleteStatement;

    private CallRewrite(String pattern, @Nullable String template, @Nullable Builder builder,
                        @Nullable Condition condition, boolean deleteStatement) {
        this.matcher = new MethodMatcher(pattern, true);
        this.template = template;
        this.builder = builder;
        this.condition = condition;
        this.deleteStatement = deleteStatement;
    }

    static CallRewrite call(String pattern, String template) {
        return new CallRewrite(pattern, template, null, null, false);
    }

    static CallRewrite custom(String pattern, Builder builder) {
        return new CallRewrite(pattern, null, builder, null, false);
    }

    /**
     * Removes a call whose only effect was on state the current SDK does not keep, where it is a statement of its own.
     */
    static CallRewrite delete(String pattern) {
        return new CallRewrite(pattern, null, null, null, true);
    }

    CallRewrite when(Condition condition) {
        return new CallRewrite(matcher.toString(), template, builder, condition, deleteStatement);
    }

    static Condition argsNull(int... indexes) {
        return call -> {
            for (int i : indexes) {
                if (i < call.args.size() && !isNull(call.args.get(i))) {
                    return false;
                }
            }
            return true;
        };
    }

    static boolean isNull(Expression e) {
        return e instanceof J.Literal && ((J.Literal) e).getValue() == null &&
               ((J.Literal) e).getType() == JavaType.Primitive.Null;
    }

    static boolean isLiteral(Expression e, Object value) {
        return e instanceof J.Literal && value.equals(((J.Literal) e).getValue());
    }

    /**
     * A matched method invocation or constructor call.
     */
    static final class Call {
        final J tree;
        final JavaType.Method type;
        final @Nullable Expression receiver;
        final List<Expression> args;
        final Cursor cursor;

        Call(J tree, JavaType.Method type, @Nullable Expression receiver, List<Expression> args, Cursor cursor) {
            this.tree = tree;
            this.type = type;
            this.receiver = receiver;
            this.args = args;
            this.cursor = cursor;
        }

        Expression arg(int i) {
            return args.get(i);
        }
    }
}

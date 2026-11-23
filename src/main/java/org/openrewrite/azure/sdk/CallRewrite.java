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
import org.openrewrite.SourceFile;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.*;

import java.util.*;

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
                if (i < call.args.size() && !isNull(call.args.get(i)) && !isDefault(call.args.get(i))) {
                    return false;
                }
            }
            return true;
        };
    }

    static Condition all(Condition... conditions) {
        return call -> {
            for (Condition condition : conditions) {
                if (!condition.test(call)) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * Whether a checked exception thrown in place of the call would be caught or declared where the call is.
     *
     * @param exceptionTypes the exception and each of its supertypes a catch or {@code throws} could name
     */
    static Condition handles(String... exceptionTypes) {
        return call -> handled(call.cursor, exceptionTypes);
    }

    static boolean handled(Cursor cursor, String... exceptionTypes) {
        Object child = cursor.getValue();
        for (Cursor c = cursor.getParentTreeCursor(); ; c = c.getParentTreeCursor()) {
            Object v = c.getValue();
            if (v instanceof J.Try) {
                J.Try t = (J.Try) v;
                boolean guarded = child == t.getBody() || (t.getResources() != null && t.getResources().contains(child));
                if (guarded) {
                    for (J.Try.Catch aCatch : t.getCatches()) {
                        TypeTree caught = aCatch.getParameter().getTree().getTypeExpression();
                        List<? extends NameTree> alternatives = caught instanceof J.MultiCatch ?
                                ((J.MultiCatch) caught).getAlternatives() : Collections.singletonList(caught);
                        for (NameTree alternative : alternatives) {
                            if (alternative != null && isAny(alternative.getType(), exceptionTypes)) {
                                return true;
                            }
                        }
                    }
                }
            } else if (v instanceof J.MethodDeclaration) {
                List<NameTree> declared = ((J.MethodDeclaration) v).getThrows();
                if (declared != null) {
                    for (NameTree thrown : declared) {
                        if (isAny(thrown.getType(), exceptionTypes)) {
                            return true;
                        }
                    }
                }
                return false;
            } else if (v instanceof J.Lambda || v instanceof J.ClassDeclaration || v instanceof SourceFile ||
                       (v instanceof J.NewClass && child == ((J.NewClass) v).getBody())) {
                return false;
            }
            child = v;
        }
    }

    private static boolean isAny(@Nullable JavaType type, String... fqns) {
        for (String fqn : fqns) {
            if (TypeUtils.isOfClassType(type, fqn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The call is one side of an {@code == null} or {@code != null} comparison.
     */
    static boolean comparedToNull(Call call) {
        Object parent = call.cursor.getParentTreeCursor().getValue();
        if (!(parent instanceof J.Binary)) {
            return false;
        }
        J.Binary binary = (J.Binary) parent;
        return (binary.getOperator() == J.Binary.Type.Equal || binary.getOperator() == J.Binary.Type.NotEqual) &&
               (isNull(binary.getLeft()) || isNull(binary.getRight()));
    }

    static boolean isNull(Expression e) {
        return e instanceof J.Literal && ((J.Literal) e).getValue() == null &&
               ((J.Literal) e).getType() == JavaType.Primitive.Null;
    }

    /**
     * Request options, an operation context or an access condition as freshly made, which a legacy call treats as it
     * treats {@code null}.
     */
    static boolean isDefault(Expression e) {
        if (e instanceof J.NewClass) {
            J.NewClass n = (J.NewClass) e;
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(n.getType());
            return n.getBody() == null && type != null && DEFAULTS.contains(type.getFullyQualifiedName()) &&
                   n.getArguments().stream().allMatch(J.Empty.class::isInstance);
        }
        if (e instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) e;
            return m.getMethodType() != null && m.getArguments().stream().allMatch(J.Empty.class::isInstance) &&
                   TypeUtils.isOfClassType(m.getMethodType().getDeclaringType(), "com.microsoft.azure.storage.AccessCondition") &&
                   "generateEmptyCondition".equals(m.getSimpleName());
        }
        return false;
    }

    private static final Set<String> DEFAULTS = new HashSet<>(Arrays.asList(
            "com.microsoft.azure.storage.OperationContext",
            "com.microsoft.azure.storage.blob.BlobRequestOptions",
            "com.microsoft.azure.storage.queue.QueueRequestOptions",
            "com.microsoft.azure.storage.table.TableRequestOptions",
            "com.microsoft.azure.storage.file.FileRequestOptions"));

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

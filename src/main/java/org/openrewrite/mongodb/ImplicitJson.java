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
package org.openrewrite.mongodb;

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * Where the code turns a document into a string other than by calling {@code toString()} or {@code toJson()} on a
 * {@code BasicDBObject} or {@code BsonDocument}, which the recipe rewrites: by string concatenation, by passing it to a
 * method that formats it, such as {@code String.valueOf} or a logger's, or by calling {@code toString()} on a
 * {@code DBObject}, which is most often a {@code BasicDBObject}. Driver 5 writes relaxed extended JSON there where driver
 * 3 wrote strict. Asking for strict JSON would change what a {@code null} document gives, or when a logger formats its
 * arguments, so these are only listed, in {@link #found}.
 */
final class ImplicitJson extends JavaIsoVisitor<ExecutionContext> {

    /**
     * The types whose methods turn an argument of type {@code Object} into a string.
     */
    private static final Set<String> FORMATTERS = new HashSet<>(Arrays.asList(
            "java.lang.String", "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.AbstractStringBuilder",
            "java.io.PrintStream", "java.io.PrintWriter", "java.util.Objects", "java.text.MessageFormat",
            "org.slf4j.Logger", "org.apache.logging.log4j.Logger", "org.apache.commons.logging.Log",
            "java.util.logging.Logger", "org.jboss.logging.BasicLogger", "org.apache.log4j.Category"));

    /**
     * What turns a document into a string where, with the expression that does it.
     */
    final List<String> found = new ArrayList<>();

    @Override
    public J.Binary visitBinary(J.Binary binary, ExecutionContext ctx) {
        J.Binary b = super.visitBinary(binary, ctx);
        if (b.getOperator() == J.Binary.Type.Addition && TypeUtils.isString(b.getType())) {
            check(b.getLeft(), b);
            check(b.getRight(), b);
        }
        return b;
    }

    @Override
    public J.AssignmentOperation visitAssignmentOperation(J.AssignmentOperation assignment, ExecutionContext ctx) {
        J.AssignmentOperation a = super.visitAssignmentOperation(assignment, ctx);
        if (a.getOperator() == J.AssignmentOperation.Type.Addition && TypeUtils.isString(a.getVariable().getType())) {
            check(a.getAssignment(), a);
        }
        return a;
    }

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
        JavaType.Method mt = m.getMethodType();
        if (mt == null) {
            return m;
        }
        if ("toString".equals(m.getSimpleName()) && m.getSelect() != null && mt.getParameterTypes().isEmpty() &&
            TypeUtils.isOfClassType(m.getSelect().getType(), "com.mongodb.DBObject")) {
            found.add(describe(m, "turns a `DBObject` into a string"));
            return m;
        }
        if (FORMATTERS.contains(mt.getDeclaringType().getFullyQualifiedName())) {
            for (Expression arg : m.getArguments()) {
                check(arg, m);
            }
        }
        return m;
    }

    private void check(Expression e, J where) {
        String type = documentType(e.getType());
        if (type != null) {
            found.add(describe(where, "turns a `" + type + "` into a string"));
        }
    }

    private static @Nullable String documentType(@Nullable JavaType type) {
        String strict = RemovedApiFixes.strictToString(type);
        if (strict != null) {
            return LegacyUsage.simpleName(strict);
        }
        return TypeUtils.isOfClassType(type, "com.mongodb.DBObject") ? "DBObject" : null;
    }

    private static String describe(J where, String what) {
        String code = where.printTrimmed().replaceAll("\\s+", " ");
        if (code.length() > 80) {
            code = code.substring(0, 77) + "...";
        }
        return "`" + code + "` " + what + ", which driver 5 writes as relaxed extended JSON where driver 3 wrote strict";
    }
}

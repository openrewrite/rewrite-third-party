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
import org.openrewrite.java.tree.*;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Merges two adjacent blocks of the same {@code MongoClientSettings.Builder.applyTo...Settings}, as the conversion
 * writes one for the options and one for the hosts, into one:
 * {@code .applyToClusterSettings(c -> c.a()).applyToClusterSettings(c -> c.b())} becomes
 * {@code .applyToClusterSettings(c -> c.a().b())}.
 */
final class MergeSettingsBlocks extends JavaIsoVisitor<ExecutionContext> {

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);
        String name = mi.getSimpleName();
        if (!name.startsWith("applyTo") || !name.endsWith("Settings") || !(mi.getSelect() instanceof J.MethodInvocation) ||
            !TypeUtils.isOfClassType(mi.getSelect().getType(), "com.mongodb.MongoClientSettings$Builder")) {
            return mi;
        }
        J.MethodInvocation first = (J.MethodInvocation) mi.getSelect();
        if (!name.equals(first.getSimpleName())) {
            return mi;
        }
        J.Lambda inner = singleLambda(first);
        J.Lambda outer = singleLambda(mi);
        if (inner == null || outer == null || !(inner.getBody() instanceof Expression) || !(outer.getBody() instanceof Expression)) {
            return mi;
        }
        J.Identifier param = parameter(outer);
        if (param == null || references(outer.getBody(), param.getSimpleName()) != 1) {
            return mi;
        }
        Expression merged = replaceRoot((Expression) outer.getBody(), param.getSimpleName(), (Expression) inner.getBody());
        if (merged == null) {
            return mi;
        }
        return first.withArguments(Collections.singletonList(inner.withBody(merged)));
    }

    private static J.@Nullable Lambda singleLambda(J.MethodInvocation m) {
        return m.getArguments().size() == 1 && m.getArguments().get(0) instanceof J.Lambda ? (J.Lambda) m.getArguments().get(0) : null;
    }

    private static J.@Nullable Identifier parameter(J.Lambda lambda) {
        if (lambda.getParameters().getParameters().size() != 1) {
            return null;
        }
        J p = lambda.getParameters().getParameters().get(0);
        if (p instanceof J.VariableDeclarations && ((J.VariableDeclarations) p).getVariables().size() == 1) {
            return ((J.VariableDeclarations) p).getVariables().get(0).getName();
        }
        return p instanceof J.Identifier ? (J.Identifier) p : null;
    }

    private static int references(J tree, String name) {
        AtomicInteger count = new AtomicInteger();
        new JavaIsoVisitor<AtomicInteger>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicInteger c) {
                if (name.equals(identifier.getSimpleName()) && identifier.getFieldType() != null) {
                    c.incrementAndGet();
                }
                return identifier;
            }
        }.visit(tree, count);
        return count.get();
    }

    /**
     * The call chain {@code body} with its root, the parameter {@code name}, replaced by {@code root}, or {@code null}
     * when the chain is not rooted at the parameter.
     */
    private static @Nullable Expression replaceRoot(Expression body, String name, Expression root) {
        if (body instanceof J.Identifier) {
            return name.equals(((J.Identifier) body).getSimpleName()) ? root.withPrefix(body.getPrefix()) : null;
        }
        if (body instanceof J.MethodInvocation && ((J.MethodInvocation) body).getSelect() != null) {
            J.MethodInvocation m = (J.MethodInvocation) body;
            Expression select = replaceRoot(m.getSelect(), name, root);
            return select == null ? null : m.withSelect(select);
        }
        return null;
    }
}

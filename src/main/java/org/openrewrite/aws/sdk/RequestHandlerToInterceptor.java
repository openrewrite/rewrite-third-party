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

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.openrewrite.Tree.randomId;

/**
 * Rewrites a {@code RequestHandler2} subclass that only edits the request in {@code beforeExecution} into a v2
 * {@code ExecutionInterceptor} with {@code modifyRequest}. The class is rewritten in place so beans and references keep working.
 */
final class RequestHandlerToInterceptor {

    static final String EXECUTION_INTERCEPTOR = "software.amazon.awssdk.core.interceptor.ExecutionInterceptor";
    private static final String V1_REQUEST = "com.amazonaws.AmazonWebServiceRequest";

    private static final Set<String> OTHER_HANDLER_METHODS = new HashSet<>(Arrays.asList(
            "beforeMarshalling", "beforeRequest", "beforeAttempt", "afterAttempt", "beforeUnmarshalling",
            "afterResponse", "afterError", "beforeClientExecution", "getCredentialsProvider"));

    private RequestHandlerToInterceptor() {
    }

    static J.@Nullable MethodDeclaration convertibleBeforeExecution(J.ClassDeclaration cd) {
        if (cd.getExtends() == null || !TypeUtils.isOfClassType(cd.getExtends().getType(), AwsClientChain.V1_REQUEST_HANDLER)) {
            return null;
        }
        J.MethodDeclaration beforeExecution = null;
        for (Statement s : cd.getBody().getStatements()) {
            if (s instanceof J.MethodDeclaration) {
                J.MethodDeclaration md = (J.MethodDeclaration) s;
                if ("beforeExecution".equals(md.getSimpleName()) && md.getParameters().size() == 1 && md.getBody() != null) {
                    beforeExecution = md;
                } else if (OTHER_HANDLER_METHODS.contains(md.getSimpleName())) {
                    return null;
                }
            }
        }
        return beforeExecution != null && isConvertibleBody(beforeExecution) ? beforeExecution : null;
    }

    /**
     * v2 requests are immutable, so only bodies whose mutations are setters on a local cast of the request can be translated:
     * each setter becomes a {@code toBuilder()} copy that is assigned back to the request variable.
     */
    private static boolean isConvertibleBody(J.MethodDeclaration md) {
        boolean[] ok = {true};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                JavaType.Method mt = method.getMethodType();
                if (isSuperBeforeExecution(method)) {
                    return method;
                }
                if (mt == null) {
                    ok[0] = false;
                    return method;
                }
                String declaring = mt.getDeclaringType().getFullyQualifiedName();
                if (declaring.startsWith("com.amazonaws.") && !isModel(declaring)) {
                    ok[0] = false;
                } else if (isModel(declaring) && method.getSimpleName().startsWith("with")) {
                    ok[0] = false;
                } else if (isModelSetter(method) && aliasSource(md, method) == null) {
                    ok[0] = false;
                }
                return super.visitMethodInvocation(method, p);
            }
        }.visit(md.getBody(), 0);
        return ok[0];
    }

    static J.ClassDeclaration convert(J.ClassDeclaration cd, J.MethodDeclaration beforeExecution, Cursor classCursor, ExecutionContext ctx) {
        J.MethodDeclaration rewritten = (J.MethodDeclaration) new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = (J.Block) super.visitBlock(block, ctx);
                // v2-migration turns `alias.setX(v)` into `alias = alias.toBuilder().x(v).build()`; the copy must also be returned
                return b.withStatements(ListUtils.flatMap(b.getStatements(), s -> {
                    if (s instanceof J.MethodInvocation && isModelSetter((J.MethodInvocation) s)) {
                        J.Identifier request = aliasSource(beforeExecution, (J.MethodInvocation) s);
                        if (request != null) {
                            J.Identifier alias = (J.Identifier) Objects.requireNonNull(((J.MethodInvocation) s).getSelect());
                            return Arrays.asList(s, new J.Assignment(randomId(), s.getPrefix(), Markers.EMPTY,
                                    request.withId(randomId()).withPrefix(Space.EMPTY),
                                    JLeftPadded.<Expression>build(alias.withId(randomId()).withPrefix(Space.SINGLE_SPACE)).withBefore(Space.SINGLE_SPACE),
                                    request.getType()));
                        }
                    }
                    return s;
                }));
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (isSuperBeforeExecution(m)) {
                    return m.getArguments().get(0).withPrefix(m.getPrefix());
                }
                return m;
            }
        }.visitNonNull(beforeExecution, ctx, new Cursor(classCursor, cd.getBody()));

        String requestName = beforeExecution.getParameters().get(0) instanceof J.VariableDeclarations ?
                ((J.VariableDeclarations) beforeExecution.getParameters().get(0)).getVariables().get(0).getSimpleName() : "request";
        String contextName = "context".equals(requestName) ? "modifyRequestContext" : "context";
        J.MethodDeclaration modifyRequest = JavaTemplate.builder(
                        "@Override\n" +
                        "public SdkRequest modifyRequest(Context.ModifyRequest " + contextName + ", ExecutionAttributes executionAttributes) {\n" +
                        "    SdkRequest " + requestName + " = " + contextName + ".request();\n" +
                        "}")
                .imports("software.amazon.awssdk.core.SdkRequest", "software.amazon.awssdk.core.interceptor.Context",
                        "software.amazon.awssdk.core.interceptor.ExecutionAttributes")
                .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "sdk-core", "utils"))
                .build()
                .apply(new Cursor(new Cursor(classCursor, cd.getBody()), beforeExecution), beforeExecution.getCoordinates().replace());
        J.Block body = Objects.requireNonNull(modifyRequest.getBody());
        List<Statement> statements = new ArrayList<>(body.getStatements());
        statements.addAll(Objects.requireNonNull(rewritten.getBody()).getStatements());
        J.MethodDeclaration replacement = modifyRequest.withPrefix(beforeExecution.getPrefix())
                .withBody(body.withStatements(statements).withEnd(rewritten.getBody().getEnd()));

        J.ClassDeclaration c = cd.withBody(cd.getBody().withStatements(ListUtils.map(cd.getBody().getStatements(),
                s -> s == beforeExecution ? replacement : s)));
        J.Identifier iface = new J.Identifier(randomId(), Space.SINGLE_SPACE, Markers.EMPTY, emptyList(), "ExecutionInterceptor",
                JavaType.ShallowClass.build(EXECUTION_INTERCEPTOR), null);
        JContainer<TypeTree> implementsContainer = c.getPadding().getImplements();
        c = c.withExtends(null);
        if (implementsContainer == null) {
            c = c.getPadding().withImplements(JContainer.build(Space.SINGLE_SPACE, singletonList(JRightPadded.build(iface)), Markers.EMPTY));
        } else {
            c = c.getPadding().withImplements(implementsContainer.getPadding().withElements(
                    ListUtils.concat(implementsContainer.getPadding().getElements(), JRightPadded.build(iface))));
        }
        if (c.getType() instanceof JavaType.Class) {
            JavaType.Class type = (JavaType.Class) c.getType();
            c = c.withType(type.withSupertype(null)
                    .withInterfaces(ListUtils.concat(type.getInterfaces(), JavaType.ShallowClass.build(EXECUTION_INTERCEPTOR))));
        }
        return c;
    }

    private static boolean isSuperBeforeExecution(J.MethodInvocation m) {
        return "beforeExecution".equals(m.getSimpleName()) && m.getSelect() instanceof J.Identifier &&
               "super".equals(((J.Identifier) m.getSelect()).getSimpleName()) && m.getArguments().size() == 1;
    }

    private static boolean isModel(String fqn) {
        return fqn.startsWith("com.amazonaws.services.") && fqn.contains(".model.");
    }

    private static boolean isModelSetter(J.MethodInvocation m) {
        JavaType.Method mt = m.getMethodType();
        return mt != null && isModel(mt.getDeclaringType().getFullyQualifiedName()) && m.getSimpleName().startsWith("set") &&
               m.getArguments().size() == 1 && m.getSelect() instanceof J.Identifier;
    }

    /**
     * For {@code alias.setX(..)}, the request variable {@code alias} was cast from: {@code T alias = (T) request;}.
     */
    private static J.@Nullable Identifier aliasSource(J.MethodDeclaration md, J.MethodInvocation setter) {
        if (!(setter.getSelect() instanceof J.Identifier)) {
            return null;
        }
        String alias = ((J.Identifier) setter.getSelect()).getSimpleName();
        J.Identifier[] source = {null};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                if (alias.equals(variable.getSimpleName()) && variable.getInitializer() instanceof J.TypeCast &&
                    ((J.TypeCast) variable.getInitializer()).getExpression() instanceof J.Identifier) {
                    J.Identifier from = (J.Identifier) ((J.TypeCast) variable.getInitializer()).getExpression();
                    if (TypeUtils.isOfClassType(from.getType(), V1_REQUEST)) {
                        source[0] = from;
                    }
                }
                return variable;
            }
        }.visit(md, 0);
        return source[0];
    }
}

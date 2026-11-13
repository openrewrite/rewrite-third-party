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
import org.openrewrite.groovy.GroovyVisitor;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import software.amazon.awssdk.v2migration.internal.utils.NamingConversionUtils;

import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.openrewrite.Tree.randomId;

/**
 * AWS's v2-migration and the Java steps of this migration only edit Java, so Groovy sources, typically Spock tests of
 * migrated Java code, keep v1 types the code under test no longer accepts. Groovy LSTs type imports, class references
 * and constructors but rarely calls on {@code def} locals, so this converts by shape rather than by method type.
 */
public class MigrateAwsSdkV1Groovy extends Recipe {

    private static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";
    private static final Set<String> SPOCK_MOCK_FACTORIES = new HashSet<>(Arrays.asList("Mock", "Stub", "Spy", "GroovyMock", "GroovyStub", "GroovySpy"));

    @Getter
    final String displayName = "Migrate AWS SDK v1 in Groovy sources to v2";

    @Getter
    final String description = "Renames v1 client and model types in Groovy sources to their v2 equivalents, turns `new Model()` " +
                               "followed by setters on the same variable into a v2 `Model.builder()...build()` chain, and makes " +
                               "interactions stubbed on a Spock mock of a v1 async client return a completed `CompletableFuture`, " +
                               "as the v2 async client's methods do.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>("com.amazonaws..*", false), new GroovyVisitor<ExecutionContext>() {
            @Override
            public J visitCompilationUnit(G.CompilationUnit cu, ExecutionContext ctx) {
                Set<String> v1Types = new TreeSet<>();
                for (JavaType type : cu.getTypesInUse().getTypesInUse()) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                    if (fq != null && v2Type(fq.getFullyQualifiedName()) != null) {
                        v1Types.add(fq.getFullyQualifiedName());
                    }
                }
                for (J.Import imp : cu.getImports()) {
                    String name = imp.getTypeName();
                    if (!imp.isStatic() && v2Type(name) != null) {
                        v1Types.add(name);
                    }
                }
                J c = super.visitCompilationUnit(cu, ctx);
                for (String v1 : v1Types) {
                    doAfterVisit(new ChangeType(v1, Objects.requireNonNull(v2Type(v1)), null).getVisitor());
                }
                return c;
            }

            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                // Folded before the children are visited, which would turn the constructor into a builder on its own
                return super.visitBlock(foldSetters(block), ctx);
            }

            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J j = super.visitNewClass(newClass, ctx);
                if (j instanceof J.NewClass) {
                    JavaType.FullyQualified model = v1Model((J.NewClass) j);
                    if (model != null) {
                        return builderChain((J.NewClass) j, model, emptyList());
                    }
                }
                return j;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (!SPOCK_MOCK_FACTORIES.contains(m.getSimpleName()) || m.getArguments().size() < 2 ||
                    !(m.getArguments().get(m.getArguments().size() - 1) instanceof J.Lambda) || !mocksV1AsyncClient(m.getArguments().get(0))) {
                    return m;
                }
                J.Lambda closure = (J.Lambda) m.getArguments().get(m.getArguments().size() - 1);
                J.Lambda stubbed = (J.Lambda) new JavaVisitor<Integer>() {
                    @Override
                    public J visitLambda(J.Lambda lambda, Integer p) {
                        // Interactions of nested closures belong to other mocks
                        return lambda == closure ? super.visitLambda(lambda, p) : lambda;
                    }

                    @Override
                    public J visitBinary(J.Binary binary, Integer p) {
                        return binary.getOperator() == J.Binary.Type.RightShift ? completeStub(binary) : binary;
                    }
                }.visitNonNull(closure, 0);
                if (stubbed == closure) {
                    return m;
                }
                maybeAddImport(COMPLETABLE_FUTURE, null, false);
                return m.withArguments(ListUtils.mapLast(m.getArguments(), a -> stubbed));
            }
        });
    }

    static @Nullable String v2Type(String v1) {
        if (!AwsSdkV2Coordinates.isV1SdkType(v1)) {
            return null;
        }
        String client = MigrateAwsSdkV1Kotlin.v2Type(v1);
        if (client != null) {
            return client;
        }
        return isModel(v1) ? NamingConversionUtils.getV2Equivalent(v1) : null;
    }

    private static boolean isModel(String fqn) {
        int model = fqn.indexOf(".model.");
        return fqn.startsWith("com.amazonaws.services.") && model > 0 && fqn.indexOf('.', model + ".model.".length()) < 0 &&
               !fqn.endsWith("Exception") && !fqn.contains("$");
    }

    private static JavaType.@Nullable FullyQualified v1Model(J.NewClass n) {
        if (n.getBody() != null || n.getArguments().stream().anyMatch(a -> !(a instanceof J.Empty))) {
            return null;
        }
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(n.getType());
        if (type == null && n.getClazz() != null) {
            type = TypeUtils.asFullyQualified(n.getClazz().getType());
        }
        return type != null && isModel(type.getFullyQualifiedName()) ? type : null;
    }

    /**
     * {@code def x = new Model()} followed by {@code x.setY(v)} statements becomes {@code def x = Model.builder().y(v).build()}.
     */
    private static J.Block foldSetters(J.Block b) {
        List<Statement> statements = b.getStatements();
        List<Statement> result = new ArrayList<>(statements.size());
        boolean changed = false;
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            J.VariableDeclarations.NamedVariable var = s instanceof J.VariableDeclarations &&
                                                       ((J.VariableDeclarations) s).getVariables().size() == 1 ?
                    ((J.VariableDeclarations) s).getVariables().get(0) : null;
            JavaType.FullyQualified model = var != null && var.getInitializer() instanceof J.NewClass ?
                    v1Model((J.NewClass) var.getInitializer()) : null;
            if (model == null) {
                result.add(s);
                continue;
            }
            List<J.MethodInvocation> setters = new ArrayList<>();
            while (i + 1 < statements.size()) {
                J.MethodInvocation setter = setterOn(statements.get(i + 1), var.getSimpleName());
                if (setter == null) {
                    break;
                }
                setters.add(setter);
                i++;
            }
            J.NewClass init = (J.NewClass) Objects.requireNonNull(var.getInitializer());
            J.VariableDeclarations.NamedVariable folded = var.withInitializer(builderChain(init, model, setters));
            result.add(((J.VariableDeclarations) s).withVariables(singletonList(folded)));
            changed = true;
        }
        return changed ? b.withStatements(result) : b;
    }

    private static J.@Nullable MethodInvocation setterOn(Statement s, String variable) {
        J j = s instanceof G.ExpressionStatement ? ((G.ExpressionStatement) s).getExpression() : s;
        if (!(j instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation m = (J.MethodInvocation) j;
        String name = m.getSimpleName();
        boolean setter = name.length() > 3 && (name.startsWith("set") || name.startsWith("with")) &&
                         Character.isUpperCase(name.charAt(name.startsWith("set") ? 3 : 4));
        return setter && m.getArguments().size() == 1 && !(m.getArguments().get(0) instanceof J.Empty) &&
               m.getSelect() instanceof J.Identifier && variable.equals(((J.Identifier) m.getSelect()).getSimpleName()) ? m : null;
    }

    private static Expression builderChain(J.NewClass n, JavaType.FullyQualified model, List<J.MethodInvocation> setters) {
        // Keeps the v1 type, which the ChangeType run afterwards renames along with the imports
        Expression chain = call(new J.Identifier(randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), model.getClassName(), model, null),
                "builder", emptyList());
        for (J.MethodInvocation setter : setters) {
            String name = setter.getSimpleName().substring(setter.getSimpleName().startsWith("set") ? 3 : 4);
            chain = call(chain, Character.toLowerCase(name.charAt(0)) + name.substring(1),
                    singletonList(setter.getArguments().get(0).withPrefix(Space.EMPTY)));
        }
        return call(chain, "build", emptyList()).withPrefix(n.getPrefix());
    }

    private static J.MethodInvocation call(Expression select, String name, List<Expression> args) {
        List<JRightPadded<Expression>> padded = new ArrayList<>();
        if (args.isEmpty()) {
            padded.add(JRightPadded.build(new J.Empty(randomId(), Space.EMPTY, Markers.EMPTY)));
        }
        for (Expression a : args) {
            padded.add(JRightPadded.build(a));
        }
        return new J.MethodInvocation(randomId(), Space.EMPTY, Markers.EMPTY, JRightPadded.build(select), null,
                new J.Identifier(randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), name, null, null),
                JContainer.build(Space.EMPTY, padded, Markers.EMPTY), null);
    }

    private static boolean mocksV1AsyncClient(Expression mocked) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(mocked.getType());
        if (type instanceof JavaType.Parameterized && "java.lang.Class".equals(type.getFullyQualifiedName()) &&
            !((JavaType.Parameterized) type).getTypeParameters().isEmpty()) {
            type = TypeUtils.asFullyQualified(((JavaType.Parameterized) type).getTypeParameters().get(0));
        }
        String v2 = type == null ? null : MigrateAwsSdkV1Kotlin.v2Type(type.getFullyQualifiedName());
        return v2 != null && v2.endsWith("AsyncClient");
    }

    /**
     * v1 async clients also expose the blocking methods, and Spock stubs return the result itself; the v2 async client's
     * methods, including those that were {@code xAsync} in v1, return a {@code CompletableFuture}.
     */
    private static J completeStub(J.Binary stub) {
        Expression left = stub.getLeft();
        boolean counted = left instanceof J.Binary && ((J.Binary) left).getOperator() == J.Binary.Type.Multiplication;
        Expression interaction = counted ? ((J.Binary) left).getRight() : left;
        if (!(interaction instanceof J.MethodInvocation) || ((J.MethodInvocation) interaction).getSelect() != null ||
            stub.getRight() instanceof J.Lambda ||
            stub.getRight() instanceof J.MethodInvocation && "completedFuture".equals(((J.MethodInvocation) stub.getRight()).getSimpleName())) {
            return stub;
        }
        J.MethodInvocation call = (J.MethodInvocation) interaction;
        if (call.getSimpleName().endsWith("Async")) {
            // v1's own future is not a CompletableFuture; such stubs keep their value and get only the v2 name
            J.MethodInvocation renamed = call.withName(call.getName().withSimpleName(
                    call.getSimpleName().substring(0, call.getSimpleName().length() - "Async".length())));
            return stub.withLeft(counted ? ((J.Binary) left).withRight(renamed) : renamed);
        }
        J.Identifier future = new J.Identifier(randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), "CompletableFuture",
                JavaType.ShallowClass.build(COMPLETABLE_FUTURE), null);
        return stub.withRight(call(future, "completedFuture", singletonList(stub.getRight().withPrefix(Space.EMPTY)))
                .withPrefix(stub.getRight().getPrefix()));
    }
}

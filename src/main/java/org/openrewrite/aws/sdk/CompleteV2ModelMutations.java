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
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.trait.Comments;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static java.util.Collections.*;
import static org.openrewrite.Tree.randomId;

/**
 * v1 request and model objects are mutable, so code builds one in one place and sets or "withs" values on it elsewhere. v2's are
 * immutable: v2-migration rebuilds them only for a {@code setX} statement on a variable, and renames every other {@code withX}
 * to a method only the builder has.
 */
public class CompleteV2ModelMutations extends ScanningRecipe<CompleteV2ModelMutations.Accumulator> {

    private static final Pattern V2_MODEL = Pattern.compile("software\\.amazon\\.awssdk\\.services\\.[a-z0-9]+\\.model\\.[A-Z][A-Za-z0-9]*");
    private static final Set<String> MODEL_METHODS = new HashSet<>(Arrays.asList("equals", "equalsBySdkFields", "getValueForField",
            "copy", "toBuilder", "fromValue", "knownValues", "builder", "serializableBuilderClass", "sdkFields", "sdkFieldNameToField",
            "hashCode", "toString", "getClass", "wait", "notify", "notifyAll", "compareTo"));

    @Getter
    final String displayName = "Rebuild immutable AWS SDK v2 models that v1 code mutated";

    @Getter
    final String description = "Runs after `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. A v1 `withX(..)` or `setX(..)` " +
            "statement on a request or model object becomes `r = r.toBuilder().x(..).build()`, and a `withX(..)` inside an " +
            "expression becomes `r.toBuilder().x(..).build()`. A private void method that rebuilds a parameter it was given returns " +
            "the rebuilt value, and its callers in the class store it back, so the change still reaches them. A non-private method " +
            "that set fields of a v1 request parameter does the same with its callers anywhere in the repository; a caller that " +
            "passes an expression rather than a variable is marked, since there is nothing to store the rebuilt value in.";

    private static final Pattern V1_MODEL = Pattern.compile("com\\.amazonaws\\.services\\.[a-z0-9]+\\.model\\..*");
    private static final AnnotationMatcher OVERRIDE = new AnnotationMatcher("@java.lang.Override");

    public static class Accumulator {
        // declaring type#name/arity of non-private methods that mutated a v1 model parameter, to the parameter's index
        final Map<String, Integer> mutating = new ConcurrentHashMap<>();
        final Set<String> overridden = ConcurrentHashMap.newKeySet();
        final Map<String, Set<String>> declarations = new ConcurrentHashMap<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    static String key(JavaType.@Nullable FullyQualified declaring, String name, int arity) {
        return (declaring == null ? "" : declaring.getFullyQualifiedName()) + "#" + name + "/" + arity;
    }

    /**
     * Runs on the v1 code, before v2-migration: finds non-private void methods whose v1 request or model parameter is set
     * through statements, which v1 callers saw through the shared object.
     */
    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                JavaType.Method mt = md.getMethodType();
                if (mt == null || md.getBody() == null || md.hasModifier(J.Modifier.Type.Private) ||
                    md.getReturnTypeExpression() == null || md.getReturnTypeExpression().getType() != JavaType.Primitive.Void) {
                    return md;
                }
                String key = key(mt.getDeclaringType(), md.getSimpleName(), md.getParameters().size());
                acc.declarations.computeIfAbsent(md.getSimpleName() + "/" + md.getParameters().size(), k -> ConcurrentHashMap.newKeySet())
                        .add(mt.getDeclaringType().getFullyQualifiedName());
                if (md.getLeadingAnnotations().stream().anyMatch(OVERRIDE::matches)) {
                    acc.overridden.add(key);
                    return md;
                }
                Map<String, Integer> params = new HashMap<>();
                for (int i = 0; i < md.getParameters().size(); i++) {
                    if (md.getParameters().get(i) instanceof J.VariableDeclarations) {
                        J.VariableDeclarations p = (J.VariableDeclarations) md.getParameters().get(i);
                        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(p.getType());
                        if (p.getVariables().size() == 1 && fq != null && V1_MODEL.matcher(fq.getFullyQualifiedName()).matches() &&
                            fq.getKind() != JavaType.FullyQualified.Kind.Enum) {
                            params.put(p.getVariables().get(0).getSimpleName(), i);
                        }
                    }
                }
                if (params.isEmpty()) {
                    return md;
                }
                Set<Integer> mutated = new HashSet<>();
                new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, Integer p) {
                        if (getCursor().getParentTreeCursor().getValue() instanceof J.Block && m.getArguments().size() == 1 &&
                            (m.getSimpleName().startsWith("set") || m.getSimpleName().startsWith("with"))) {
                            Expression root = m.getSelect();
                            while (root instanceof J.MethodInvocation) {
                                root = ((J.MethodInvocation) root).getSelect();
                            }
                            if (root instanceof J.Identifier && params.containsKey(((J.Identifier) root).getSimpleName())) {
                                mutated.add(params.get(((J.Identifier) root).getSimpleName()));
                            }
                        }
                        return super.visitMethodInvocation(m, p);
                    }

                    @Override
                    public J.Lambda visitLambda(J.Lambda l, Integer p) {
                        return l;
                    }
                }.visit(md.getBody(), 0);
                if (mutated.size() == 1) {
                    acc.mutating.put(key, mutated.iterator().next());
                }
                return md;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        Map<String, Integer> mutating = new HashMap<>(acc.mutating);
        mutating.keySet().removeAll(acc.overridden);
        // A same-named method in another class may override this one
        mutating.keySet().removeIf(k -> acc.declarations.getOrDefault(k.substring(k.indexOf('#') + 1), emptySet()).size() > 1);
        return JavaOnly.check(new UsesType<>("software.amazon.awssdk.services..*", true), new JavaVisitor<ExecutionContext>() {

            @Override
            public J visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = (J.MethodDeclaration) super.visitMethodDeclaration(method, ctx);
                JavaType.Method mt = md.getMethodType();
                Integer index = mt == null ? null : mutating.get(key(mt.getDeclaringType(), md.getSimpleName(), md.getParameters().size()));
                if (index == null || md.getBody() == null || md.getReturnTypeExpression() == null ||
                    md.getReturnTypeExpression().getType() != JavaType.Primitive.Void ||
                    !(md.getParameters().get(index) instanceof J.VariableDeclarations) ||
                    !isV2ModelType(((J.VariableDeclarations) md.getParameters().get(index)).getType())) {
                    return md;
                }
                return returnParameter(md, index);
            }

            private @Nullable J storeResult(J.MethodInvocation call) {
                JavaType.Method mt = call.getMethodType();
                Integer index = mt == null ? null : mutating.get(key(mt.getDeclaringType(), call.getSimpleName(), call.getArguments().size()));
                if (index == null || !(getCursor().getParentTreeCursor().getValue() instanceof J.Block)) {
                    return null;
                }
                Expression arg = call.getArguments().get(index);
                if (!(arg instanceof J.Identifier) && !(arg instanceof J.FieldAccess)) {
                    return SearchResult.found(call, "Returns the rebuilt request, which v1 updated in place; store it where this caller needs it");
                }
                return new J.Assignment(randomId(), call.getPrefix(), Markers.EMPTY, arg.withPrefix(Space.EMPTY),
                        JLeftPadded.build((Expression) call.withPrefix(Space.SINGLE_SPACE)).withBefore(Space.SINGLE_SPACE),
                        arg.getType());
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J j = super.visitMethodInvocation(method, ctx);
                if (!(j instanceof J.MethodInvocation)) {
                    return j;
                }
                J.MethodInvocation m = (J.MethodInvocation) j;
                J stored = storeResult(m);
                if (stored != null) {
                    return stored;
                }
                J chained = chainedSetters(method, ctx);
                if (chained != null) {
                    return chained;
                }
                Expression select = m.getSelect();
                if (select == null || m.getArguments().size() != 1 || m.getArguments().get(0) instanceof J.Empty ||
                    !isV2Model(select) || MODEL_METHODS.contains(m.getSimpleName()) ||
                    V2MigrationScope.isMockitoStubbedOrVerified(getCursor())) {
                    return m;
                }
                String property = property(m.getSimpleName());
                boolean statement = getCursor().getParentTreeCursor().getValue() instanceof J.Block;
                if (statement && (select instanceof J.Identifier || select instanceof J.FieldAccess)) {
                    return JavaTemplate.builder("#{any()} = #{any()}.toBuilder()." + property + "(#{any()}).build()")
                            .contextSensitive()
                            .build()
                            .apply(getCursor(), method.getCoordinates().replace(), select, select, m.getArguments().get(0));
                }
                if (!statement) {
                    return JavaTemplate.apply("#{any()}.toBuilder()." + property + "(#{any()}).build()", getCursor(), method.getCoordinates().replace(), select, m.getArguments().get(0));
                }
                return m;
            }

            /**
             * A statement of several v1 setters chained on one model, such as {@code r.withA(a).withB(b);}, rebuilds it once.
             */
            private @Nullable J chainedSetters(J.MethodInvocation original, ExecutionContext ctx) {
                if (!(getCursor().getParentTreeCursor().getValue() instanceof J.Block) || V2MigrationScope.isMockitoStubbedOrVerified(getCursor())) {
                    return null;
                }
                List<J.MethodInvocation> links = new ArrayList<>();
                Expression e = original;
                while (e instanceof J.MethodInvocation) {
                    J.MethodInvocation link = (J.MethodInvocation) e;
                    if (link.getArguments().size() != 1 || link.getArguments().get(0) instanceof J.Empty ||
                        MODEL_METHODS.contains(link.getSimpleName())) {
                        return null;
                    }
                    links.add(0, link);
                    e = link.getSelect();
                }
                if (links.size() < 2 || !(e instanceof J.Identifier || e instanceof J.FieldAccess) || !isV2Model(e)) {
                    return null;
                }
                StringBuilder code = new StringBuilder("#{any()} = #{any()}.toBuilder()");
                List<Object> args = new ArrayList<>(Arrays.asList(e, e));
                for (J.MethodInvocation link : links) {
                    code.append('.').append(property(link.getSimpleName())).append("(#{any()})");
                    args.add(link.getArguments().get(0));
                }
                return JavaTemplate.builder(code.append(".build()").toString())
                        .contextSensitive()
                        .build()
                        .apply(getCursor(), original.getCoordinates().replace(), args.toArray());
            }

            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = (J.Block) super.visitBlock(block, ctx);
                return b.withStatements(foldRebuilds(b.getStatements(), new Cursor(getCursor().getParentOrThrow(), b)));
            }

            @Override
            public J visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration c = (J.ClassDeclaration) super.visitClassDeclaration(classDecl, ctx);
                Map<String, Integer> returning = new HashMap<>();
                c = c.withBody(c.getBody().withStatements(ListUtils.map(c.getBody().getStatements(), s -> {
                    if (!(s instanceof J.MethodDeclaration)) {
                        return s;
                    }
                    J.MethodDeclaration md = (J.MethodDeclaration) s;
                    Integer index = rebuiltParameter(md);
                    if (index == null) {
                        return s;
                    }
                    returning.put(md.getSimpleName() + "/" + md.getParameters().size(), index);
                    return returnParameter(md, index);
                })));
                if (returning.isEmpty()) {
                    return c;
                }
                return new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.Block visitBlock(J.Block block, Integer p) {
                        J.Block b = super.visitBlock(block, p);
                        return b.withStatements(ListUtils.map(b.getStatements(), s -> {
                            if (!(s instanceof J.MethodInvocation)) {
                                return s;
                            }
                            J.MethodInvocation call = (J.MethodInvocation) s;
                            Integer index = returning.get(call.getSimpleName() + "/" + call.getArguments().size());
                            if (index == null || !(call.getSelect() == null || call.getSelect() instanceof J.Identifier &&
                                                                         "this".equals(((J.Identifier) call.getSelect()).getSimpleName())) ||
                                !(call.getArguments().get(index) instanceof J.Identifier)) {
                                return s;
                            }
                            J.Identifier target = (J.Identifier) call.getArguments().get(index);
                            return new J.Assignment(randomId(), call.getPrefix(), Markers.EMPTY, target.withPrefix(Space.EMPTY),
                                    JLeftPadded.build((Expression) call.withPrefix(Space.SINGLE_SPACE)).withBefore(Space.SINGLE_SPACE),
                                    target.getType());
                        }));
                    }
                }.visitNonNull(c, 0, getCursor().getParentOrThrow());
            }

            /**
             * The parameter a private void method rebuilds, when it rebuilds exactly one.
             */
            private @Nullable Integer rebuiltParameter(J.MethodDeclaration md) {
                if (md.getBody() == null || !md.hasModifier(J.Modifier.Type.Private) || md.getReturnTypeExpression() == null ||
                    md.getReturnTypeExpression().getType() != JavaType.Primitive.Void) {
                    return null;
                }
                Map<String, Integer> params = new HashMap<>();
                for (int i = 0; i < md.getParameters().size(); i++) {
                    if (md.getParameters().get(i) instanceof J.VariableDeclarations) {
                        J.VariableDeclarations p = (J.VariableDeclarations) md.getParameters().get(i);
                        if (p.getVariables().size() == 1 && isV2ModelType(p.getType())) {
                            params.put(p.getVariables().get(0).getSimpleName(), i);
                        }
                    }
                }
                if (params.isEmpty()) {
                    return null;
                }
                Set<Integer> rebuilt = new HashSet<>();
                boolean[] lambda = {false};
                new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.Lambda visitLambda(J.Lambda l, Integer p) {
                        lambda[0] = true;
                        return l;
                    }

                    @Override
                    public J.Assignment visitAssignment(J.Assignment assignment, Integer p) {
                        if (assignment.getVariable() instanceof J.Identifier &&
                            params.containsKey(((J.Identifier) assignment.getVariable()).getSimpleName()) &&
                            rebuilds(assignment.getAssignment())) {
                            rebuilt.add(params.get(((J.Identifier) assignment.getVariable()).getSimpleName()));
                        }
                        return super.visitAssignment(assignment, p);
                    }
                }.visit(md.getBody(), 0);
                return rebuilt.size() == 1 && !lambda[0] ? rebuilt.iterator().next() : null;
            }

            private J.MethodDeclaration returnParameter(J.MethodDeclaration md, int index) {
                J.VariableDeclarations param = (J.VariableDeclarations) md.getParameters().get(index);
                String name = param.getVariables().get(0).getSimpleName();
                TypeTree type = Objects.requireNonNull(param.getTypeExpression());
                J.Identifier ref = new J.Identifier(randomId(), Space.SINGLE_SPACE, Markers.EMPTY, emptyList(), name,
                        param.getType(), null);
                J.Block body = Objects.requireNonNull(md.getBody());
                body = (J.Block) new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.Return visitReturn(J.Return r, Integer p) {
                        return r.getExpression() == null ? r.withExpression(ref.withId(randomId())) : r;
                    }

                    @Override
                    public J.Lambda visitLambda(J.Lambda l, Integer p) {
                        return l;
                    }

                    @Override
                    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, Integer p) {
                        return cd;
                    }
                }.visitNonNull(body, 0);
                List<Statement> statements = body.getStatements();
                if (statements.isEmpty() || !(statements.get(statements.size() - 1) instanceof J.Return)) {
                    Space indent = statements.isEmpty() ? Space.format("\n") : statements.get(statements.size() - 1).getPrefix();
                    body = body.withStatements(ListUtils.concat(statements,
                            new J.Return(randomId(), indent, Markers.EMPTY, ref.withId(randomId()))));
                }
                JavaType.Method mt = md.getMethodType() == null ? null : md.getMethodType().withReturnType(param.getType());
                return md.withReturnTypeExpression(((TypeTree) type.withId(randomId())).withPrefix(md.getReturnTypeExpression().getPrefix()))
                        .withBody(body)
                        .withMethodType(mt)
                        .withName(md.getName().withType(mt));
            }
        });
    }

    /**
     * Rebuilds that directly follow the declaration of the model they rebuild join its builder chain, as v1 code that built a
     * model with setters reads best as one builder.
     */
    private static List<Statement> foldRebuilds(List<Statement> statements, Cursor blockCursor) {
        List<Statement> result = new ArrayList<>(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            Statement st = statements.get(i);
            if (!(st instanceof J.VariableDeclarations) || ((J.VariableDeclarations) st).getVariables().size() != 1 ||
                !isV2ModelType(((J.VariableDeclarations) st).getType())) {
                result.add(st);
                continue;
            }
            J.VariableDeclarations decl = (J.VariableDeclarations) st;
            J.VariableDeclarations.NamedVariable var = decl.getVariables().get(0);
            if (!(var.getInitializer() instanceof J.MethodInvocation) || !"build".equals(((J.MethodInvocation) var.getInitializer()).getSimpleName()) ||
                !(((J.MethodInvocation) var.getInitializer()).getSelect() instanceof J.MethodInvocation)) {
                result.add(st);
                continue;
            }
            J.MethodInvocation build = (J.MethodInvocation) var.getInitializer();
            Expression chain = build.getSelect();
            boolean droppedBucket = false;
            int j = i + 1;
            for (; j < statements.size(); j++) {
                J.MethodInvocation link = rebuildOf(statements.get(j), var.getSimpleName());
                if (link == null) {
                    break;
                }
                if ("bucket".equals(link.getSimpleName()) && TypeUtils.isOfClassType(decl.getType(), "software.amazon.awssdk.services.s3.model.S3Object")) {
                    droppedBucket = true;
                    continue;
                }
                chain = link.withSelect(chain).withArguments(ListUtils.map(link.getArguments(), a -> longLiteral(link, a)));
            }
            if (j == i + 1) {
                result.add(st);
                continue;
            }
            J.VariableDeclarations folded = decl.withVariables(singletonList(var.withInitializer(build.withSelect(chain))));
            if (droppedBucket) {
                folded = Comments.of(new Cursor(blockCursor, folded))
                        .comment(" v1's S3ObjectSummary held its bucket; v2's S3Object does not, the listing request names it");
            }
            result.add(folded);
            i = j - 1;
        }
        return result;
    }

    /**
     * The {@code p(v)} link of a {@code name = name.toBuilder().p(v).build();} statement.
     */
    private static J.@Nullable MethodInvocation rebuildOf(Statement st, String name) {
        if (!(st instanceof J.Assignment) || !(((J.Assignment) st).getVariable() instanceof J.Identifier) ||
            !name.equals(((J.Identifier) ((J.Assignment) st).getVariable()).getSimpleName()) ||
            !(((J.Assignment) st).getAssignment() instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation build = (J.MethodInvocation) ((J.Assignment) st).getAssignment();
        if (!"build".equals(build.getSimpleName()) || !(build.getSelect() instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation link = (J.MethodInvocation) build.getSelect();
        if (link.getArguments().size() != 1 || !(link.getSelect() instanceof J.MethodInvocation) ||
            !"toBuilder".equals(((J.MethodInvocation) link.getSelect()).getSimpleName()) ||
            !(((J.MethodInvocation) link.getSelect()).getSelect() instanceof J.Identifier) ||
            !name.equals(((J.Identifier) ((J.MethodInvocation) link.getSelect()).getSelect()).getSimpleName())) {
            return null;
        }
        return link;
    }

    /**
     * v1 setters took primitive longs, so int literals passed to them do not box to v2's {@code Long} properties.
     */
    private static Expression longLiteral(J.MethodInvocation link, Expression arg) {
        if (!(arg instanceof J.Literal) || ((J.Literal) arg).getType() != JavaType.Primitive.Int || ((J.Literal) arg).getValue() == null) {
            return arg;
        }
        JavaType.Method mt = link.getMethodType();
        boolean takesLong = mt != null && mt.getParameterTypes().size() == 1 ?
                TypeUtils.isOfClassType(mt.getParameterTypes().get(0), "java.lang.Long") :
                "size".equals(link.getSimpleName()) || "contentLength".equals(link.getSimpleName());
        if (!takesLong) {
            return arg;
        }
        J.Literal l = (J.Literal) arg;
        return l.withValue(((Number) Objects.requireNonNull(l.getValue())).longValue())
                .withValueSource(l.getValueSource() + "L")
                .withType(JavaType.Primitive.Long);
    }

    private static boolean rebuilds(Expression e) {
        for (Expression x = e; x instanceof J.MethodInvocation; x = ((J.MethodInvocation) x).getSelect()) {
            if ("toBuilder".equals(((J.MethodInvocation) x).getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static String property(String name) {
        String p = name;
        if ((p.startsWith("with") || p.startsWith("set")) && p.length() > (p.startsWith("with") ? 4 : 3) &&
            Character.isUpperCase(p.charAt(p.startsWith("with") ? 4 : 3))) {
            p = p.substring(p.startsWith("with") ? 4 : 3);
            p = Character.toLowerCase(p.charAt(0)) + p.substring(1);
        }
        return p;
    }

    private static boolean isV2Model(Expression select) {
        // A class reference such as StorageClass.fromValue(..) is not an instance
        if (select instanceof J.Identifier && ((J.Identifier) select).getFieldType() == null) {
            return false;
        }
        if (select instanceof J.FieldAccess && ((J.FieldAccess) select).getName().getFieldType() == null) {
            return false;
        }
        return isV2ModelType(select.getType());
    }

    private static boolean isV2ModelType(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq == null || fq.getKind() == JavaType.FullyQualified.Kind.Enum) {
            return false;
        }
        String name = fq.getFullyQualifiedName();
        return V2_MODEL.matcher(name).matches() && !name.endsWith("Builder") && !name.endsWith("Exception");
    }
}

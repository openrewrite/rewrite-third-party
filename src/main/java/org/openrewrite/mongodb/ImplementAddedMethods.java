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

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.RemoveAnnotationVisitor;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * Adds to each class that implements an interface or abstract class of the driver the methods that driver 5 requires of
 * it and the class lacks, so that it compiles against driver 5 and does what it did before. A method the driver never
 * calls on the class throws {@link UnsupportedOperationException}; one it calls does what driver 3 did where it called
 * the class, and where nothing can, the scan keeps the driver where it is. {@link #added} says what was added where,
 * for someone to implement the methods the code needs. A method that overrides one driver 5 removed, as
 * {@code MongoIterable.forEach(Block)}, stays as a method of the class, without the {@code @Override} that would no
 * longer compile.
 */
final class ImplementAddedMethods extends JavaIsoVisitor<ExecutionContext> {

    /** For each class that methods were added to, the type that requires them and the methods. */
    final List<String> added = new ArrayList<>();

    @Override
    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
        J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);
        if (c.getKind() == J.ClassDeclaration.Kind.Type.Interface || c.getKind() == J.ClassDeclaration.Kind.Type.Annotation ||
            c.hasModifier(J.Modifier.Type.Abstract)) {
            return c;
        }
        List<AddedMethods.Missing> missing = AddedMethods.get().missing(c.getType(), Collections.emptyList(), true);
        J.Block body = implement(missing, c.getBody(), "`" + c.getSimpleName() + "`", ctx);
        return c.withBody(body);
    }

    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
        J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
        JavaType.Method type = m.getMethodType();
        if (type == null || m.getLeadingAnnotations().isEmpty() || !overridesRemovedApi(type)) {
            return m;
        }
        return (J.MethodDeclaration) new RemoveAnnotationVisitor(OVERRIDE).visitNonNull(m, ctx, getCursor().getParentOrThrow());
    }

    private static final AnnotationMatcher OVERRIDE = new AnnotationMatcher("@java.lang.Override");

    /**
     * Whether the method overrides a method of the driver that driver 5 removed, found by its erased parameter types,
     * since the type variables of the driver's declaration are not the class's.
     */
    private static boolean overridesRemovedApi(JavaType.Method method) {
        List<String> parameters = RemovedApiFinder.erasedParameterTypes(method);
        Deque<JavaType.FullyQualified> types = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        types.add(method.getDeclaringType());
        while (!types.isEmpty()) {
            JavaType.FullyQualified t = types.poll();
            if (!seen.add(t.getFullyQualifiedName())) {
                continue;
            }
            if (t != method.getDeclaringType() && RemovedApiFinder.isDriverType(t.getFullyQualifiedName())) {
                for (JavaType.Method m : t.getMethods()) {
                    if (m.getName().equals(method.getName()) && RemovedApiFinder.erasedParameterTypes(m).equals(parameters)) {
                        return RemovedApi.get().isRemoved('M', t.getFullyQualifiedName(), m.getName(), parameters);
                    }
                }
            }
            if (t.getSupertype() != null) {
                types.add(t.getSupertype());
            }
            types.addAll(t.getInterfaces());
        }
        return false;
    }

    @Override
    public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
        J.NewClass n = super.visitNewClass(newClass, ctx);
        if (n.getBody() == null || n.getClazz() == null) {
            return n;
        }
        List<JavaType.Method> declared = new ArrayList<>();
        for (Statement s : n.getBody().getStatements()) {
            if (s instanceof J.MethodDeclaration && ((J.MethodDeclaration) s).getMethodType() != null) {
                declared.add(((J.MethodDeclaration) s).getMethodType());
            }
        }
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(n.getClazz().getType());
        List<AddedMethods.Missing> missing = AddedMethods.get().missing(type, declared, false);
        return n.withBody(implement(missing, n.getBody(), "an anonymous `" + (type == null ? "" : type.getClassName()) + "`", ctx));
    }

    private J.Block implement(List<AddedMethods.Missing> missing, J.Block body, String owner, ExecutionContext ctx) {
        if (missing.isEmpty()) {
            return body;
        }
        for (AddedMethods.Missing m : missing) {
            if (m.declaration == null) {
                // The scan keeps the driver where it is for this class
                return body;
            }
        }
        Map<String, List<String>> thrown = new LinkedHashMap<>();
        Map<String, List<String>> kept = new LinkedHashMap<>();
        J.Block b = body;
        for (AddedMethods.Missing m : missing) {
            String implementation = m.method.body == null ? "throw new UnsupportedOperationException();" :
                    m.method.body.replace("\n", "\n    ");
            b = JavaTemplate.builder("@Override\n" + m.declaration + " {\n    " + implementation + "\n}")
                    .contextSensitive()
                    .javaParser(RemovedApiFixes.parser(ctx))
                    .build()
                    .apply(new Cursor(getCursor(), b), b.getCoordinates().lastStatement());
            doAfterVisit(ShortenFullyQualifiedTypeReferences.modifyOnly(b.getStatements().get(b.getStatements().size() - 1)));
            (m.method.body == null ? thrown : kept).computeIfAbsent(m.type, k -> new ArrayList<>()).add("`" + m.method.label() + "`");
        }
        for (Map.Entry<String, List<String>> e : thrown.entrySet()) {
            added.add(owner + " implements `" + LegacyUsage.simpleName(e.getKey()) + "`, to which driver 5 adds " +
                      String.join(", ", e.getValue()) + "; the methods added throw `UnsupportedOperationException` until they are implemented");
        }
        for (Map.Entry<String, List<String>> e : kept.entrySet()) {
            added.add(owner + " implements `" + LegacyUsage.simpleName(e.getKey()) + "`, to which driver 5 adds " +
                      String.join(", ", e.getValue()) + ", which the driver calls; the methods added do what driver 3 did where it called the class");
        }
        return b;
    }
}

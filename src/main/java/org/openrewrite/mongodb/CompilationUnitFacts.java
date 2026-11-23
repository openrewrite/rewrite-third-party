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
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

import java.util.*;

import static org.openrewrite.mongodb.MongoTypes.*;

/**
 * Decisions about individual variables of one compilation unit that the conversion needs before it rewrites them:
 * <ul>
 *     <li>whether a {@code DBCursor} variable becomes a {@code FindIterable} or a {@code MongoCursor},</li>
 *     <li>whether a {@code WriteResult} variable holds the result of an update or a delete, and</li>
 *     <li>which local {@code DBObject} variables can be declared {@code BasicDBObject} so they can be passed where
 *     driver 5 takes a {@code Bson}.</li>
 * </ul>
 */
final class CompilationUnitFacts {

    enum CursorKind {ITERABLE, CURSOR}

    enum WriteKind {UPDATE, UPDATE_WITH_UPSERT, DELETE}

    final Map<String, CursorKind> cursorKinds = new HashMap<>();
    final Map<String, WriteKind> writeResultKinds = new HashMap<>();
    final Set<String> basicDbObjectLocals = new HashSet<>();
    final Set<String> names = new HashSet<>();
    final List<String> blockers = new ArrayList<>();

    private static final Set<String> CURSOR_ONLY = new HashSet<>(Arrays.asList(
            "hasNext", "next", "tryNext"));
    private static final Set<String> ITERABLE_ONLY = new HashSet<>(Arrays.asList(
            "iterator", "forEach", "spliterator", "sort", "limit", "skip", "batchSize", "hint", "maxTime", "comment",
            "noCursorTimeout", "toArray", "one", "count", "size"));

    static String key(JavaType.@Nullable Variable v) {
        if (v == null) {
            return "";
        }
        return String.valueOf(v.getOwner()) + "#" + v.getName();
    }

    static CompilationUnitFacts of(JavaSourceFile cu) {
        CompilationUnitFacts facts = new CompilationUnitFacts();
        Map<String, EnumSet<CursorKind>> cursorNeeds = new HashMap<>();
        Map<String, String> cursorLabels = new HashMap<>();
        Set<String> cursorParams = new HashSet<>();
        Map<String, Integer> dbObjectAssignments = new HashMap<>();
        Set<String> dbObjectNonBasic = new HashSet<>();
        Set<String> dbObjectUsedByDriver = new HashSet<>();
        Map<String, List<WriteKind>> writeAssignments = new HashMap<>();

        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier ident, ExecutionContext ctx) {
                facts.names.add(ident.getSimpleName());
                JavaType.Variable v = ident.getFieldType();
                if (v == null || isDeclaration()) {
                    return ident;
                }
                String type = fqn(v.getType());
                if (DB_CURSOR.equals(type)) {
                    classifyCursorUse(key(v), ident.getSimpleName());
                } else if (WRITE_RESULT.equals(type)) {
                    classifyWriteResultUse(key(v));
                } else if (DB_OBJECT.equals(type)) {
                    Cursor parent = getCursor().getParentTreeCursor();
                    if (parent.getValue() instanceof J.MethodInvocation) {
                        J.MethodInvocation mi = parent.getValue();
                        String declaring = fqn(mi.getMethodType() == null ? null : mi.getMethodType().getDeclaringType());
                        if (mi.getArguments().contains(ident) && (DB_COLLECTION.equals(declaring) || DB.equals(declaring) || DB_CURSOR.equals(declaring))) {
                            dbObjectUsedByDriver.add(key(v));
                        }
                    } else if (parent.getValue() instanceof J.Assignment && ((J.Assignment) parent.getValue()).getVariable() == ident) {
                        recordDbObjectAssignment(key(v), ((J.Assignment) parent.getValue()).getAssignment());
                    }
                }
                return ident;
            }

            private boolean isDeclaration() {
                return getCursor().getParentTreeCursor().getValue() instanceof J.VariableDeclarations.NamedVariable &&
                       ((J.VariableDeclarations.NamedVariable) getCursor().getParentTreeCursor().getValue()).getName() == getCursor().getValue();
            }

            private void classifyCursorUse(String k, String name) {
                cursorLabels.put(k, name);
                Cursor parentCursor = getCursor().getParentTreeCursor();
                Object parent = parentCursor.getValue();
                J.Identifier ident = getCursor().getValue();
                EnumSet<CursorKind> needs = cursorNeeds.computeIfAbsent(k, x -> EnumSet.noneOf(CursorKind.class));
                if (parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() == ident) {
                    String m = ((J.MethodInvocation) parent).getSimpleName();
                    if (CURSOR_ONLY.contains(m)) {
                        needs.add(CursorKind.CURSOR);
                    } else if (ITERABLE_ONLY.contains(m)) {
                        needs.add(CursorKind.ITERABLE);
                        if (!(parentCursor.getParentTreeCursor().getValue() instanceof J.Block) &&
                            !"iterator".equals(m) && !"forEach".equals(m) && !"toArray".equals(m) && !"one".equals(m) &&
                            !"count".equals(m) && !"size".equals(m) && !"spliterator".equals(m)) {
                            // e.g. `return cursor.limit(10)`: fine, the result is the same iterable
                        }
                    } else if (!"close".equals(m) && !"toString".equals(m) && !"hashCode".equals(m) && !"equals".equals(m)) {
                        facts.blockers.add("calls `DBCursor." + m + "` on `" + name + "`, which driver 5 has no counterpart for");
                    }
                } else if (parent instanceof J.ForEachLoop.Control) {
                    needs.add(CursorKind.ITERABLE);
                } else if (parent instanceof J.Return) {
                    needs.add(CursorKind.ITERABLE);
                } else if (parent instanceof J.MethodInvocation || parent instanceof J.NewClass) {
                    // Passed as an argument
                    JavaType.Method m = parent instanceof J.MethodInvocation ? ((J.MethodInvocation) parent).getMethodType() :
                            ((J.NewClass) parent).getConstructorType();
                    List<Expression> args = parent instanceof J.MethodInvocation ? ((J.MethodInvocation) parent).getArguments() :
                            ((J.NewClass) parent).getArguments();
                    int i = args.indexOf(ident);
                    JavaType param = m == null || i < 0 || i >= m.getParameterTypes().size() ? null : m.getParameterTypes().get(i);
                    String paramType = fqn(param);
                    if ("java.util.Iterator".equals(paramType)) {
                        needs.add(CursorKind.CURSOR);
                    } else {
                        needs.add(CursorKind.ITERABLE);
                    }
                } else if (parent instanceof J.Assignment && ((J.Assignment) parent).getVariable() == ident) {
                    // Reassigned: the new value is adapted like the initializer
                } else if (parent instanceof J.Assignment || parent instanceof J.VariableDeclarations.NamedVariable) {
                    facts.blockers.add("copies the `DBCursor` `" + name + "` into another variable");
                }
            }

            private void classifyWriteResultUse(String k) {
                Object parent = getCursor().getParentTreeCursor().getValue();
                J.Identifier ident = getCursor().getValue();
                if (parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() == ident) {
                    String m = ((J.MethodInvocation) parent).getSimpleName();
                    if ("getN".equals(m) || "isUpdateOfExisting".equals(m) || "wasAcknowledged".equals(m)) {
                        return;
                    }
                    facts.blockers.add("calls `WriteResult." + m + "`, which has no counterpart on the driver 5 results");
                } else if (parent instanceof J.Assignment && ((J.Assignment) parent).getVariable() == ident) {
                    facts.blockers.add("reassigns a `WriteResult` variable");
                } else {
                    facts.blockers.add("passes a `WriteResult` along, whose type depends on the write that produced it");
                }
            }

            private void recordDbObjectAssignment(String k, Expression value) {
                dbObjectAssignments.merge(k, 1, Integer::sum);
                if (!isBasicDbObject(value)) {
                    dbObjectNonBasic.add(k);
                }
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                J.VariableDeclarations v = super.visitVariableDeclarations(multiVariable, ctx);
                String type = fqn(v.getType());
                boolean local = getCursor().getParentTreeCursor().getValue() instanceof J.Block &&
                                !(getCursor().getParentTreeCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration) &&
                                !(getCursor().getParentTreeCursor().getParentTreeCursor().getValue() instanceof J.NewClass);
                boolean parameter = getCursor().getParentTreeCursor().getValue() instanceof J.MethodDeclaration ||
                                    getCursor().getParentTreeCursor().getValue() instanceof J.Lambda;
                for (J.VariableDeclarations.NamedVariable nv : v.getVariables()) {
                    String k = key(nv.getVariableType());
                    if (DB_CURSOR.equals(type)) {
                        cursorLabels.put(k, nv.getSimpleName());
                        if (parameter) {
                            cursorParams.add(k);
                        }
                    } else if (WRITE_RESULT.equals(type)) {
                        WriteKind kind = local && nv.getInitializer() != null ? writeKindOf(nv.getInitializer()) : null;
                        if (kind == null) {
                            facts.blockers.add("declares the `WriteResult` `" + nv.getSimpleName() + "` other than as a local holding an update or a remove");
                        } else {
                            writeAssignments.computeIfAbsent(k, x -> new ArrayList<>()).add(kind);
                        }
                    } else if (DB_OBJECT.equals(type) && local && v.getTypeExpression() != null) {
                        dbObjectAssignments.merge(k, 0, Integer::sum);
                        if (nv.getInitializer() != null && !isBasicDbObject(nv.getInitializer())) {
                            dbObjectNonBasic.add(k);
                        }
                    }
                }
                return v;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                if (WRITE_RESULT.equals(fqn(m.getReturnTypeExpression() == null ? null : m.getReturnTypeExpression().getType()))) {
                    facts.blockers.add("declares the method `" + m.getSimpleName() + "` returning a `WriteResult`");
                }
                return m;
            }
        }.visit(cu, new org.openrewrite.InMemoryExecutionContext());

        for (Map.Entry<String, EnumSet<CursorKind>> e : cursorNeeds.entrySet()) {
            EnumSet<CursorKind> needs = e.getValue();
            if (needs.contains(CursorKind.CURSOR) && needs.contains(CursorKind.ITERABLE)) {
                facts.blockers.add("uses the `DBCursor` `" + cursorLabels.get(e.getKey()) +
                                   "` both as an iterator and as a query it modifies or iterates again");
            } else if (needs.contains(CursorKind.CURSOR)) {
                if (cursorParams.contains(e.getKey())) {
                    facts.blockers.add("iterates the `DBCursor` parameter `" + cursorLabels.get(e.getKey()) + "` by hand");
                }
                facts.cursorKinds.put(e.getKey(), CursorKind.CURSOR);
            }
        }
        for (Map.Entry<String, List<WriteKind>> e : writeAssignments.entrySet()) {
            facts.writeResultKinds.put(e.getKey(), e.getValue().get(0));
        }
        for (String k : dbObjectAssignments.keySet()) {
            if (!dbObjectNonBasic.contains(k) && dbObjectUsedByDriver.contains(k)) {
                facts.basicDbObjectLocals.add(k);
            }
        }
        return facts;
    }

    CursorKind cursorKind(JavaType.@Nullable Variable v) {
        CursorKind kind = cursorKinds.get(key(v));
        return kind == null ? CursorKind.ITERABLE : kind;
    }

    static boolean isBasicDbObject(Expression e) {
        Expression unwrapped = e;
        while (unwrapped instanceof J.Parentheses) {
            unwrapped = (Expression) ((J.Parentheses<?>) unwrapped).getTree();
        }
        if (unwrapped instanceof J.Literal && ((J.Literal) unwrapped).getValue() == null) {
            return true;
        }
        return TypeUtils.isAssignableTo(BASIC_DB_OBJECT, unwrapped.getType());
    }

    /**
     * The kind of write a {@code WriteResult} initializer performs, when it is a call the conversion translates.
     */
    static @Nullable WriteKind writeKindOf(Expression e) {
        if (!(e instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation mi = (J.MethodInvocation) e;
        if (!DB_COLLECTION.equals(fqn(mi.getMethodType() == null ? null : mi.getMethodType().getDeclaringType()))) {
            return null;
        }
        switch (mi.getSimpleName()) {
            case "remove":
                return WriteKind.DELETE;
            case "updateMulti":
                return WriteKind.UPDATE;
            case "update":
                if (mi.getArguments().size() >= 3) {
                    Expression upsert = mi.getArguments().get(2);
                    if (upsert instanceof J.Literal && Boolean.FALSE.equals(((J.Literal) upsert).getValue())) {
                        return WriteKind.UPDATE;
                    }
                    return WriteKind.UPDATE_WITH_UPSERT;
                }
                return WriteKind.UPDATE;
            default:
                return null;
        }
    }
}

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
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.*;

import java.util.List;

import static org.openrewrite.mongodb.MongoTypes.*;

/**
 * Keeps the {@code JAVA_LEGACY} UUID representation that driver 3 used by default for the legacy {@code MongoClient}s
 * of code that stays on the legacy API, since the driver 5 legacy client encodes no UUID until it is told how.
 */
final class LegacyUuidRepresentation extends JavaVisitor<ExecutionContext> {

    private static final String JAVA_LEGACY = ".uuidRepresentation(UuidRepresentation.JAVA_LEGACY)";
    private static final String OPTIONS = "MongoClientOptions.builder()" + JAVA_LEGACY + ".build()";

    private J template(Expression tree, String code, ExecutionContext ctx, Object... args) {
        J result = JavaTemplate.builder(code)
                .javaParser(RemovedApiFixes.parser(ctx))
                .imports("com.mongodb.MongoClient", "com.mongodb.MongoClientOptions", "com.mongodb.MongoClientURI",
                        "com.mongodb.ServerAddress", "org.bson.UuidRepresentation")
                .build()
                .apply(new Cursor(getCursor().getParentOrThrow(), tree), tree.getCoordinates().replace(), args);
        maybeAddImport("org.bson.UuidRepresentation");
        if (code.contains("MongoClientOptions")) {
            maybeAddImport(MONGO_CLIENT_OPTIONS);
        }
        if (code.contains("ServerAddress")) {
            maybeAddImport("com.mongodb.ServerAddress");
        }
        return result;
    }

    /**
     * Whether the builder this call or constructor starts already sets a UUID representation.
     */
    private boolean chainSetsUuidRepresentation() {
        Cursor c = getCursor();
        Object child = c.getValue();
        Cursor parent = c.getParentTreeCursor();
        while (parent.getValue() instanceof J.MethodInvocation && ((J.MethodInvocation) parent.getValue()).getSelect() == child) {
            if ("uuidRepresentation".equals(((J.MethodInvocation) parent.getValue()).getSimpleName())) {
                return true;
            }
            child = parent.getValue();
            parent = parent.getParentTreeCursor();
        }
        return false;
    }

    @Override
    public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        J j = super.visitMethodInvocation(method, ctx);
        if (!(j instanceof J.MethodInvocation)) {
            return j;
        }
        J.MethodInvocation mi = (J.MethodInvocation) j;
        JavaType.Method mt = mi.getMethodType();
        if (mt != null && MONGO_CLIENT_OPTIONS.equals(fqn(mt.getDeclaringType())) && "builder".equals(mi.getSimpleName()) &&
            (mi.getArguments().isEmpty() || mi.getArguments().get(0) instanceof J.Empty) && !chainSetsUuidRepresentation()) {
            return template(mi, "MongoClientOptions.builder()" + JAVA_LEGACY, ctx);
        }
        return mi;
    }

    @Override
    public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
        J j = super.visitNewClass(newClass, ctx);
        if (!(j instanceof J.NewClass)) {
            return j;
        }
        J.NewClass nc = (J.NewClass) j;
        String type = fqn(newClass.getType());
        if (nc.getBody() != null || type == null) {
            return nc;
        }
        List<String> params = RemovedApiFinder.erasedParameterTypes(newClass.getConstructorType());
        List<Expression> a = nc.getArguments();
        switch (type) {
            case MONGO_CLIENT_OPTIONS_BUILDER:
                if (params.isEmpty() && !chainSetsUuidRepresentation()) {
                    return template(nc, "new MongoClientOptions.Builder()" + JAVA_LEGACY, ctx);
                }
                return nc;
            case MONGO_CLIENT_URI:
                if (params.size() == 1) {
                    return template(nc, "new MongoClientURI(#{any(java.lang.String)}, MongoClientOptions.builder()" + JAVA_LEGACY + ")", ctx, a.get(0));
                }
                return nc;
            case LEGACY_MONGO_CLIENT:
            case MONGO:
                if (params.isEmpty()) {
                    return template(nc, "new MongoClient(new ServerAddress(), " + OPTIONS + ")", ctx);
                }
                if (params.size() == 1 && "java.lang.String".equals(params.get(0))) {
                    return template(nc, "new MongoClient(#{any(java.lang.String)}, " + OPTIONS + ")", ctx, a.get(0));
                }
                if (params.size() == 2 && "java.lang.String".equals(params.get(0)) && "int".equals(params.get(1))) {
                    return template(nc, "new MongoClient(new ServerAddress(#{any(java.lang.String)}, #{any(int)}), " + OPTIONS + ")", ctx, a.get(0), a.get(1));
                }
                if (params.size() == 1 && "com.mongodb.ServerAddress".equals(params.get(0))) {
                    return template(nc, "new MongoClient(#{any(com.mongodb.ServerAddress)}, " + OPTIONS + ")", ctx, a.get(0));
                }
                if (params.size() == 1 && "java.util.List".equals(params.get(0))) {
                    return template(nc, "new MongoClient(#{any(java.util.List<com.mongodb.ServerAddress>)}, " + OPTIONS + ")", ctx, a.get(0));
                }
                return nc;
            default:
                return nc;
        }
    }
}

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
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/**
 * Fully attributed driver 5 types, taken from a stub compiled against the driver 5 type table, to put in place of the
 * legacy types the conversion replaces.
 */
final class ModernTypes {

    static JavaParser.Builder<?, ?> parser(ExecutionContext ctx) {
        return JavaParser.fromJavaVersion().classpathFromResources(ctx, "mongodb-driver-sync-5", "mongodb-driver-core-5", "bson-5");
    }

    private static final String STUB = "" +
            "import com.mongodb.BasicDBObject;\n" +
            "import com.mongodb.ConnectionString;\n" +
            "import com.mongodb.DBObject;\n" +
            "import com.mongodb.MongoClientSettings;\n" +
            "import com.mongodb.client.FindIterable;\n" +
            "import com.mongodb.client.MongoClient;\n" +
            "import com.mongodb.client.MongoCollection;\n" +
            "import com.mongodb.client.MongoCursor;\n" +
            "import com.mongodb.client.MongoDatabase;\n" +
            "import com.mongodb.client.result.DeleteResult;\n" +
            "import com.mongodb.client.result.UpdateResult;\n" +
            "class ModernMongoTypes {\n" +
            "    MongoClient client;\n" +
            "    MongoDatabase database;\n" +
            "    MongoCollection<DBObject> collection;\n" +
            "    FindIterable<DBObject> findIterable;\n" +
            "    MongoCursor<DBObject> cursor;\n" +
            "    ConnectionString connectionString;\n" +
            "    MongoClientSettings settings;\n" +
            "    MongoClientSettings.Builder settingsBuilder;\n" +
            "    UpdateResult updateResult;\n" +
            "    DeleteResult deleteResult;\n" +
            "    BasicDBObject basicDBObject;\n" +
            "}\n";

    final Map<String, JavaType> byName = new HashMap<>();

    /**
     * The types, parsed once per run.
     */
    static ModernTypes get(ExecutionContext ctx) {
        return ctx.computeMessageIfAbsent(ModernTypes.class.getName(), k -> new ModernTypes(ctx));
    }

    private ModernTypes(ExecutionContext ctx) {
        SourceFile sf = parser(ctx).build().parse(ctx, STUB).findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not parse the driver 5 type stub"));
        J.CompilationUnit cu = (J.CompilationUnit) sf;
        for (Statement s : cu.getClasses().get(0).getBody().getStatements()) {
            J.VariableDeclarations v = (J.VariableDeclarations) s;
            JavaType type = v.getVariables().get(0).getType();
            if (type == null || type instanceof JavaType.Unknown) {
                throw new IllegalStateException("The driver 5 type table does not resolve " + v.getTypeExpression());
            }
            byName.put(v.getVariables().get(0).getSimpleName(), type);
        }
    }

    JavaType client() {
        return byName.get("client");
    }

    JavaType database() {
        return byName.get("database");
    }

    JavaType collection() {
        return byName.get("collection");
    }

    JavaType findIterable() {
        return byName.get("findIterable");
    }

    JavaType cursor() {
        return byName.get("cursor");
    }

    JavaType connectionString() {
        return byName.get("connectionString");
    }

    JavaType settings() {
        return byName.get("settings");
    }

    JavaType settingsBuilder() {
        return byName.get("settingsBuilder");
    }

    JavaType updateResult() {
        return byName.get("updateResult");
    }

    JavaType deleteResult() {
        return byName.get("deleteResult");
    }

    JavaType basicDBObject() {
        return byName.get("basicDBObject");
    }

    /**
     * The driver 5 type standing in for a legacy type, when every use of it maps to the same type.
     */
    @Nullable
    JavaType replacementFor(String legacyFqn) {
        switch (legacyFqn) {
            case MongoTypes.MONGO:
            case MongoTypes.LEGACY_MONGO_CLIENT:
                return client();
            case MongoTypes.DB:
                return database();
            case MongoTypes.DB_COLLECTION:
                return collection();
            case MongoTypes.DB_CURSOR:
                return findIterable();
            case MongoTypes.MONGO_CLIENT_URI:
                return connectionString();
            case MongoTypes.MONGO_CLIENT_OPTIONS:
                return settings();
            case MongoTypes.MONGO_CLIENT_OPTIONS_BUILDER:
                return settingsBuilder();
            case MongoTypes.COMMAND_RESULT:
                return basicDBObject();
            case MongoTypes.WRITE_RESULT:
                return updateResult();
            default:
                return null;
        }
    }

    /**
     * A type tree that names {@code type} by its simple name (with its type arguments), for files that import it.
     */
    static TypeTree typeTree(JavaType type, Space prefix) {
        if (type instanceof JavaType.Parameterized) {
            JavaType.Parameterized p = (JavaType.Parameterized) type;
            List<JRightPadded<Expression>> args = new ArrayList<>();
            for (JavaType arg : p.getTypeParameters()) {
                args.add(JRightPadded.build((Expression) typeTree(arg, Space.EMPTY)));
            }
            return new J.ParameterizedType(Tree.randomId(), prefix, Markers.EMPTY,
                    (NameTree) typeTree(p.getType(), Space.EMPTY), JContainer.build(Space.EMPTY, args, Markers.EMPTY), p);
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq != null && fq.getOwningClass() != null) {
            // A nested type, `Outer.Nested`, as the parser builds it
            JavaType.FullyQualified owner = fq.getOwningClass();
            String simpleName = fq.getFullyQualifiedName().substring(fq.getFullyQualifiedName().lastIndexOf('$') + 1);
            return new J.FieldAccess(Tree.randomId(), prefix, Markers.EMPTY,
                    (Expression) typeTree(owner, Space.EMPTY),
                    JLeftPadded.build(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), simpleName, type, null)),
                    type);
        }
        String name = fq == null ? String.valueOf(type) : fq.getClassName();
        return new J.Identifier(Tree.randomId(), prefix, Markers.EMPTY, emptyList(), name, type, null);
    }

    /**
     * The fully qualified names a file must import to name {@code type}.
     */
    static List<String> importsFor(JavaType type) {
        List<String> imports = new ArrayList<>();
        collectImports(type, imports);
        return imports;
    }

    private static void collectImports(JavaType type, List<String> imports) {
        if (type instanceof JavaType.Parameterized) {
            collectImports(((JavaType.Parameterized) type).getType(), imports);
            for (JavaType p : ((JavaType.Parameterized) type).getTypeParameters()) {
                collectImports(p, imports);
            }
            return;
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq != null) {
            JavaType.FullyQualified outer = fq.getOwningClass();
            imports.add(outer != null ? outer.getFullyQualifiedName() : fq.getFullyQualifiedName());
        }
    }

    static List<JavaType> singleton(JavaType t) {
        return singletonList(t);
    }
}

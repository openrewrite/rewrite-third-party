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
import org.openrewrite.Tree;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.*;

import java.util.*;

import static org.openrewrite.mongodb.MongoTypes.*;

/**
 * Translates the legacy MongoDB API ({@code com.mongodb.MongoClient}, {@code DB}, {@code DBCollection},
 * {@code DBCursor} and their options) to the driver 5 API. Documents stay {@code DBObject}s, which driver 5 still
 * encodes and decodes, so the values code reads and writes keep their types and behavior.
 * <p>
 * In dry-run mode it only reports what it could not translate, so that the scan and the edit agree on every call.
 */
class LegacyApiConverter extends JavaVisitor<ExecutionContext> {

    private static final String COLL = "#{any(com.mongodb.client.MongoCollection<com.mongodb.DBObject>)}";
    private static final String DATABASE = "#{any(com.mongodb.client.MongoDatabase)}";
    private static final String CLIENT = "#{any(com.mongodb.client.MongoClient)}";
    private static final String ITERABLE = "#{any(com.mongodb.client.FindIterable<com.mongodb.DBObject>)}";
    private static final String BUILDER = "#{any(com.mongodb.MongoClientSettings.Builder)}";
    private static final String CONN = "#{any(com.mongodb.ConnectionString)}";

    private static final Map<String, String> IMPORTS = new LinkedHashMap<>();

    static {
        for (String fqn : Arrays.asList(
                "com.mongodb.BasicDBObject", "com.mongodb.DBObject", "com.mongodb.ConnectionString",
                "com.mongodb.MongoClientSettings", "com.mongodb.ServerAddress", "com.mongodb.MongoCredential",
                "com.mongodb.WriteConcern", "com.mongodb.ReadPreference",
                "com.mongodb.client.MongoClients", "com.mongodb.client.MongoClient", "com.mongodb.client.MongoDatabase",
                "com.mongodb.client.MongoCollection", "com.mongodb.client.FindIterable", "com.mongodb.client.MongoCursor",
                "com.mongodb.client.model.IndexOptions", "com.mongodb.client.model.UpdateOptions",
                "com.mongodb.client.model.ReplaceOptions", "com.mongodb.client.model.CreateCollectionOptions",
                "com.mongodb.client.model.FindOneAndUpdateOptions", "com.mongodb.client.model.FindOneAndReplaceOptions",
                "com.mongodb.client.model.FindOneAndDeleteOptions", "com.mongodb.client.model.ReturnDocument",
                "com.mongodb.connection.ClusterConnectionMode", "org.bson.UuidRepresentation",
                "java.util.Arrays", "java.util.ArrayList", "java.util.TreeSet", "java.util.Collections",
                "java.util.Date", "org.bson.types.ObjectId", "java.util.concurrent.TimeUnit")) {
            IMPORTS.put(fqn.substring(fqn.lastIndexOf('.') + 1), fqn);
        }
    }

    /**
     * Raised by a rule that cannot translate a use, and recorded in {@link #unsupported}.
     */
    static final class Unsupported extends RuntimeException {
        Unsupported(String message) {
            super(message, null, false, false);
        }
    }

    final boolean dryRun;
    final boolean javaLegacyUuid;
    final List<String> unsupported = new ArrayList<>();
    @Nullable CompilationUnitFacts facts;
    private @Nullable ModernTypes modern;

    LegacyApiConverter(boolean dryRun, boolean javaLegacyUuid) {
        this.dryRun = dryRun;
        this.javaLegacyUuid = javaLegacyUuid;
    }

    private ModernTypes modern(ExecutionContext ctx) {
        if (modern == null) {
            modern = ModernTypes.get(ctx);
        }
        return modern;
    }

    private CompilationUnitFacts facts() {
        if (facts == null) {
            facts = CompilationUnitFacts.of(getCursor().firstEnclosingOrThrow(JavaSourceFile.class));
        }
        return facts;
    }

    @Override
    public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
        facts = CompilationUnitFacts.of(cu);
        if (!dryRun) {
            for (String legacy : CONVERTED) {
                maybeRemoveImport(legacy.replace('$', '.'));
            }
            for (String helper : Arrays.asList("java.util.Arrays", "java.util.Collections", "java.util.List",
                    "com.google.common.collect.Lists", "com.google.common.collect.ImmutableList", "com.mongodb.MongoCredential",
                    "com.mongodb.ServerAddress", "com.mongodb.WriteConcern", "com.mongodb.ReadPreference")) {
                maybeRemoveImport(helper);
            }
        }
        return super.visitCompilationUnit(cu, ctx);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Template support
    // ---------------------------------------------------------------------------------------------------------

    private final class Tpl {
        final StringBuilder code = new StringBuilder();
        final List<Object> args = new ArrayList<>();

        Tpl text(String s) {
            code.append(s);
            return this;
        }

        Tpl any(String placeholder, J tree) {
            code.append(placeholder);
            args.add(tree);
            return this;
        }

        Tpl expr(Expression e) {
            return any("#{any(" + placeholderType(e) + ")}", e);
        }

        /**
         * An argument driver 5 takes as a {@code Bson}: a {@code BasicDBObject} is one, any other {@code DBObject}
         * is copied into one.
         */
        Tpl bson(Expression e) {
            if (isBson(e)) {
                return any("#{any(org.bson.conversions.Bson)}", e);
            }
            code.append("new BasicDBObject(");
            any("#{any(com.mongodb.DBObject)}", e);
            code.append(".toMap())");
            return this;
        }
    }

    private Tpl tpl() {
        return new Tpl();
    }

    private String placeholderType(Expression e) {
        JavaType type = e.getType();
        if (type instanceof JavaType.Primitive) {
            return ((JavaType.Primitive) type).getKeyword();
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq == null) {
            return "java.lang.Object";
        }
        String fqn = fq.getFullyQualifiedName();
        if (CONVERTED.contains(fqn) || fqn.startsWith("com.mongodb.async")) {
            return "java.lang.Object";
        }
        return fqn.replace('$', '.');
    }

    private boolean isBson(Expression e) {
        if (CompilationUnitFacts.isBasicDbObject(e) || TypeUtils.isAssignableTo("org.bson.conversions.Bson", e.getType())) {
            return true;
        }
        if (e instanceof J.Identifier && ((J.Identifier) e).getFieldType() != null) {
            return facts().basicDbObjectLocals.contains(CompilationUnitFacts.key(((J.Identifier) e).getFieldType()));
        }
        return false;
    }

    private J apply(Expression tree, Tpl t, ExecutionContext ctx) {
        if (dryRun) {
            return tree;
        }
        String code = t.code.toString();
        JavaTemplate.Builder builder = JavaTemplate.builder(code)
                .javaParser(ModernTypes.parser(ctx))
                .imports(IMPORTS.values().toArray(new String[0]));
        if (code.startsWith("if (")) {
            builder = builder.contextSensitive();
        }
        J result = builder.build().apply(new Cursor(getCursor().getParentOrThrow(), tree), tree.getCoordinates().replace(), t.args.toArray());
        for (Map.Entry<String, String> imp : IMPORTS.entrySet()) {
            if (code.matches("(?s).*\\b" + imp.getKey() + "\\b.*")) {
                maybeAddImport(imp.getValue());
            }
        }
        return restoreFormatting(tree, result, t.args);
    }

    /**
     * Puts back the line breaks the template dropped: before each argument that started on a line of its own, and
     * between the receiver and the call when the call started on a line of its own.
     */
    static J restoreFormatting(Expression original, J result, List<Object> args) {
        Map<UUID, Space> prefixes = new HashMap<>();
        for (Object a : args) {
            if (a instanceof J && ((J) a).getPrefix().getWhitespace().contains("\n")) {
                prefixes.put(((J) a).getId(), ((J) a).getPrefix());
            }
        }
        Space selectAfter = null;
        UUID receiver = null;
        if (original instanceof J.MethodInvocation && ((J.MethodInvocation) original).getPadding().getSelect() != null) {
            JRightPadded<Expression> select = ((J.MethodInvocation) original).getPadding().getSelect();
            if (select.getAfter().getWhitespace().contains("\n")) {
                selectAfter = select.getAfter();
                receiver = select.getElement().getId();
            }
        }
        if (prefixes.isEmpty() && selectAfter == null) {
            return result;
        }
        Space after = selectAfter;
        UUID receiverId = receiver;
        return new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, Integer p) {
                J.MethodInvocation mi = super.visitMethodInvocation(m, p);
                if (after != null && mi.getPadding().getSelect() != null && mi.getSelect() != null &&
                    mi.getSelect().getId().equals(receiverId)) {
                    mi = mi.getPadding().withSelect(mi.getPadding().getSelect().withAfter(after));
                }
                return mi;
            }

            @Override
            public @Nullable J postVisit(J tree, Integer p) {
                Space prefix = prefixes.get(tree.getId());
                return prefix == null ? tree : tree.withPrefix(prefix);
            }
        }.visitNonNull(result, 0);
    }

    private static Unsupported unsupported(String reason) {
        return new Unsupported(reason);
    }

    private static @Nullable Boolean booleanLiteral(Expression e) {
        if (e instanceof J.Literal && ((J.Literal) e).getValue() instanceof Boolean) {
            return (Boolean) ((J.Literal) e).getValue();
        }
        return null;
    }

    private static boolean isNull(Expression e) {
        return e instanceof J.Literal && ((J.Literal) e).getValue() == null && ((J.Literal) e).getType() == JavaType.Primitive.Null;
    }

    private static List<String> paramTypes(JavaType.@Nullable Method m) {
        List<String> types = new ArrayList<>();
        if (m != null) {
            for (JavaType p : m.getParameterTypes()) {
                if (p instanceof JavaType.Array) {
                    types.add(String.valueOf(fqn(((JavaType.Array) p).getElemType())) + "[]");
                } else if (p instanceof JavaType.Primitive) {
                    types.add(((JavaType.Primitive) p).getKeyword());
                } else {
                    types.add(String.valueOf(fqn(p)));
                }
            }
        }
        return types;
    }

    private static boolean params(JavaType.@Nullable Method m, String... types) {
        return paramTypes(m).equals(Arrays.asList(types));
    }

    private String uniqueName(String preferred) {
        String name = preferred;
        int i = 2;
        while (facts().names.contains(name)) {
            name = preferred + i++;
        }
        return name;
    }

    private boolean isStatement() {
        return getCursor().getParentTreeCursor().getValue() instanceof J.Block ||
               getCursor().getParentTreeCursor().getValue() instanceof J.Case ||
               getCursor().getParentTreeCursor().getValue() instanceof J.If ||
               getCursor().getParentTreeCursor().getValue() instanceof J.If.Else ||
               getCursor().getParentTreeCursor().getValue() instanceof J.ForLoop ||
               getCursor().getParentTreeCursor().getValue() instanceof J.ForEachLoop ||
               getCursor().getParentTreeCursor().getValue() instanceof J.WhileLoop;
    }

    private boolean isInBlock() {
        return getCursor().getParentTreeCursor().getValue() instanceof J.Block;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Method invocations
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public @Nullable J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        J.MethodInvocation original = method;
        J j = super.visitMethodInvocation(method, ctx);
        if (!(j instanceof J.MethodInvocation)) {
            return j;
        }
        J.MethodInvocation mi = (J.MethodInvocation) j;
        JavaType.Method mt = original.getMethodType();
        String declaring = fqn(mt == null ? null : mt.getDeclaringType());
        if (declaring == null) {
            return mi;
        }
        try {
            switch (declaring) {
                case DB_COLLECTION:
                    return collection(original, mi, ctx);
                case DB:
                    return database(original, mi, ctx);
                case DB_CURSOR:
                    return cursor(original, mi, ctx);
                case MONGO:
                case LEGACY_MONGO_CLIENT:
                    return client(original, mi, ctx);
                case MONGO_CLIENT_URI:
                    return uri(original, mi, ctx);
                case MONGO_CLIENT_OPTIONS:
                    return options(original, mi, ctx);
                case MONGO_CLIENT_OPTIONS_BUILDER:
                    return optionsBuilder(original, mi, ctx);
                case WRITE_RESULT:
                    return writeResult(original, mi, ctx);
                case COMMAND_RESULT:
                    return commandResult(original, mi, ctx);
                default:
                    return mi;
            }
        } catch (Unsupported e) {
            unsupported.add(e.getMessage());
            return mi;
        }
    }

    private Expression receiver(J.MethodInvocation mi) {
        if (mi.getSelect() == null) {
            throw unsupported("calls " + LegacyUsage.describe(mi.getMethodType()) + " without a receiver, from a subclass");
        }
        return mi.getSelect();
    }

    private J collection(J.MethodInvocation original, J.MethodInvocation mi, ExecutionContext ctx) {
        JavaType.Method mt = original.getMethodType();
        List<Expression> a = mi.getArguments();
        boolean noArgs = a.isEmpty() || a.get(0) instanceof J.Empty;
        Expression r = receiver(mi);
        boolean resultUsed = !isInBlock();
        switch (mi.getSimpleName()) {
            case "insert": {
                requireResultUnused(resultUsed, mi);
                Tpl t = tpl().any(COLL, r);
                List<Expression> docs = new ArrayList<>(a);
                if (params(mt, DB_OBJECT, "com.mongodb.WriteConcern")) {
                    t.text(".withWriteConcern(").expr(a.get(1)).text(")");
                    docs = Collections.singletonList(a.get(0));
                } else if (params(mt, "com.mongodb.WriteConcern", DB_OBJECT + "[]")) {
                    t.text(".withWriteConcern(").expr(a.get(0)).text(")");
                    docs = a.subList(1, a.size());
                } else if (params(mt, DB_OBJECT + "[]", "com.mongodb.WriteConcern")) {
                    t.text(".withWriteConcern(").expr(a.get(1)).text(")");
                    docs = Collections.singletonList(a.get(0));
                } else if (params(mt, "java.util.List", "com.mongodb.WriteConcern")) {
                    t.text(".withWriteConcern(").expr(a.get(1)).text(")");
                    return apply(mi, t.text(".insertMany(").any("#{any(java.util.List<? extends com.mongodb.DBObject>)}", a.get(0)).text(")"), ctx);
                } else if (params(mt, "java.util.List")) {
                    return apply(mi, t.text(".insertMany(").any("#{any(java.util.List<? extends com.mongodb.DBObject>)}", a.get(0)).text(")"), ctx);
                } else if (!params(mt, DB_OBJECT + "[]")) {
                    throw unsupported("calls `DBCollection.insert` with options driver 5 takes differently");
                }
                if (docs.isEmpty() || docs.get(0) instanceof J.Empty) {
                    throw unsupported("calls `DBCollection.insert` with no documents");
                }
                if (docs.size() == 1 && !(docs.get(0).getType() instanceof JavaType.Array)) {
                    return apply(mi, t.text(".insertOne(").any("#{any(com.mongodb.DBObject)}", docs.get(0)).text(")"), ctx);
                }
                t.text(".insertMany(Arrays.asList(");
                for (int i = 0; i < docs.size(); i++) {
                    if (i > 0) {
                        t.text(", ");
                    }
                    if (docs.get(i).getType() instanceof JavaType.Array) {
                        t.any("#{anyArray(com.mongodb.DBObject)}", docs.get(i));
                    } else {
                        t.any("#{any(com.mongodb.DBObject)}", docs.get(i));
                    }
                }
                return apply(mi, t.text("))"), ctx);
            }
            case "save": {
                requireResultUnused(resultUsed, mi);
                if (!(params(mt, DB_OBJECT) || params(mt, DB_OBJECT, "com.mongodb.WriteConcern"))) {
                    throw unsupported("calls `DBCollection.save` with an encoder");
                }
                Expression doc = a.get(0);
                if (!(doc instanceof J.Identifier) || !(r instanceof J.Identifier || r instanceof J.FieldAccess)) {
                    throw unsupported("saves a document that is not held in a variable, which `insertOne` or `replaceOne` would need twice");
                }
                Tpl coll = tpl();
                Tpl t = tpl().text("if (").any("#{any(com.mongodb.DBObject)}", copy(doc)).text(".get(\"_id\") == null) {\n")
                        .any(COLL, copy(r));
                if (a.size() > 1) {
                    t.text(".withWriteConcern(").expr(copy(a.get(1))).text(")");
                }
                t.text(".insertOne(").any("#{any(com.mongodb.DBObject)}", copy(doc)).text(");\n} else {\n")
                        .any(COLL, copy(r));
                if (a.size() > 1) {
                    t.text(".withWriteConcern(").expr(copy(a.get(1))).text(")");
                }
                t.text(".replaceOne(new BasicDBObject(\"_id\", ").any("#{any(com.mongodb.DBObject)}", copy(doc))
                        .text(".get(\"_id\")), ").any("#{any(com.mongodb.DBObject)}", copy(doc))
                        .text(", new ReplaceOptions().upsert(true));\n}");
                return apply(mi, t, ctx);
            }
            case "update":
            case "updateMulti": {
                boolean multi = "updateMulti".equals(mi.getSimpleName());
                Expression upsert = null;
                Expression writeConcern = null;
                if (params(mt, DB_OBJECT, DB_OBJECT)) {
                    // defaults
                } else if (params(mt, DB_OBJECT, DB_OBJECT, "boolean", "boolean") ||
                           params(mt, DB_OBJECT, DB_OBJECT, "boolean", "boolean", "com.mongodb.WriteConcern")) {
                    upsert = a.get(2);
                    Boolean m = booleanLiteral(a.get(3));
                    if (m == null) {
                        throw unsupported("calls `DBCollection.update` with a `multi` flag known only at runtime");
                    }
                    multi = m;
                    if (a.size() > 4) {
                        writeConcern = a.get(4);
                    }
                } else {
                    throw unsupported("calls `DBCollection.update` with options driver 5 takes differently");
                }
                Shape shape = multi ? Shape.UPDATE : shapeOf(original.getArguments().get(1));
                if (shape == Shape.UNKNOWN) {
                    throw unsupported("calls `DBCollection.update` with a document that may be a replacement or an update, `" +
                                      LegacyUsage.line(original.getArguments().get(1)) + "`");
                }
                if (Boolean.FALSE.equals(upsert == null ? null : booleanLiteral(upsert))) {
                    upsert = null;
                }
                Tpl t = tpl().any(COLL, r);
                if (writeConcern != null) {
                    t.text(".withWriteConcern(").expr(writeConcern).text(")");
                }
                if (shape == Shape.REPLACEMENT) {
                    t.text(".replaceOne(").bson(a.get(0)).text(", ").any("#{any(com.mongodb.DBObject)}", a.get(1));
                    if (upsert != null) {
                        t.text(", new ReplaceOptions().upsert(").expr(upsert).text(")");
                    }
                } else {
                    t.text(multi ? ".updateMany(" : ".updateOne(").bson(a.get(0)).text(", ").bson(a.get(1));
                    if (upsert != null) {
                        t.text(", new UpdateOptions().upsert(").expr(upsert).text(")");
                    }
                }
                return apply(mi, t.text(")"), ctx);
            }
            case "remove": {
                Tpl t = tpl().any(COLL, r);
                if (params(mt, DB_OBJECT, "com.mongodb.WriteConcern")) {
                    t.text(".withWriteConcern(").expr(a.get(1)).text(")");
                } else if (!params(mt, DB_OBJECT)) {
                    throw unsupported("calls `DBCollection.remove` with options driver 5 takes differently");
                }
                return apply(mi, t.text(".deleteMany(").bson(a.get(0)).text(")"), ctx);
            }
            case "find": {
                Tpl t = tpl().any(COLL, r);
                if (noArgs) {
                    return apply(mi, t.text(".find()"), ctx);
                } else if (params(mt, DB_OBJECT)) {
                    return apply(mi, find(t, a.get(0)), ctx);
                } else if (params(mt, DB_OBJECT, DB_OBJECT)) {
                    find(t, a.get(0));
                    if (!isNull(a.get(1))) {
                        t.text(".projection(").bson(a.get(1)).text(")");
                    }
                    return apply(mi, t, ctx);
                }
                throw unsupported("calls `DBCollection.find` with options driver 5 takes differently");
            }
            case "findOne": {
                Tpl t = tpl().any(COLL, r);
                if (noArgs) {
                    return apply(mi, t.text(".find().first()"), ctx);
                }
                List<String> p = paramTypes(mt);
                int rp = p.indexOf("com.mongodb.ReadPreference");
                if (rp >= 0) {
                    t.text(".withReadPreference(").expr(a.get(rp)).text(")");
                }
                if ("java.lang.Object".equals(p.get(0))) {
                    t.text(".find(new BasicDBObject(\"_id\", ").any("#{any(java.lang.Object)}", a.get(0)).text("))");
                } else if (DB_OBJECT.equals(p.get(0))) {
                    find(t, a.get(0));
                } else {
                    throw unsupported("calls `DBCollection.findOne` with options driver 5 takes differently");
                }
                if (p.size() > 1 && DB_OBJECT.equals(p.get(1)) && !isNull(a.get(1))) {
                    t.text(".projection(").bson(a.get(1)).text(")");
                }
                if (p.size() > 2 && DB_OBJECT.equals(p.get(2)) && !isNull(a.get(2))) {
                    t.text(".sort(").bson(a.get(2)).text(")");
                }
                return apply(mi, t.text(".first()"), ctx);
            }
            case "count":
            case "getCount": {
                Tpl t = tpl().any(COLL, r);
                if (params(mt, DB_OBJECT, "com.mongodb.ReadPreference")) {
                    t.text(".withReadPreference(").expr(a.get(1)).text(")");
                } else if (params(mt, "com.mongodb.ReadPreference")) {
                    return apply(mi, t.text(".withReadPreference(").expr(a.get(0)).text(").estimatedDocumentCount()"), ctx);
                } else if (noArgs) {
                    return apply(mi, t.text(".estimatedDocumentCount()"), ctx);
                } else if (!params(mt, DB_OBJECT)) {
                    throw unsupported("calls `DBCollection." + mi.getSimpleName() + "` with options driver 5 takes differently");
                }
                if (isNull(a.get(0))) {
                    return apply(mi, t.text(".countDocuments()"), ctx);
                }
                return apply(mi, t.text(".countDocuments(").bson(a.get(0)).text(")"), ctx);
            }
            case "distinct": {
                String element = distinctElementType();
                Tpl t = tpl().any(COLL, r).text(".distinct(").expr(a.get(0));
                if (params(mt, "java.lang.String", DB_OBJECT)) {
                    t.text(", ").bson(a.get(1));
                } else if (!params(mt, "java.lang.String")) {
                    throw unsupported("calls `DBCollection.distinct` with options driver 5 takes differently");
                }
                return apply(mi, t.text(", " + element + ".class).into(new ArrayList<" + element + ">())"), ctx);
            }
            case "createIndex":
            case "ensureIndex": {
                Tpl t = tpl().any(COLL, r).text(".createIndex(");
                if (params(mt, "java.lang.String")) {
                    return apply(mi, t.text("new BasicDBObject(").expr(a.get(0)).text(", 1))"), ctx);
                }
                if (!(p0(mt, DB_OBJECT))) {
                    throw unsupported("calls `DBCollection." + mi.getSimpleName() + "` with keys driver 5 takes differently");
                }
                t.bson(a.get(0));
                if (params(mt, DB_OBJECT)) {
                    return apply(mi, t.text(")"), ctx);
                } else if (params(mt, DB_OBJECT, "java.lang.String")) {
                    return apply(mi, t.text(", new IndexOptions().name(").expr(a.get(1)).text("))"), ctx);
                } else if (params(mt, DB_OBJECT, "java.lang.String", "boolean")) {
                    return apply(mi, t.text(", new IndexOptions().name(").expr(a.get(1)).text(").unique(").expr(a.get(2)).text("))"), ctx);
                } else if (params(mt, DB_OBJECT, DB_OBJECT)) {
                    t.text(", ");
                    indexOptions(t, original.getArguments().get(1), a.get(1));
                    return apply(mi, t.text(")"), ctx);
                }
                throw unsupported("calls `DBCollection." + mi.getSimpleName() + "` with options driver 5 takes differently");
            }
            case "dropIndex": {
                Tpl t = tpl().any(COLL, r).text(".dropIndex(");
                if (params(mt, "java.lang.String")) {
                    return apply(mi, t.expr(a.get(0)).text(")"), ctx);
                }
                return apply(mi, t.bson(a.get(0)).text(")"), ctx);
            }
            case "dropIndexes": {
                if (noArgs) {
                    return apply(mi, tpl().any(COLL, r).text(".dropIndexes()"), ctx);
                }
                return apply(mi, tpl().any(COLL, r).text(".dropIndex(").expr(a.get(0)).text(")"), ctx);
            }
            case "getIndexInfo":
                return apply(mi, tpl().any(COLL, r).text(".listIndexes(DBObject.class).into(new ArrayList<DBObject>())"), ctx);
            case "drop":
                return apply(mi, tpl().any(COLL, r).text(".drop()"), ctx);
            case "getName":
                return apply(mi, tpl().any(COLL, r).text(".getNamespace().getCollectionName()"), ctx);
            case "getFullName":
                return apply(mi, tpl().any(COLL, r).text(".getNamespace().getFullName()"), ctx);
            case "getWriteConcern":
            case "getReadPreference":
            case "getReadConcern":
                return apply(mi, tpl().any(COLL, r).text("." + mi.getSimpleName() + "()"), ctx);
            case "findAndRemove":
                return apply(mi, tpl().any(COLL, r).text(".findOneAndDelete(").bson(a.get(0)).text(")"), ctx);
            case "findAndModify":
                return findAndModify(original, mi, r, ctx);
            default:
                throw unsupported("calls " + LegacyUsage.describe(mt) + ", which driver 5 has no direct counterpart for");
        }
    }

    private static boolean p0(JavaType.@Nullable Method mt, String type) {
        List<String> p = paramTypes(mt);
        return !p.isEmpty() && p.get(0).equals(type);
    }

    private void requireResultUnused(boolean resultUsed, J.MethodInvocation mi) {
        if (resultUsed) {
            throw unsupported("uses the `WriteResult` of `DBCollection." + mi.getSimpleName() + "`, which driver 5 reports differently");
        }
    }

    private Tpl find(Tpl t, Expression query) {
        if (isNull(query)) {
            return t.text(".find()");
        }
        return t.text(".find(").bson(query).text(")");
    }

    private J findAndModify(J.MethodInvocation original, J.MethodInvocation mi, Expression r, ExecutionContext ctx) {
        JavaType.Method mt = original.getMethodType();
        List<Expression> a = mi.getArguments();
        Tpl t = tpl().any(COLL, r);
        if (params(mt, DB_OBJECT, DB_OBJECT) || params(mt, DB_OBJECT, DB_OBJECT, DB_OBJECT)) {
            int u = a.size() - 1;
            Shape shape = shapeOf(original.getArguments().get(u));
            if (shape == Shape.UNKNOWN) {
                throw unsupported("calls `DBCollection.findAndModify` with a document that may be a replacement or an update");
            }
            if (shape == Shape.UPDATE) {
                t.text(".findOneAndUpdate(").bson(a.get(0)).text(", ").bson(a.get(u));
            } else {
                t.text(".findOneAndReplace(").bson(a.get(0)).text(", ").any("#{any(com.mongodb.DBObject)}", a.get(u));
            }
            if (a.size() == 3 && !isNull(a.get(1))) {
                t.text(shape == Shape.UPDATE ? ", new FindOneAndUpdateOptions().sort(" : ", new FindOneAndReplaceOptions().sort(")
                        .bson(a.get(1)).text(")");
            }
            return apply(mi, t.text(")"), ctx);
        }
        if (params(mt, DB_OBJECT, DB_OBJECT, DB_OBJECT, "boolean", DB_OBJECT, "boolean", "boolean")) {
            Expression query = a.get(0);
            Expression fields = a.get(1);
            Expression sort = a.get(2);
            Boolean remove = booleanLiteral(a.get(3));
            Expression update = a.get(4);
            Expression returnNew = a.get(5);
            Expression upsert = a.get(6);
            if (remove == null) {
                throw unsupported("calls `DBCollection.findAndModify` with a `remove` flag known only at runtime");
            }
            if (remove) {
                t.text(".findOneAndDelete(").bson(query).text(", new FindOneAndDeleteOptions()");
                options(t, fields, sort);
                return apply(mi, t.text(")"), ctx);
            }
            Shape shape = shapeOf(original.getArguments().get(4));
            if (shape == Shape.UNKNOWN) {
                throw unsupported("calls `DBCollection.findAndModify` with a document that may be a replacement or an update");
            }
            if (shape == Shape.UPDATE) {
                t.text(".findOneAndUpdate(").bson(query).text(", ").bson(update).text(", new FindOneAndUpdateOptions()");
            } else {
                t.text(".findOneAndReplace(").bson(query).text(", ").any("#{any(com.mongodb.DBObject)}", update)
                        .text(", new FindOneAndReplaceOptions()");
            }
            options(t, fields, sort);
            Boolean rn = booleanLiteral(returnNew);
            if (rn == null) {
                t.text(".returnDocument(").expr(returnNew).text(" ? ReturnDocument.AFTER : ReturnDocument.BEFORE)");
            } else if (rn) {
                t.text(".returnDocument(ReturnDocument.AFTER)");
            }
            if (!Boolean.FALSE.equals(booleanLiteral(upsert))) {
                t.text(".upsert(").expr(upsert).text(")");
            }
            return apply(mi, t.text(")"), ctx);
        }
        throw unsupported("calls `DBCollection.findAndModify` with options driver 5 takes differently");
    }

    private void options(Tpl t, Expression fields, Expression sort) {
        if (!isNull(fields)) {
            t.text(".projection(").bson(fields).text(")");
        }
        if (!isNull(sort)) {
            t.text(".sort(").bson(sort).text(")");
        }
    }

    private void indexOptions(Tpl t, Expression originalOptions, Expression options) {
        Map<String, Expression> entries = literalEntries(originalOptions);
        if (entries == null) {
            throw unsupported("creates an index with options that are not spelled out where it is created");
        }
        t.text("new IndexOptions()");
        Map<String, Expression> converted = literalEntries(options);
        if (converted == null) {
            throw unsupported("creates an index with options that are not spelled out where it is created");
        }
        for (Map.Entry<String, Expression> e : converted.entrySet()) {
            switch (e.getKey()) {
                case "unique":
                case "sparse":
                case "background":
                case "name":
                    t.text("." + e.getKey() + "(").expr(e.getValue()).text(")");
                    break;
                case "expireAfterSeconds":
                    t.text(".expireAfter(").any("#{any(long)}", e.getValue()).text("L, TimeUnit.SECONDS)");
                    break;
                case "dropDups":
                    // removed in MongoDB 3.0, the server ignores it
                    break;
                default:
                    throw unsupported("creates an index with the option `" + e.getKey() + "`");
            }
        }
    }

    /**
     * The keys and values of a {@code new BasicDBObject(k, v).append(k, v)} chain, in order, or {@code null} when
     * the document is not spelled out that way.
     */
    static @Nullable Map<String, Expression> literalEntries(Expression e) {
        Map<String, Expression> entries = new LinkedHashMap<>();
        Expression current = e;
        Deque<J.MethodInvocation> appends = new ArrayDeque<>();
        while (current instanceof J.MethodInvocation &&
               ("append".equals(((J.MethodInvocation) current).getSimpleName()) || "put".equals(((J.MethodInvocation) current).getSimpleName())) &&
               ((J.MethodInvocation) current).getArguments().size() == 2) {
            appends.push((J.MethodInvocation) current);
            current = ((J.MethodInvocation) current).getSelect();
        }
        if (!(current instanceof J.NewClass) || !BASIC_DB_OBJECT.equals(fqn(current.getType()))) {
            return null;
        }
        J.NewClass nc = (J.NewClass) current;
        List<Expression> args = nc.getArguments();
        if (args.size() == 2) {
            if (!(args.get(0) instanceof J.Literal)) {
                return null;
            }
            entries.put(String.valueOf(((J.Literal) args.get(0)).getValue()), args.get(1));
        } else if (!(args.isEmpty() || args.get(0) instanceof J.Empty)) {
            return null;
        }
        for (J.MethodInvocation append : appends) {
            if (!(append.getArguments().get(0) instanceof J.Literal)) {
                return null;
            }
            entries.put(String.valueOf(((J.Literal) append.getArguments().get(0)).getValue()), append.getArguments().get(1));
        }
        return entries;
    }

    enum Shape {UPDATE, REPLACEMENT, UNKNOWN}

    /**
     * Whether a document passed to {@code update} holds update operators, which driver 5 applies with
     * {@code updateOne}, or is a replacement, which it applies with {@code replaceOne}.
     */
    Shape shapeOf(Expression doc) {
        Expression e = doc;
        while (e instanceof J.Parentheses) {
            e = (Expression) ((J.Parentheses<?>) e).getTree();
        }
        Map<String, Expression> entries = literalEntries(e);
        if (entries == null && e instanceof J.MethodInvocation) {
            entries = builderEntries((J.MethodInvocation) e);
        }
        if (entries == null && e instanceof J.Identifier) {
            entries = localEntries((J.Identifier) e);
        }
        if (entries == null || entries.isEmpty()) {
            return Shape.UNKNOWN;
        }
        boolean operators = false;
        boolean fields = false;
        for (String k : entries.keySet()) {
            if (k.startsWith("$")) {
                operators = true;
            } else {
                fields = true;
            }
        }
        if (operators == fields) {
            return Shape.UNKNOWN;
        }
        return operators ? Shape.UPDATE : Shape.REPLACEMENT;
    }

    /**
     * The keys of a {@code BasicDBObjectBuilder.start(...).add(...).get()} chain.
     */
    private static @Nullable Map<String, Expression> builderEntries(J.MethodInvocation get) {
        if (!"get".equals(get.getSimpleName()) || !"com.mongodb.BasicDBObjectBuilder".equals(fqn(get.getMethodType() == null ? null : get.getMethodType().getDeclaringType()))) {
            return null;
        }
        Map<String, Expression> entries = new LinkedHashMap<>();
        Deque<J.MethodInvocation> chain = new ArrayDeque<>();
        Expression current = get.getSelect();
        while (current instanceof J.MethodInvocation) {
            chain.push((J.MethodInvocation) current);
            current = ((J.MethodInvocation) current).getSelect();
        }
        for (J.MethodInvocation link : chain) {
            String name = link.getSimpleName();
            if (("add".equals(name) || "append".equals(name) || "start".equals(name)) && link.getArguments().size() == 2) {
                if (!(link.getArguments().get(0) instanceof J.Literal)) {
                    return null;
                }
                entries.put(String.valueOf(((J.Literal) link.getArguments().get(0)).getValue()), link.getArguments().get(1));
            } else if (!"start".equals(name)) {
                return null;
            }
        }
        return entries;
    }

    /**
     * The keys a local variable holds: those of its initializer and of the {@code put} and {@code append} statements
     * on it in the same block.
     */
    private @Nullable Map<String, Expression> localEntries(J.Identifier ident) {
        JavaType.Variable v = ident.getFieldType();
        if (v == null) {
            return null;
        }
        String key = CompilationUnitFacts.key(v);
        J.Block block = getCursor().firstEnclosing(J.Block.class);
        Cursor c = getCursor();
        Map<String, Expression>[] found = new Map[1];
        // Find the enclosing method body that declares it
        J.MethodDeclaration method = c.firstEnclosing(J.MethodDeclaration.class);
        J searchRoot = method != null ? method : block;
        if (searchRoot == null) {
            return null;
        }
        new JavaVisitor<Integer>() {
            @Override
            public J visitVariableDeclarations(J.VariableDeclarations multiVariable, Integer p) {
                for (J.VariableDeclarations.NamedVariable nv : multiVariable.getVariables()) {
                    if (key.equals(CompilationUnitFacts.key(nv.getVariableType())) && nv.getInitializer() != null) {
                        Expression init = nv.getInitializer();
                        Map<String, Expression> entries = literalEntries(init);
                        if (entries == null && init instanceof J.MethodInvocation) {
                            entries = builderEntries((J.MethodInvocation) init);
                        }
                        found[0] = entries == null ? null : new LinkedHashMap<>(entries);
                    }
                }
                return super.visitVariableDeclarations(multiVariable, p);
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation m, Integer p) {
                if (found[0] != null && m.getSelect() instanceof J.Identifier &&
                    key.equals(CompilationUnitFacts.key(((J.Identifier) m.getSelect()).getFieldType())) &&
                    ("put".equals(m.getSimpleName()) || "append".equals(m.getSimpleName())) && m.getArguments().size() == 2 &&
                    m.getArguments().get(0) instanceof J.Literal) {
                    found[0].put(String.valueOf(((J.Literal) m.getArguments().get(0)).getValue()), m.getArguments().get(1));
                }
                return super.visitMethodInvocation(m, p);
            }
        }.visit(searchRoot, 0);
        return found[0];
    }

    /**
     * The element type of the list a {@code distinct} call returns, taken from the variable or method it is
     * assigned or returned to, so that the {@code List<String>} that compiled against the raw legacy list still does.
     */
    private static final Set<String> DISTINCT_ELEMENT_TYPES = new HashSet<>(Arrays.asList(
            "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Boolean",
            "java.util.Date", "org.bson.types.ObjectId"));

    private String distinctElementType() {
        Cursor parent = getCursor().getParentTreeCursor();
        JavaType target = null;
        if (parent.getValue() instanceof J.VariableDeclarations.NamedVariable) {
            target = ((J.VariableDeclarations.NamedVariable) parent.getValue()).getType();
        } else if (parent.getValue() instanceof J.Assignment) {
            target = ((J.Assignment) parent.getValue()).getVariable().getType();
        } else if (parent.getValue() instanceof J.Return) {
            J.MethodDeclaration m = getCursor().firstEnclosing(J.MethodDeclaration.class);
            target = m == null || m.getReturnTypeExpression() == null ? null : m.getReturnTypeExpression().getType();
        }
        if (target instanceof JavaType.Parameterized && ((JavaType.Parameterized) target).getTypeParameters().size() == 1) {
            JavaType element = ((JavaType.Parameterized) target).getTypeParameters().get(0);
            if (!(element instanceof JavaType.GenericTypeVariable) && !(element instanceof JavaType.Parameterized)) {
                String fqn = fqn(element);
                if (fqn != null && DISTINCT_ELEMENT_TYPES.contains(fqn)) {
                    return fqn.substring(fqn.lastIndexOf('.') + 1);
                }
            }
        }
        // Driver 5 decodes the values with the codec of a class, and has none for Object
        throw unsupported("calls `DBCollection.distinct` where the type of the values is not declared as a `List` of one type");
    }

    private J database(J.MethodInvocation original, J.MethodInvocation mi, ExecutionContext ctx) {
        JavaType.Method mt = original.getMethodType();
        List<Expression> a = mi.getArguments();
        Expression r = receiver(mi);
        switch (mi.getSimpleName()) {
            case "getCollection":
                return apply(mi, tpl().any(DATABASE, r).text(".getCollection(").expr(a.get(0)).text(", DBObject.class)"), ctx);
            case "getName":
            case "getWriteConcern":
            case "getReadPreference":
            case "getReadConcern":
                return apply(mi, tpl().any(DATABASE, r).text("." + mi.getSimpleName() + "()"), ctx);
            case "getCollectionNames":
                return apply(mi, tpl().any(DATABASE, r).text(".listCollectionNames().into(new TreeSet<String>())"), ctx);
            case "collectionExists":
                // DB.collectionExists compares the names ignoring case
                return apply(mi, tpl().any(DATABASE, r).text(".listCollectionNames().into(new ArrayList<String>()).stream().anyMatch((")
                        .expr(a.get(0)).text(")::equalsIgnoreCase)"), ctx);
            case "dropDatabase":
                return apply(mi, tpl().any(DATABASE, r).text(".drop()"), ctx);
            case "createCollection": {
                if (!isInBlock()) {
                    throw unsupported("uses the collection `DB.createCollection` returns, where driver 5 returns nothing");
                }
                Tpl t = tpl().any(DATABASE, r).text(".createCollection(").expr(a.get(0));
                if (!isNull(a.get(1))) {
                    Map<String, Expression> entries = literalEntries(a.get(1));
                    if (entries == null) {
                        throw unsupported("creates a collection with options that are not spelled out where it is created");
                    }
                    t.text(", new CreateCollectionOptions()");
                    for (Map.Entry<String, Expression> e : entries.entrySet()) {
                        switch (e.getKey()) {
                            case "capped":
                                t.text(".capped(").expr(e.getValue()).text(")");
                                break;
                            case "size":
                                t.text(".sizeInBytes(").any("#{any(long)}", e.getValue()).text(")");
                                break;
                            case "max":
                                t.text(".maxDocuments(").any("#{any(long)}", e.getValue()).text(")");
                                break;
                            default:
                                throw unsupported("creates a collection with the option `" + e.getKey() + "`");
                        }
                    }
                }
                return apply(mi, t.text(")"), ctx);
            }
            case "command": {
                Tpl t = tpl().any(DATABASE, r).text(".runCommand(");
                if (params(mt, "java.lang.String")) {
                    t.text("new BasicDBObject(").expr(a.get(0)).text(", 1)");
                } else if (p0(mt, DB_OBJECT)) {
                    t.bson(a.get(0));
                } else {
                    throw unsupported("calls `DB.command` with options driver 5 takes differently");
                }
                if (params(mt, DB_OBJECT, "com.mongodb.ReadPreference") || params(mt, "java.lang.String", "com.mongodb.ReadPreference")) {
                    t.text(", ").expr(a.get(1));
                } else if (a.size() > 1) {
                    throw unsupported("calls `DB.command` with options driver 5 takes differently");
                }
                return apply(mi, t.text(", BasicDBObject.class)"), ctx);
            }
            case "getStats":
                return apply(mi, tpl().any(DATABASE, r).text(".runCommand(new BasicDBObject(\"dbStats\", 1), BasicDBObject.class)"), ctx);
            default:
                throw unsupported("calls " + LegacyUsage.describe(mt) + ", which driver 5 has no direct counterpart for");
        }
    }

    private @Nullable J cursor(J.MethodInvocation original, J.MethodInvocation mi, ExecutionContext ctx) {
        JavaType.Method mt = original.getMethodType();
        List<Expression> a = mi.getArguments();
        Expression r = receiver(mi);
        Expression originalReceiver = original.getSelect();
        boolean cursorVariable = originalReceiver instanceof J.Identifier &&
                                 facts().cursorKind(((J.Identifier) originalReceiver).getFieldType()) == CompilationUnitFacts.CursorKind.CURSOR;
        String name = mi.getSimpleName();
        if (cursorVariable) {
            switch (name) {
                case "hasNext":
                case "next":
                case "tryNext":
                case "close":
                    return apply(mi, tpl().any("#{any(com.mongodb.client.MongoCursor<com.mongodb.DBObject>)}", r).text("." + name + "()"), ctx);
                default:
                    throw unsupported("calls `DBCursor." + name + "` on a cursor it iterates by hand");
            }
        }
        switch (name) {
            case "sort":
            case "hint":
                if (p0(mt, "java.lang.String")) {
                    return apply(mi, tpl().any(ITERABLE, r).text(".hintString(").expr(a.get(0)).text(")"), ctx);
                }
                return apply(mi, tpl().any(ITERABLE, r).text("." + name + "(").bson(a.get(0)).text(")"), ctx);
            case "limit":
            case "skip":
            case "batchSize":
            case "comment":
            case "noCursorTimeout":
                return apply(mi, tpl().any(ITERABLE, r).text("." + name + "(").expr(a.get(0)).text(")"), ctx);
            case "maxTime":
                return apply(mi, tpl().any(ITERABLE, r).text(".maxTime(").expr(a.get(0)).text(", ").expr(a.get(1)).text(")"), ctx);
            case "iterator":
                return apply(mi, tpl().any(ITERABLE, r).text(".iterator()"), ctx);
            case "toArray":
                if (a.isEmpty() || a.get(0) instanceof J.Empty) {
                    return apply(mi, tpl().any(ITERABLE, r).text(".into(new ArrayList<DBObject>())"), ctx);
                }
                return apply(mi, tpl().any(ITERABLE, r).text(".limit(").expr(a.get(0)).text(").into(new ArrayList<DBObject>())"), ctx);
            case "one":
                return apply(mi, tpl().any(ITERABLE, r).text(".first()"), ctx);
            case "hasNext":
            case "next":
                if (originalReceiver instanceof J.Identifier) {
                    throw unsupported("calls `DBCursor." + name + "` on a cursor it also modifies");
                }
                return apply(mi, tpl().any(ITERABLE, r).text(".iterator()." + name + "()"), ctx);
            case "close":
                if (!isInBlock()) {
                    throw unsupported("closes a `DBCursor` other than in a statement of its own");
                }
                // A FindIterable holds no server cursor until it is iterated
                return dryRun ? mi : null;
            case "count":
            case "size": {
                J.MethodInvocation find = findCall(original);
                if (find == null) {
                    throw unsupported("counts a `DBCursor` whose query is not in the same expression");
                }
                if ("size".equals(name) && hasLimitOrSkip(original)) {
                    throw unsupported("calls `DBCursor.size` on a query with a limit or a skip");
                }
                J.MethodInvocation convertedFind = convertedFindCall(r);
                if (convertedFind == null || convertedFind.getSelect() == null) {
                    throw unsupported("counts a `DBCursor` whose query is not in the same expression");
                }
                Tpl t = tpl().any(COLL, convertedFind.getSelect());
                List<Expression> fa = convertedFind.getArguments();
                if (fa.isEmpty() || fa.get(0) instanceof J.Empty) {
                    return apply(mi, t.text(".countDocuments()"), ctx);
                }
                return apply(mi, t.text(".countDocuments(").any("#{any(org.bson.conversions.Bson)}", fa.get(0)).text(")"), ctx);
            }
            default:
                throw unsupported("calls " + LegacyUsage.describe(mt) + ", which driver 5 has no direct counterpart for");
        }
    }

    private static J.@Nullable MethodInvocation findCall(J.MethodInvocation original) {
        Expression current = original.getSelect();
        while (current instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) current;
            String declaring = fqn(m.getMethodType() == null ? null : m.getMethodType().getDeclaringType());
            if (DB_COLLECTION.equals(declaring) && "find".equals(m.getSimpleName())) {
                return m;
            }
            if (!DB_CURSOR.equals(declaring)) {
                return null;
            }
            current = m.getSelect();
        }
        return null;
    }

    private static boolean hasLimitOrSkip(J.MethodInvocation original) {
        Expression current = original.getSelect();
        while (current instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) current;
            if ("limit".equals(m.getSimpleName()) || "skip".equals(m.getSimpleName())) {
                return true;
            }
            current = m.getSelect();
        }
        return false;
    }

    private static J.@Nullable MethodInvocation convertedFindCall(Expression converted) {
        Expression current = converted;
        while (current instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) current;
            if ("find".equals(m.getSimpleName())) {
                return m;
            }
            if ("projection".equals(m.getSimpleName()) || "sort".equals(m.getSimpleName()) || "limit".equals(m.getSimpleName()) ||
                "skip".equals(m.getSimpleName()) || "batchSize".equals(m.getSimpleName()) || "hint".equals(m.getSimpleName())) {
                current = m.getSelect();
            } else {
                return null;
            }
        }
        return null;
    }

    private J client(J.MethodInvocation original, J.MethodInvocation mi, ExecutionContext ctx) {
        JavaType.Method mt = original.getMethodType();
        List<Expression> a = mi.getArguments();
        String name = mi.getSimpleName();
        if ("getDefaultCodecRegistry".equals(name)) {
            return apply(mi, tpl().text("MongoClientSettings.getDefaultCodecRegistry()"), ctx);
        }
        Expression r = receiver(mi);
        switch (name) {
            case "getDB":
            case "getDatabase":
                return apply(mi, tpl().any(CLIENT, r).text(".getDatabase(").expr(a.get(0)).text(")"), ctx);
            case "close":
            case "getWriteConcern":
            case "getReadPreference":
            case "getReadConcern":
            case "listDatabaseNames":
            case "listDatabases":
            case "startSession":
            case "watch":
            case "getClusterDescription": {
                Tpl t = tpl().any(CLIENT, r).text("." + name + "(");
                for (int i = 0; i < a.size(); i++) {
                    if (a.get(i) instanceof J.Empty) {
                        continue;
                    }
                    if (i > 0) {
                        t.text(", ");
                    }
                    t.expr(a.get(i));
                }
                return apply(mi, t.text(")"), ctx);
            }
            case "getDatabaseNames":
                return apply(mi, tpl().any(CLIENT, r).text(".listDatabaseNames().into(new ArrayList<String>())"), ctx);
            case "dropDatabase":
                return apply(mi, tpl().any(CLIENT, r).text(".getDatabase(").expr(a.get(0)).text(").drop()"), ctx);
            default:
                throw unsupported("calls " + LegacyUsage.describe(mt) + ", which the driver 5 `MongoClient` has no counterpart for");
        }
    }

    private J uri(J.MethodInvocation original, J.MethodInvocation mi, ExecutionContext ctx) {
        Expression r = receiver(mi);
        switch (mi.getSimpleName()) {
            case "getURI":
            case "toString":
                // Driver 5 redacts what toString returns
                return apply(mi, tpl().any(CONN, r).text(".getConnectionString()"), ctx);
            case "getCredentials":
                return apply(mi, tpl().any(CONN, r).text(".getCredential()"), ctx);
            case "getDatabase":
            case "getCollection":
            case "getHosts":
            case "getUsername":
            case "getPassword":
                return apply(mi, tpl().any(CONN, r).text("." + mi.getSimpleName() + "()"), ctx);
            default:
                throw unsupported("calls " + LegacyUsage.describe(original.getMethodType()) + ", which `ConnectionString` has no counterpart for");
        }
    }

    private J options(J.MethodInvocation original, J.MethodInvocation mi, ExecutionContext ctx) {
        if ("builder".equals(mi.getSimpleName())) {
            if (mi.getArguments().isEmpty() || mi.getArguments().get(0) instanceof J.Empty) {
                return apply(mi, settingsBuilder(tpl()), ctx);
            }
            return apply(mi, tpl().text("MongoClientSettings.builder(").any("#{any(com.mongodb.MongoClientSettings)}", mi.getArguments().get(0)).text(")"), ctx);
        }
        throw unsupported("reads `MongoClientOptions." + mi.getSimpleName() + "`, which `MongoClientSettings` keeps elsewhere");
    }

    private Tpl settingsBuilder(Tpl t) {
        t.text("MongoClientSettings.builder()");
        if (javaLegacyUuid) {
            t.text(".uuidRepresentation(UuidRepresentation.JAVA_LEGACY)");
        }
        return t;
    }

    private static final Map<String, String[]> BUILDER_SETTINGS = new HashMap<>();

    static {
        // option -> {applyTo..., builder method, unit or null}
        BUILDER_SETTINGS.put("connectionsPerHost", new String[]{"ConnectionPool", "maxSize", null});
        BUILDER_SETTINGS.put("minConnectionsPerHost", new String[]{"ConnectionPool", "minSize", null});
        BUILDER_SETTINGS.put("maxWaitTime", new String[]{"ConnectionPool", "maxWaitTime", "ms"});
        BUILDER_SETTINGS.put("maxConnectionIdleTime", new String[]{"ConnectionPool", "maxConnectionIdleTime", "ms"});
        BUILDER_SETTINGS.put("maxConnectionLifeTime", new String[]{"ConnectionPool", "maxConnectionLifeTime", "ms"});
        BUILDER_SETTINGS.put("addConnectionPoolListener", new String[]{"ConnectionPool", "addConnectionPoolListener", null});
        BUILDER_SETTINGS.put("connectTimeout", new String[]{"Socket", "connectTimeout", "ms"});
        BUILDER_SETTINGS.put("socketTimeout", new String[]{"Socket", "readTimeout", "ms"});
        BUILDER_SETTINGS.put("serverSelectionTimeout", new String[]{"Cluster", "serverSelectionTimeout", "ms"});
        BUILDER_SETTINGS.put("requiredReplicaSetName", new String[]{"Cluster", "requiredReplicaSetName", null});
        BUILDER_SETTINGS.put("localThreshold", new String[]{"Cluster", "localThreshold", "ms"});
        BUILDER_SETTINGS.put("serverSelector", new String[]{"Cluster", "serverSelector", null});
        BUILDER_SETTINGS.put("addClusterListener", new String[]{"Cluster", "addClusterListener", null});
        BUILDER_SETTINGS.put("heartbeatFrequency", new String[]{"Server", "heartbeatFrequency", "ms"});
        BUILDER_SETTINGS.put("minHeartbeatFrequency", new String[]{"Server", "minHeartbeatFrequency", "ms"});
        BUILDER_SETTINGS.put("addServerListener", new String[]{"Server", "addServerListener", null});
        BUILDER_SETTINGS.put("addServerMonitorListener", new String[]{"Server", "addServerMonitorListener", null});
        BUILDER_SETTINGS.put("sslEnabled", new String[]{"Ssl", "enabled", null});
        BUILDER_SETTINGS.put("sslInvalidHostNameAllowed", new String[]{"Ssl", "invalidHostNameAllowed", null});
        BUILDER_SETTINGS.put("sslContext", new String[]{"Ssl", "context", null});
    }

    private static final Set<String> BUILDER_SAME = new HashSet<>(Arrays.asList(
            "readPreference", "writeConcern", "readConcern", "retryWrites", "retryReads", "applicationName",
            "codecRegistry", "compressorList", "uuidRepresentation", "addCommandListener", "autoEncryptionSettings", "build"));

    /**
     * Options driver 4 removed, with nothing to set in their place: keep-alive is always on, the wait queue is gone,
     * and the JMX, description and finalizer settings no longer exist.
     */
    private static final Set<String> BUILDER_DROPPED = new HashSet<>(Arrays.asList(
            "socketKeepAlive", "threadsAllowedToBlockForConnectionMultiplier", "alwaysUseMBeans", "description",
            "cursorFinalizerEnabled", "legacyDefaults", "heartbeatConnectTimeout", "heartbeatSocketTimeout"));

    private @Nullable J optionsBuilder(J.MethodInvocation original, J.MethodInvocation mi, ExecutionContext ctx) {
        String name = mi.getSimpleName();
        Expression r = receiver(mi);
        List<Expression> a = mi.getArguments();
        if (BUILDER_DROPPED.contains(name)) {
            if (dryRun) {
                return mi;
            }
            if (isInBlock()) {
                return null;
            }
            return r.withPrefix(mi.getPrefix());
        }
        if (BUILDER_SAME.contains(name)) {
            Tpl t = tpl().any(BUILDER, r).text("." + name + "(");
            if (!a.isEmpty() && !(a.get(0) instanceof J.Empty)) {
                t.expr(a.get(0));
            }
            return apply(mi, t.text(")"), ctx);
        }
        String[] setting = BUILDER_SETTINGS.get(name);
        if (setting == null) {
            throw unsupported("sets `MongoClientOptions." + name + "`, which `MongoClientSettings` has no counterpart for");
        }
        String param = uniqueName(setting[0].substring(0, 1).toLowerCase() + setting[0].substring(1).replace("ConnectionPool", "pool").replace("connectionPool", "pool"));
        if ("ConnectionPool".equals(setting[0])) {
            param = uniqueName("pool");
        }
        Tpl t = tpl().any(BUILDER, r).text(".applyTo" + setting[0] + "Settings(" + param + " -> " + param + "." + setting[1] + "(").expr(a.get(0));
        if ("ms".equals(setting[2])) {
            t.text(", TimeUnit.MILLISECONDS");
        }
        return apply(mi, t.text("))"), ctx);
    }

    private J writeResult(J.MethodInvocation original, J.MethodInvocation mi, ExecutionContext ctx) {
        Expression r = receiver(mi);
        Expression originalReceiver = original.getSelect();
        CompilationUnitFacts.WriteKind kind = null;
        if (originalReceiver instanceof J.Identifier) {
            kind = facts().writeResultKinds.get(CompilationUnitFacts.key(((J.Identifier) originalReceiver).getFieldType()));
        } else if (originalReceiver != null) {
            kind = CompilationUnitFacts.writeKindOf(originalReceiver);
        }
        if (kind == null) {
            throw unsupported("reads the `WriteResult` of a write whose kind is not known where it is read");
        }
        String result = kind == CompilationUnitFacts.WriteKind.DELETE ? "com.mongodb.client.result.DeleteResult" :
                "com.mongodb.client.result.UpdateResult";
        switch (mi.getSimpleName()) {
            case "getN":
                if (kind == CompilationUnitFacts.WriteKind.DELETE) {
                    return apply(mi, tpl().text("(int) ").any("#{any(" + result + ")}", r).text(".getDeletedCount()"), ctx);
                }
                if (kind == CompilationUnitFacts.WriteKind.UPDATE_WITH_UPSERT) {
                    if (!(r instanceof J.Identifier)) {
                        throw unsupported("counts the documents an upsert wrote, which driver 5 reports in two fields");
                    }
                    return apply(mi, tpl().text("(int) (").any("#{any(" + result + ")}", r).text(".getMatchedCount() + (")
                            .any("#{any(" + result + ")}", copy(r)).text(".getUpsertedId() != null ? 1 : 0))"), ctx);
                }
                return apply(mi, tpl().text("(int) ").any("#{any(" + result + ")}", r).text(".getMatchedCount()"), ctx);
            case "isUpdateOfExisting":
                if (kind == CompilationUnitFacts.WriteKind.DELETE) {
                    throw unsupported("asks whether a remove updated an existing document");
                }
                return apply(mi, tpl().any("#{any(" + result + ")}", r).text(".getMatchedCount() > 0"), ctx);
            case "wasAcknowledged":
                return apply(mi, tpl().any("#{any(" + result + ")}", r).text(".wasAcknowledged()"), ctx);
            default:
                throw unsupported("calls `WriteResult." + mi.getSimpleName() + "`, which has no counterpart on the driver 5 results");
        }
    }

    private @Nullable J commandResult(J.MethodInvocation original, J.MethodInvocation mi, ExecutionContext ctx) {
        if ("throwOnError".equals(mi.getSimpleName()) && isInBlock()) {
            // runCommand throws a MongoCommandException when the command fails
            return dryRun ? mi : null;
        }
        throw unsupported("calls `CommandResult." + mi.getSimpleName() + "`; driver 5 throws when a command fails instead");
    }

    private static <T extends J> T copy(T tree) {
        return tree.withId(Tree.randomId());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
        J.NewClass original = newClass;
        J j = super.visitNewClass(newClass, ctx);
        if (!(j instanceof J.NewClass)) {
            return j;
        }
        J.NewClass nc = (J.NewClass) j;
        String type = fqn(original.getType());
        if (type == null) {
            return nc;
        }
        try {
            switch (type) {
                case MONGO:
                case LEGACY_MONGO_CLIENT:
                    return newClient(original, nc, ctx);
                case MONGO_CLIENT_URI:
                    if (nc.getArguments().size() == 1) {
                        return apply(nc, tpl().text("new ConnectionString(").expr(nc.getArguments().get(0)).text(")"), ctx);
                    }
                    if (getCursor().getParentTreeCursor().getValue() instanceof J.NewClass &&
                        isLegacyClient(((J.NewClass) getCursor().getParentTreeCursor().getValue()).getType())) {
                        return nc;
                    }
                    throw unsupported("keeps a `MongoClientURI` with options apart from the client it configures");
                case MONGO_CLIENT_OPTIONS_BUILDER:
                    if (nc.getArguments().isEmpty() || nc.getArguments().get(0) instanceof J.Empty) {
                        return apply(nc, settingsBuilder(tpl()), ctx);
                    }
                    return apply(nc, tpl().text("MongoClientSettings.builder(").any("#{any(com.mongodb.MongoClientSettings)}", nc.getArguments().get(0)).text(")"), ctx);
                default:
                    if (LEGACY_ARTIFACT_ONLY.contains(type) || REMOVED.contains(type)) {
                        throw unsupported("creates a `" + LegacyUsage.simpleName(type) + "`, which only the legacy driver has");
                    }
                    return nc;
            }
        } catch (Unsupported e) {
            unsupported.add(e.getMessage());
            return nc;
        }
    }

    private static boolean isLegacyClient(@Nullable JavaType type) {
        String fqn = fqn(type);
        return MONGO.equals(fqn) || LEGACY_MONGO_CLIENT.equals(fqn);
    }

    private J newClient(J.NewClass original, J.NewClass nc, ExecutionContext ctx) {
        JavaType.Method ctor = original.getConstructorType();
        List<String> p = paramTypes(ctor);
        List<Expression> a = nc.getArguments();
        List<Expression> oa = original.getArguments();
        if (p.isEmpty()) {
            if (javaLegacyUuid) {
                return apply(nc, tpl().text("MongoClients.create(").text(settingsBuilderText()).text(".build())"), ctx);
            }
            return apply(nc, tpl().text("MongoClients.create()"), ctx);
        }
        if (p.equals(Collections.singletonList("java.lang.String"))) {
            return apply(nc, connectionString(tpl(), a.get(0), null), ctx);
        }
        if (p.equals(Arrays.asList("java.lang.String", "int"))) {
            return apply(nc, connectionString(tpl(), a.get(0), a.get(1)), ctx);
        }
        if (p.equals(Collections.singletonList(MONGO_CLIENT_URI))) {
            Expression uri = a.get(0);
            Expression originalUri = oa.get(0);
            if (originalUri instanceof J.NewClass && ((J.NewClass) originalUri).getArguments().size() == 2) {
                // new MongoClientURI(uri, builder)
                List<Expression> ua = ((J.NewClass) uri).getArguments();
                Tpl t = tpl().text("MongoClients.create(");
                builderOf(t, ua.get(1), true);
                return apply(nc, t.text(".applyConnectionString(new ConnectionString(").expr(ua.get(0)).text(")).build())"), ctx);
            }
            if (!javaLegacyUuid) {
                if (uri instanceof J.NewClass && ((J.NewClass) uri).getArguments().size() == 1) {
                    return apply(nc, tpl().text("MongoClients.create(").expr(((J.NewClass) uri).getArguments().get(0)).text(")"), ctx);
                }
                return apply(nc, tpl().text("MongoClients.create(").any(CONN, uri).text(")"), ctx);
            }
            return apply(nc, tpl().text("MongoClients.create(" + settingsBuilderText() + ".applyConnectionString(").any(CONN, uri).text(").build())"), ctx);
        }
        // Server addresses, credentials and options
        Expression hosts = null;
        boolean multiple = false;
        Expression credential = null;
        Expression options = null;
        String hostString = null;
        for (int i = 0; i < p.size(); i++) {
            String t = p.get(i);
            JavaType paramType = ctor == null ? null : ctor.getParameterTypes().get(i);
            if (i == 0 && "com.mongodb.ServerAddress".equals(t)) {
                hosts = a.get(0);
            } else if (i == 0 && "java.util.List".equals(t)) {
                hosts = a.get(0);
                multiple = true;
            } else if (i == 0 && "java.lang.String".equals(t)) {
                hostString = "host";
                hosts = a.get(0);
            } else if (i == 1 && "com.mongodb.ServerAddress".equals(t) && hosts != null && !multiple) {
                // Mongo(ServerAddress left, ServerAddress right)
                multiple = true;
                hostString = "pair";
            } else if ("com.mongodb.MongoCredential".equals(t)) {
                if (mayBeNull(oa.get(i))) {
                    // As the removed API replacement leaves a list of credentials, which the settings do not take
                    throw unsupported("creates a `MongoClient` with a credential that may be null, which `MongoClientSettings` does not take");
                }
                credential = a.get(i);
            } else if ("java.util.List".equals(t) && i == 1) {
                credential = singleCredential(oa.get(i), a.get(i));
            } else if (MONGO_CLIENT_OPTIONS.equals(t)) {
                options = a.get(i);
            } else {
                throw unsupported("creates a `MongoClient` with a `" + LegacyUsage.simpleName(t) + "`, which driver 5 takes differently");
            }
        }
        if (hosts == null) {
            throw unsupported("creates a `MongoClient` with arguments driver 5 takes differently");
        }
        String cluster = uniqueName("cluster");
        Tpl t = tpl().text("MongoClients.create(");
        if (options != null) {
            builderOf(t, options, false);
        } else {
            t.text(settingsBuilderText());
        }
        t.text(".applyToClusterSettings(" + cluster + " -> " + cluster + ".hosts(");
        if ("host".equals(hostString)) {
            t.text("Collections.singletonList(new ServerAddress(").expr(hosts).text("))");
        } else if ("pair".equals(hostString)) {
            t.text("Arrays.asList(").any("#{any(com.mongodb.ServerAddress)}", hosts).text(", ").any("#{any(com.mongodb.ServerAddress)}", a.get(1)).text(")");
        } else if (multiple) {
            t.any("#{any(java.util.List<com.mongodb.ServerAddress>)}", hosts);
        } else {
            t.text("Collections.singletonList(").any("#{any(com.mongodb.ServerAddress)}", hosts).text(")");
        }
        t.text(")");
        if (multiple) {
            t.text(".mode(ClusterConnectionMode.MULTIPLE)");
        }
        t.text(")");
        if (credential != null) {
            t.text(".credential(").any("#{any(com.mongodb.MongoCredential)}", credential).text(")");
        }
        return apply(nc, t.text(".build())"), ctx);
    }

    private String settingsBuilderText() {
        return javaLegacyUuid ? "MongoClientSettings.builder().uuidRepresentation(UuidRepresentation.JAVA_LEGACY)" : "MongoClientSettings.builder()";
    }

    /**
     * Appends a {@code MongoClientSettings.Builder} for converted options: the builder itself when the options are built
     * in place, otherwise a builder initialized from them.
     */
    private void builderOf(Tpl t, Expression options, boolean isBuilder) {
        if (isBuilder) {
            t.any(BUILDER, options);
            return;
        }
        if (options instanceof J.MethodInvocation && "build".equals(((J.MethodInvocation) options).getSimpleName()) &&
            ((J.MethodInvocation) options).getSelect() != null &&
            TypeUtils.isOfClassType(((J.MethodInvocation) options).getSelect().getType(), "com.mongodb.MongoClientSettings$Builder")) {
            t.any(BUILDER, ((J.MethodInvocation) options).getSelect());
            return;
        }
        t.text("MongoClientSettings.builder(").any("#{any(com.mongodb.MongoClientSettings)}", options).text(")");
    }

    private static boolean mayBeNull(Expression e) {
        Expression unwrapped = e;
        while (unwrapped instanceof J.Parentheses) {
            unwrapped = (Expression) ((J.Parentheses<?>) unwrapped).getTree();
        }
        if (unwrapped instanceof J.Literal) {
            return ((J.Literal) unwrapped).getValue() == null;
        }
        if (unwrapped instanceof J.Ternary) {
            return mayBeNull(((J.Ternary) unwrapped).getTruePart()) || mayBeNull(((J.Ternary) unwrapped).getFalsePart());
        }
        return false;
    }

    private @Nullable Expression singleCredential(Expression original, Expression converted) {
        if (converted instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) converted;
            String declaring = fqn(m.getMethodType() == null ? null : m.getMethodType().getDeclaringType());
            boolean listFactory = ("java.util.Arrays".equals(declaring) && "asList".equals(m.getSimpleName())) ||
                                  ("java.util.Collections".equals(declaring) && "singletonList".equals(m.getSimpleName())) ||
                                  ("com.google.common.collect.Lists".equals(declaring) && "newArrayList".equals(m.getSimpleName())) ||
                                  ("java.util.List".equals(declaring) && "of".equals(m.getSimpleName()));
            if (listFactory && m.getArguments().size() == 1 && !(m.getArguments().get(0) instanceof J.Empty)) {
                return m.getArguments().get(0);
            }
            if (listFactory || ("java.util.Collections".equals(declaring) && "emptyList".equals(m.getSimpleName()))) {
                if (m.getArguments().isEmpty() || m.getArguments().get(0) instanceof J.Empty) {
                    return null;
                }
            }
        }
        throw unsupported("creates a `MongoClient` with a list of credentials, where driver 5 takes one");
    }

    private Tpl connectionString(Tpl t, Expression host, @Nullable Expression port) {
        String suffix = javaLegacyUuid ? "/?uuidRepresentation=javaLegacy" : "";
        if (host instanceof J.Literal && ((J.Literal) host).getValue() instanceof String &&
            (port == null || port instanceof J.Literal)) {
            String uri = "mongodb://" + ((J.Literal) host).getValue() + (port == null ? "" : ":" + ((J.Literal) port).getValue()) + suffix;
            return t.text("MongoClients.create(\"" + uri.replace("\\", "\\\\").replace("\"", "\\\"") + "\")");
        }
        t.text("MongoClients.create(\"mongodb://\" + ").any("#{any(java.lang.String)}", host);
        if (port != null) {
            t.text(" + \":\" + ").any("#{any(int)}", port);
        }
        if (!suffix.isEmpty()) {
            t.text(" + \"" + suffix + "\"");
        }
        return t.text(")");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Declarations and types
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public J visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
        J.VariableDeclarations v = multiVariable;
        String type = fqn(v.getType());
        JavaType replacement = null;
        if (v.getTypeExpression() != null && type != null && !v.getVariables().isEmpty()) {
            JavaType.Variable first = v.getVariables().get(0).getVariableType();
            if (DB_CURSOR.equals(type)) {
                replacement = facts().cursorKind(first) == CompilationUnitFacts.CursorKind.CURSOR ? modern(ctx).cursor() : modern(ctx).findIterable();
            } else if (WRITE_RESULT.equals(type)) {
                CompilationUnitFacts.WriteKind kind = facts().writeResultKinds.get(CompilationUnitFacts.key(first));
                replacement = kind == CompilationUnitFacts.WriteKind.DELETE ? modern(ctx).deleteResult() : modern(ctx).updateResult();
            } else if (DB_OBJECT.equals(type) && facts().basicDbObjectLocals.contains(CompilationUnitFacts.key(first))) {
                replacement = modern(ctx).basicDBObject();
            }
        }
        J j = super.visitVariableDeclarations(v, ctx);
        if (!(j instanceof J.VariableDeclarations)) {
            return j;
        }
        v = (J.VariableDeclarations) j;
        if (replacement != null && !dryRun) {
            JavaType r = replacement;
            v = v.withTypeExpression(ModernTypes.typeTree(r, v.getTypeExpression().getPrefix()));
            v = v.withVariables(ListUtils_map(v.getVariables(), nv -> nv
                    .withName(nv.getName().withType(r).withFieldType(nv.getName().getFieldType() == null ? null : nv.getName().getFieldType().withType(r)))
                    .withVariableType(nv.getVariableType() == null ? null : nv.getVariableType().withType(r))));
            for (String imp : ModernTypes.importsFor(r)) {
                maybeAddImport(imp);
            }
        }
        if (!dryRun && DB_CURSOR.equals(type) && replacement != null &&
            replacement == modern(ctx).cursor()) {
            v = v.withVariables(ListUtils_map(v.getVariables(), nv -> {
                if (nv.getInitializer() != null && !isNull(nv.getInitializer()) && needsIterator(nv.getInitializer())) {
                    Expression init = nv.getInitializer();
                    J.MethodInvocation iterator = JavaTemplate.builder(ITERABLE + ".iterator()")
                            .javaParser(ModernTypes.parser(ctx))
                            .build()
                            .apply(new Cursor(getCursor(), init), init.getCoordinates().replace(), init);
                    return nv.withInitializer(iterator.withPrefix(init.getPrefix()));
                }
                return nv;
            }));
        }
        return v;
    }

    private static boolean needsIterator(Expression e) {
        return TypeUtils.isOfClassType(e.getType(), FIND_ITERABLE) || DB_CURSOR.equals(fqn(e.getType()));
    }

    @Override
    public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
        J.Assignment original = assignment;
        J j = super.visitAssignment(assignment, ctx);
        if (dryRun || !(j instanceof J.Assignment)) {
            return j;
        }
        J.Assignment a = (J.Assignment) j;
        if (original.getVariable() instanceof J.Identifier &&
            DB_CURSOR.equals(fqn(((J.Identifier) original.getVariable()).getFieldType() == null ? null : ((J.Identifier) original.getVariable()).getFieldType().getType())) &&
            facts().cursorKind(((J.Identifier) original.getVariable()).getFieldType()) == CompilationUnitFacts.CursorKind.CURSOR &&
            !isNull(a.getAssignment()) && needsIterator(a.getAssignment())) {
            Expression value = a.getAssignment();
            J.MethodInvocation iterator = JavaTemplate.builder(ITERABLE + ".iterator()")
                    .javaParser(ModernTypes.parser(ctx))
                    .build()
                    .apply(new Cursor(getCursor(), value), value.getCoordinates().replace(), value);
            a = a.withAssignment(iterator.withPrefix(value.getPrefix()));
        }
        return a;
    }

    @Override
    public J visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
        J.Identifier i = (J.Identifier) super.visitIdentifier(identifier, ctx);
        if (dryRun) {
            return i;
        }
        JavaType.Variable fieldType = identifier.getFieldType();
        if (fieldType != null) {
            String legacy = fqn(fieldType.getType());
            JavaType r = null;
            if (DB_CURSOR.equals(legacy) && facts().cursorKind(fieldType) == CompilationUnitFacts.CursorKind.CURSOR) {
                r = modern(ctx).cursor();
            } else if (WRITE_RESULT.equals(legacy) && facts().writeResultKinds.get(CompilationUnitFacts.key(fieldType)) == CompilationUnitFacts.WriteKind.DELETE) {
                r = modern(ctx).deleteResult();
            } else if (DB_OBJECT.equals(legacy) && facts().basicDbObjectLocals.contains(CompilationUnitFacts.key(fieldType))) {
                r = modern(ctx).basicDBObject();
            } else if (legacy != null && CONVERTED.contains(legacy)) {
                r = modern(ctx).replacementFor(legacy);
            }
            if (r != null) {
                return i.withType(r).withFieldType(i.getFieldType() == null ? null : i.getFieldType().withType(r));
            }
            return i;
        }
        // A type name, e.g. in `List<DBCollection>` or a method's return type
        String legacy = fqn(identifier.getType());
        if (legacy != null && CONVERTED.contains(legacy) && isTypeName()) {
            JavaType r = modern(ctx).replacementFor(legacy);
            if (r != null) {
                if ("var".equals(i.getSimpleName())) {
                    // A local declared with `var` takes the type of what it is initialized with
                    return i.withType(r);
                }
                for (String imp : ModernTypes.importsFor(r)) {
                    maybeAddImport(imp);
                }
                return ModernTypes.typeTree(r, i.getPrefix());
            }
        }
        return i;
    }

    private boolean isTypeName() {
        Object parent = getCursor().getParentTreeCursor().getValue();
        if (parent instanceof J.MethodInvocation || parent instanceof J.Import || parent instanceof J.FieldAccess ||
            parent instanceof J.NewClass || parent instanceof J.MemberReference) {
            return false;
        }
        return true;
    }

    @Override
    public J visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
        J j = super.visitFieldAccess(fieldAccess, ctx);
        if (dryRun || !(j instanceof J.FieldAccess)) {
            return j;
        }
        J.FieldAccess fa = (J.FieldAccess) j;
        // A qualified type name, e.g. `MongoClientOptions.Builder` or `com.mongodb.DBCollection`
        String legacy = fqn(fieldAccess.getType());
        if (fa.getName().getFieldType() == null && legacy != null && CONVERTED.contains(legacy) &&
            !(getCursor().getParentTreeCursor().getValue() instanceof J.Import) &&
            !(getCursor().getParentTreeCursor().getValue() instanceof J.MethodInvocation) &&
            !(getCursor().getParentTreeCursor().getValue() instanceof J.FieldAccess)) {
            JavaType r = modern(ctx).replacementFor(legacy);
            if (r != null) {
                for (String imp : ModernTypes.importsFor(r)) {
                    maybeAddImport(imp);
                }
                return ModernTypes.typeTree(r, fa.getPrefix());
            }
        }
        return fa;
    }

    @Override
    public @Nullable JavaType visitType(@Nullable JavaType javaType, ExecutionContext ctx) {
        if (dryRun || javaType == null) {
            return javaType;
        }
        return mapType(javaType, ctx, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * The types already mapped, so that a type maps to the same instance wherever it appears, as a method's type does on
     * its declaration and on its name.
     */
    private final Map<JavaType, JavaType> mappedTypes = new IdentityHashMap<>();

    private JavaType mapType(JavaType type, ExecutionContext ctx, Set<JavaType> seen) {
        JavaType cached = mappedTypes.get(type);
        if (cached != null) {
            return cached;
        }
        if (!seen.add(type)) {
            return type;
        }
        JavaType result = mapTypeUncached(type, ctx, seen);
        mappedTypes.put(type, result);
        return result;
    }

    private JavaType mapTypeUncached(JavaType type, ExecutionContext ctx, Set<JavaType> seen) {
        if (type instanceof JavaType.Parameterized) {
            JavaType.Parameterized p = (JavaType.Parameterized) type;
            boolean changed = false;
            List<JavaType> params = new ArrayList<>();
            for (JavaType t : p.getTypeParameters()) {
                JavaType m = mapType(t, ctx, seen);
                changed |= m != t;
                params.add(m);
            }
            JavaType base = mapType(p.getType(), ctx, seen);
            if (base != p.getType() && base instanceof JavaType.FullyQualified) {
                return base;
            }
            return changed ? p.withTypeParameters(params) : p;
        }
        if (type instanceof JavaType.Array) {
            JavaType.Array arr = (JavaType.Array) type;
            JavaType elem = mapType(arr.getElemType(), ctx, seen);
            return elem == arr.getElemType() ? arr : arr.withElemType(elem);
        }
        if (type instanceof JavaType.Method) {
            JavaType.Method m = (JavaType.Method) type;
            String declaring = fqn(m.getDeclaringType());
            if (declaring != null && CONVERTED.contains(declaring)) {
                return m;
            }
            JavaType ret = mapType(m.getReturnType(), ctx, seen);
            List<JavaType> params = new ArrayList<>();
            boolean changed = ret != m.getReturnType();
            for (JavaType t : m.getParameterTypes()) {
                JavaType mt = mapType(t, ctx, seen);
                changed |= mt != t;
                params.add(mt);
            }
            return changed ? m.withReturnType(ret).withParameterTypes(params) : m;
        }
        if (type instanceof JavaType.Variable) {
            JavaType.Variable v = (JavaType.Variable) type;
            JavaType t = v.getType() == null ? null : mapType(v.getType(), ctx, seen);
            return t == v.getType() ? v : v.withType(t);
        }
        if (type instanceof JavaType.FullyQualified) {
            String fqn = ((JavaType.FullyQualified) type).getFullyQualifiedName();
            if (CONVERTED.contains(fqn)) {
                JavaType r = modern(ctx).replacementFor(fqn);
                return r == null ? type : r;
            }
        }
        return type;
    }

    private static <T> List<T> ListUtils_map(List<T> list, java.util.function.UnaryOperator<T> f) {
        return org.openrewrite.internal.ListUtils.map(list, f);
    }
}

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
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;

import java.util.*;

import static java.util.Objects.requireNonNull;
import static org.openrewrite.mongodb.MongoTypes.fqn;

/**
 * Replaces the uses of driver 3 API that driver 5 removed and that have an equivalent, in code that keeps the legacy
 * API as well as in code about to move to the driver 5 API. {@link RemovedApiFinder} says which uses these are.
 */
final class RemovedApiFixes {

    private RemovedApiFixes() {
    }

    static JavaParser.Builder<?, ?> parser(ExecutionContext ctx) {
        return JavaParser.fromJavaVersion().classpathFromResources(ctx,
                "mongodb-driver-legacy-5", "mongodb-driver-sync-5", "mongodb-driver-core-5", "bson-5");
    }

    private static final String[][] WRITE_CONCERNS = {
            {"SAFE", "ACKNOWLEDGED"},
            {"NORMAL", "UNACKNOWLEDGED"},
            {"FSYNC_SAFE", "JOURNALED"},
            {"FSYNCED", "JOURNALED"},
            {"JOURNAL_SAFE", "JOURNALED"},
            {"REPLICAS_SAFE", "W2"},
            {"REPLICA_ACKNOWLEDGED", "W2"},
    };

    private static final String[][] RENAMES = {
            {"com.mongodb.MongoCredential createMongoCRCredential(..)", "createCredential"},
            {"com.mongodb.ReadPreference isSlaveOk()", "isSecondaryOk"},
            {"org.bson.BSONObject containsKey(java.lang.String)", "containsField"},
            {"com.mongodb.ConnectionString getURI()", "getConnectionString"},
            {"com.mongodb.WriteConcern withJ(boolean)", "withJournal"},
            {"com.mongodb.DB getMongo()", "getMongoClient"},
            {"com.mongodb.client.MongoCollection count()", "estimatedDocumentCount"},
            {"com.mongodb.client.MongoCollection count(..)", "countDocuments"},
    };

    /**
     * The visitors to run, in order, on a Java source file.
     */
    static List<TreeVisitor<?, ExecutionContext>> visitors() {
        List<TreeVisitor<?, ExecutionContext>> visitors = new ArrayList<>();
        visitors.add(new ChangeType("com.mongodb.Mongo", "com.mongodb.MongoClient", true).getVisitor());
        for (String[] wc : WRITE_CONCERNS) {
            visitors.add(new ReplaceConstantWithAnotherConstant("com.mongodb.WriteConcern." + wc[0],
                    "com.mongodb.WriteConcern." + wc[1]).getVisitor());
        }
        for (String[] rename : RENAMES) {
            visitors.add(new ChangeMethodName(rename[0], rename[1], true, null).getVisitor());
        }
        visitors.add(new Calls());
        return visitors;
    }

    static J.CompilationUnit apply(J.CompilationUnit cu, ExecutionContext ctx) {
        J.CompilationUnit result = cu;
        for (TreeVisitor<?, ExecutionContext> v : visitors()) {
            Tree t = v.visit(result, ctx);
            if (t instanceof J.CompilationUnit) {
                result = (J.CompilationUnit) t;
            }
        }
        return result;
    }

    private static final Set<String> DROPPED_BUILDER_OPTIONS = new HashSet<>(Arrays.asList(
            "socketKeepAlive", "threadsAllowedToBlockForConnectionMultiplier", "alwaysUseMBeans", "description",
            "legacyDefaults"));

    private static final Set<String> REPLACE_OPTIONS = new HashSet<>(Arrays.asList(
            "upsert", "bypassDocumentValidation", "collation", "hint", "hintString", "comment", "let"));

    /**
     * Whether {@code replaceOne}'s {@code UpdateOptions} can become {@code ReplaceOptions}: built in place from
     * settings both have, or held in a variable whose settings can be copied.
     */
    static boolean isConvertibleUpdateOptions(Expression options) {
        return updateOptionsChain(options) != null || RemovedApiFinder.isRepeatable(options);
    }

    private static @Nullable List<J.MethodInvocation> updateOptionsChain(Expression options) {
        Deque<J.MethodInvocation> chain = new ArrayDeque<>();
        Expression current = options;
        while (current instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) current;
            if (!REPLACE_OPTIONS.contains(m.getSimpleName())) {
                return null;
            }
            chain.push(m);
            current = m.getSelect();
        }
        if (current instanceof J.NewClass && TypeUtils.isOfClassType(current.getType(), "com.mongodb.client.model.UpdateOptions") &&
            (((J.NewClass) current).getArguments().isEmpty() || ((J.NewClass) current).getArguments().get(0) instanceof J.Empty)) {
            return new ArrayList<>(chain);
        }
        return null;
    }

    /**
     * The types whose {@code toJson()} wrote strict extended JSON in driver 3 and writes relaxed extended JSON in driver 5.
     */
    private static final Set<String> STRICT_TO_JSON = new HashSet<>(Arrays.asList(
            "com.mongodb.BasicDBObject", "org.bson.Document", "org.bson.BsonDocument", "org.bson.RawBsonDocument"));

    /**
     * The types whose {@code toString()} returns what their {@code toJson()} does.
     */
    static final Set<String> STRICT_TO_STRING = new HashSet<>(Arrays.asList(
            "com.mongodb.BasicDBObject", "org.bson.BsonDocument"));

    /**
     * The driver type of {@link #STRICT_TO_STRING} whose {@code toString()} a value of {@code type} has, or {@code null}
     * when it has none, or a class of the code between them declares {@code toString()} or the {@code toJson()} that
     * driver 3's {@code toString()} called.
     */
    static @Nullable String strictToString(@Nullable JavaType type) {
        return strictToString(type, true);
    }

    /**
     * As {@link #strictToString(JavaType)}, where {@code ownToString} says whether a {@code toString()} that the code
     * declares counts, which it does not for {@code super.toString()}.
     */
    private static @Nullable String strictToString(@Nullable JavaType type, boolean ownToString) {
        for (JavaType.FullyQualified t = TypeUtils.asFullyQualified(type); t != null; t = t.getSupertype()) {
            String fqn = t.getFullyQualifiedName();
            if (STRICT_TO_STRING.contains(fqn)) {
                return fqn;
            }
            if (!RemovedApiFinder.isDriverType(fqn)) {
                for (JavaType.Method m : t.getMethods()) {
                    if ((ownToString && "toString".equals(m.getName()) || "toJson".equals(m.getName())) &&
                        m.getParameterTypes().isEmpty()) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static final class Calls extends JavaVisitor<ExecutionContext> {

        private static final String[] IMPORTS = {
                "com.mongodb.BasicDBObject", "com.mongodb.ReadPreference", "com.mongodb.WriteConcern", "com.mongodb.MongoClient",
                "com.mongodb.MongoClientOptions", "com.mongodb.client.model.ReplaceOptions",
                "com.mongodb.client.model.DBCollectionCountOptions", "java.util.ArrayList", "org.bson.json.JsonMode",
                "org.bson.json.JsonWriterSettings"};

        /**
         * The same expression with ids of its own, for a template that reads it twice.
         */
        private Expression copy(Expression e) {
            return (Expression) new JavaVisitor<Integer>() {
                @Override
                public J preVisit(J tree, Integer p) {
                    return tree.withId(Tree.randomId());
                }
            }.visitNonNull(e, 0);
        }

        private J template(Expression tree, String code, ExecutionContext ctx, Object... args) {
            J result = JavaTemplate.builder(code)
                    .javaParser(parser(ctx))
                    .imports(IMPORTS)
                    .build()
                    .apply(new Cursor(getCursor().getParentOrThrow(), tree), tree.getCoordinates().replace(), args);
            for (String imp : IMPORTS) {
                String simpleName = imp.substring(imp.lastIndexOf('.') + 1);
                if (code.matches("(?s).*\\b" + simpleName + "\\b.*")) {
                    maybeAddImport(imp);
                }
            }
            return result;
        }

        private static boolean isSuper(Expression select) {
            return select instanceof J.Identifier && "super".equals(((J.Identifier) select).getSimpleName());
        }

        /**
         * What {@link #strictToString(JavaType)} is for {@code super.toString()} where the cursor is: driver 3's
         * {@code toString()} called the {@code toJson()} of the class it is called in.
         */
        private @Nullable String superToString() {
            Cursor c = getCursor().dropParentUntil(v -> v instanceof J.ClassDeclaration ||
                                                        v instanceof J.NewClass && ((J.NewClass) v).getBody() != null ||
                                                        v == Cursor.ROOT_VALUE);
            if (c.getValue() instanceof J.ClassDeclaration) {
                return strictToString(((J.ClassDeclaration) c.getValue()).getType(), false);
            }
            if (c.getValue() instanceof J.NewClass) {
                J.NewClass n = c.getValue();
                for (Statement s : requireNonNull(n.getBody()).getStatements()) {
                    if (s instanceof J.MethodDeclaration && "toJson".equals(((J.MethodDeclaration) s).getSimpleName()) &&
                        ((J.MethodDeclaration) s).getParameters().stream().allMatch(J.Empty.class::isInstance)) {
                        return null;
                    }
                }
                return n.getClazz() == null ? null : strictToString(n.getClazz().getType(), false);
            }
            return null;
        }

        @Override
        public @Nullable J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation original = method;
            J j = super.visitMethodInvocation(method, ctx);
            if (!(j instanceof J.MethodInvocation)) {
                return j;
            }
            J.MethodInvocation mi = (J.MethodInvocation) j;
            JavaType.Method mt = original.getMethodType();
            if (mt == null) {
                return mi;
            }
            String declaring = fqn(mt.getDeclaringType());
            String receiver = mi.getSelect() == null ? declaring : fqn(mi.getSelect().getType());
            String name = mi.getSimpleName();
            List<Expression> args = mi.getArguments();
            Expression select = mi.getSelect();
            boolean statement = getCursor().getParentTreeCursor().getValue() instanceof J.Block;

            // Driver 5 writes relaxed extended JSON where driver 3 wrote strict
            if (select != null && (args.isEmpty() || args.get(0) instanceof J.Empty) &&
                ("toJson".equals(name) || "toString".equals(name))) {
                // Only where the driver's own method is called, not a class's override of it
                if ("toJson".equals(name) ? STRICT_TO_JSON.contains(declaring) :
                        declaring != null && declaring.equals(isSuper(select) ? superToString() : strictToString(select.getType()))) {
                    return template(mi, "#{any(" + declaring + ")}.toJson(JsonWriterSettings.builder().outputMode(JsonMode.STRICT).build())",
                            ctx, select);
                }
            }

            // Driver 5 redacts the credentials and option values of the connection string that toString returns
            if ("toString".equals(name) && select != null && (args.isEmpty() || args.get(0) instanceof J.Empty)) {
                if (MongoTypes.MONGO_CLIENT_URI.equals(declaring)) {
                    return template(mi, "#{any(com.mongodb.MongoClientURI)}.getURI()", ctx, select);
                }
                if ("com.mongodb.ConnectionString".equals(declaring)) {
                    return template(mi, "#{any(com.mongodb.ConnectionString)}.getConnectionString()", ctx, select);
                }
            }

            if ("com.mongodb.MongoClientOptions$Builder".equals(declaring) && select != null) {
                if (DROPPED_BUILDER_OPTIONS.contains(name)) {
                    return statement ? null : select.withPrefix(mi.getPrefix());
                }
                if ("socketFactory".equals(name) && RemovedApiFinder.isDefaultSslSocketFactory(args.get(0))) {
                    return template(mi, "#{any(com.mongodb.MongoClientOptions.Builder)}.sslEnabled(true)", ctx, select);
                }
                return mi;
            }
            if (("com.mongodb.MongoClient".equals(declaring) || "com.mongodb.Mongo".equals(declaring)) &&
                "getDatabaseNames".equals(name) && select != null) {
                return template(mi, "#{any(com.mongodb.MongoClient)}.listDatabaseNames().into(new ArrayList<String>())", ctx, select);
            }
            if (("com.mongodb.MongoClient".equals(declaring) || "com.mongodb.Mongo".equals(declaring)) &&
                "getAllAddress".equals(name) && select != null) {
                // Driver 3 answered with the hosts of the cluster settings too, without waiting for the cluster
                return template(mi, "#{any(com.mongodb.MongoClient)}.getClusterDescription().getClusterSettings().getHosts()", ctx, select);
            }
            if ("forEach".equals(name) && args.size() == 1 && declaring != null && declaring.startsWith("com.mongodb.client.") &&
                TypeUtils.isOfClassType(mt.getParameterTypes().get(0), "com.mongodb.Block")) {
                return mi.withArguments(ListUtils.map(args, a -> blockToConsumer(a, ctx)));
            }
            if ("com.mongodb.client.MongoCollection".equals(declaring) && "replaceOne".equals(name) &&
                !args.isEmpty() && TypeUtils.isOfClassType(mt.getParameterTypes().get(mt.getParameterTypes().size() - 1),
                    "com.mongodb.client.model.UpdateOptions")) {
                int last = args.size() - 1;
                Expression options = args.get(last);
                J replaced = replaceOptions(options, ctx);
                if (replaced == null) {
                    return mi;
                }
                maybeRemoveImport("com.mongodb.client.model.UpdateOptions");
                return mi.withArguments(ListUtils.map(args, (i, a) -> i == last ? (Expression) replaced.withPrefix(a.getPrefix()) : a));
            }
            if ("isModifiedCountAvailable".equals(name) && RemovedApiFinder.isRepeatable(select) &&
                ("com.mongodb.client.result.UpdateResult".equals(declaring) || "com.mongodb.bulk.BulkWriteResult".equals(declaring) ||
                 "com.mongodb.BulkWriteResult".equals(declaring))) {
                return new J.Literal(Tree.randomId(), mi.getPrefix(), mi.getMarkers(), true, "true", null, JavaType.Primitive.Boolean);
            }
            if ("com.mongodb.DB".equals(declaring) && select != null) {
                if ("getStats".equals(name)) {
                    return template(mi, "#{any(com.mongodb.DB)}.command(\"dbStats\")", ctx, select);
                }
                if ("slaveOk".equals(name)) {
                    return template(mi, "#{any(com.mongodb.DB)}.setReadPreference(ReadPreference.secondaryPreferred())", ctx, select);
                }
                return mi;
            }
            if ("com.mongodb.DBCollection".equals(declaring) && select != null) {
                return collection(mi, mt, select, name, args, ctx);
            }
            if ("com.mongodb.DBCursor".equals(declaring) && select != null) {
                switch (name) {
                    case "slaveOk":
                        return template(mi, "#{any(com.mongodb.DBCursor)}.setReadPreference(ReadPreference.secondaryPreferred())", ctx, select);
                    case "snapshot":
                        // MongoDB 4.0 removed snapshot mode, and driver 5 supports no server older than 4.0
                        return statement ? null : select.withPrefix(mi.getPrefix());
                    case "addOption": {
                        String option = RemovedApiFinder.queryOption(original.getArguments().get(0));
                        if ("QUERYOPTION_NOTIMEOUT".equals(option)) {
                            return template(mi, "#{any(com.mongodb.DBCursor)}.noCursorTimeout(true)", ctx, select);
                        }
                        if ("QUERYOPTION_SLAVEOK".equals(option)) {
                            return template(mi, "#{any(com.mongodb.DBCursor)}.setReadPreference(ReadPreference.secondaryPreferred())", ctx, select);
                        }
                        return mi;
                    }
                    default:
                        return mi;
                }
            }
            if (MongoTypes.UTIL_JSON.equals(declaring) && args.size() == 1) {
                if ("serialize".equals(name) && TypeUtils.isAssignableTo(MongoTypes.BASIC_DB_OBJECT, args.get(0).getType())) {
                    return template(mi, "#{any(com.mongodb.BasicDBObject)}.toJson()", ctx, args.get(0));
                }
                if ("parse".equals(name) && getCursor().getParentTreeCursor().getValue() instanceof J.TypeCast) {
                    J.TypeCast cast = getCursor().getParentTreeCursor().getValue();
                    String target = fqn(cast.getType());
                    if (MongoTypes.DB_OBJECT.equals(target) || MongoTypes.BASIC_DB_OBJECT.equals(target)) {
                        maybeRemoveImport(MongoTypes.UTIL_JSON);
                        maybeAddImport(MongoTypes.BASIC_DB_OBJECT);
                        return template(mi, "BasicDBObject.parse(#{any(java.lang.String)})", ctx, args.get(0));
                    }
                }
            }
            return mi;
        }

        @Override
        public J visitTypeCast(J.TypeCast typeCast, ExecutionContext ctx) {
            J j = super.visitTypeCast(typeCast, ctx);
            if (j instanceof J.TypeCast) {
                J.TypeCast cast = (J.TypeCast) j;
                Expression e = cast.getExpression();
                if (e instanceof J.MethodInvocation && "parse".equals(((J.MethodInvocation) e).getSimpleName()) &&
                    TypeUtils.isOfClassType(e.getType(), MongoTypes.BASIC_DB_OBJECT) &&
                    ((J.MethodInvocation) e).getSelect() instanceof J.Identifier &&
                    "BasicDBObject".equals(((J.Identifier) ((J.MethodInvocation) e).getSelect()).getSimpleName())) {
                    // `(DBObject) BasicDBObject.parse(s)`: the cast is no longer needed
                    return e.withPrefix(cast.getPrefix());
                }
            }
            return j;
        }

        private J collection(J.MethodInvocation mi, JavaType.Method mt, Expression select, String name,
                             List<Expression> args, ExecutionContext ctx) {
            List<String> params = RemovedApiFinder.erasedParameterTypes(mt);
            String coll = "#{any(com.mongodb.DBCollection)}";
            switch (name) {
                case "slaveOk":
                    return template(mi, coll + ".setReadPreference(ReadPreference.secondaryPreferred())", ctx, select);
                case "getStats":
                    if (RemovedApiFinder.isRepeatable(select)) {
                        return template(mi, coll + ".getDB().command(new BasicDBObject(\"collStats\", " + coll + ".getName()))", ctx, select, select);
                    }
                    return mi;
                case "isCapped":
                    if (RemovedApiFinder.isRepeatable(select)) {
                        return template(mi, coll + ".getDB().command(new BasicDBObject(\"collStats\", " + coll + ".getName())).getBoolean(\"capped\")", ctx, select, select);
                    }
                    return mi;
                case "find":
                    if (params.equals(Arrays.asList(MongoTypes.DB_OBJECT, MongoTypes.DB_OBJECT, "int", "int"))) {
                        return template(mi, coll + ".find(#{any(com.mongodb.DBObject)}, #{any(com.mongodb.DBObject)}).skip(#{any(int)}).batchSize(#{any(int)})",
                                ctx, select, args.get(0), args.get(1), args.get(2), args.get(3));
                    }
                    return mi;
                case "getCount":
                    if (params.equals(Collections.singletonList("com.mongodb.ReadPreference"))) {
                        return template(mi, coll + ".getCount(new BasicDBObject(), new DBCollectionCountOptions().readPreference(#{any(com.mongodb.ReadPreference)}))",
                                ctx, select, args.get(0));
                    }
                    if (params.equals(Arrays.asList(MongoTypes.DB_OBJECT, MongoTypes.DB_OBJECT))) {
                        // the server never used the fields of a count
                        return template(mi, coll + ".getCount(#{any(com.mongodb.DBObject)})", ctx, select, args.get(0));
                    }
                    if (params.equals(Arrays.asList(MongoTypes.DB_OBJECT, MongoTypes.DB_OBJECT, "com.mongodb.ReadPreference"))) {
                        return template(mi, coll + ".getCount(#{any(com.mongodb.DBObject)}, new DBCollectionCountOptions().readPreference(#{any(com.mongodb.ReadPreference)}))",
                                ctx, select, args.get(0), args.get(2));
                    }
                    if (params.equals(Arrays.asList(MongoTypes.DB_OBJECT, MongoTypes.DB_OBJECT, "long", "long"))) {
                        return template(mi, coll + ".getCount(#{any(com.mongodb.DBObject)}, new DBCollectionCountOptions().limit((int) #{any(long)}).skip((int) #{any(long)}))",
                                ctx, select, args.get(0), args.get(2), args.get(3));
                    }
                    if (params.equals(Arrays.asList(MongoTypes.DB_OBJECT, MongoTypes.DB_OBJECT, "long", "long", "com.mongodb.ReadPreference"))) {
                        return template(mi, coll + ".getCount(#{any(com.mongodb.DBObject)}, new DBCollectionCountOptions().limit((int) #{any(long)}).skip((int) #{any(long)}).readPreference(#{any(com.mongodb.ReadPreference)}))",
                                ctx, select, args.get(0), args.get(2), args.get(3), args.get(4));
                    }
                    return mi;
                default:
                    return mi;
            }
        }

        private @Nullable J replaceOptions(Expression options, ExecutionContext ctx) {
            List<J.MethodInvocation> chain = updateOptionsChain(options);
            if (chain != null) {
                StringBuilder code = new StringBuilder("new ReplaceOptions()");
                List<Object> args = new ArrayList<>();
                for (J.MethodInvocation link : chain) {
                    code.append('.').append(link.getSimpleName()).append('(');
                    Expression arg = link.getArguments().get(0);
                    code.append("#{any(").append(placeholder(arg)).append(")})");
                    args.add(arg);
                }
                return template(options, code.toString(), ctx, args.toArray());
            }
            if (RemovedApiFinder.isRepeatable(options)) {
                String o = "#{any(com.mongodb.client.model.UpdateOptions)}";
                return template(options, "new ReplaceOptions().upsert(" + o + ".isUpsert()).bypassDocumentValidation(" + o +
                                         ".getBypassDocumentValidation()).collation(" + o + ".getCollation())", ctx, options, options, options);
            }
            return null;
        }

        private static String placeholder(Expression e) {
            JavaType t = e.getType();
            if (t instanceof JavaType.Primitive) {
                return ((JavaType.Primitive) t).getKeyword();
            }
            String fqn = fqn(t);
            return fqn == null ? "java.lang.Object" : fqn.replace('$', '.');
        }

        private Expression blockToConsumer(Expression arg, ExecutionContext ctx) {
            Expression e = arg;
            if (e instanceof J.TypeCast && TypeUtils.isOfClassType(((J.TypeCast) e).getType(), "com.mongodb.Block") &&
                ((J.TypeCast) e).getExpression() instanceof J.Lambda) {
                maybeRemoveImport("com.mongodb.Block");
                return ((J.TypeCast) e).getExpression().withPrefix(e.getPrefix());
            }
            if (e instanceof J.NewClass && ((J.NewClass) e).getBody() != null && ((J.NewClass) e).getClazz() != null &&
                TypeUtils.isOfClassType(((J.NewClass) e).getClazz().getType(), "com.mongodb.Block")) {
                return anonymousConsumer((J.NewClass) e);
            }
            return (Expression) JavaTemplate.builder("#{any(com.mongodb.Block)}::apply")
                    .javaParser(parser(ctx))
                    .build()
                    .apply(new Cursor(getCursor(), e), e.getCoordinates().replace(), e);
        }

        private J.NewClass anonymousConsumer(J.NewClass block) {
            JavaType.ShallowClass consumer = JavaType.ShallowClass.build("java.util.function.Consumer");
            TypeTree clazz = block.getClazz();
            if (clazz instanceof J.ParameterizedType) {
                J.ParameterizedType pt = (J.ParameterizedType) clazz;
                List<JavaType> typeArgs = new ArrayList<>();
                if (pt.getTypeParameters() != null) {
                    for (Expression tp : pt.getTypeParameters()) {
                        typeArgs.add(tp.getType());
                    }
                }
                JavaType.Parameterized parameterized = new JavaType.Parameterized(null, consumer, typeArgs);
                clazz = pt.withClazz(consumerName(pt.getClazz(), consumer)).withType(parameterized);
            } else if (clazz != null) {
                clazz = consumerName(clazz, consumer);
            } else {
                return block;
            }
            maybeRemoveImport("com.mongodb.Block");
            maybeAddImport("java.util.function.Consumer");
            J.Block body = block.getBody();
            body = body.withStatements(ListUtils.map(body.getStatements(), s -> {
                if (s instanceof J.MethodDeclaration && "apply".equals(((J.MethodDeclaration) s).getSimpleName()) &&
                    ((J.MethodDeclaration) s).getParameters().size() == 1) {
                    J.MethodDeclaration md = (J.MethodDeclaration) s;
                    return md.withName(md.getName().withSimpleName("accept"))
                            .withMethodType(md.getMethodType() == null ? null : md.getMethodType().withName("accept"));
                }
                return s;
            }));
            return block.withClazz(clazz).withBody(body);
        }

        private static J.Identifier consumerName(NameTree name, JavaType.ShallowClass consumer) {
            return new J.Identifier(Tree.randomId(), name.getPrefix(), name.getMarkers(), Collections.emptyList(),
                    "Consumer", consumer, null);
        }

        @Override
        public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
            J j = super.visitNewClass(newClass, ctx);
            if (!(j instanceof J.NewClass)) {
                return j;
            }
            J.NewClass nc = (J.NewClass) j;
            String type = fqn(newClass.getType());
            List<String> params = RemovedApiFinder.erasedParameterTypes(newClass.getConstructorType());
            if ("com.mongodb.WriteConcern".equals(type) && params.isEmpty() && nc.getBody() == null) {
                // `new WriteConcern()` was `w: 0`
                return template(nc, "WriteConcern.UNACKNOWLEDGED", ctx);
            }
            if (("com.mongodb.MongoClient".equals(type) || "com.mongodb.Mongo".equals(type)) && params.size() >= 2 &&
                params.size() <= 3 && "java.util.List".equals(params.get(1))) {
                RemovedApiFinder.Credential credential = RemovedApiFinder.credentialOf(nc.getArguments().get(1));
                if (credential == null) {
                    return nc;
                }
                Expression first = nc.getArguments().get(0);
                String firstPlaceholder = "java.util.List".equals(params.get(0)) ?
                        "#{any(java.util.List<com.mongodb.ServerAddress>)}" : "#{any(com.mongodb.ServerAddress)}";
                List<Object> args = new ArrayList<>();
                args.add(first);
                String code;
                if (credential.isNone()) {
                    code = "new MongoClient(" + firstPlaceholder + (params.size() == 3 ? ", #{any(com.mongodb.MongoClientOptions)})" : ")");
                } else {
                    String credentialCode;
                    if (credential.single != null) {
                        credentialCode = "#{any(com.mongodb.MongoCredential)}";
                        args.add(credential.single);
                    } else if (credential.list != null) {
                        credentialCode = "#{any(java.util.List<com.mongodb.MongoCredential>)}.isEmpty() ? null : " +
                                         "#{any(java.util.List<com.mongodb.MongoCredential>)}.get(0)";
                        args.add(credential.list);
                        args.add(copy(credential.list));
                    } else {
                        credentialCode = "#{anyArray(com.mongodb.MongoCredential)}.length == 0 ? null : " +
                                         "#{anyArray(com.mongodb.MongoCredential)}[0]";
                        args.add(credential.array);
                        args.add(copy(credential.array));
                    }
                    code = "new MongoClient(" + firstPlaceholder + ", " + credentialCode + ", " +
                           (params.size() == 3 ? "#{any(com.mongodb.MongoClientOptions)})" : "MongoClientOptions.builder().build())");
                }
                if (params.size() == 3) {
                    args.add(nc.getArguments().get(2));
                }
                maybeRemoveImport("java.util.Arrays");
                maybeRemoveImport("java.util.Collections");
                maybeRemoveImport("java.util.ArrayList");
                maybeRemoveImport("java.util.LinkedList");
                maybeRemoveImport("com.google.common.collect.Lists");
                maybeRemoveImport("com.google.common.collect.ImmutableList");
                return template(nc, code, ctx, args.toArray());
            }
            return nc;
        }
    }
}

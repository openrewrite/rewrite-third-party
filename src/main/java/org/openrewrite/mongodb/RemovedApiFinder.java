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
import org.openrewrite.Tree;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

import java.util.*;

import static org.openrewrite.mongodb.MongoTypes.fqn;

/**
 * Finds each use of the driver 3.12 API that driver 5 no longer has, and says whether
 * {@code org.openrewrite.mongodb.MigrateRemovedMongoApis} replaces it.
 */
abstract class RemovedApiFinder extends JavaIsoVisitor<ExecutionContext> {

    /**
     * @param tree        the use
     * @param description what is used, such as {@code DB.eval}
     * @param fixable     whether {@code MigrateRemovedMongoApis} replaces it
     */
    abstract void found(J tree, String description, boolean fixable);

    private final Set<UUID> covered = new HashSet<>();

    private static final Set<String> DROPPED_BUILDER_OPTIONS = new HashSet<>(Arrays.asList(
            "socketKeepAlive", "threadsAllowedToBlockForConnectionMultiplier", "alwaysUseMBeans", "description",
            "legacyDefaults"));

    private static final Set<String> CONTAINS_KEY_TYPES = new HashSet<>(Arrays.asList(
            "com.mongodb.DBObject", "com.mongodb.BasicDBObject", "com.mongodb.BasicDBList", "com.mongodb.CommandResult",
            "com.mongodb.LazyDBObject", "com.mongodb.LazyDBList"));

    private static final Set<String> FIXED_WRITE_CONCERNS = new HashSet<>(Arrays.asList(
            "SAFE", "NORMAL", "FSYNC_SAFE", "FSYNCED", "JOURNAL_SAFE", "REPLICAS_SAFE", "REPLICA_ACKNOWLEDGED"));

    /**
     * The constructors of {@code Mongo} that {@code MongoClient} has too, so that the change of type keeps them.
     */
    private static final Set<List<String>> MONGO_CONSTRUCTORS = new HashSet<>(Arrays.asList(
            Collections.emptyList(),
            Collections.singletonList("java.lang.String"),
            Arrays.asList("java.lang.String", "int"),
            Collections.singletonList("com.mongodb.ServerAddress"),
            Collections.singletonList("java.util.List"),
            Arrays.asList("com.mongodb.ServerAddress", "com.mongodb.MongoClientOptions"),
            Arrays.asList("java.util.List", "com.mongodb.MongoClientOptions")));

    static boolean isDriverType(@Nullable String fqn) {
        return fqn != null && (fqn.startsWith("com.mongodb.") || fqn.startsWith("org.bson."));
    }

    static List<String> erasedParameterTypes(JavaType.@Nullable Method method) {
        if (method == null) {
            return Collections.emptyList();
        }
        List<String> params = new ArrayList<>(method.getParameterTypes().size());
        for (JavaType t : method.getParameterTypes()) {
            params.add(erase(t));
        }
        return params;
    }

    static String erase(@Nullable JavaType type) {
        if (type instanceof JavaType.Primitive) {
            return ((JavaType.Primitive) type).getKeyword();
        }
        if (type instanceof JavaType.Array) {
            return erase(((JavaType.Array) type).getElemType()) + "[]";
        }
        if (type instanceof JavaType.GenericTypeVariable) {
            List<JavaType> bounds = ((JavaType.GenericTypeVariable) type).getBounds();
            return bounds.isEmpty() ? "java.lang.Object" : erase(bounds.get(0));
        }
        String fqn = fqn(type);
        return fqn == null ? "java.lang.Object" : fqn;
    }

    private static String simple(String fqn) {
        return LegacyUsage.simpleName(fqn);
    }

    @Override
    public J.Import visitImport(J.Import _import, ExecutionContext ctx) {
        return _import;
    }

    @Override
    public J.Package visitPackage(J.Package pkg, ExecutionContext ctx) {
        return pkg;
    }

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        JavaType.Method mt = method.getMethodType();
        String declaring = fqn(mt == null ? null : mt.getDeclaringType());
        if (mt != null && isDriverType(declaring)) {
            String cls = declaring;
            boolean isStatic = mt.hasFlags(Flag.Static);
            if (!isStatic && method.getSelect() != null && isDriverType(fqn(method.getSelect().getType()))) {
                cls = fqn(method.getSelect().getType());
            }
            if (MongoTypes.MONGO.equals(cls)) {
                cls = MongoTypes.LEGACY_MONGO_CLIENT;
            }
            String name = method.getSimpleName();
            RemovedApi api = RemovedApi.get();
            if (api.isRemovedType(cls)) {
                boolean fixable = isFixableJsonCall(cls, name, method);
                if (fixable && method.getSelect() != null) {
                    covered.add(method.getSelect().getId());
                }
                found(method, "`" + simple(cls) + "." + name + "`", fixable);
            } else if (api.isRemoved('M', cls, name, erasedParameterTypes(mt))) {
                boolean fixable = isFixableMethod(cls, name, method, mt);
                if (fixable) {
                    for (Expression arg : method.getArguments()) {
                        covered.add(arg.getId());
                    }
                }
                found(method, "`" + simple(cls) + "." + name + "`", fixable);
            }
        }
        return super.visitMethodInvocation(method, ctx);
    }

    private boolean isFixableJsonCall(String cls, String name, J.MethodInvocation method) {
        if (!MongoTypes.UTIL_JSON.equals(cls) || method.getArguments().size() != 1) {
            return false;
        }
        if ("parse".equals(name)) {
            Object parent = getCursor().getParentTreeCursor().getValue();
            while (parent instanceof J.Parentheses) {
                parent = getCursor().getParentTreeCursor().getParentTreeCursor().getValue();
            }
            if (parent instanceof J.TypeCast) {
                String target = fqn(((J.TypeCast) parent).getType());
                return MongoTypes.DB_OBJECT.equals(target) || MongoTypes.BASIC_DB_OBJECT.equals(target);
            }
            return false;
        }
        if ("serialize".equals(name)) {
            return TypeUtils.isAssignableTo(MongoTypes.BASIC_DB_OBJECT, method.getArguments().get(0).getType());
        }
        return false;
    }

    private boolean isFixableMethod(String cls, String name, J.MethodInvocation method, JavaType.Method mt) {
        List<Expression> args = method.getArguments();
        switch (cls) {
            case "com.mongodb.MongoClientOptions$Builder":
                if (DROPPED_BUILDER_OPTIONS.contains(name)) {
                    return true;
                }
                return "socketFactory".equals(name) && isDefaultSslSocketFactory(args.get(0));
            case "com.mongodb.MongoCredential":
                return "createMongoCRCredential".equals(name);
            case "com.mongodb.ReadPreference":
            case "com.mongodb.TaggableReadPreference":
                return "isSlaveOk".equals(name);
            case "com.mongodb.MongoClient":
                return "getDatabaseNames".equals(name);
            case "com.mongodb.client.MongoCollection":
                if ("replaceOne".equals(name)) {
                    return !args.isEmpty() && RemovedApiFixes.isConvertibleUpdateOptions(args.get(args.size() - 1));
                }
                return "count".equals(name);
            case "com.mongodb.client.result.UpdateResult":
            case "com.mongodb.bulk.BulkWriteResult":
            case "com.mongodb.BulkWriteResult":
                return "isModifiedCountAvailable".equals(name) && isRepeatable(method.getSelect());
            case "com.mongodb.ConnectionString":
                return "getURI".equals(name);
            case "com.mongodb.WriteConcern":
                return "withJ".equals(name);
            case "com.mongodb.DB":
                return "getStats".equals(name) || "slaveOk".equals(name);
            case "com.mongodb.DBCollection":
                switch (name) {
                    case "slaveOk":
                    case "getCount":
                        return true;
                    case "find":
                        return args.size() == 4;
                    case "getStats":
                    case "isCapped":
                        return isRepeatable(method.getSelect());
                    default:
                        return false;
                }
            case "com.mongodb.DBCursor":
                if ("slaveOk".equals(name) || "snapshot".equals(name)) {
                    return true;
                }
                return "addOption".equals(name) && args.size() == 1 && queryOption(args.get(0)) != null;
            default:
                if ("forEach".equals(name) && cls.startsWith("com.mongodb.client.")) {
                    return true;
                }
                return "containsKey".equals(name) && CONTAINS_KEY_TYPES.contains(cls);
        }
    }

    /**
     * The name of a {@code Bytes.QUERYOPTION_*} constant that {@code DBCursor.addOption} is given and that has a
     * replacement, or {@code null}.
     */
    static @Nullable String queryOption(Expression e) {
        String name = null;
        JavaType.Variable v = null;
        if (e instanceof J.FieldAccess) {
            v = ((J.FieldAccess) e).getName().getFieldType();
        } else if (e instanceof J.Identifier) {
            v = ((J.Identifier) e).getFieldType();
        }
        if (v != null && "com.mongodb.Bytes".equals(fqn(v.getOwner()))) {
            name = v.getName();
        }
        return "QUERYOPTION_NOTIMEOUT".equals(name) || "QUERYOPTION_SLAVEOK".equals(name) ? name : null;
    }

    static boolean isDefaultSslSocketFactory(Expression e) {
        if (e instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) e;
            String declaring = fqn(m.getMethodType() == null ? null : m.getMethodType().getDeclaringType());
            return "getDefault".equals(m.getSimpleName()) && m.getArguments().stream().allMatch(a -> a instanceof J.Empty) &&
                   ("javax.net.ssl.SSLSocketFactory".equals(declaring) || "javax.net.SocketFactory".equals(declaring) &&
                    m.getSelect() != null && TypeUtils.isOfClassType(m.getSelect().getType(), "javax.net.ssl.SSLSocketFactory"));
        }
        return false;
    }

    /**
     * Whether an expression can be evaluated twice without a difference: a variable or a field.
     */
    static boolean isRepeatable(@Nullable Expression e) {
        if (e instanceof J.Identifier) {
            return true;
        }
        if (e instanceof J.FieldAccess) {
            Expression target = ((J.FieldAccess) e).getTarget();
            return target instanceof J.Identifier || isRepeatable(target);
        }
        return false;
    }

    @Override
    public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
        String cls = fqn(newClass.getType());
        if (isDriverType(cls) && newClass.getClazz() != null) {
            covered.add(newClass.getClazz().getId());
            List<String> params = erasedParameterTypes(newClass.getConstructorType());
            RemovedApi api = RemovedApi.get();
            if (MongoTypes.MONGO.equals(cls)) {
                if (!MONGO_CONSTRUCTORS.contains(params)) {
                    found(newClass, "`new Mongo(" + String.join(", ", simpleNames(params)) + ")`", false);
                }
            } else if (api.isRemovedType(cls)) {
                found(newClass, "`" + simple(cls) + "`", false);
            } else if (api.isRemoved('C', cls, "<init>", params)) {
                boolean fixable = MongoTypes.LEGACY_MONGO_CLIENT.equals(cls) && params.size() >= 2 && params.size() <= 3 &&
                                  "java.util.List".equals(params.get(1)) && singletonListElement(newClass.getArguments().get(1)) != null ||
                                  "com.mongodb.WriteConcern".equals(cls) && params.isEmpty();
                found(newClass, "`new " + simple(cls) + "(" + String.join(", ", simpleNames(params)) + ")`", fixable);
            }
        }
        return super.visitNewClass(newClass, ctx);
    }

    private static List<String> simpleNames(List<String> params) {
        List<String> names = new ArrayList<>(params.size());
        for (String p : params) {
            names.add(simple(p));
        }
        return names;
    }

    /**
     * The one element of a list built in place with {@code Arrays.asList}, {@code Collections.singletonList},
     * {@code List.of} or Guava's {@code Lists.newArrayList}.
     */
    static @Nullable Expression singletonListElement(Expression e) {
        if (e instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) e;
            String declaring = fqn(m.getMethodType() == null ? null : m.getMethodType().getDeclaringType());
            String name = m.getSimpleName();
            boolean factory = "java.util.Arrays".equals(declaring) && "asList".equals(name) ||
                              "java.util.Collections".equals(declaring) && "singletonList".equals(name) ||
                              "java.util.List".equals(declaring) && "of".equals(name) ||
                              "com.google.common.collect.Lists".equals(declaring) && "newArrayList".equals(name) ||
                              "com.google.common.collect.ImmutableList".equals(declaring) && "of".equals(name);
            if (factory && m.getArguments().size() == 1 && !(m.getArguments().get(0) instanceof J.Empty) &&
                !(m.getArguments().get(0).getType() instanceof JavaType.Array) &&
                !TypeUtils.isAssignableTo("java.lang.Iterable", m.getArguments().get(0).getType())) {
                return m.getArguments().get(0);
            }
        }
        return null;
    }

    @Override
    public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
        JavaType.Variable v = fieldAccess.getName().getFieldType();
        if (v != null) {
            if (checkField(fieldAccess, v)) {
                covered.add(fieldAccess.getTarget().getId());
            }
            return super.visitFieldAccess(fieldAccess, ctx);
        }
        // A qualified type name
        String type = fqn(fieldAccess.getType());
        if (RemovedApi.get().isRemovedType(type) && !covered.contains(fieldAccess.getId())) {
            found(fieldAccess, "`" + simple(type) + "`", MongoTypes.MONGO.equals(type));
            return fieldAccess;
        }
        return super.visitFieldAccess(fieldAccess, ctx);
    }

    /**
     * @return whether the field is a removed one
     */
    private boolean checkField(J tree, JavaType.Variable v) {
        String owner = fqn(v.getOwner());
        if (!isDriverType(owner)) {
            return false;
        }
        if (RemovedApi.get().isRemovedType(owner)) {
            boolean fixable = covered.contains(tree.getId());
            if (!fixable) {
                found(tree, "`" + simple(owner) + "." + v.getName() + "`", false);
            }
            return true;
        }
        if (RemovedApi.get().isRemoved('F', owner, v.getName(), Collections.emptyList())) {
            found(tree, "`" + simple(owner) + "." + v.getName() + "`",
                    "com.mongodb.WriteConcern".equals(owner) && FIXED_WRITE_CONCERNS.contains(v.getName()));
            return true;
        }
        return false;
    }

    @Override
    public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
        if (covered.contains(identifier.getId())) {
            return identifier;
        }
        JavaType.Variable v = identifier.getFieldType();
        Object parent = getCursor().getParentTreeCursor().getValue();
        if (v != null) {
            // A static field used by its simple name, through a static import
            if (!(parent instanceof J.FieldAccess) && !(parent instanceof J.VariableDeclarations.NamedVariable) &&
                v.getOwner() instanceof JavaType.FullyQualified) {
                checkField(identifier, v);
            }
            return identifier;
        }
        String type = fqn(identifier.getType());
        if (RemovedApi.get().isRemovedType(type) && !(parent instanceof J.FieldAccess) &&
            !(parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getName() == identifier)) {
            found(identifier, "`" + simple(type) + "`", MongoTypes.MONGO.equals(type));
        }
        return identifier;
    }

    /**
     * Arguments covered by a fixable use, which the replacement takes care of.
     */
    boolean isCovered(Tree tree) {
        return covered.contains(tree.getId());
    }
}

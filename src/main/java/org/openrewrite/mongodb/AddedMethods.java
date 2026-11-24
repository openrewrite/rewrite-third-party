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
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The abstract methods that driver 5 requires of the interfaces and abstract classes of driver 3 that grew during 3.x or
 * since, read from {@code META-INF/rewrite/mongodb/added-methods.tsv}, and which of them a class that implements those
 * types lacks.
 */
final class AddedMethods {

    private static final String RESOURCE = "/META-INF/rewrite/mongodb/added-methods.tsv";

    private static volatile @Nullable AddedMethods instance;

    static final class Method {
        final String name;
        final String erasedParameters;
        /** Each parameter's erased type, or {@code *} where it is a type variable. */
        final String[] patterns;
        final boolean returnsValue;
        final boolean internal;
        /** Whether the driver calls the method on a class of the code, so that one that throws cannot stand in. */
        final boolean called;
        final String declaration;
        /** What the method does where driver 3 called the class, or {@code null} to throw. */
        final @Nullable String body;

        Method(String name, String erasedParameters, String patterns, String returnType, String kind, String declaration,
               @Nullable String body) {
            this.name = name;
            this.erasedParameters = erasedParameters;
            this.patterns = patterns.isEmpty() ? new String[0] : patterns.split(",");
            this.returnsValue = !"void".equals(returnType);
            this.internal = "internal".equals(kind);
            this.called = "called".equals(kind);
            this.declaration = declaration;
            this.body = body;
        }

        String key() {
            return name + "(" + erasedParameters + ")";
        }

        String label() {
            StringJoiner p = new StringJoiner(", ");
            for (String t : erasedParameters.isEmpty() ? new String[0] : erasedParameters.split(",")) {
                p.add(LegacyUsage.simpleName(t));
            }
            return name + "(" + p + ")";
        }

        /** Whether a method of the code with these erased parameter types implements this one, or is meant to. */
        boolean implementedBy(List<@Nullable String> parameters) {
            if (parameters.size() != patterns.length) {
                return false;
            }
            for (int i = 0; i < patterns.length; i++) {
                String p = parameters.get(i);
                // A parameter whose type is unknown could be the one this needs
                if (p != null && !"*".equals(patterns[i]) && !patterns[i].equals(p)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * A method a class lacks, with its declaration written for that class, or {@code null} for the declaration when it
     * cannot be written or cannot stand in, and {@link #why} says why.
     */
    static final class Missing {
        final String type;
        final Method method;
        final @Nullable String declaration;
        final @Nullable String why;

        Missing(String type, Method method, @Nullable String declaration, @Nullable String why) {
            this.type = type;
            this.method = method;
            this.declaration = declaration;
            this.why = why;
        }
    }

    private final Map<String, List<String>> typeVariables = new HashMap<>();
    private final Map<String, List<Method>> methods = new HashMap<>();

    static AddedMethods get() {
        AddedMethods a = instance;
        if (a == null) {
            synchronized (AddedMethods.class) {
                a = instance;
                if (a == null) {
                    a = new AddedMethods();
                    instance = a;
                }
            }
        }
        return a;
    }

    private AddedMethods() {
        try (InputStream in = AddedMethods.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                typeVariables.put(f[0], f[1].isEmpty() ? Collections.emptyList() : Arrays.asList(f[1].split(",")));
                methods.computeIfAbsent(f[0], k -> new ArrayList<>())
                        .add(new Method(f[2], f[3], f[4], f[5], f[6], f[7],
                                f.length > 8 && !f[8].isEmpty() ? f[8].replace("\\n", "\n") : null));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The methods that driver 5 requires of a concrete class and that neither it nor a class of its own it extends
     * declares.
     *
     * @param type     the class, or for an anonymous class the type it instantiates
     * @param declared the methods an anonymous class declares, which its type does not hold
     * @param declares whether {@code type} is the class being declared, whose own type variables its body names
     */
    List<Missing> missing(JavaType.@Nullable FullyQualified type, Collection<JavaType.Method> declared, boolean declares) {
        if (type == null) {
            return Collections.emptyList();
        }
        Map<String, List<JavaType.Method>> have = new HashMap<>();
        for (JavaType.Method m : declared) {
            add(have, m);
        }
        Map<String, List<@Nullable String>> required = new LinkedHashMap<>();
        walk(type, Collections.emptyMap(), true, declares, have, required);
        // A concrete class of the driver that the class extends implements every type of the driver it is one of, also
        // where the class names that type itself, as a JsonWriter that implements BsonWriter
        for (JavaType.FullyQualified s = type.getSupertype(); s != null; s = s.getSupertype()) {
            if (isDriver(s.getFullyQualifiedName()) && s.getKind() == JavaType.FullyQualified.Kind.Class &&
                !s.hasFlags(Flag.Abstract)) {
                JavaType.FullyQualified concrete = s;
                required.keySet().removeIf(fqn -> TypeUtils.isAssignableTo(fqn, concrete));
                break;
            }
        }
        Map<String, Missing> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<@Nullable String>> r : required.entrySet()) {
            for (Method m : methods.get(r.getKey())) {
                if (out.containsKey(m.key())) {
                    continue;
                }
                JavaType.Method implementation = implementation(have, m);
                if (implementation != null) {
                    if (returnsValue(implementation) != m.returnsValue) {
                        // Driver 5 changed what the method returns, as MongoCollection.insertOne, which returns a result
                        out.put(m.key(), new Missing(r.getKey(), m, null, m.returnsValue ?
                                "which it declares to return a value, where the class's returns nothing" :
                                "which it declares to return nothing, where the class's returns a value"));
                    }
                    continue;
                }
                String declaration = m.internal ? null : write(m, typeVariables.get(r.getKey()), r.getValue());
                String why = m.internal ? "which names a type of its internal API" :
                        declaration == null ? "whose type arguments the class leaves unbound" :
                        m.called && m.body == null ? "which the driver calls on it, so that a method that throws cannot stand in" : null;
                out.put(m.key(), new Missing(r.getKey(), m, why == null ? declaration : null, why));
            }
        }
        return new ArrayList<>(out.values());
    }

    private static boolean isDriver(String fqn) {
        return fqn.startsWith("com.mongodb.") || fqn.startsWith("org.bson.");
    }

    private static JavaType.@Nullable Method implementation(Map<String, List<JavaType.Method>> have, Method m) {
        for (JavaType.Method candidate : have.getOrDefault(m.name, Collections.emptyList())) {
            List<@Nullable String> parameters = new ArrayList<>();
            for (JavaType t : candidate.getParameterTypes()) {
                parameters.add(erase(t));
            }
            if (m.implementedBy(parameters)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean returnsValue(JavaType.Method m) {
        // An unknown return type could be the one driver 5 declares
        return m.getReturnType() != JavaType.Primitive.Void;
    }

    private static void add(Map<String, List<JavaType.Method>> have, JavaType.Method m) {
        have.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
    }

    /**
     * Collects the methods that the types in the hierarchy of {@code t} declare, when they are the code's own, and the
     * type arguments of each type of the table, in the order of its type variables. A type variable that {@code env}
     * does not bind is in scope only in the type arguments of the type the walk starts from, where the code declares
     * it; further up, as where a class extends a raw type, it is unbound.
     */
    private void walk(JavaType t, Map<String, String> env, boolean start, boolean declares,
                      Map<String, List<JavaType.Method>> have, Map<String, List<@Nullable String>> required) {
        JavaType.FullyQualified cls;
        Map<String, String> bound = new HashMap<>();
        if (t instanceof JavaType.Parameterized) {
            JavaType.Parameterized p = (JavaType.Parameterized) t;
            cls = p.getType();
            List<JavaType> vars = cls.getTypeParameters();
            List<JavaType> args = p.getTypeParameters();
            for (int i = 0; i < vars.size() && i < args.size(); i++) {
                if (vars.get(i) instanceof JavaType.GenericTypeVariable) {
                    String arg = source(args.get(i), env, start);
                    if (arg != null) {
                        bound.put(((JavaType.GenericTypeVariable) vars.get(i)).getName(), arg);
                    }
                }
            }
        } else if (t instanceof JavaType.FullyQualified) {
            cls = (JavaType.FullyQualified) t;
            if (start && declares) {
                // The class's own type variables, which its body names
                for (JavaType v : cls.getTypeParameters()) {
                    if (v instanceof JavaType.GenericTypeVariable) {
                        String name = ((JavaType.GenericTypeVariable) v).getName();
                        bound.put(name, name);
                    }
                }
            }
        } else {
            return;
        }
        String fqn = cls.getFullyQualifiedName();
        if (required.containsKey(fqn)) {
            return;
        }
        boolean driver = isDriver(fqn);
        if (!driver) {
            for (JavaType.Method m : cls.getMethods()) {
                // An abstract method, as those of the JDK's interfaces, implements nothing
                if (!m.hasFlags(Flag.Abstract) && !m.hasFlags(Flag.Static)) {
                    add(have, m);
                }
            }
        } else if (methods.containsKey(fqn)) {
            List<@Nullable String> args = new ArrayList<>();
            for (JavaType v : cls.getTypeParameters()) {
                args.add(v instanceof JavaType.GenericTypeVariable ? bound.get(((JavaType.GenericTypeVariable) v).getName()) : null);
            }
            required.put(fqn, args);
        }
        if (driver && cls.getKind() == JavaType.FullyQualified.Kind.Class && !cls.hasFlags(Flag.Abstract)) {
            // A concrete class of the driver implements what its own types require
            return;
        }
        if (cls.getSupertype() != null) {
            walk(cls.getSupertype(), bound, false, false, have, required);
        }
        for (JavaType.FullyQualified i : cls.getInterfaces()) {
            walk(i, bound, false, false, have, required);
        }
    }

    private static final Pattern JAVA_LANG = Pattern.compile("\\bjava\\.lang\\.([A-Z]\\w*)\\b(?!\\.)");

    private static final Pattern METHOD_TYPE_PARAMETERS = Pattern.compile("^(?:public|protected) <([^>]+)> ");

    /**
     * The declaration with the type variables of its type replaced by the arguments the class gives them, or
     * {@code null} when it leaves one it needs unbound.
     */
    private static @Nullable String write(Method m, List<String> vars, List<@Nullable String> args) {
        Set<String> shadowed = new HashSet<>();
        Matcher tp = METHOD_TYPE_PARAMETERS.matcher(m.declaration);
        if (tp.find()) {
            for (String p : tp.group(1).split(",")) {
                shadowed.add(p.trim().split("\\s+")[0]);
            }
        }
        String declaration = m.declaration;
        for (int i = 0; i < vars.size(); i++) {
            String v = vars.get(i);
            if (shadowed.contains(v) || !Pattern.compile("\\b" + v + "\\b").matcher(declaration).find()) {
                continue;
            }
            String arg = i < args.size() ? args.get(i) : null;
            if (arg == null) {
                return null;
            }
            declaration = declaration.replaceAll("\\b" + v + "\\b", Matcher.quoteReplacement(arg));
        }
        // Shortening leaves the names of java.lang qualified
        return JAVA_LANG.matcher(declaration).replaceAll("$1");
    }

    /**
     * The source of a type argument, with its type variables bound as {@code env} binds them, or {@code null} when it
     * cannot be written.
     *
     * @param free whether a type variable that {@code env} does not bind is in scope, as the code's own
     */
    private static @Nullable String source(JavaType t, Map<String, String> env, boolean free) {
        if (t instanceof JavaType.Primitive) {
            return ((JavaType.Primitive) t).getKeyword();
        }
        if (t instanceof JavaType.Array) {
            String e = source(((JavaType.Array) t).getElemType(), env, free);
            return e == null ? null : e + "[]";
        }
        if (t instanceof JavaType.GenericTypeVariable) {
            JavaType.GenericTypeVariable g = (JavaType.GenericTypeVariable) t;
            if ("?".equals(g.getName())) {
                if (g.getVariance() == JavaType.GenericTypeVariable.Variance.INVARIANT || g.getBounds().isEmpty()) {
                    return "?";
                }
                String b = source(g.getBounds().get(0), env, free);
                return b == null ? null : (g.getVariance() == JavaType.GenericTypeVariable.Variance.COVARIANT ? "? extends " : "? super ") + b;
            }
            String b = env.get(g.getName());
            return b != null ? b : free ? g.getName() : null;
        }
        if (t instanceof JavaType.Parameterized) {
            JavaType.Parameterized p = (JavaType.Parameterized) t;
            StringJoiner args = new StringJoiner(", ", "<", ">");
            for (JavaType a : p.getTypeParameters()) {
                String s = source(a, env, free);
                if (s == null) {
                    return null;
                }
                args.add(s);
            }
            return p.getType().getFullyQualifiedName().replace('$', '.') + args;
        }
        if (t instanceof JavaType.FullyQualified && !(t instanceof JavaType.Unknown)) {
            return ((JavaType.FullyQualified) t).getFullyQualifiedName().replace('$', '.');
        }
        return null;
    }

    private static @Nullable String erase(JavaType t) {
        if (t instanceof JavaType.Primitive) {
            return ((JavaType.Primitive) t).getKeyword();
        }
        if (t instanceof JavaType.Array) {
            String e = erase(((JavaType.Array) t).getElemType());
            return e == null ? null : e + "[]";
        }
        if (t instanceof JavaType.GenericTypeVariable) {
            List<JavaType> bounds = ((JavaType.GenericTypeVariable) t).getBounds();
            return bounds.isEmpty() ? "java.lang.Object" : erase(bounds.get(0));
        }
        if (t instanceof JavaType.FullyQualified && !(t instanceof JavaType.Unknown)) {
            return ((JavaType.FullyQualified) t).getFullyQualifiedName();
        }
        // Unknown, which any parameter could be
        return null;
    }
}

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
 * The abstract methods that driver 5 adds to the interfaces and abstract classes of driver 3, read from
 * {@code META-INF/rewrite/mongodb/added-methods.tsv}, and which of them a class that implements those types lacks.
 */
final class AddedMethods {

    private static final String RESOURCE = "/META-INF/rewrite/mongodb/added-methods.tsv";

    private static volatile @Nullable AddedMethods instance;

    static final class Method {
        final String name;
        final String erasedParameters;
        final boolean internal;
        final String declaration;

        Method(String name, String erasedParameters, boolean internal, String declaration) {
            this.name = name;
            this.erasedParameters = erasedParameters;
            this.internal = internal;
            this.declaration = declaration;
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
    }

    /**
     * A method a class lacks, with its declaration written for that class, or {@code null} for the declaration when it
     * cannot be written: it names a type of the driver's internal API, or the class leaves a type variable unbound.
     */
    static final class Missing {
        final String type;
        final Method method;
        final @Nullable String declaration;

        Missing(String type, Method method, @Nullable String declaration) {
            this.type = type;
            this.method = method;
            this.declaration = declaration;
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
                methods.computeIfAbsent(f[0], k -> new ArrayList<>()).add(new Method(f[2], f[3], "internal".equals(f[4]), f[5]));
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
     */
    List<Missing> missing(JavaType.@Nullable FullyQualified type, Collection<JavaType.Method> declared) {
        if (type == null) {
            return Collections.emptyList();
        }
        Set<String> have = new HashSet<>();
        for (JavaType.Method m : declared) {
            have.add(key(m));
        }
        Map<String, List<@Nullable String>> required = new LinkedHashMap<>();
        walk(type, Collections.emptyMap(), have, required);
        Map<String, Missing> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<@Nullable String>> r : required.entrySet()) {
            for (Method m : methods.get(r.getKey())) {
                if (!have.contains(m.key()) && !out.containsKey(m.key())) {
                    out.put(m.key(), new Missing(r.getKey(), m, m.internal ? null : write(m, typeVariables.get(r.getKey()), r.getValue())));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Collects the methods that the types in the hierarchy of {@code t} declare, when they are the code's own, and the
     * type arguments of each type of the table, in the order of its type variables.
     */
    private void walk(JavaType t, Map<String, String> env, Set<String> have, Map<String, List<@Nullable String>> required) {
        JavaType.FullyQualified cls;
        Map<String, String> bound = new HashMap<>();
        if (t instanceof JavaType.Parameterized) {
            JavaType.Parameterized p = (JavaType.Parameterized) t;
            cls = p.getType();
            List<JavaType> vars = cls.getTypeParameters();
            List<JavaType> args = p.getTypeParameters();
            for (int i = 0; i < vars.size() && i < args.size(); i++) {
                if (vars.get(i) instanceof JavaType.GenericTypeVariable) {
                    String arg = source(args.get(i), env);
                    if (arg != null) {
                        bound.put(((JavaType.GenericTypeVariable) vars.get(i)).getName(), arg);
                    }
                }
            }
        } else if (t instanceof JavaType.FullyQualified) {
            cls = (JavaType.FullyQualified) t;
        } else {
            return;
        }
        String fqn = cls.getFullyQualifiedName();
        if (required.containsKey(fqn)) {
            return;
        }
        boolean driver = fqn.startsWith("com.mongodb.") || fqn.startsWith("org.bson.");
        if (!driver) {
            for (JavaType.Method m : cls.getMethods()) {
                have.add(key(m));
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
            walk(cls.getSupertype(), bound, have, required);
        }
        for (JavaType.FullyQualified i : cls.getInterfaces()) {
            walk(i, bound, have, required);
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
     */
    private static @Nullable String source(JavaType t, Map<String, String> env) {
        if (t instanceof JavaType.Primitive) {
            return ((JavaType.Primitive) t).getKeyword();
        }
        if (t instanceof JavaType.Array) {
            String e = source(((JavaType.Array) t).getElemType(), env);
            return e == null ? null : e + "[]";
        }
        if (t instanceof JavaType.GenericTypeVariable) {
            JavaType.GenericTypeVariable g = (JavaType.GenericTypeVariable) t;
            if ("?".equals(g.getName())) {
                if (g.getVariance() == JavaType.GenericTypeVariable.Variance.INVARIANT || g.getBounds().isEmpty()) {
                    return "?";
                }
                String b = source(g.getBounds().get(0), env);
                return b == null ? null : (g.getVariance() == JavaType.GenericTypeVariable.Variance.COVARIANT ? "? extends " : "? super ") + b;
            }
            // Bound by a supertype, or else a type variable of the class itself, which its body can name
            return env.getOrDefault(g.getName(), g.getName());
        }
        if (t instanceof JavaType.Parameterized) {
            JavaType.Parameterized p = (JavaType.Parameterized) t;
            StringJoiner args = new StringJoiner(", ", "<", ">");
            for (JavaType a : p.getTypeParameters()) {
                String s = source(a, env);
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

    private static String key(JavaType.Method m) {
        StringJoiner p = new StringJoiner(",");
        for (JavaType t : m.getParameterTypes()) {
            p.add(erase(t));
        }
        return m.getName() + "(" + p + ")";
    }

    private static String erase(JavaType t) {
        if (t instanceof JavaType.Primitive) {
            return ((JavaType.Primitive) t).getKeyword();
        }
        if (t instanceof JavaType.Array) {
            return erase(((JavaType.Array) t).getElemType()) + "[]";
        }
        if (t instanceof JavaType.GenericTypeVariable) {
            List<JavaType> bounds = ((JavaType.GenericTypeVariable) t).getBounds();
            return bounds.isEmpty() ? "java.lang.Object" : erase(bounds.get(0));
        }
        if (t instanceof JavaType.FullyQualified) {
            return ((JavaType.FullyQualified) t).getFullyQualifiedName();
        }
        return String.valueOf(t);
    }
}

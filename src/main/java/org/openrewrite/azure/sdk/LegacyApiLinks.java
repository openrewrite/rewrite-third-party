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
package org.openrewrite.azure.sdk;

import org.jspecify.annotations.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

import java.util.*;

/**
 * How a source passes legacy types to the repository's other sources: the members it declares with a legacy type in
 * their signature, those of other sources it calls or overrides, and the legacy types it hands to or takes from
 * dependency injection. Two sources that share any of them are migrated together or not at all, since one migrated
 * without the other would pass current types where legacy ones are expected.
 */
final class LegacyApiLinks {

    private static final Set<String> INJECTED = new HashSet<>(Arrays.asList("Autowired", "Inject", "Resource", "Value"));
    private static final Set<String> PRODUCES = new HashSet<>(Arrays.asList("Bean", "Produces"));
    private static final Set<String> COMPONENTS = new HashSet<>(Arrays.asList("Component", "Service", "Repository",
            "Controller", "RestController", "Configuration", "ApplicationScoped", "RequestScoped", "SessionScoped",
            "Singleton", "Stateless", "Stateful", "Named"));

    /**
     * {@code Type#member} of the members declared here whose signature has a legacy type.
     */
    final Set<String> declared = new HashSet<>();

    /**
     * {@code Type#member} of the members of other types used or overridden here whose signature has a legacy type.
     */
    final Set<String> used = new HashSet<>();

    /**
     * The legacy types this source hands to or takes from dependency injection.
     */
    final Set<String> injected = new HashSet<>();

    static LegacyApiLinks of(JavaSourceFile source) {
        LegacyApiLinks links = new LegacyApiLinks();
        new JavaIsoVisitor<LegacyApiLinks>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, LegacyApiLinks l) {
                JavaType.Method type = method.getMethodType();
                if (type != null && mentionsLegacy(type)) {
                    l.declared.add(key(type.getDeclaringType(), type.getName()));
                    J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
                    if (owner != null && owner.getType() != null) {
                        // What it overrides has the same legacy types in its signature
                        for (String supertype : supertypes(owner.getType())) {
                            l.used.add(supertype + "#" + type.getName());
                        }
                    }
                }
                if (type != null && (annotated(method.getLeadingAnnotations(), PRODUCES))) {
                    legacyTypes(type.getReturnType(), l.injected, 0);
                }
                if (type != null && (annotated(method.getLeadingAnnotations(), INJECTED) ||
                                     method.isConstructor() && isComponent(getCursor().firstEnclosing(J.ClassDeclaration.class)))) {
                    for (JavaType parameter : type.getParameterTypes()) {
                        legacyTypes(parameter, l.injected, 0);
                    }
                }
                return super.visitMethodDeclaration(method, l);
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, LegacyApiLinks l) {
                Object parent = getCursor().getParentTreeCursor().getValue();
                if (parent instanceof J.Block && getCursor().getParentTreeCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration) {
                    J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
                    for (J.VariableDeclarations.NamedVariable v : multiVariable.getVariables()) {
                        JavaType.Variable field = v.getVariableType();
                        if (field != null && mentionsLegacy(field.getType(), 0)) {
                            l.declared.add(key(field.getOwner(), field.getName()));
                        }
                    }
                    if (annotated(multiVariable.getLeadingAnnotations(), INJECTED) ||
                        !multiVariable.hasModifier(J.Modifier.Type.Static) && isComponent(owner)) {
                        legacyTypes(multiVariable.getType(), l.injected, 0);
                    }
                }
                return super.visitVariableDeclarations(multiVariable, l);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, LegacyApiLinks l) {
                use(method.getMethodType(), l);
                return super.visitMethodInvocation(method, l);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, LegacyApiLinks l) {
                use(newClass.getConstructorType(), l);
                return super.visitNewClass(newClass, l);
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference memberRef, LegacyApiLinks l) {
                use(memberRef.getMethodType(), l);
                return super.visitMemberReference(memberRef, l);
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, LegacyApiLinks l) {
                JavaType.Variable field = identifier.getFieldType();
                if (field != null && field.getOwner() instanceof JavaType.FullyQualified &&
                    !LegacyAzureTypes.isLegacy(field.getOwner()) && mentionsLegacy(field.getType(), 0)) {
                    l.used.add(key(field.getOwner(), field.getName()));
                }
                return super.visitIdentifier(identifier, l);
            }

            private void use(JavaType.@Nullable Method type, LegacyApiLinks l) {
                if (type != null && !LegacyAzureTypes.isLegacy(type.getDeclaringType()) && mentionsLegacy(type)) {
                    l.used.add(key(type.getDeclaringType(), type.getName()));
                }
            }
        }.visit(source, links);
        links.used.removeAll(links.declared);
        return links;
    }

    /**
     * For each source that is migrated although it shares legacy types with one that is left, a source it is left
     * with, found through any chain of shared members and injected types.
     *
     * @param links per source, how it passes legacy types to the others
     * @param left  the sources left for reasons of their own
     */
    static <S> Map<S, S> leftWith(Map<S, LegacyApiLinks> links, Set<S> left) {
        Map<S, S> parent = new HashMap<>();
        for (S s : links.keySet()) {
            parent.put(s, s);
        }
        Map<String, S> declaring = new HashMap<>();
        Map<String, S> injecting = new HashMap<>();
        for (Map.Entry<S, LegacyApiLinks> e : links.entrySet()) {
            for (String member : e.getValue().declared) {
                S other = declaring.putIfAbsent(member, e.getKey());
                if (other != null) {
                    union(parent, other, e.getKey());
                }
            }
            for (String type : e.getValue().injected) {
                S other = injecting.putIfAbsent(type, e.getKey());
                if (other != null) {
                    union(parent, other, e.getKey());
                }
            }
        }
        for (Map.Entry<S, LegacyApiLinks> e : links.entrySet()) {
            for (String member : e.getValue().used) {
                S other = declaring.get(member);
                if (other != null) {
                    union(parent, other, e.getKey());
                }
            }
        }
        Map<S, S> blockedBy = new HashMap<>();
        for (S s : links.keySet()) {
            if (left.contains(s)) {
                blockedBy.putIfAbsent(find(parent, s), s);
            }
        }
        Map<S, S> leftWith = new HashMap<>();
        for (S s : links.keySet()) {
            S with = blockedBy.get(find(parent, s));
            if (with != null && !left.contains(s)) {
                leftWith.put(s, with);
            }
        }
        return leftWith;
    }

    private static <S> S find(Map<S, S> parent, S s) {
        S root = s;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        while (!parent.get(s).equals(root)) {
            S next = parent.get(s);
            parent.put(s, root);
            s = next;
        }
        return root;
    }

    private static <S> void union(Map<S, S> parent, S a, S b) {
        S ra = find(parent, a);
        S rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    private static boolean annotated(List<J.Annotation> annotations, Set<String> names) {
        for (J.Annotation a : annotations) {
            if (names.contains(a.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isComponent(J.@Nullable ClassDeclaration c) {
        return c != null && annotated(c.getLeadingAnnotations(), COMPONENTS);
    }

    private static String key(@Nullable JavaType owner, String member) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(owner);
        return (fq == null ? String.valueOf(owner) : fq.getFullyQualifiedName().replace('$', '.')) + "#" + member;
    }

    private static Set<String> supertypes(JavaType.FullyQualified type) {
        Set<String> supertypes = new LinkedHashSet<>();
        Deque<JavaType.FullyQualified> next = new ArrayDeque<>();
        if (type.getSupertype() != null) {
            next.add(type.getSupertype());
        }
        next.addAll(type.getInterfaces());
        while (!next.isEmpty() && supertypes.size() < 50) {
            JavaType.FullyQualified t = next.poll();
            String fqn = t.getFullyQualifiedName().replace('$', '.');
            if (fqn.startsWith("java.") || LegacyAzureTypes.isLegacy(t) || !supertypes.add(fqn)) {
                continue;
            }
            if (t.getSupertype() != null) {
                next.add(t.getSupertype());
            }
            next.addAll(t.getInterfaces());
        }
        return supertypes;
    }

    private static boolean mentionsLegacy(JavaType.Method method) {
        if (mentionsLegacy(method.getReturnType(), 0)) {
            return true;
        }
        for (JavaType parameter : method.getParameterTypes()) {
            if (mentionsLegacy(parameter, 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsLegacy(@Nullable JavaType type, int depth) {
        Set<String> found = new HashSet<>();
        legacyTypes(type, found, depth);
        return !found.isEmpty();
    }

    private static void legacyTypes(@Nullable JavaType type, Set<String> found, int depth) {
        if (type == null || depth > 4) {
            return;
        }
        if (type instanceof JavaType.Array) {
            legacyTypes(((JavaType.Array) type).getElemType(), found, depth + 1);
        } else if (type instanceof JavaType.GenericTypeVariable) {
            for (JavaType bound : ((JavaType.GenericTypeVariable) type).getBounds()) {
                legacyTypes(bound, found, depth + 1);
            }
        } else if (type instanceof JavaType.FullyQualified) {
            if (LegacyAzureTypes.isLegacy(type)) {
                found.add(((JavaType.FullyQualified) type).getFullyQualifiedName().replace('$', '.'));
            }
            if (type instanceof JavaType.Parameterized) {
                for (JavaType parameter : ((JavaType.Parameterized) type).getTypeParameters()) {
                    legacyTypes(parameter, found, depth + 1);
                }
            }
        }
    }
}

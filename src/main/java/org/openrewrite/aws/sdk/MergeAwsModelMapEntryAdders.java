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
package org.openrewrite.aws.sdk;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2 models have no {@code addXEntry(key, value)}, and v2-migration leaves it in place, so a v1 chain that builds a map
 * entry by entry does not compile after it.
 */
public class MergeAwsModelMapEntryAdders extends Recipe {

    private static final Pattern ADDER = Pattern.compile("add([A-Z][A-Za-z0-9]*)Entry");
    private static final Pattern V1_MODEL = Pattern.compile("com\\.amazonaws\\.services\\.[a-z0-9]+\\.model\\..+");
    // Map.of takes at most ten entries
    private static final int MAX_ENTRIES = 10;

    @Getter
    final String displayName = "Merge the map entry adders of AWS SDK v1 model chains";

    @Getter
    final String description = "Runs before `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. In a chain that starts from a " +
            "new v1 request or model object, replaces the `addXEntry(key, value)` calls of one map with a single `withX(Map.of(..))`, " +
            "which v2-migration turns into the builder's `x(..)`. Chains that also set the map otherwise are left alone.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>("com.amazonaws.services..model..*", true), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                Object parent = getCursor().getParentTreeCursor().getValue();
                if (parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() == method) {
                    return m;
                }
                J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                if (cu != null && cu.getMarkers().findFirst(JavaVersion.class).map(v -> v.getMajorVersion() < 9).orElse(false)) {
                    return m;
                }
                return merge(m, ctx);
            }

            private J.MethodInvocation merge(J.MethodInvocation outer, ExecutionContext ctx) {
                List<J.MethodInvocation> chain = new ArrayList<>();
                Expression root = outer;
                while (root instanceof J.MethodInvocation) {
                    chain.add(0, (J.MethodInvocation) root);
                    root = ((J.MethodInvocation) root).getSelect();
                }
                if (!(root instanceof J.NewClass) || !isV1Model(root.getType()) ||
                    ((J.NewClass) root).getArguments().stream().anyMatch(a -> !(a instanceof J.Empty))) {
                    return outer;
                }
                Map<String, List<J.MethodInvocation>> adders = new LinkedHashMap<>();
                for (J.MethodInvocation call : chain) {
                    String map = mapOf(call);
                    if (map != null) {
                        adders.computeIfAbsent(map, k -> new ArrayList<>()).add(call);
                    }
                }
                adders.entrySet().removeIf(e -> e.getValue().size() > MAX_ENTRIES || setsOtherwise(chain, e.getKey()) ||
                                                withMethod(e.getValue().get(0), e.getKey()) == null);
                if (adders.isEmpty()) {
                    return outer;
                }
                Expression current = root;
                for (J.MethodInvocation call : chain) {
                    String map = mapOf(call);
                    List<J.MethodInvocation> group = map == null ? null : adders.get(map);
                    if (group == null) {
                        current = call.withSelect(current);
                    } else if (group.get(0) == call) {
                        current = withMap(call.withSelect(current), map, group, ctx);
                    }
                }
                maybeAddImport("java.util.Map");
                return (J.MethodInvocation) current;
            }

            private J.MethodInvocation withMap(J.MethodInvocation first, String map, List<J.MethodInvocation> group, ExecutionContext ctx) {
                StringJoiner placeholders = new StringJoiner(", ");
                List<Object> args = new ArrayList<>();
                for (J.MethodInvocation adder : group) {
                    placeholders.add("#{any()}").add("#{any()}");
                    args.add(adder.getArguments().get(0));
                    args.add(adder.getArguments().get(1));
                }
                J.MethodInvocation mapOf = JavaTemplate.builder("Map.of(" + placeholders + ")")
                        .imports("java.util.Map")
                        .build()
                        .apply(new Cursor(getCursor().getParentOrThrow(), first), first.getCoordinates().replace(), args.toArray());
                JavaType.Method with = withMethod(first, map);
                return first.withName(first.getName().withSimpleName("with" + map).withType(with))
                        .withMethodType(with)
                        .withArguments(Collections.singletonList(mapOf.withPrefix(Space.EMPTY)));
            }
        });
    }

    private static @Nullable String mapOf(J.MethodInvocation call) {
        Matcher adder = ADDER.matcher(call.getSimpleName());
        if (!adder.matches() || call.getArguments().size() != 2 || call.getMethodType() == null ||
            !isV1Model(call.getMethodType().getDeclaringType())) {
            return null;
        }
        return adder.group(1);
    }

    private static boolean setsOtherwise(List<J.MethodInvocation> chain, String map) {
        for (J.MethodInvocation call : chain) {
            String name = call.getSimpleName();
            if (name.equals("with" + map) || name.equals("set" + map) || name.equals("clear" + map + "Entries")) {
                return true;
            }
        }
        return false;
    }

    private static JavaType.@Nullable Method withMethod(J.MethodInvocation adder, String map) {
        JavaType.Method type = adder.getMethodType();
        if (type == null) {
            return null;
        }
        for (JavaType.Method candidate : type.getDeclaringType().getMethods()) {
            if (candidate.getName().equals("with" + map) && candidate.getParameterTypes().size() == 1 &&
                TypeUtils.isAssignableTo("java.util.Map", candidate.getParameterTypes().get(0))) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isV1Model(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq != null && V1_MODEL.matcher(fq.getFullyQualifiedName()).matches();
    }
}

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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.azure.sdk.search.FindAzureSdkLegacyRemnants;
import org.openrewrite.java.internal.TypesInUse;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What {@code org.openrewrite.azure.sdk.MigrateAzureSdkLegacyToCurrent} makes of a source, for its recipes to agree on
 * which sources they migrate and for recipes that need to know the result before it runs.
 * <p>
 * The migration changes a source entirely or not at all. A source with anything its rewrites can't migrate, such as a
 * call that has no counterpart, is left as it is, since migrated around that call it would mix legacy and current
 * types that don't convert into one another. Its module keeps the legacy artifacts it needs, so it compiles as before,
 * and FindAzureSdkLegacyRemnants marks what stops it from being migrated. Once a person has migrated those by hand,
 * the next run migrates the rest. A source that shares legacy types with one that is left, through a member or
 * dependency injection, is left with it.
 */
public final class LegacyAzureMigration {

    private static final String BLOCKERS = LegacyAzureMigration.class.getName() + ".blockers";
    private static final String SIMULATING = LegacyAzureMigration.class.getName() + ".simulating";
    private static final String LEFT_WITH = LegacyAzureMigration.class.getName() + ".leftWith";
    private static final String UNBUILT = LegacyAzureMigration.class.getName() + ".unbuilt";

    private LegacyAzureMigration() {
    }

    private static List<Recipe> callRecipes() {
        return Arrays.asList(
                new MigrateAzureStorageBlob(),
                new MigrateAdal4jToMsal4j(),
                new MigrateAzureEventHubs(),
                new MigrateAzureServiceBus()
        );
    }

    /**
     * Whether the migration leaves a source as it is: for one of the reasons
     * {@link LegacyAzureTypes#isLeftAsItIs(Tree)} gives, since its rewrites would leave something of the legacy SDK
     * in it, or with another source that is left.
     */
    public static boolean isLeftAsItIs(Tree source, ExecutionContext ctx) {
        if (!(source instanceof JavaSourceFile)) {
            return LegacyAzureTypes.isLeftAsItIs(source);
        }
        if (simulating(ctx).contains(source.getId())) {
            return false;
        }
        return LegacyAzureTypes.isLeftAsItIs(source) || !blockers((JavaSourceFile) source, ctx).isEmpty() ||
               leftWith(source, ctx) != null || isUnbuilt(source, ctx);
    }

    /**
     * Whether a source is left since no build the recipe can edit compiles it, such as a Gradle subproject whose build
     * file has a name of its own that the repository's model doesn't attribute to it, as
     * {@code MigrateAzureSdkDependencies} found when it scanned the repository.
     */
    public static boolean isUnbuilt(Tree source, ExecutionContext ctx) {
        Set<UUID> unbuilt = ctx.getMessage(UNBUILT);
        return unbuilt != null && unbuilt.contains(source.getId());
    }

    static void leaveUnbuilt(Set<UUID> unbuilt, ExecutionContext ctx) {
        ctx.computeMessageIfAbsent(UNBUILT, k -> ConcurrentHashMap.<UUID>newKeySet()).addAll(unbuilt);
    }

    /**
     * The path of a source that this one is left with, as {@code MigrateAzureSdkDependencies} found when it scanned
     * the repository, or null when it is not, or when no scan ran.
     */
    public static @Nullable String leftWith(Tree source, ExecutionContext ctx) {
        Map<UUID, String> leftWith = ctx.getMessage(LEFT_WITH);
        return leftWith == null ? null : leftWith.get(source.getId());
    }

    /**
     * Records the sources each source is left with. A source stays left in the run's later cycles, when the sources
     * that were migrated no longer show what linked them.
     */
    static void leaveWith(Map<UUID, String> leftWith, ExecutionContext ctx) {
        Map<UUID, String> known = ctx.computeMessageIfAbsent(LEFT_WITH, k -> new ConcurrentHashMap<>());
        for (Map.Entry<UUID, String> e : leftWith.entrySet()) {
            known.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    /**
     * The ids of the trees in a source that the migration's rewrites can't migrate, as the source was when the run
     * first asked. A rewrite keeps the id of a tree it changes, so later in the run they still name the same trees.
     */
    public static Set<UUID> blockers(JavaSourceFile source, ExecutionContext ctx) {
        Map<UUID, Set<UUID>> known = ctx.computeMessageIfAbsent(BLOCKERS, k -> new ConcurrentHashMap<>());
        Set<UUID> blockers = known.get(source.getId());
        if (blockers == null) {
            blockers = usesLegacy(source) ? simulateBlockers(source, ctx) : Collections.emptySet();
            known.put(source.getId(), blockers);
        }
        return blockers;
    }

    private static Set<UUID> simulateBlockers(JavaSourceFile source, ExecutionContext ctx) {
        Set<UUID> simulating = simulating(ctx);
        simulating.add(source.getId());
        try {
            return FindAzureSdkLegacyRemnants.remnants(run(source, callRecipes(), ctx), ctx);
        } finally {
            simulating.remove(source.getId());
        }
    }

    private static Set<UUID> simulating(ExecutionContext ctx) {
        return ctx.computeMessageIfAbsent(SIMULATING, k -> ConcurrentHashMap.newKeySet());
    }

    private static boolean usesLegacy(JavaSourceFile source) {
        TypesInUse typesInUse = source.getTypesInUse();
        for (JavaType type : typesInUse.getTypesInUse()) {
            if (LegacyAzureTypes.isLegacy(type) || LegacyAzureTypes.isBuiltOnLegacy(type)) {
                return true;
            }
        }
        for (JavaType.Method method : typesInUse.getUsedMethods()) {
            if (LegacyAzureTypes.isLegacy(method.getDeclaringType()) || LegacyAzureTypes.isBuiltOnLegacy(method.getDeclaringType())) {
                return true;
            }
        }
        for (JavaType.Variable variable : typesInUse.getVariables()) {
            if (LegacyAzureTypes.isLegacy(variable.getOwner())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The source as the Java recipes of the migration leave it, each visiting only what a recipe run would let it.
     */
    static Tree simulate(Tree source, ExecutionContext ctx) {
        if (isLeftAsItIs(source, ctx)) {
            return source;
        }
        List<Recipe> recipes = new ArrayList<>(callRecipes());
        recipes.add(new ChangeLegacyAzureTypes());
        return run(source, recipes, ctx);
    }

    private static Tree run(Tree source, List<Recipe> recipes, ExecutionContext ctx) {
        Tree t = source;
        for (Recipe recipe : recipes) {
            TreeVisitor<?, ExecutionContext> visitor = recipe.getVisitor();
            if (t instanceof SourceFile && !visitor.isAcceptable((SourceFile) t, ctx)) {
                continue;
            }
            Tree after = visitor.visit(t, ctx);
            if (after != null) {
                t = after;
            }
        }
        return t;
    }
}

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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.java.dependencies.ChangeDependency;
import org.openrewrite.java.dependencies.UpgradeDependencyVersion;
import org.openrewrite.java.format.NormalizeLineBreaksVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.AddManagedDependencyVisitor;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.mongodb.table.MongoDriverMigrationDecisions;
import org.openrewrite.style.GeneralFormatStyle;
import org.openrewrite.text.PlainText;
import org.openrewrite.xml.tree.Xml;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moves a repository from MongoDB Java driver 3 to driver 5: the code of each module from the legacy API to the
 * driver 5 API where every use has a translation, the uses of API that driver 5 removed to their replacements, and the
 * driver dependency to {@code mongodb-driver-sync} 5, or to {@code mongodb-driver-legacy} 5 where some module keeps
 * the legacy API.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateMongoJavaDriver3To5 extends ScanningRecipe<MigrateMongoJavaDriver3To5.Accumulator> {

    private static final String VERSION = "5.x";

    @Getter
    final String displayName = "Migrate from MongoDB Java driver 3 to 5";

    @Getter
    final String description = "Moves the Java code of each module from the legacy API (`com.mongodb.MongoClient`, `DB`, " +
            "`DBCollection`, `DBCursor`, `MongoClientURI`, `MongoClientOptions`) to the driver 5 API " +
            "(`com.mongodb.client.MongoClient`, `MongoDatabase`, `MongoCollection<DBObject>`, `FindIterable<DBObject>` or " +
            "`MongoCursor<DBObject>`, `ConnectionString`, `MongoClientSettings`), keeping documents as `DBObject`s, which driver 5 " +
            "still encodes and decodes. A module converts only when every use of the legacy API in it, and in the modules it " +
            "shares legacy-typed declarations with, has a translation, nothing outside the repository takes or returns its " +
            "legacy types, and no framework injects them; otherwise it keeps the legacy API. Uses of API that driver 5 removed, " +
            "such as `WriteConcern.SAFE`, `com.mongodb.util.JSON`, `Mongo`, `MongoCollection.count` and " +
            "`MongoIterable.forEach(Block)`, move to their replacements, and the driver dependency moves to " +
            "`mongodb-driver-sync` 5, or `mongodb-driver-legacy` 5 when a module keeps the legacy API. The driver stays " +
            "where it is when a library bound to driver 3, such as Spring Data MongoDB before 4.3, Morphia or Jongo, is on the " +
            "classpath, or when a use of removed API has no replacement; `FindRemovedMongoApis` marks those. Classes that " +
            "implement a driver type get the abstract methods driver 5 requires of it that they lack, such as " +
            "`MongoCursor.available()`: those the driver never calls on the class throw `UnsupportedOperationException`, " +
            "`CodecRegistry.get(Class, CodecRegistry)` does what driver 3 did, and where nothing can stand in, as for a " +
            "`ByteBuf`, or a method returns nothing where driver 5's returns a result, the driver stays where it is. A " +
            "method that overrides one driver 5 removed, such as `MongoIterable.forEach(Block)`, loses its `@Override`. " +
            "Calls whose result driver 5 changed keep the old one: " +
            "`toJson()`, and the `toString()` of `BasicDBObject` and `BsonDocument`, ask for the strict extended JSON that " +
            "driver 3 wrote; where the code turns a document into a string otherwise, by string concatenation, a logger or " +
            "the `toString()` of a `DBObject`, driver 5 writes relaxed JSON, and `MongoDriverMigrationDecisions` lists the " +
            "place. The `toString()` of `MongoClientURI` and `ConnectionString`, which driver 5 redacts, becomes " +
            "`getURI()` or `getConnectionString()`. A `module-info.java` requires the modules of driver 5; a repository with " +
            "a Java module stays on driver 3 when a module would keep the legacy API, which no Java module can read in " +
            "driver 5. Clients of repositories that use `java.util.UUID` keep the `JAVA_LEGACY` UUID representation that " +
            "driver 3 used by default. One change cannot be kept: driver 3 refused with an `IllegalArgumentException` a field " +
            "name that contains `.` or starts with `$` in a document it inserted or replaced, and driver 5 leaves that to the " +
            "server, except for a `$` at the top level of a replacement, so code that relied on the refusal needs a check of " +
            "its own. `MongoDriverMigrationDecisions` lists each " +
            "module's outcome and the reasons for it.";

    transient MongoDriverMigrationDecisions decisions = new MongoDriverMigrationDecisions(this);

    public static class Accumulator {
        final LegacyUsage usage = new LegacyUsage();
        final Set<String> reported = ConcurrentHashMap.newKeySet();
        final List<Delegate> toSync = new ArrayList<>();
        final List<Delegate> toLegacy = new ArrayList<>();
        final List<Delegate> upgrades = new ArrayList<>();

        List<Delegate> all() {
            List<Delegate> all = new ArrayList<>(toSync);
            all.addAll(toLegacy);
            all.addAll(upgrades);
            return all;
        }
    }

    static final class Delegate {
        final ScanningRecipe<Object> recipe;
        final Object acc;

        @SuppressWarnings("unchecked")
        Delegate(ScanningRecipe<?> recipe, ExecutionContext ctx) {
            this.recipe = (ScanningRecipe<Object>) recipe;
            this.acc = this.recipe.getInitialValue(ctx);
        }

        void scan(Tree tree, ExecutionContext ctx) {
            recipe.getScanner(acc).visit(tree, ctx);
        }

        @Nullable Tree edit(@Nullable Tree tree, ExecutionContext ctx) {
            return recipe.getVisitor(acc).visit(tree, ctx);
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        Accumulator acc = new Accumulator();
        for (String artifact : Arrays.asList("mongo-java-driver", "mongodb-driver")) {
            acc.toSync.add(new Delegate(new ChangeDependency("org.mongodb", artifact, "org.mongodb", "mongodb-driver-sync",
                    VERSION, null, true, true), ctx));
            acc.toLegacy.add(new Delegate(new ChangeDependency("org.mongodb", artifact, "org.mongodb", "mongodb-driver-legacy",
                    VERSION, null, true, true), ctx));
        }
        for (String artifact : Arrays.asList("bson", "mongodb-driver-core", "mongodb-driver-sync", "mongodb-driver-legacy")) {
            acc.upgrades.add(new Delegate(new UpgradeDependencyVersion("org.mongodb", artifact, VERSION, null, true, null), ctx));
        }
        return acc;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        LegacyScanner scanner = new LegacyScanner(acc.usage);
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    scanner.visit(tree, ctx);
                    if (isBuildFile((SourceFile) tree)) {
                        for (Delegate d : acc.all()) {
                            d.scan(tree, ctx);
                        }
                    }
                }
                return tree;
            }
        };
    }

    private static boolean isBuildFile(SourceFile sf) {
        return sf.getMarkers().findFirst(MavenResolutionResult.class).isPresent() ||
               sf.getMarkers().findFirst(GradleProject.class).isPresent() ||
               sf.getSourcePath().toString().endsWith(".toml") ||
               sf.getSourcePath().toString().endsWith(".gradle") ||
               sf.getSourcePath().toString().endsWith(".gradle.kts") ||
               sf.getSourcePath().toString().endsWith(".properties") && sf.getSourcePath().getFileName().toString().startsWith("gradle");
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                LegacyUsage usage = acc.usage;
                if (usage.modulesUsingLegacyApi.isEmpty() && !usage.declaresDriver) {
                    return tree;
                }
                reportDriver(acc, ctx);
                boolean upgrade = usage.upgradesDriver();
                if (isBuildFile((SourceFile) tree)) {
                    if (!upgrade) {
                        return tree;
                    }
                    Tree t = tree;
                    for (Delegate d : usage.keepsLegacyApi() ? acc.toLegacy : acc.toSync) {
                        t = d.edit(t, ctx);
                    }
                    for (Delegate d : acc.upgrades) {
                        t = d.edit(t, ctx);
                    }
                    if (t instanceof Xml.Document) {
                        t = pinDriverParts((Xml.Document) t, ctx);
                    }
                    return t;
                }
                if (tree instanceof PlainText && ModuleRequires.isDescriptor((SourceFile) tree)) {
                    // The Java parser leaves module descriptors as text
                    return upgrade ? ModuleRequires.requireDriver5((PlainText) tree) : tree;
                }
                if (!(tree instanceof J.CompilationUnit)) {
                    return tree;
                }
                J edited = editJava((J.CompilationUnit) tree, acc, upgrade, ctx);
                if (upgrade) {
                    // Imports that compiled against driver 3 while nothing used them, and that driver 5 cannot resolve
                    edited = new VanishedImports(!usage.keepsLegacyApi()).visitNonNull(edited, ctx);
                    // Classes implementing driver types that driver 5 adds abstract methods to
                    ImplementAddedMethods implement = new ImplementAddedMethods();
                    edited = implement.visitNonNull(edited, ctx);
                    for (String added : implement.added) {
                        decisions.insertRow(ctx, new MongoDriverMigrationDecisions.Row(MongoClasspath.moduleKey((SourceFile) tree),
                                "Added methods driver 5 requires", ((SourceFile) tree).getSourcePath().toString(), added));
                    }
                    // Documents turned into strings where the recipe cannot ask for the strict JSON driver 3 wrote
                    ImplicitJson json = new ImplicitJson();
                    json.visit(edited, ctx);
                    for (String found : new LinkedHashSet<>(json.found)) {
                        decisions.insertRow(ctx, new MongoDriverMigrationDecisions.Row(MongoClasspath.moduleKey((SourceFile) tree),
                                "Relaxed JSON where driver 3 wrote strict", ((SourceFile) tree).getSourcePath().toString(), found));
                    }
                }
                return keepLineBreaks((J.CompilationUnit) tree, edited, ctx);
            }
        };
    }

    /**
     * The imports that the edits add take the line breaks of the style detected for the whole repository, which a file
     * of its own can differ from, so a file whose line breaks are all of one kind keeps that kind.
     */
    private static J keepLineBreaks(J.CompilationUnit before, J after, ExecutionContext ctx) {
        if (after == before) {
            return after;
        }
        String text = before.printAll();
        int breaks = 0;
        int crlf = 0;
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            breaks++;
            if (i > 0 && text.charAt(i - 1) == '\r') {
                crlf++;
            }
        }
        if (breaks == 0 || (crlf != 0 && crlf != breaks)) {
            return after;
        }
        return new NormalizeLineBreaksVisitor<ExecutionContext>(new GeneralFormatStyle(crlf == breaks), null)
                .visitNonNull(after, ctx);
    }

    /**
     * A parent or an imported BOM that manages the parts of the driver, as Spring Boot's does, holds them at its own
     * driver 3 version under the driver 5 artifact that depends on them, so they are pinned to the driver's version.
     */
    private static Xml.Document pinDriverParts(Xml.Document pom, ExecutionContext ctx) {
        MavenResolutionResult mrr = pom.getMarkers().findFirst(MavenResolutionResult.class).orElse(null);
        if (mrr == null) {
            return pom;
        }
        String driver = null;
        for (String artifact : Arrays.asList("mongodb-driver-sync", "mongodb-driver-legacy")) {
            for (ResolvedDependency d : mrr.findDependencies("org.mongodb", artifact, null)) {
                if (!d.isTransitive() && MongoClasspath.isAtLeast(d.getVersion(), "5.0")) {
                    driver = d.getVersion();
                }
            }
        }
        if (driver == null) {
            return pom;
        }
        Xml.Document d = pom;
        for (String part : Arrays.asList("bson", "mongodb-driver-core", "mongodb-driver-sync")) {
            for (ResolvedDependency r : mrr.findDependencies("org.mongodb", part, null)) {
                if (r.isTransitive() && !MongoClasspath.isAtLeast(r.getVersion(), "5.0")) {
                    d = (Xml.Document) new AddManagedDependencyVisitor("org.mongodb", part, driver, null, null, null,
                            "Matches the MongoDB driver, where a parent or BOM manages an older version").visitNonNull(d, ctx);
                    break;
                }
            }
        }
        return d;
    }

    private J editJava(J.CompilationUnit cu, Accumulator acc, boolean upgrade, ExecutionContext ctx) {
        LegacyUsage usage = acc.usage;
        J.CompilationUnit result = upgrade ? RemovedApiFixes.apply(cu, ctx) : cu;
        String module = MongoClasspath.moduleKey(cu);
        if (!usage.modulesUsingLegacyApi.contains(module)) {
            return result;
        }
        reportModule(acc, module, ctx);
        if (!usage.converts(module)) {
            if (upgrade && usage.usesUuid) {
                return new LegacyUuidRepresentation().visitNonNull(result, ctx);
            }
            return result;
        }
        if (!LegacyScanner.usesLegacyApi(result)) {
            return result;
        }
        LegacyApiConverter converter = new LegacyApiConverter(false, usage.usesUuid);
        J converted = converter.visitNonNull(result, ctx);
        if (!converter.unsupported.isEmpty()) {
            // The scan found nothing untranslatable here, so this is a gap in the recipe: say so, and leave the file
            for (String reason : new LinkedHashSet<>(converter.unsupported)) {
                decisions.insertRow(ctx, new MongoDriverMigrationDecisions.Row(module, "Conversion failed",
                        cu.getSourcePath().toString(), reason));
            }
            return result;
        }
        return new MergeSettingsBlocks().visitNonNull(converted, ctx);
    }

    private void reportDriver(Accumulator acc, ExecutionContext ctx) {
        if (!acc.reported.add("\u0000driver")) {
            return;
        }
        LegacyUsage usage = acc.usage;
        if (usage.upgradesDriver()) {
            decisions.insertRow(ctx, new MongoDriverMigrationDecisions.Row("", "Driver moved to 5", "",
                    "The driver dependency moves to driver 5"));
            return;
        }
        String why = usage.whyNotUpgraded();
        if (usage.boundLibraries.isEmpty() && !usage.removedApiUses.isEmpty()) {
            for (LegacyUsage.Blocker b : usage.removedApiUses) {
                decisions.insertRow(ctx, new MongoDriverMigrationDecisions.Row(b.module, "Driver not moved to 5", b.sourcePath,
                        b.clause()));
            }
        } else {
            decisions.insertRow(ctx, new MongoDriverMigrationDecisions.Row("", "Driver not moved to 5", "",
                    why == null ? "" : "The repository " + why));
        }
    }

    private void reportModule(Accumulator acc, String module, ExecutionContext ctx) {
        if (!acc.reported.add(module)) {
            return;
        }
        List<String> reasons = acc.usage.blockedModules().get(module);
        if (reasons == null) {
            decisions.insertRow(ctx, new MongoDriverMigrationDecisions.Row(module, "Converted to the driver 5 API", "",
                    "Every use of the legacy API has a translation"));
            return;
        }
        for (String reason : new LinkedHashSet<>(reasons)) {
            int colon = reason.indexOf(": ");
            boolean hasPath = colon > 0 && !reason.substring(0, colon).contains(" ");
            decisions.insertRow(ctx, new MongoDriverMigrationDecisions.Row(module, "Kept the legacy API",
                    hasPath ? reason.substring(0, colon) : "", hasPath ? reason.substring(colon + 2) : reason));
        }
    }
}

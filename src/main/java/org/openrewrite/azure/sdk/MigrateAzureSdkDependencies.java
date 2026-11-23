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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.gradle.AddDependencyVisitor;
import org.openrewrite.gradle.RemoveDependency;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.maven.AddManagedDependencyVisitor;
import org.openrewrite.maven.MavenDownloadingException;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.MavenSettings;
import org.openrewrite.maven.RemoveRedundantDependencyVersions;
import org.openrewrite.maven.internal.MavenPomDownloader;
import org.openrewrite.maven.tree.Dependency;
import org.openrewrite.maven.tree.GroupArtifact;
import org.openrewrite.maven.tree.MavenMetadata;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.semver.Semver;
import org.openrewrite.semver.VersionComparator;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides each module's dependencies from what the migration will leave of its code. The scanner sees the code before
 * the migration, so it runs the migration on each legacy source in memory: the current Azure artifacts the result uses
 * are added, and a legacy artifact the module used and the result no longer uses is removed.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAzureSdkDependencies extends ScanningRecipe<MigrateAzureSdkDependencies.Accumulator> {

    /**
     * Package prefix of the current SDK to the artifact that provides it, and the release line to add, most specific
     * first. Packages that only arrive transitively, such as {@code com.azure.core}, are absent.
     */
    private static final String[][] CURRENT = {
            {"com.azure.storage.blob.", "com.azure:azure-storage-blob", "12.x"},
            {"com.azure.storage.queue.", "com.azure:azure-storage-queue", "12.x"},
            {"com.azure.storage.file.share.", "com.azure:azure-storage-file-share", "12.x"},
            {"com.azure.data.tables.", "com.azure:azure-data-tables", "12.x"},
            {"com.azure.identity.", "com.azure:azure-identity", "1.x"},
            {"com.azure.messaging.eventhubs.checkpointstore.blob.", "com.azure:azure-messaging-eventhubs-checkpointstore-blob", "1.x"},
            {"com.azure.messaging.eventhubs.", "com.azure:azure-messaging-eventhubs", "5.x"},
            {"com.azure.messaging.servicebus.", "com.azure:azure-messaging-servicebus", "7.x"},
            {"com.azure.security.keyvault.secrets.", "com.azure:azure-security-keyvault-secrets", "4.x"},
            {"com.azure.security.keyvault.keys.", "com.azure:azure-security-keyvault-keys", "4.x"},
            {"com.azure.resourcemanager.", "com.azure.resourcemanager:azure-resourcemanager", "2.x"},
            {"com.azure.cosmos.", "com.azure:azure-cosmos", "4.x"},
            {"com.microsoft.aad.msal4j.", "com.microsoft.azure:msal4j", "1.x"},
    };

    /**
     * Package prefix of the legacy SDK to the artifacts that provide it.
     */
    private static final String[][] LEGACY = {
            {"com.microsoft.azure.storage.", "com.microsoft.azure:azure-storage"},
            {"com.microsoft.aad.adal4j.", "com.microsoft.azure:adal4j"},
            {"com.microsoft.azure.eventprocessorhost.", "com.microsoft.azure:azure-eventhubs-eph"},
            {"com.microsoft.azure.eventhubs.", "com.microsoft.azure:azure-eventhubs"},
            {"com.microsoft.azure.servicebus.", "com.microsoft.azure:azure-servicebus"},
            {"com.microsoft.azure.keyvault.", "com.microsoft.azure:azure-keyvault"},
            {"com.microsoft.azure.documentdb.", "com.microsoft.azure:azure-documentdb"},
    };

    @Getter
    final String displayName = "Swap legacy Azure SDK dependencies for current ones";

    @Getter
    final String description = "Adds the current Azure SDK and MSAL4J artifacts each module's code uses once migrated, " +
                               "in the test scope when only tests use them, and removes the legacy `com.microsoft.azure` " +
                               "artifacts and ADAL4J the module used and no longer does. A legacy artifact that still has " +
                               "uses the migration could not rewrite stays.";

    public static class Accumulator {
        final Set<Path> modules = ConcurrentHashMap.newKeySet();
        /**
         * Per source: artifact to whether only test code needs it.
         */
        final Map<Path, Map<String, Boolean>> needed = new ConcurrentHashMap<>();
        final Map<Path, Set<String>> usedLegacy = new ConcurrentHashMap<>();
        final Map<Path, Set<String>> remainingLegacy = new ConcurrentHashMap<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile s = (SourceFile) tree;
                if (isMavenPom(s) || isGradleBuild(s)) {
                    acc.modules.add(dir(s.getSourcePath()));
                } else if (s instanceof J.CompilationUnit) {
                    scan((J.CompilationUnit) s, acc, ctx);
                }
                return tree;
            }
        };
    }

    private static void scan(J.CompilationUnit cu, Accumulator acc, ExecutionContext ctx) {
        Set<String> before = legacyArtifacts(cu);
        if (before.isEmpty()) {
            return;
        }
        acc.usedLegacy.put(cu.getSourcePath(), before);
        Tree migrated = LegacyAzureMigration.simulate(cu, ctx);
        if (!(migrated instanceof JavaSourceFile)) {
            return;
        }
        JavaSourceFile after = (JavaSourceFile) migrated;
        acc.remainingLegacy.put(cu.getSourcePath(), legacyArtifacts(after));
        Map<String, Boolean> needed = new HashMap<>();
        boolean test = isTest(cu);
        for (String fqn : typesInUse(after)) {
            for (String[] current : CURRENT) {
                if (fqn.startsWith(current[0])) {
                    needed.put(current[1] + ":" + current[2], test);
                    break;
                }
            }
        }
        acc.needed.put(cu.getSourcePath(), needed);
    }

    private static Set<String> legacyArtifacts(JavaSourceFile s) {
        Set<String> artifacts = new HashSet<>();
        for (String fqn : typesInUse(s)) {
            for (String[] legacy : LEGACY) {
                if (fqn.startsWith(legacy[0])) {
                    artifacts.add(legacy[1]);
                    break;
                }
            }
        }
        return artifacts;
    }

    private static Set<String> typesInUse(JavaSourceFile s) {
        Set<String> fqns = new HashSet<>();
        for (JavaType type : s.getTypesInUse().getTypesInUse()) {
            if (type instanceof JavaType.FullyQualified) {
                fqns.add(((JavaType.FullyQualified) type).getFullyQualifiedName());
            }
        }
        for (JavaType.Method m : s.getTypesInUse().getUsedMethods()) {
            fqns.add(m.getDeclaringType().getFullyQualifiedName());
        }
        return fqns;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile) || acc.usedLegacy.isEmpty()) {
                    return tree;
                }
                SourceFile s = (SourceFile) tree;
                if (isMavenPom(s)) {
                    return editPom((Xml.Document) s, acc, ctx);
                } else if (isGradleBuild(s) && s instanceof JavaSourceFile) {
                    return editGradle((JavaSourceFile) s, acc, ctx);
                }
                return tree;
            }
        };
    }

    private static Tree editPom(Xml.Document pom, Accumulator acc, ExecutionContext ctx) {
        Module module = module(dir(pom.getSourcePath()), acc);
        if (module.isEmpty()) {
            return pom;
        }
        ResolvedPom resolved = pom.getMarkers().findFirst(MavenResolutionResult.class).get().getPom();
        Set<String> declared = new HashSet<>();
        for (Dependency d : resolved.getRequested().getDependencies()) {
            declared.add(resolved.getValue(d.getGroupId()) + ":" + resolved.getValue(d.getArtifactId()));
        }
        Xml.Document p = pom;
        Set<String> azure = new TreeSet<>();
        for (String ga : declared) {
            if (ga.startsWith("com.azure:")) {
                azure.add(ga);
            }
        }
        for (String needed : module.needed.keySet()) {
            String[] gav = needed.split(":");
            if (gav[0].equals("com.azure")) {
                azure.add(gav[0] + ":" + gav[1]);
            }
        }
        boolean aligned = false;
        if (azure.size() > 1 && resolved.getManagedVersion("com.azure", "azure-core", null, null) == null) {
            // Maven takes the azure-core of the first com.azure artifact declared, which can be older than another
            // needs, so the Azure SDK BOM picks one azure-core and the artifact versions released with it
            String bom = latest("com.azure", "azure-sdk-bom", "1.x", p.getMarkers().findFirst(MavenResolutionResult.class).get(), ctx);
            if (bom != null) {
                p = (Xml.Document) new AddManagedDependencyVisitor("com.azure", "azure-sdk-bom", bom, "import", "pom", null, null)
                        .visitNonNull(p, ctx);
                resolved = p.getMarkers().findFirst(MavenResolutionResult.class).get().getPom();
                aligned = true;
            }
        }
        for (Map.Entry<String, Boolean> needed : module.needed.entrySet()) {
            String[] gav = needed.getKey().split(":");
            if (declared.contains(gav[0] + ":" + gav[1])) {
                continue;
            }
            String managed = resolved.getManagedVersion(gav[0], gav[1], null, null);
            p = (Xml.Document) new org.openrewrite.maven.AddDependencyVisitor(gav[0], gav[1], managed != null ? managed : gav[2], null,
                    needed.getValue() ? "test" : null, true, null, null, null, null).visitNonNull(p, ctx);
        }
        if (aligned) {
            // The artifacts the module already declared move to the BOM's versions too, never to older ones
            p = (Xml.Document) new RemoveRedundantDependencyVersions("com.azure", "*",
                    RemoveRedundantDependencyVersions.Comparator.GTE, null).getVisitor().visitNonNull(p, ctx);
        }
        for (String legacy : module.removable()) {
            String[] ga = legacy.split(":");
            p = (Xml.Document) new org.openrewrite.maven.RemoveDependency(ga[0], ga[1], null).getVisitor().visitNonNull(p, ctx);
        }
        return p;
    }

    /**
     * The newest release of an artifact in a version range, from the repositories the module resolves from.
     */
    private static @Nullable String latest(String groupId, String artifactId, String range, MavenResolutionResult mrr, ExecutionContext ctx) {
        VersionComparator comparator = Semver.validate(range, null).getValue();
        if (comparator == null) {
            return null;
        }
        try {
            MavenSettings settings = MavenExecutionContextView.view(ctx).effectiveSettings(mrr);
            MavenMetadata metadata = new MavenPomDownloader(mrr.getProjectPoms(), ctx, settings, mrr.getActiveProfiles())
                    .downloadMetadata(new GroupArtifact(groupId, artifactId), null, mrr.getPom().getRepositories());
            String latest = null;
            for (String version : metadata.getVersioning().getVersions()) {
                if (comparator.isValid(null, version) && (latest == null || comparator.compare(null, version, latest) > 0)) {
                    latest = version;
                }
            }
            return latest;
        } catch (MavenDownloadingException e) {
            return null;
        }
    }

    private static Tree editGradle(JavaSourceFile build, Accumulator acc, ExecutionContext ctx) {
        Module module = module(dir(build.getSourcePath()), acc);
        if (module.isEmpty()) {
            return build;
        }
        GradleProject gp = build.getMarkers().findFirst(GradleProject.class).get();
        Set<String> declared = new HashSet<>();
        for (GradleDependencyConfiguration c : gp.getConfigurations()) {
            for (Dependency d : c.getRequested()) {
                declared.add(d.getGroupId() + ":" + d.getArtifactId());
            }
        }
        JavaSourceFile g = build;
        for (Map.Entry<String, Boolean> needed : module.needed.entrySet()) {
            String[] gav = needed.getKey().split(":");
            String configuration = needed.getValue() ? "testImplementation" : "implementation";
            if (declared.contains(gav[0] + ":" + gav[1]) || gp.getConfiguration(configuration) == null) {
                continue;
            }
            g = (JavaSourceFile) new AddDependencyVisitor(configuration, gav[0], gav[1], gav[2], null, null, null, null,
                    MigrateAzureSdkDependencies::isTopLevelDependencies, null).visitNonNull(g, ctx);
        }
        for (String legacy : module.removable()) {
            String[] ga = legacy.split(":");
            g = (JavaSourceFile) new RemoveDependency(ga[0], ga[1], null).getVisitor().visitNonNull(g, ctx);
        }
        return g;
    }

    /**
     * Without it {@code AddDependencyVisitor} also adds to {@code buildscript { dependencies {} }}.
     */
    private static boolean isTopLevelDependencies(Cursor cursor) {
        if (cursor.getValue() instanceof J.Block) {
            return cursor.getParentOrThrow().getValue() instanceof JavaSourceFile;
        }
        Cursor parent = cursor.getParentTreeCursor();
        return parent.getValue() instanceof JavaSourceFile;
    }

    private static final class Module {
        final Map<String, Boolean> needed = new TreeMap<>();
        final Set<String> used = new TreeSet<>();
        final Set<String> remaining = new TreeSet<>();

        boolean isEmpty() {
            return used.isEmpty();
        }

        Set<String> removable() {
            Set<String> removable = new TreeSet<>(used);
            removable.removeAll(remaining);
            return removable;
        }
    }

    /**
     * The sources of the module in {@code dir}, without those of the modules nested in it.
     */
    private static Module module(Path dir, Accumulator acc) {
        Module module = new Module();
        for (Map.Entry<Path, Set<String>> used : acc.usedLegacy.entrySet()) {
            Path source = used.getKey();
            if (!dir.equals(owner(source, acc.modules))) {
                continue;
            }
            module.used.addAll(used.getValue());
            module.remaining.addAll(acc.remainingLegacy.getOrDefault(source, Collections.emptySet()));
            for (Map.Entry<String, Boolean> needed : acc.needed.getOrDefault(source, Collections.emptyMap()).entrySet()) {
                // Main code needing an artifact puts it in the main scope, whatever the tests need
                module.needed.merge(needed.getKey(), needed.getValue(), (a, b) -> a && b);
            }
        }
        return module;
    }

    private static @Nullable Path owner(Path source, Set<Path> modules) {
        Path owner = null;
        int ownerDepth = -1;
        for (Path module : modules) {
            int depth = module.toString().isEmpty() ? 0 : module.getNameCount();
            if ((depth == 0 || source.startsWith(module)) && depth > ownerDepth) {
                owner = module;
                ownerDepth = depth;
            }
        }
        return owner;
    }

    private static Path dir(Path sourcePath) {
        Path parent = sourcePath.getParent();
        return parent == null ? Paths.get("") : parent;
    }

    private static boolean isTest(SourceFile s) {
        Optional<JavaSourceSet> sourceSet = s.getMarkers().findFirst(JavaSourceSet.class);
        if (sourceSet.isPresent()) {
            return sourceSet.get().getName().toLowerCase(Locale.ROOT).contains("test");
        }
        return s.getSourcePath().toString().replace('\\', '/').contains("/src/test/");
    }

    private static boolean isMavenPom(SourceFile s) {
        return s instanceof Xml.Document && s.getMarkers().findFirst(MavenResolutionResult.class).isPresent();
    }

    private static boolean isGradleBuild(SourceFile s) {
        String path = s.getSourcePath().toString();
        return (path.endsWith(".gradle") || path.endsWith(".gradle.kts")) &&
               !path.endsWith("settings.gradle") && !path.endsWith("settings.gradle.kts") &&
               s.getMarkers().findFirst(GradleProject.class).isPresent();
    }
}

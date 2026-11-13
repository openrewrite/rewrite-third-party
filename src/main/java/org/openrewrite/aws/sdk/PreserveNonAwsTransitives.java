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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.aws.sdk.table.AwsSdkV2DependencyChanges;
import org.openrewrite.gradle.AddDependencyVisitor;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.maven.UpdateMavenModel;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;

/**
 * Usable on its own, or embedded in another scanning recipe through {@link #getScanner} and {@link #preserve} with an
 * accumulator from {@link #getInitialValue}. Adding is idempotent, so running it from several recipes is safe.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class PreserveNonAwsTransitives extends ScanningRecipe<PreserveNonAwsTransitives.Accumulator> {

    /**
     * Guarded artifact (group:artifact) to the packages whose use means the module needs it.
     */
    private static final Map<String, List<String>> GUARDED = new LinkedHashMap<>();

    /**
     * An artifact that is redundant when the key artifact is added, because it is a dependency of it.
     */
    private static final Map<String, String> IMPLIED_BY = new HashMap<>();

    static {
        GUARDED.put("org.apache.httpcomponents:httpclient", Arrays.asList(
                "org.apache.http.client", "org.apache.http.impl.client", "org.apache.http.conn",
                "org.apache.http.impl.conn", "org.apache.http.auth", "org.apache.http.impl.auth",
                "org.apache.http.cookie", "org.apache.http.impl.cookie", "org.apache.http.impl.execchain"));
        GUARDED.put("org.apache.httpcomponents:httpcore", singletonList("org.apache.http"));
        GUARDED.put("com.fasterxml.jackson.core:jackson-databind", singletonList("com.fasterxml.jackson.databind"));
        GUARDED.put("com.fasterxml.jackson.core:jackson-core", singletonList("com.fasterxml.jackson.core"));
        GUARDED.put("com.fasterxml.jackson.core:jackson-annotations", singletonList("com.fasterxml.jackson.annotation"));
        GUARDED.put("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor", singletonList("com.fasterxml.jackson.dataformat.cbor"));
        GUARDED.put("joda-time:joda-time", singletonList("org.joda.time"));
        GUARDED.put("commons-logging:commons-logging", singletonList("org.apache.commons.logging"));
        GUARDED.put("software.amazon.ion:ion-java", singletonList("com.amazon.ion"));

        IMPLIED_BY.put("org.apache.httpcomponents:httpcore", "org.apache.httpcomponents:httpclient");
        IMPLIED_BY.put("com.fasterxml.jackson.core:jackson-core", "com.fasterxml.jackson.core:jackson-databind");
        IMPLIED_BY.put("com.fasterxml.jackson.core:jackson-annotations", "com.fasterxml.jackson.core:jackson-databind");
    }

    @Getter
    final String displayName = "Preserve non-AWS libraries inherited from AWS SDK v1";

    @Getter
    final String description = "Adds Apache HttpClient, Jackson, Joda-Time, Commons Logging and Ion as direct dependencies " +
            "where the module's code uses them but only received them transitively through an AWS SDK v1 artifact " +
            "(the `aws-java-sdk` bundle, `aws-java-sdk-*` or the awslabs request-signing interceptor), which the " +
            "migration removes. The previously resolved version is kept, or omitted when dependency management supplies it.";

    @Getter
    transient AwsSdkV2DependencyChanges changes = new AwsSdkV2DependencyChanges(this);

    public static class Accumulator {
        final Set<Path> moduleDirs = ConcurrentHashMap.newKeySet();

        /**
         * Source path to the guarded artifacts it references, split into main (false) and test (true).
         */
        final Map<Path, Map<Boolean, Set<String>>> usesBySource = new ConcurrentHashMap<>();

        /**
         * Module directory to guarded artifacts resolved only through AWS v1, keyed by main (false) / test (true),
         * with the version resolved before the migration.
         */
        final Map<Path, Map<Boolean, Map<String, String>>> awsOnlyByModule = new ConcurrentHashMap<>();
    }

    /**
     * Whether a resolved dependency is an AWS SDK v1 artifact whose transitives disappear with the migration.
     */
    public static boolean isAwsV1Artifact(@Nullable String groupId, @Nullable String artifactId) {
        return AwsSdkV2Coordinates.isV1SdkArtifact(groupId, artifactId) ||
               "com.amazonaws".equals(groupId) && "jmespath-java".equals(artifactId) ||
               "aws-request-signing-apache-interceptor".equals(artifactId);
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
                if (BuildFiles.isBuildFile(s)) {
                    scanBuildFile(s, acc);
                } else if (s instanceof JavaSourceFile) {
                    scanSource((JavaSourceFile) s, acc);
                }
                return tree;
            }
        };
    }

    private static void scanSource(JavaSourceFile s, Accumulator acc) {
        Set<String> used = new HashSet<>();
        for (JavaType type : s.getTypesInUse().getTypesInUse()) {
            JavaType.FullyQualified fq = type instanceof JavaType.FullyQualified ? (JavaType.FullyQualified) type : null;
            if (fq != null) {
                String artifact = guardedArtifactFor(fq.getFullyQualifiedName());
                if (artifact != null) {
                    used.add(artifact);
                }
            }
        }
        if (!used.isEmpty()) {
            acc.usesBySource.computeIfAbsent(s.getSourcePath(), k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(BuildFiles.isTestSource(s), k -> ConcurrentHashMap.newKeySet())
                    .addAll(used);
        }
    }

    static @Nullable String guardedArtifactFor(String fqn) {
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, List<String>> e : GUARDED.entrySet()) {
            for (String pkg : e.getValue()) {
                if (AwsSdkV2Coordinates.inPackage(fqn, pkg) && pkg.length() > bestLength) {
                    best = e.getKey();
                    bestLength = pkg.length();
                }
            }
        }
        return best;
    }

    private static void scanBuildFile(SourceFile buildFile, Accumulator acc) {
        Path module = BuildFiles.moduleDir(buildFile);
        acc.moduleDirs.add(module);
        for (Map.Entry<Boolean, List<ResolvedDependency>> e : BuildFiles.directResolvedByTest(buildFile).entrySet()) {
            Map<String, ResolvedDependency> all = BuildFiles.reachable(e.getValue(), d -> true);
            Map<String, ResolvedDependency> withoutAws = BuildFiles.reachable(e.getValue(),
                    d -> !isAwsV1Artifact(d.getGroupId(), d.getArtifactId()));
            for (String ga : GUARDED.keySet()) {
                ResolvedDependency d = all.get(ga);
                if (d != null && !withoutAws.containsKey(ga)) {
                    acc.awsOnlyByModule.computeIfAbsent(module, k -> new ConcurrentHashMap<>())
                            .computeIfAbsent(e.getKey(), k -> new ConcurrentHashMap<>())
                            .put(ga, d.getVersion());
                }
            }
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile) || !BuildFiles.isBuildFile((SourceFile) tree)) {
                    return tree;
                }
                return preserve((SourceFile) tree, acc, changes, ctx);
            }
        };
    }

    /**
     * Adds the guarded artifacts a build file's module needs. Callable from another recipe's edit visitor, which passes
     * its own data table so rows are attributed to it.
     */
    public SourceFile preserve(SourceFile buildFile, Accumulator acc, AwsSdkV2DependencyChanges table, ExecutionContext ctx) {
        Path module = BuildFiles.moduleDir(buildFile);
        Map<Boolean, Map<String, String>> awsOnly = acc.awsOnlyByModule.get(module);
        if (awsOnly == null) {
            return buildFile;
        }
        Map<String, Boolean> needed = neededArtifacts(module, acc);
        SourceFile result = buildFile;
        for (Map.Entry<String, Boolean> e : needed.entrySet()) {
            String ga = e.getKey();
            boolean testOnly = e.getValue();
            String version = awsOnly.getOrDefault(false, emptyMap()).get(ga);
            if (version == null && testOnly) {
                version = awsOnly.getOrDefault(true, emptyMap()).get(ga);
            }
            if (version == null) {
                continue;
            }
            String implier = IMPLIED_BY.get(ga);
            if (implier != null && needed.containsKey(implier)) {
                continue;
            }
            String[] parts = ga.split(":");
            SourceFile before = result;
            result = add(result, parts[0], parts[1], version, testOnly, ctx);
            if (result != before) {
                table.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(buildFile.getSourcePath().toString(),
                        "preserved-transitive", ga + ":" + version,
                        "Used by the module's code and previously resolved only through an AWS SDK v1 artifact"));
            }
        }
        return result;
    }

    /**
     * Guarded artifacts used in the module, mapped to whether they are used by tests only.
     */
    private static Map<String, Boolean> neededArtifacts(Path module, Accumulator acc) {
        Map<String, Boolean> needed = new TreeMap<>();
        for (Map.Entry<Path, Map<Boolean, Set<String>>> e : acc.usesBySource.entrySet()) {
            if (!module.equals(BuildFiles.owningModule(e.getKey(), acc.moduleDirs))) {
                continue;
            }
            for (Map.Entry<Boolean, Set<String>> byTest : e.getValue().entrySet()) {
                for (String ga : byTest.getValue()) {
                    needed.merge(ga, byTest.getKey(), (a, b) -> a && b);
                }
            }
        }
        return needed;
    }

    private static SourceFile add(SourceFile buildFile, String groupId, String artifactId, String version,
                                  boolean testOnly, ExecutionContext ctx) {
        if (buildFile instanceof Xml.Document) {
            MavenResolutionResult mrr = buildFile.getMarkers().findFirst(MavenResolutionResult.class).get();
            boolean managed = mrr.getPom().getManagedVersion(groupId, artifactId, null, null) != null;
            Xml.Document pom = BuildFiles.addMavenDependency((Xml.Document) buildFile, groupId, artifactId,
                    managed ? null : version, testOnly ? "test" : null, ctx);
            return pom == buildFile ? pom : (Xml.Document) new UpdateMavenModel<ExecutionContext>().visitNonNull(pom, ctx);
        }
        GradleProject gp = buildFile.getMarkers().findFirst(GradleProject.class).orElse(null);
        if (gp == null || BuildFiles.declaresGradleDependency(gp, groupId, artifactId)) {
            return buildFile;
        }
        String configuration = testOnly ? "testImplementation" : "implementation";
        if (gp.getConfiguration(configuration) == null) {
            return buildFile;
        }
        // Gradle has no reliable view of what a dependency-management plugin would supply, so keep the resolved version.
        return (SourceFile) new AddDependencyVisitor(groupId, artifactId, version, null, configuration,
                null, null, null, BuildFiles::isTopLevelDependencies, null).visitNonNull(buildFile, ctx);
    }
}

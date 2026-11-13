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
import org.openrewrite.gradle.RemoveDependency;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.gradle.trait.GradleDependency;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.marker.Markup;
import org.openrewrite.maven.AddManagedDependencyVisitor;
import org.openrewrite.maven.UpdateMavenModel;
import org.openrewrite.maven.tree.Dependency;
import org.openrewrite.maven.tree.ManagedDependency;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.properties.PropertiesVisitor;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.semver.LatestRelease;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Xml;
import org.openrewrite.yaml.DeleteProperty;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.emptySet;
import static org.openrewrite.aws.sdk.AwsSdkV2Coordinates.*;

// Usage is keyed on the v1 types seen in the scan, which also covers modules that only received v1 transitively
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAwsSdkV2Dependencies extends ScanningRecipe<MigrateAwsSdkV2Dependencies.Accumulator> {

    private static final Pattern V1_TYPE_IN_TEXT = Pattern.compile("com\\.amazonaws\\.services\\.[a-z0-9]+(?:\\.[A-Za-z0-9_$]+)+");
    private static final String LOGGING_PREFIX = "logging.level.";

    /**
     * v2 artifacts that are loaded at runtime (credential providers load STS/SSO reflectively, HTTP clients are found
     * through service loading), so not referencing their types does not make them unused.
     */
    private static final Set<String> RUNTIME_ONLY = new HashSet<>(Arrays.asList(
            "sts", "sso", "ssooidc", "apache-client", "netty-nio-client", "url-connection-client", "aws-crt-client"));

    @Getter
    final String displayName = "Complete the AWS SDK v2 dependency migration";

    @Getter
    final String description = "Runs after `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. Adds the v2 artifacts each module's " +
            "AWS SDK v1 code needs (also where v1 only arrived transitively), versionless under one `software.amazon.awssdk:bom`; " +
            "replaces the `com.amazonaws:aws-java-sdk` bundle and any remaining `aws-java-sdk-*` artifact; strips pinned v2 versions; " +
            "removes converted artifacts the module does not use; keeps non-AWS libraries the module used through SDK v1; and " +
            "deletes `logging.level.com.amazonaws.*` keys, whose v1 loggers no longer exist.";

    @Getter
    transient AwsSdkV2DependencyChanges changes = new AwsSdkV2DependencyChanges(this);

    @Getter
    transient PreserveNonAwsTransitives preserveNonAwsTransitives = new PreserveNonAwsTransitives();

    public static class Accumulator {
        final Set<Path> moduleDirs = ConcurrentHashMap.newKeySet();

        /**
         * Source path to the v1 types it uses, split into main (false) and test (true).
         */
        final Map<Path, Map<Boolean, Set<String>>> v1TypesBySource = new ConcurrentHashMap<>();

        /**
         * Types declared by the repository's own sources; some vendor classes into {@code com.amazonaws} packages.
         */
        final Set<String> declaredTypes = ConcurrentHashMap.newKeySet();

        private volatile @Nullable Map<Path, Map<Boolean, Set<String>>> requiredBySource;

        /**
         * Source path to the v2 artifacts it needs. Computed after scanning, once every declared type is known.
         */
        Map<Path, Map<Boolean, Set<String>>> requiredBySource() {
            Map<Path, Map<Boolean, Set<String>>> r = requiredBySource;
            if (r == null) {
                r = new HashMap<>();
                for (Map.Entry<Path, Map<Boolean, Set<String>>> e : v1TypesBySource.entrySet()) {
                    for (Map.Entry<Boolean, Set<String>> byTest : e.getValue().entrySet()) {
                        for (String fqn : byTest.getValue()) {
                            String artifact = declaredTypes.contains(fqn) ? null : v2ArtifactForV1Type(fqn);
                            if (artifact != null) {
                                r.computeIfAbsent(e.getKey(), k -> new HashMap<>())
                                        .computeIfAbsent(byTest.getKey(), k -> new HashSet<>()).add(artifact);
                            }
                        }
                    }
                }
                requiredBySource = r;
            }
            return r;
        }

        /**
         * Module directory to v1 artifact ids its build file declared before the migration.
         */
        final Map<Path, Set<String>> originalV1Artifacts = new ConcurrentHashMap<>();

        /**
         * Module directory to v2 artifact ids its build file declared before the migration.
         */
        final Map<Path, Set<String>> originalV2Artifacts = new ConcurrentHashMap<>();

        /**
         * Maven modules (by directory) whose own pom manages a v1 or v2 AWS BOM, and each module's parent module.
         */
        final Set<Path> managesAwsBom = ConcurrentHashMap.newKeySet();
        final Map<Path, Path> parentModule = new ConcurrentHashMap<>();

        /**
         * Gradle modules that request an AWS BOM in any configuration, possibly from a parent build script.
         */
        final Set<Path> gradleRequestsAwsBom = ConcurrentHashMap.newKeySet();

        PreserveNonAwsTransitives.@Nullable Accumulator preserve;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        Accumulator acc = new Accumulator();
        acc.preserve = preserveNonAwsTransitives.getInitialValue(ctx);
        return acc;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        TreeVisitor<?, ExecutionContext> preserveScanner = preserveNonAwsTransitives.getScanner(acc.preserve);
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile s = (SourceFile) tree;
                preserveScanner.visit(s, ctx);
                if (BuildFiles.isMavenPom(s)) {
                    scanPom((Xml.Document) s, acc);
                } else if (BuildFiles.isGradleBuild(s)) {
                    scanGradle(s, acc);
                } else if (s instanceof JavaSourceFile) {
                    scanSource((JavaSourceFile) s, acc);
                } else if (s instanceof Xml.Document) {
                    scanText(s, s.printAll(), acc);
                }
                return tree;
            }
        };
    }

    private static void scanSource(JavaSourceFile s, Accumulator acc) {
        Set<String> fqns = new HashSet<>();
        for (JavaType type : s.getTypesInUse().getTypesInUse()) {
            if (type instanceof JavaType.FullyQualified) {
                fqns.add(((JavaType.FullyQualified) type).getFullyQualifiedName());
            }
        }
        for (JavaType.Method m : s.getTypesInUse().getUsedMethods()) {
            fqns.add(m.getDeclaringType().getFullyQualifiedName());
        }
        boolean test = BuildFiles.isTestSource(s);
        for (String fqn : fqns) {
            if (isV1SdkType(fqn)) {
                require(acc, s.getSourcePath(), test, fqn);
            }
        }
        if (!(s instanceof J.CompilationUnit)) {
            return;
        }
        new JavaIsoVisitor<Set<String>>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, Set<String> declared) {
                if (classDecl.getType() != null) {
                    declared.add(classDecl.getType().getFullyQualifiedName());
                }
                return super.visitClassDeclaration(classDecl, declared);
            }
        }.visit(s, acc.declaredTypes);
    }

    private static void scanText(SourceFile s, String text, Accumulator acc) {
        Matcher m = V1_TYPE_IN_TEXT.matcher(text);
        while (m.find()) {
            if (isV1SdkType(m.group())) {
                require(acc, s.getSourcePath(), BuildFiles.isTestSource(s), m.group());
            }
        }
    }

    private static void require(Accumulator acc, Path source, boolean test, String v1Type) {
        acc.v1TypesBySource.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(test, k -> ConcurrentHashMap.newKeySet())
                .add(v1Type);
    }

    private static void scanPom(Xml.Document pom, Accumulator acc) {
        Path module = BuildFiles.moduleDir(pom);
        acc.moduleDirs.add(module);
        MavenResolutionResult mrr = pom.getMarkers().findFirst(MavenResolutionResult.class).get();
        ResolvedPom resolved = mrr.getPom();
        for (Dependency d : resolved.getRequested().getDependencies()) {
            recordDeclared(acc, module, resolved.getValue(d.getGroupId()), resolved.getValue(d.getArtifactId()));
        }
        for (ManagedDependency md : resolved.getRequested().getDependencyManagement()) {
            String g = resolved.getValue(md.getGroupId());
            String a = resolved.getValue(md.getArtifactId());
            recordDeclared(acc, module, g, a);
            if (isAwsBom(g, a)) {
                acc.managesAwsBom.add(module);
            }
        }
        MavenResolutionResult parent = mrr.getParent();
        if (parent != null && parent.getPom().getRequested().getSourcePath() != null) {
            Path parentPath = parent.getPom().getRequested().getSourcePath().getParent();
            acc.parentModule.put(module, parentPath == null ? Paths.get("") : parentPath);
        }
    }

    private static void scanGradle(SourceFile build, Accumulator acc) {
        Path module = BuildFiles.moduleDir(build);
        acc.moduleDirs.add(module);
        GradleProject gp = build.getMarkers().findFirst(GradleProject.class).get();
        for (GradleDependencyConfiguration c : gp.getConfigurations()) {
            for (Dependency d : c.getRequested()) {
                recordDeclared(acc, module, d.getGroupId(), d.getArtifactId());
                if (isAwsBom(d.getGroupId(), d.getArtifactId())) {
                    acc.gradleRequestsAwsBom.add(module);
                }
            }
        }
    }

    private static void recordDeclared(Accumulator acc, Path module, @Nullable String groupId, @Nullable String artifactId) {
        if (artifactId == null) {
            return;
        }
        if (isV1SdkArtifact(groupId, artifactId)) {
            acc.originalV1Artifacts.computeIfAbsent(module, k -> ConcurrentHashMap.newKeySet()).add(artifactId);
        } else if (GROUP_ID.equals(groupId)) {
            acc.originalV2Artifacts.computeIfAbsent(module, k -> ConcurrentHashMap.newKeySet()).add(artifactId);
        }
    }

    private static boolean isAwsBom(@Nullable String groupId, @Nullable String artifactId) {
        return GROUP_ID.equals(groupId) && BOM_ARTIFACT_ID.equals(artifactId) ||
               V1_GROUP_ID.equals(groupId) && "aws-java-sdk-bom".equals(artifactId);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile s = (SourceFile) tree;
                if (BuildFiles.isMavenPom(s)) {
                    return editPom((Xml.Document) s, acc, ctx);
                } else if (BuildFiles.isGradleBuild(s)) {
                    return editGradle((JavaSourceFile) s, acc, ctx);
                } else if (s instanceof Properties.File) {
                    return deleteLoggingKeys((Properties.File) s, ctx);
                } else if (s instanceof Yaml.Documents) {
                    return deleteLoggingKeys((Yaml.Documents) s, ctx);
                }
                return s;
            }
        };
    }

    /**
     * v2 artifacts to declare in a module, mapped to whether only tests need them. Core artifacts are dropped when a
     * service artifact (which depends on them) is present.
     */
    private static Map<String, Boolean> required(Path module, Accumulator acc) {
        Map<String, Boolean> required = new TreeMap<>();
        for (Map.Entry<Path, Map<Boolean, Set<String>>> e : acc.requiredBySource().entrySet()) {
            if (module.equals(BuildFiles.owningModule(e.getKey(), acc.moduleDirs))) {
                mergeUsage(required, e.getValue());
            }
        }
        boolean hasService = false;
        for (String a : required.keySet()) {
            hasService |= !isCoreArtifact(a) && !"apache-client".equals(a);
        }
        if (hasService) {
            required.keySet().removeIf(AwsSdkV2Coordinates::isCoreArtifact);
        }
        return required;
    }

    /**
     * Modules already on v2 before the migration are left as their authors set them up.
     */
    private static boolean wasOnV1(Path module, Map<String, Boolean> required, Accumulator acc) {
        return !required.isEmpty() || acc.originalV1Artifacts.containsKey(module);
    }

    /**
     * v2 artifacts used anywhere at or below a module directory. Used for removal decisions, so that a parent build
     * file that declares dependencies for its children is judged by its children's usage.
     */
    private static Set<String> usedAtOrBelow(Path module, Accumulator acc) {
        Map<String, Boolean> used = new HashMap<>();
        for (Map.Entry<Path, Map<Boolean, Set<String>>> e : acc.requiredBySource().entrySet()) {
            if (module.toString().isEmpty() || e.getKey().startsWith(module)) {
                mergeUsage(used, e.getValue());
            }
        }
        return used.keySet();
    }

    private static void mergeUsage(Map<String, Boolean> into, Map<Boolean, Set<String>> usage) {
        for (Map.Entry<Boolean, Set<String>> byTest : usage.entrySet()) {
            for (String a : byTest.getValue()) {
                into.merge(a, byTest.getKey(), (x, y) -> x && y);
            }
        }
    }

    /**
     * v2 artifacts that this run converted from a v1 artifact the module declared and that nothing at or below the module
     * uses.
     */
    private static boolean isUnusedConversion(String v2Artifact, Path module, Accumulator acc) {
        if (RUNTIME_ONLY.contains(v2Artifact) || BOM_ARTIFACT_ID.equals(v2Artifact) ||
            acc.originalV2Artifacts.getOrDefault(module, emptySet()).contains(v2Artifact)) {
            return false;
        }
        boolean converted = false;
        for (String v1 : acc.originalV1Artifacts.getOrDefault(module, emptySet())) {
            converted |= v2Artifact.equals(v2ArtifactForV1Artifact(v1));
        }
        if (!converted) {
            return false;
        }
        Set<String> used = usedAtOrBelow(module, acc);
        if (isCoreArtifact(v2Artifact)) {
            // Core artifacts are needed when anything AWS is used, either directly or through a service artifact.
            return used.isEmpty();
        }
        return !used.contains(v2Artifact);
    }

    private boolean mavenAncestorManagesBom(Path module, Accumulator acc) {
        Path p = acc.parentModule.get(module);
        Set<Path> seen = new HashSet<>();
        while (p != null && seen.add(p)) {
            if (acc.managesAwsBom.contains(p)) {
                return true;
            }
            p = acc.parentModule.get(p);
        }
        return false;
    }

    private Xml.Document editPom(Xml.Document pom, Accumulator acc, ExecutionContext ctx) {
        Path module = BuildFiles.moduleDir(pom);
        String path = pom.getSourcePath().toString();
        MavenResolutionResult mrr = pom.getMarkers().findFirst(MavenResolutionResult.class).get();
        ResolvedPom resolved = mrr.getPom();
        Map<String, Boolean> required = required(module, acc);
        if (!wasOnV1(module, required, acc)) {
            return pom;
        }
        String version = version();

        Xml.Document doc = (Xml.Document) new XmlVisitor<ExecutionContext>() {
            @Override
            public @Nullable Xml visitTag(Xml.Tag tag, ExecutionContext ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"dependency".equals(t.getName()) || !isDependencyListEntry(getCursor())) {
                    return t;
                }
                t = withoutStaleV1Warnings(t);
                String g = resolved.getValue(t.getChildValue("groupId").orElse(null));
                String a = resolved.getValue(t.getChildValue("artifactId").orElse(null));
                boolean managed = isManagedEntry(getCursor());
                if (isV1SdkArtifact(g, a)) {
                    changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "removed", g + ":" + a,
                            "AWS SDK v1 artifact; the module's code was migrated to v2"));
                    return null;
                }
                if (!GROUP_ID.equals(g) || a == null) {
                    return t;
                }
                if (BOM_ARTIFACT_ID.equals(a)) {
                    return managed ? alignMavenBom(t, resolved, path, ctx) : t;
                }
                if (!managed && isUnusedConversion(a, module, acc)) {
                    changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "removed-unused", g + ":" + a,
                            "Converted from a v1 artifact, but no code in the module uses this service"));
                    return null;
                }
                if (managed) {
                    // The BOM manages every v2 artifact, so a pinned managed entry would only fork the version.
                    changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "removed", g + ":" + a + " (managed)",
                            "Managed by " + GROUP_ID + ":" + BOM_ARTIFACT_ID));
                    return null;
                }
                Optional<Xml.Tag> v = t.getChild("version");
                if (v.isPresent()) {
                    changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "version-removed", g + ":" + a,
                            "Managed by " + GROUP_ID + ":" + BOM_ARTIFACT_ID + ":" + version));
                    return t.withContent(ListUtils.map(t.getContent(), c -> c == v.get() ? null : c));
                }
                return t;
            }
        }.visitNonNull(pom, ctx);

        boolean usesV2 = !required.isEmpty() || declaresV2Artifact(doc);
        if (usesV2 && !mavenManagesBom(doc) && !acc.managesAwsBom.contains(module) && !mavenAncestorManagesBom(module, acc)) {
            doc = addMavenBom(doc, version, ctx);
            changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "bom-added",
                    GROUP_ID + ":" + BOM_ARTIFACT_ID + ":" + version, "Manages every v2 artifact at one version"));
        }
        for (Map.Entry<String, Boolean> e : required.entrySet()) {
            String artifact = e.getKey();
            if (inheritsMavenDependency(resolved, artifact)) {
                continue;
            }
            Xml.Document before = doc;
            doc = BuildFiles.addMavenDependency(doc, GROUP_ID, artifact, null, e.getValue() ? "test" : null, ctx);
            if (doc != before) {
                changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "added", GROUP_ID + ":" + artifact,
                        "The module's code used the v1 counterpart"));
            }
        }
        if (doc != pom) {
            doc = (Xml.Document) new UpdateMavenModel<ExecutionContext>().visitNonNull(doc, ctx);
        }
        return (Xml.Document) preserveNonAwsTransitives.preserve(doc, Objects.requireNonNull(acc.preserve), changes, ctx);
    }

    /**
     * v2-migration renames v1 artifacts one at a time; renaming the v1 BOM first leaves warnings about the not-yet-renamed
     * versionless v1 artifacts on their tags. They are stale once every artifact is renamed.
     */
    private static Xml.Tag withoutStaleV1Warnings(Xml.Tag tag) {
        return tag.withMarkers(tag.getMarkers().withMarkers(ListUtils.map(tag.getMarkers().getMarkers(),
                m -> m instanceof Markup && ((Markup) m).getMessage().contains(V1_GROUP_ID + ":aws-java-sdk") ? null : m)));
    }

    private static boolean isDependencyListEntry(Cursor cursor) {
        Object parent = cursor.getParentTreeCursor().getValue();
        return parent instanceof Xml.Tag && "dependencies".equals(((Xml.Tag) parent).getName());
    }

    private static boolean isManagedEntry(Cursor cursor) {
        Cursor deps = cursor.getParentTreeCursor();
        Object dm = deps.getParentTreeCursor().getValue();
        return dm instanceof Xml.Tag && "dependencyManagement".equals(((Xml.Tag) dm).getName());
    }

    private static boolean inheritsMavenDependency(ResolvedPom resolved, String artifact) {
        for (Dependency d : resolved.getRequestedDependencies()) {
            if (GROUP_ID.equals(resolved.getValue(d.getGroupId())) && artifact.equals(resolved.getValue(d.getArtifactId()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresV2Artifact(Xml.Document pom) {
        Xml.Tag deps = pom.getRoot().getChild("dependencies").orElse(null);
        if (deps != null) {
            for (Xml.Tag d : deps.getChildren("dependency")) {
                if (GROUP_ID.equals(d.getChildValue("groupId").orElse(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean mavenManagesBom(Xml.Document pom) {
        Xml.Tag deps = pom.getRoot().getChild("dependencyManagement").flatMap(dm -> dm.getChild("dependencies")).orElse(null);
        if (deps != null) {
            for (Xml.Tag d : deps.getChildren("dependency")) {
                if (GROUP_ID.equals(d.getChildValue("groupId").orElse(null)) &&
                    BOM_ARTIFACT_ID.equals(d.getChildValue("artifactId").orElse(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Xml.Tag alignMavenBom(Xml.Tag bom, ResolvedPom resolved, String path, ExecutionContext ctx) {
        String declared = bom.getChildValue("version").orElse(null);
        String current = resolved.getValue(declared);
        if (declared == null || current == null || declared.contains("${") || !isOlder(current, version())) {
            // A property-based version is left alone; the property may be shared with other artifacts.
            return bom;
        }
        changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "bom-aligned",
                GROUP_ID + ":" + BOM_ARTIFACT_ID + ":" + current + " -> " + version(), "One v2 version for every AWS step"));
        return bom.withChildValue("version", version());
    }

    private static Xml.Document addMavenBom(Xml.Document pom, String version, ExecutionContext ctx) {
        return (Xml.Document) new AddManagedDependencyVisitor(GROUP_ID, BOM_ARTIFACT_ID, version,
                "import", "pom", null, null).visitNonNull(pom, ctx);
    }

    static boolean isOlder(String current, String target) {
        try {
            return new LatestRelease(null).compare(null, current, target) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    private JavaSourceFile editGradle(JavaSourceFile build, Accumulator acc, ExecutionContext ctx) {
        Path module = BuildFiles.moduleDir(build);
        String path = build.getSourcePath().toString();
        Map<String, Boolean> required = required(module, acc);
        if (!wasOnV1(module, required, acc)) {
            return build;
        }
        String version = version();

        JavaSourceFile g = build;
        Set<String> declaredV1 = new TreeSet<>();
        Set<String> declaredV2 = new TreeSet<>();
        boolean[] hasBom = {false};
        new GradleDependency.Matcher().asVisitor((GradleDependency dep, ExecutionContext c) -> {
            String group = dep.getDeclaredGroupId();
            String artifact = dep.getDeclaredArtifactId();
            if (isV1SdkArtifact(group, artifact)) {
                declaredV1.add(artifact);
            } else if (GROUP_ID.equals(group) && artifact != null) {
                if (BOM_ARTIFACT_ID.equals(artifact)) {
                    hasBom[0] = true;
                } else {
                    declaredV2.add(artifact);
                }
            }
            return dep.getTree();
        }).visit(g, ctx);

        for (String v1 : declaredV1) {
            JavaSourceFile before = g;
            g = (JavaSourceFile) new RemoveDependency(V1_GROUP_ID, v1, null).getVisitor().visitNonNull(g, ctx);
            if (g != before) {
                changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "removed", V1_GROUP_ID + ":" + v1,
                        "AWS SDK v1 artifact; the module's code was migrated to v2"));
            }
        }
        for (String v2 : declaredV2) {
            if (!isUnusedConversion(v2, module, acc)) {
                continue;
            }
            JavaSourceFile before = g;
            g = (JavaSourceFile) new RemoveDependency(GROUP_ID, v2, null).getVisitor().visitNonNull(g, ctx);
            if (g != before) {
                changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "removed-unused", GROUP_ID + ":" + v2,
                        "Converted from a v1 artifact, but no code in the module uses this service"));
            }
        }

        boolean bomInEffect = hasBom[0] || acc.gradleRequestsAwsBom.contains(module);
        boolean usesV2 = !required.isEmpty() || !declaredV2.isEmpty();
        GradleProject gp = g.getMarkers().findFirst(GradleProject.class).get();
        if (usesV2 && !bomInEffect && gp.getConfiguration("implementation") != null) {
            JavaSourceFile before = g;
            g = (JavaSourceFile) new AddDependencyVisitor(GROUP_ID, BOM_ARTIFACT_ID, version, null, "implementation",
                    null, null, null, BuildFiles::isTopLevelDependencies, AddDependencyVisitor.DependencyModifier.PLATFORM).visitNonNull(g, ctx);
            if (g != before) {
                bomInEffect = true;
                changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "bom-added",
                        GROUP_ID + ":" + BOM_ARTIFACT_ID + ":" + version, "Manages every v2 artifact at one version"));
            }
        }

        if (bomInEffect) {
            g = (JavaSourceFile) new GradleDependency.Matcher().groupId(GROUP_ID).asVisitor((GradleDependency dep, ExecutionContext c) -> {
                String declared = dep.getDeclaredVersion();
                if (declared == null) {
                    return dep.getTree();
                }
                if (BOM_ARTIFACT_ID.equals(dep.getDeclaredArtifactId())) {
                    if (!declared.startsWith("$") && isOlder(declared, version)) {
                        changes.insertRow(c, new AwsSdkV2DependencyChanges.Row(path, "bom-aligned",
                                GROUP_ID + ":" + BOM_ARTIFACT_ID + ":" + declared + " -> " + version, "One v2 version for every AWS step"));
                        return dep.withDeclaredVersion(version).getTree();
                    }
                    return dep.getTree();
                }
                changes.insertRow(c, new AwsSdkV2DependencyChanges.Row(path, "version-removed",
                        GROUP_ID + ":" + dep.getDeclaredArtifactId(), "Managed by " + GROUP_ID + ":" + BOM_ARTIFACT_ID));
                return dep.removeVersion().getTree();
            }).visitNonNull(g, ctx);
        }

        for (Map.Entry<String, Boolean> e : required.entrySet()) {
            String artifact = e.getKey();
            gp = g.getMarkers().findFirst(GradleProject.class).get();
            if (declaredV2.contains(artifact) || BuildFiles.declaresGradleDependency(gp, GROUP_ID, artifact)) {
                continue;
            }
            String configuration = e.getValue() ? "testImplementation" : "implementation";
            if (gp.getConfiguration(configuration) == null) {
                continue;
            }
            JavaSourceFile before = g;
            g = (JavaSourceFile) new AddDependencyVisitor(GROUP_ID, artifact, bomInEffect ? null : version, null, configuration,
                    null, null, null, BuildFiles::isTopLevelDependencies, null).visitNonNull(g, ctx);
            if (g != before) {
                changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "added", GROUP_ID + ":" + artifact,
                        "The module's code used the v1 counterpart"));
            }
        }
        return (JavaSourceFile) preserveNonAwsTransitives.preserve(g, Objects.requireNonNull(acc.preserve), changes, ctx);
    }

    static boolean isV1LoggerKey(String key) {
        if (!key.startsWith(LOGGING_PREFIX)) {
            return false;
        }
        String logger = key.substring(LOGGING_PREFIX.length());
        return "com.amazonaws".equals(logger) || isV1SdkType(logger);
    }

    private Properties.File deleteLoggingKeys(Properties.File file, ExecutionContext ctx) {
        String path = file.getSourcePath().toString();
        return (Properties.File) new PropertiesVisitor<ExecutionContext>() {
            @Override
            public Properties visitFile(Properties.File f, ExecutionContext ctx) {
                List<Properties.Content> kept = new ArrayList<>();
                String carriedPrefix = null;
                for (Properties.Content c : f.getContent()) {
                    if (c instanceof Properties.Entry && isV1LoggerKey(((Properties.Entry) c).getKey())) {
                        reportLoggingKey(path, ((Properties.Entry) c).getKey(), ctx);
                        if (kept.isEmpty() && carriedPrefix == null) {
                            // Keep the file's leading whitespace rather than the removed line's separator.
                            carriedPrefix = c.getPrefix();
                        }
                        continue;
                    }
                    if (kept.isEmpty() && carriedPrefix != null) {
                        c = (Properties.Content) c.withPrefix(carriedPrefix);
                    }
                    kept.add(c);
                }
                return kept.size() == f.getContent().size() ? f : f.withContent(kept);
            }
        }.visitNonNull(file, ctx);
    }

    private Yaml.Documents deleteLoggingKeys(Yaml.Documents yaml, ExecutionContext ctx) {
        Set<String> keys = new LinkedHashSet<>();
        new YamlIsoVisitor<Set<String>>() {
            @Override
            public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry entry, Set<String> found) {
                if (entry.getValue() instanceof Yaml.Scalar) {
                    StringBuilder key = new StringBuilder(entry.getKey().getValue());
                    for (Cursor c = getCursor().getParentTreeCursor(); c != null && !(c.getValue() instanceof Yaml.Document); c = c.getParent()) {
                        if (c.getValue() instanceof Yaml.Mapping.Entry) {
                            key.insert(0, ((Yaml.Mapping.Entry) c.getValue()).getKey().getValue() + ".");
                        }
                    }
                    if (isV1LoggerKey(key.toString())) {
                        found.add(key.toString());
                    }
                }
                return super.visitMappingEntry(entry, found);
            }
        }.visit(yaml, keys);
        Yaml.Documents result = yaml;
        for (String key : keys) {
            result = (Yaml.Documents) new DeleteProperty(key, null, false, null).getVisitor().visitNonNull(result, ctx);
            reportLoggingKey(yaml.getSourcePath().toString(), key, ctx);
        }
        return result;
    }

    private void reportLoggingKey(String path, String key, ExecutionContext ctx) {
        changes.insertRow(ctx, new AwsSdkV2DependencyChanges.Row(path, "logging-key-removed", key,
                "Configures AWS SDK v1 loggers, which no longer exist; v2 logs under `software.amazon.awssdk` with different " +
                "logger names, so no equivalent key is added"));
    }
}

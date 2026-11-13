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
package org.openrewrite.aws.signing;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.aws.sdk.AwsSdkV2Coordinates;
import org.openrewrite.aws.sdk.PreserveNonAwsTransitives;
import org.openrewrite.aws.sdk.table.AwsSdkV2DependencyChanges;
import org.openrewrite.aws.signing.table.AwsRequestSigningRemnants;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.dependencies.ChangeDependency;
import org.openrewrite.java.dependencies.UpgradeDependencyVersion;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.tree.*;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.util.Collections.emptyMap;
import static org.openrewrite.aws.signing.AwsRequestSigning.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAwsRequestSigningApacheInterceptorDependency extends ScanningRecipe<MigrateAwsRequestSigningApacheInterceptorDependency.Accumulator> {

    private static final XPathMatcher DEPENDENCY = new XPathMatcher("/project/dependencies/dependency");
    private static final XPathMatcher REPOSITORIES = new XPathMatcher("/project/repositories");
    private static final XPathMatcher REPOSITORY = new XPathMatcher("/project/repositories/repository");

    @Option(displayName = "Remove the JitPack repository",
            description = "Remove the JitPack repository from build files that no longer declare any `com.github.*` dependency. " +
                          "Defaults to `false`, since other builds may rely on it.",
            required = false)
    @Nullable
    Boolean removeJitPackRepository;

    @Getter
    String displayName = "Replace the `aws-request-signing-apache-interceptor` dependency with AWS SDK v2 `auth`";

    @Getter
    String description = "Replaces `com.github.awslabs:aws-request-signing-apache-interceptor` with `software.amazon.awssdk:auth`, " +
                         "which provides the `AwsV4HttpSigner` the generated `AwsSigV4RequestInterceptor` uses. Adds `auth` to " +
                         "modules that vendored the interceptor, raises an AWS SDK v2 BOM older than 2.21.0, removes a system-scope " +
                         "jar that only added the interceptor to an SDK v1 jar the module already declares, and declares the " +
                         "libraries the module used that only reached it through the removed artifact. Modules with a site the " +
                         "interceptor migration could not handle keep the dependency.";

    @Override
    public String getInstanceNameSuffix() {
        return Boolean.TRUE.equals(removeJitPackRepository) ? "and remove JitPack" : "";
    }

    public static class Accumulator {
        final Map<String, Module> modules = new HashMap<>();
        ChangeDependency.@Nullable Accumulator change;
        UpgradeDependencyVersion.@Nullable Accumulator upgradeBom;
        PreserveNonAwsTransitives.@Nullable Accumulator preserve;

        Module module(SourceFile sf) {
            return modules.computeIfAbsent(projectKey(sf), k -> new Module());
        }
    }

    static class Module {
        int migratableSites;
        int inreachSites;
        int unmigratableSites;
        boolean vendored;

        // a jar that is not awslabs' but provides the interceptor, such as an SDK v1 jar it was injected into
        @Nullable
        String carrier;

        @Nullable
        Boolean carrierRedundant;

        // system-scope dependency paths by group:artifact
        final Map<String, Path> systemPaths = new HashMap<>();

        boolean migrates() {
            return unmigratableSites == 0;
        }

        boolean removesCarrier() {
            return carrier != null && migrates() && migratableSites > 0 && Boolean.TRUE.equals(carrierRedundant);
        }

        @Nullable
        Path carrierJar() {
            if (!removesCarrier()) {
                return null;
            }
            String[] gav = Objects.requireNonNull(carrier).split(":");
            return gav.length < 2 ? null : systemPaths.get(gav[0] + ":" + gav[1]);
        }
    }

    transient ChangeDependency change = new ChangeDependency(GROUP_ID, ARTIFACT_ID, V2_GROUP_ID, V2_AUTH, AwsSdkV2Coordinates.version(),
            null, null, true);
    transient UpgradeDependencyVersion upgradeBom = new UpgradeDependencyVersion(V2_GROUP_ID, V2_BOM, AwsSdkV2Coordinates.version(),
            null, null, null);
    transient PreserveNonAwsTransitives preserveNonAwsTransitives = new PreserveNonAwsTransitives();
    transient AwsRequestSigningRemnants remnants = new AwsRequestSigningRemnants(this);
    transient AwsSdkV2DependencyChanges dependencyChanges = new AwsSdkV2DependencyChanges(this);

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        Accumulator acc = new Accumulator();
        acc.change = change.getInitialValue(ctx);
        acc.upgradeBom = upgradeBom.getInitialValue(ctx);
        acc.preserve = preserveNonAwsTransitives.getInitialValue(ctx);
        return acc;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        TreeVisitor<?, ExecutionContext> changeScanner = change.getScanner(Objects.requireNonNull(acc.change));
        TreeVisitor<?, ExecutionContext> bomScanner = upgradeBom.getScanner(Objects.requireNonNull(acc.upgradeBom));
        TreeVisitor<?, ExecutionContext> preserveScanner = preserveNonAwsTransitives.getScanner(Objects.requireNonNull(acc.preserve));
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile sf = (SourceFile) tree;
                preserveScanner.visit(sf, ctx);
                if (!(sf instanceof JavaSourceFile) && !(sf instanceof Xml.Document)) {
                    return tree;
                }
                if (changeScanner.isAcceptable(sf, ctx)) {
                    changeScanner.visit(sf, ctx);
                }
                if (bomScanner.isAcceptable(sf, ctx)) {
                    bomScanner.visit(sf, ctx);
                }
                Module module = acc.module(sf);
                if (sf instanceof Xml.Document && sf.getMarkers().findFirst(MavenResolutionResult.class).isPresent()) {
                    scanSystemPaths((Xml.Document) sf, module);
                } else if (sf instanceof JavaSourceFile && !sf.getMarkers().findFirst(GradleProject.class).isPresent()) {
                    scanSource((JavaSourceFile) sf, module);
                }
                return tree;
            }
        };
    }

    private static void scanSource(JavaSourceFile cu, Module module) {
        if (declaresInterceptor(cu)) {
            module.vendored = true;
            return;
        }
        for (InterceptorSite site : InterceptorSite.find(cu)) {
            if (site.unmigratable == null) {
                module.migratableSites++;
                if (site.inreach) {
                    module.inreachSites++;
                }
            } else {
                module.unmigratableSites++;
            }
        }
        if (isTest(cu)) {
            return;
        }
        JavaSourceSet sourceSet = cu.getMarkers().findFirst(JavaSourceSet.class).orElse(null);
        String provider = interceptorProvider(cu);
        if (provider != null && !isAwslabsGav(provider) && module.carrier == null) {
            module.carrier = provider;
            module.carrierRedundant = isRedundantCarrier(provider, sourceSet == null ? emptyMap() : gavToTypes(sourceSet));
        }
    }

    /**
     * Whether every type the carrier provides, apart from the interceptor, also comes from another jar on the classpath.
     */
    static boolean isRedundantCarrier(String carrier, Map<String, List<JavaType.FullyQualified>> gavToTypes) {
        Set<String> others = new HashSet<>();
        for (Map.Entry<String, List<JavaType.FullyQualified>> gav : gavToTypes.entrySet()) {
            if (!gav.getKey().equals(carrier)) {
                gav.getValue().forEach(t -> others.add(t.getFullyQualifiedName()));
            }
        }
        List<JavaType.FullyQualified> provided = gavToTypes.get(carrier);
        if (provided == null || others.isEmpty()) {
            return false;
        }
        for (JavaType.FullyQualified type : provided) {
            if (!INTERCEPTOR.equals(type.getFullyQualifiedName()) && !type.getFullyQualifiedName().startsWith(INTERCEPTOR + "$") &&
                !others.contains(type.getFullyQualifiedName())) {
                return false;
            }
        }
        return true;
    }

    private static void scanSystemPaths(Xml.Document pom, Module module) {
        Path dir = pom.getSourcePath().getParent() == null ? Paths.get("") : pom.getSourcePath().getParent();
        new MavenIsoVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer p) {
                if (DEPENDENCY.matches(getCursor()) && "system".equals(tag.getChildValue("scope").orElse(null))) {
                    String systemPath = tag.getChildValue("systemPath").orElse(null);
                    String groupId = tag.getChildValue("groupId").orElse(null);
                    String artifactId = tag.getChildValue("artifactId").orElse(null);
                    if (systemPath != null && groupId != null && artifactId != null) {
                        String relative = systemPath.replace("${project.basedir}", "").replace("${basedir}", "");
                        if (!relative.equals(systemPath)) {
                            module.systemPaths.put(groupId + ":" + artifactId, dir.resolve(relative.replaceFirst("^/", "")).normalize());
                        }
                    }
                }
                return super.visitTag(tag, p);
            }
        }.visit(pom, 0);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        TreeVisitor<?, ExecutionContext> changeVisitor = change.getVisitor(Objects.requireNonNull(acc.change));
        TreeVisitor<?, ExecutionContext> bomVisitor = upgradeBom.getVisitor(Objects.requireNonNull(acc.upgradeBom));
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile sf = (SourceFile) tree;
                for (Module m : acc.modules.values()) {
                    if (sf.getSourcePath().equals(m.carrierJar())) {
                        return null;
                    }
                }
                if (!(sf instanceof JavaSourceFile) && !(sf instanceof Xml.Document)) {
                    return tree;
                }
                Module module = acc.modules.get(projectKey(sf));
                if (module == null) {
                    return tree;
                }
                MavenResolutionResult mrr = sf.getMarkers().findFirst(MavenResolutionResult.class).orElse(null);
                GradleProject gp = sf.getMarkers().findFirst(GradleProject.class).orElse(null);
                if (mrr == null && gp == null) {
                    return tree;
                }
                BuildFile build = mrr != null ? BuildFile.maven(mrr) : BuildFile.gradle(gp);
                Tree t = tree;
                // RemoveUnusedInreachSigningInterceptor still sees the migrated usages in this cycle, so the swap happens here.
                if (module.migrates() && module.inreachSites > 0 && build.declares(INREACH_GROUP_ID, INREACH_ARTIFACT_ID)) {
                    t = removeDependency(sf, INREACH_GROUP_ID, INREACH_ARTIFACT_ID, ctx);
                }
                if (build.declares(GROUP_ID, ARTIFACT_ID)) {
                    if (!module.migrates()) {
                        return tree;
                    }
                    if (t instanceof Xml.Document) {
                        t = inlineVersionProperty((Xml.Document) t, ctx);
                    }
                    t = build.declares(V2_GROUP_ID, V2_AUTH) ?
                            removeDependency((SourceFile) t, GROUP_ID, ARTIFACT_ID, ctx) :
                            changeVisitor.visit(t, ctx);
                    String bom = build.declaredVersion(V2_GROUP_ID, V2_BOM);
                    if (bom != null && compareVersions(bom, V2_MIN_SIGNER_VERSION) < 0 && t != null) {
                        t = bomVisitor.visit(t, ctx);
                    }
                    if (t != null) {
                        t = preserveNonAwsTransitives.preserve((SourceFile) t, Objects.requireNonNull(acc.preserve), dependencyChanges, ctx);
                    }
                } else if (module.migrates() && (module.migratableSites > 0 || module.vendored) &&
                           !build.resolves(GROUP_ID, ARTIFACT_ID) && !build.resolvesSigner()) {
                    t = addDependency((SourceFile) t, build, V2_GROUP_ID, V2_AUTH, AwsSdkV2Coordinates.version(), ctx);
                }
                if (module.carrier != null && t instanceof Xml.Document) {
                    t = handleCarrier((Xml.Document) t, module, ctx);
                }
                if (Boolean.TRUE.equals(removeJitPackRepository) && t != null && module.migrates() &&
                    !build.declaresOtherJitPackDependency()) {
                    t = removeJitPack((SourceFile) t, ctx);
                }
                return t;
            }
        };
    }

    private Tree handleCarrier(Xml.Document pom, Module module, ExecutionContext ctx) {
        String carrier = Objects.requireNonNull(module.carrier);
        String[] gav = carrier.split(":");
        if (gav.length < 2) {
            return pom;
        }
        if (module.removesCarrier()) {
            // A system-scope dependency is not resolved, so remove its declaration directly.
            return new MavenIsoVisitor<ExecutionContext>() {
                @Override
                public Xml.@Nullable Tag visitTag(Xml.Tag tag, ExecutionContext c) {
                    if (DEPENDENCY.matches(getCursor()) && gav[0].equals(tag.getChildValue("groupId").orElse(null)) &&
                        gav[1].equals(tag.getChildValue("artifactId").orElse(null))) {
                        return null;
                    }
                    return super.visitTag(tag, c);
                }
            }.visitNonNull(pom, ctx);
        }
        return new MavenIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext c) {
                Xml.Tag t = super.visitTag(tag, c);
                if (DEPENDENCY.matches(getCursor()) && gav[0].equals(t.getChildValue("groupId").orElse(null)) &&
                    gav[1].equals(t.getChildValue("artifactId").orElse(null))) {
                    remnants.insertRow(c, new AwsRequestSigningRemnants.Row(pom.getSourcePath().toString(), "system jar",
                            carrier + " provides AWSRequestSigningApacheInterceptor along with types no other dependency provides"));
                    return SearchResult.found(t, "Provides awslabs' AWSRequestSigningApacheInterceptor along with types no other dependency provides; remove it by hand once nothing needs them");
                }
                return t;
            }
        }.visitNonNull(pom, ctx);
    }

    /**
     * Replaces a version property only the awslabs dependency uses with its value and removes the property, so it is not
     * left behind holding the new artifact's version under the old name.
     */
    private static Xml.Document inlineVersionProperty(Xml.Document pom, ExecutionContext ctx) {
        String[] property = new String[2];
        new MavenIsoVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer p) {
                if (DEPENDENCY.matches(getCursor()) && GROUP_ID.equals(tag.getChildValue("groupId").orElse(null)) &&
                    ARTIFACT_ID.equals(tag.getChildValue("artifactId").orElse(null))) {
                    String version = tag.getChildValue("version").orElse("");
                    if (version.startsWith("${") && version.endsWith("}")) {
                        property[0] = version.substring(2, version.length() - 1);
                    }
                }
                return super.visitTag(tag, p);
            }
        }.visit(pom, 0);
        if (property[0] == null) {
            return pom;
        }
        String reference = "${" + property[0] + "}";
        String printed = pom.printAll();
        if (printed.indexOf(reference) != printed.lastIndexOf(reference)) {
            return pom;
        }
        XPathMatcher propertyMatcher = new XPathMatcher("/project/properties/" + property[0]);
        new MavenIsoVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer p) {
                if (propertyMatcher.matches(getCursor())) {
                    property[1] = tag.getValue().orElse(null);
                }
                return super.visitTag(tag, p);
            }
        }.visit(pom, 0);
        if (property[1] == null) {
            return pom;
        }
        String value = property[1];
        return (Xml.Document) new MavenIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.@Nullable Tag visitTag(Xml.Tag tag, ExecutionContext c) {
                if (propertyMatcher.matches(getCursor())) {
                    return null;
                }
                Xml.Tag t = super.visitTag(tag, c);
                if ("properties".equals(t.getName()) && (t.getContent() == null || t.getContent().isEmpty()) &&
                    getCursor().getParentTreeCursor().getValue() instanceof Xml.Tag &&
                    "project".equals(((Xml.Tag) getCursor().getParentTreeCursor().getValue()).getName())) {
                    return null;
                }
                if ("version".equals(t.getName()) && reference.equals(t.getValue().orElse(null))) {
                    return t.withValue(value);
                }
                return t;
            }
        }.visitNonNull(pom, ctx);
    }

    private static Tree removeDependency(SourceFile sf, String groupId, String artifactId, ExecutionContext ctx) {
        if (sf instanceof Xml.Document) {
            return new org.openrewrite.maven.RemoveDependency(groupId, artifactId, null).getVisitor().visitNonNull(sf, ctx);
        }
        return new org.openrewrite.gradle.RemoveDependency(groupId, artifactId, null).getVisitor().visitNonNull(sf, ctx);
    }

    private static Tree addDependency(SourceFile sf, BuildFile build, String groupId, String artifactId, String version,
                                      ExecutionContext ctx) {
        if (sf instanceof Xml.Document) {
            return new org.openrewrite.maven.AddDependencyVisitor(groupId, artifactId, version, null, null, null, null,
                    null, null, null).visitNonNull(sf, ctx);
        }
        return new org.openrewrite.gradle.AddDependencyVisitor(groupId, artifactId, version, null, build.mainConfiguration(),
                null, null, null, MigrateAwsRequestSigningApacheInterceptorDependency::isTopLevelDependencies, null).visitNonNull(sf, ctx);
    }

    // keeps the dependency out of buildscript { dependencies {} }
    private static boolean isTopLevelDependencies(Cursor cursor) {
        if (cursor.getValue() instanceof J.Block) {
            return cursor.getParentOrThrow().getValue() instanceof JavaSourceFile;
        }
        return cursor.getParentOrThrow().firstEnclosing(J.MethodInvocation.class) == null;
    }

    private static Tree removeJitPack(SourceFile sf, ExecutionContext ctx) {
        if (sf instanceof Xml.Document) {
            return new MavenIsoVisitor<ExecutionContext>() {
                @Override
                public Xml.@Nullable Tag visitTag(Xml.Tag tag, ExecutionContext c) {
                    if (REPOSITORY.matches(getCursor()) && tag.getChildValue("url").map(u -> u.contains("jitpack.io")).orElse(false)) {
                        return null;
                    }
                    Xml.Tag t = super.visitTag(tag, c);
                    if (REPOSITORIES.matches(getCursor()) && (t.getContent() == null || t.getContent().isEmpty())) {
                        return null;
                    }
                    return t;
                }
            }.visitNonNull(sf, ctx);
        }
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.@Nullable MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext c) {
                if ("maven".equals(method.getSimpleName()) && method.printTrimmed(getCursor()).contains("jitpack.io")) {
                    Cursor parent = getCursor().dropParentUntil(p -> p instanceof J.MethodInvocation || p == Cursor.ROOT_VALUE);
                    if (parent.getValue() instanceof J.MethodInvocation &&
                        "repositories".equals(((J.MethodInvocation) parent.getValue()).getSimpleName())) {
                        return null;
                    }
                }
                return super.visitMethodInvocation(method, c);
            }
        }.visitNonNull(sf, ctx);
    }

    /**
     * What a Maven or Gradle build file declares and resolves.
     */
    private static abstract class BuildFile {
        abstract List<Dependency> requested();

        abstract boolean resolves(String groupId, String artifactId);

        abstract @Nullable String resolvedVersion(String groupId, String artifactId);

        abstract @Nullable String declaredVersion(String groupId, String artifactId);

        String mainConfiguration() {
            return "implementation";
        }

        boolean declares(String groupId, String artifactId) {
            for (Dependency d : requested()) {
                if (groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId())) {
                    return true;
                }
            }
            return false;
        }

        boolean resolvesSigner() {
            String version = resolvedVersion(V2_GROUP_ID, "http-auth-aws");
            return version != null && compareVersions(version, V2_MIN_SIGNER_VERSION) >= 0;
        }

        boolean declaresOtherJitPackDependency() {
            for (Dependency d : requested()) {
                if (d.getGroupId() != null && d.getGroupId().startsWith("com.github.") &&
                    !(GROUP_ID.equals(d.getGroupId()) && ARTIFACT_ID.equals(d.getArtifactId()))) {
                    return true;
                }
            }
            return false;
        }

        static BuildFile maven(MavenResolutionResult mrr) {
            return new BuildFile() {
                @Override
                List<Dependency> requested() {
                    return mrr.getPom().getRequested().getDependencies();
                }

                @Override
                boolean resolves(String groupId, String artifactId) {
                    return !mrr.findDependencies(groupId, artifactId, null).isEmpty();
                }

                @Override
                @Nullable
                String resolvedVersion(String groupId, String artifactId) {
                    List<ResolvedDependency> found = mrr.findDependencies(groupId, artifactId, Scope.Compile);
                    return found.isEmpty() ? null : found.get(0).getVersion();
                }

                @Override
                @Nullable
                String declaredVersion(String groupId, String artifactId) {
                    for (ResolvedManagedDependency dm : mrr.getPom().getDependencyManagement()) {
                        if (dm.getBomGav() != null && groupId.equals(dm.getBomGav().getGroupId()) &&
                            artifactId.equals(dm.getBomGav().getArtifactId())) {
                            return dm.getBomGav().getVersion();
                        }
                    }
                    return null;
                }
            };
        }

        static BuildFile gradle(GradleProject gp) {
            return new BuildFile() {
                @Override
                List<Dependency> requested() {
                    List<Dependency> requested = new ArrayList<>();
                    for (GradleDependencyConfiguration c : gp.getConfigurations()) {
                        requested.addAll(c.getRequested());
                    }
                    return requested;
                }

                @Override
                boolean resolves(String groupId, String artifactId) {
                    for (GradleDependencyConfiguration c : gp.getConfigurations()) {
                        if (c.findResolvedDependency(groupId, artifactId) != null) {
                            return true;
                        }
                    }
                    return false;
                }

                @Override
                @Nullable
                String resolvedVersion(String groupId, String artifactId) {
                    GradleDependencyConfiguration c = gp.getConfiguration("compileClasspath");
                    ResolvedDependency d = c == null ? null : c.findResolvedDependency(groupId, artifactId);
                    return d == null ? null : d.getVersion();
                }

                @Override
                @Nullable
                String declaredVersion(String groupId, String artifactId) {
                    for (Dependency d : requested()) {
                        if (groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId())) {
                            return d.getVersion();
                        }
                    }
                    return null;
                }

                @Override
                String mainConfiguration() {
                    return gp.getConfiguration("implementation") != null ? "implementation" : "compile";
                }
            };
        }
    }
}

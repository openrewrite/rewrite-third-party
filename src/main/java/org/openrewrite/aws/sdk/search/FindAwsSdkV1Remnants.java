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
package org.openrewrite.aws.sdk.search;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.aws.build.DeclaredDependencies;
import org.openrewrite.aws.build.DeclaredDependencies.Declaration;
import org.openrewrite.aws.sdk.AwsSdkV2Coordinates;
import org.openrewrite.aws.sdk.table.AwsSdkV1Remnants;
import org.openrewrite.binary.Binary;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.maven.UpdateMavenModel;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.Pom;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.quark.Quark;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.*;
import static org.openrewrite.aws.sdk.table.AwsSdkV1Remnants.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindAwsSdkV1Remnants extends ScanningRecipe<FindAwsSdkV1Remnants.Accumulator> {

    private static final Pattern V1_TEXT = Pattern.compile("com\\.amazonaws\\.[A-Za-z][A-Za-z0-9_$]*(?:\\.[A-Za-z0-9_$]+)*");
    private static final String MIGRATION_COMMENT = "AWS SDK for Java v2 migration";

    @Getter
    final String displayName = "Find AWS SDK v1 remnants";

    @Getter
    final String description = "Finds AWS SDK for Java v1 types, dependencies (declared or transitive, including v1-bound " +
            "libraries such as Kinesis Client Library 1.x), textual references and TODO comments left by AWS's v2-migration. " +
            "Libraries that merely share the `com.amazonaws` package root (Lambda runtime, X-Ray, Serverless Java Container, " +
            "Secrets Manager caching) are not reported. Each finding gets a reason: `unmigratable` when a recipe deliberately " +
            "left and marked it, otherwise `missed`.";

    @Getter
    transient AwsSdkV1Remnants remnants = new AwsSdkV1Remnants(this);

    public static class Accumulator {
        final Set<String> declared = ConcurrentHashMap.newKeySet();
        // Build files as they were before this run, so edits made earlier in the run can be detected
        final Map<Path, Integer> pomsBefore = new ConcurrentHashMap<>();
        // Poms re-read by this recipe, so modules visited later resolve against their edited parent
        final Map<Path, Pom> refreshedPoms = new ConcurrentHashMap<>();
        // group:artifact of the repository's own modules, whose dependencies are reported on their own build files
        final Set<String> modules = ConcurrentHashMap.newKeySet();
        // direct group:artifact:version to the v1 group:artifact:version it brings in
        final Map<String, Set<String>> transitives = new ConcurrentHashMap<>();
        final Map<String, Set<String>> versions = new ConcurrentHashMap<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    /**
     * Collects the repository's own types, since some projects vendor classes into {@code com.amazonaws} packages,
     * and the build files before the run.
     */
    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        Set<String> declared = acc.declared;
        JavaIsoVisitor<ExecutionContext> classes = new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                if (classDecl.getType() != null) {
                    declared.add(classDecl.getType().getFullyQualifiedName());
                }
                return super.visitClassDeclaration(classDecl, ctx);
            }
        };
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof J.CompilationUnit) {
                    classes.visit(tree, ctx);
                }
                if (tree instanceof Xml.Document && ((Xml.Document) tree).getMarkers().findFirst(MavenResolutionResult.class).isPresent()) {
                    acc.pomsBefore.put(((Xml.Document) tree).getSourcePath(), ((Xml.Document) tree).printAll().hashCode());
                    MavenResolutionResult mrr = ((Xml.Document) tree).getMarkers().findFirst(MavenResolutionResult.class).get();
                    acc.modules.add(mrr.getPom().getGroupId() + ":" + mrr.getPom().getArtifactId());
                    recordTransitives(singletonList(directs((Xml.Document) tree)), acc);
                } else if (tree instanceof JavaSourceFile && ((JavaSourceFile) tree).getMarkers().findFirst(GradleProject.class).isPresent()) {
                    GradleProject gp = ((JavaSourceFile) tree).getMarkers().findFirst(GradleProject.class).get();
                    acc.modules.add(gp.getGroup() + ":" + gp.getName());
                    recordTransitives(singletonList(directs((JavaSourceFile) tree)), acc);
                }
                return tree;
            }
        };
    }

    private static boolean isV1(String fqn, Set<String> declared) {
        // KPL's package is the same on 0.x and 1.x; a remaining KPL 0.x is reported as a dependency
        if (!AwsSdkV2Coordinates.isV1SdkType(fqn) || AwsSdkV2Coordinates.isKplType(fqn) || declared.contains(fqn)) {
            return false;
        }
        for (String d : declared) {
            if (d.startsWith(fqn + ".")) {
                return false;
            }
        }
        return true;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        Set<String> declared = acc.declared;
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile s = (SourceFile) tree;
                if (s.getMarkers().findFirst(MavenResolutionResult.class).isPresent() && s instanceof Xml.Document) {
                    return findInPom((Xml.Document) s, acc, ctx);
                } else if (s instanceof JavaSourceFile && (s.getMarkers().findFirst(GradleProject.class).isPresent() || isGradleScript(s))) {
                    // A root script that only configures subprojects has no build marker of its own
                    return findInGradle((JavaSourceFile) s, acc, ctx);
                } else if (s instanceof JavaSourceFile) {
                    return findInSource((JavaSourceFile) s, declared, ctx);
                } else if (!(s instanceof Quark) && !(s instanceof Binary)) {
                    return findInText(s, declared, ctx);
                }
                return s;
            }
        };
    }

    private static boolean isGradleScript(SourceFile s) {
        String path = s.getSourcePath().toString();
        return DeclaredDependencies.isBuildFile(s) && (path.endsWith(".gradle") || path.endsWith(".gradle.kts"));
    }

    private static boolean hasUnmigratableMarker(Tree t) {
        for (SearchResult r : t.getMarkers().findAll(SearchResult.class)) {
            if (r.getDescription() != null && r.getDescription().startsWith(UNMIGRATABLE_MARKER_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private JavaSourceFile findInSource(JavaSourceFile cu, Set<String> declared, ExecutionContext ctx) {
        String path = cu.getSourcePath().toString();
        boolean fileMarked = hasUnmigratableMarker(cu);
        Set<String> reported = new HashSet<>();
        return (JavaSourceFile) new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Import visitImport(J.Import _import, ExecutionContext ctx) {
                String name = _import.getTypeName();
                if (_import.isStatic()) {
                    // static imports name a member; its declaring type is what matters
                    name = _import.getQualid().getTarget().printTrimmed(getCursor()).replace(" ", "");
                }
                String fqn = "*".equals(_import.getQualid().getSimpleName()) ? _import.getPackageName() : name;
                if (isV1(fqn, declared)) {
                    return report(_import, fqn);
                }
                return _import;
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                J.Identifier id = super.visitIdentifier(identifier, ctx);
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(id.getType());
                if (fq != null && id.getFieldType() == null && fq.getClassName().endsWith(id.getSimpleName()) &&
                    getCursor().firstEnclosing(J.Import.class) == null &&
                    getCursor().firstEnclosing(J.Package.class) == null &&
                    isV1(fq.getFullyQualifiedName(), declared) &&
                    !reported.contains(fq.getFullyQualifiedName())) {
                    return report(id, fq.getFullyQualifiedName());
                }
                return id;
            }

            @Override
            public @Nullable J preVisit(J tree, ExecutionContext ctx) {
                for (Comment c : tree.getPrefix().getComments()) {
                    String text = c.printComment(getCursor());
                    if (text.contains(MIGRATION_COMMENT)) {
                        String todo = text.replaceAll("^/\\*+|\\*+/$|^//", "").trim();
                        remnants.insertRow(ctx, new AwsSdkV1Remnants.Row(path, "migration-comment", todo, UNMIGRATABLE,
                                "AWS's v2-migration could not convert this and left a TODO"));
                        return SearchResult.found(tree, "AWS SDK v2 migration TODO");
                    }
                }
                return tree;
            }

            private <T extends J> T report(T tree, String fqn) {
                boolean unmigratable = fileMarked || hasUnmigratableMarker(tree) || AwsSdkV2Coordinates.isKclV1Type(fqn);
                if (reported.add(fqn)) {
                    remnants.insertRow(ctx, new AwsSdkV1Remnants.Row(path, "type", fqn,
                            unmigratable ? UNMIGRATABLE : MISSED,
                            AwsSdkV2Coordinates.isKclV1Type(fqn) ?
                                    "Kinesis Client Library 1.x only works with SDK v1; port to KCL 2.x/3.x" :
                                    "Still references AWS SDK v1"));
                }
                return SearchResult.found(tree, "AWS SDK v1 type " + fqn);
            }
        }.visitNonNull(cu, ctx);
    }

    /**
     * Whether a resolved artifact is AWS SDK v1 or a library release bound to it.
     */
    static @Nullable String v1Reason(ResolvedDependency d) {
        return v1Reason(d.getGroupId(), d.getArtifactId(), d.getVersion());
    }

    static @Nullable String v1Reason(String g, String a, @Nullable String version) {
        String v = version == null ? "" : version;
        if (AwsSdkV2Coordinates.isV1SdkArtifact(g, a)) {
            return "AWS SDK v1";
        }
        if (!"com.amazonaws".equals(g)) {
            return null;
        }
        if ("amazon-kinesis-client".equals(a) && v.startsWith("1.")) {
            return "Kinesis Client Library 1.x";
        }
        if ("amazon-kinesis-producer".equals(a) && v.startsWith("0.")) {
            return "Kinesis Producer Library 0.x";
        }
        if ("amazon-sqs-java-messaging-lib".equals(a) && v.startsWith("1.")) {
            return "Amazon SQS Java Messaging Library 1.x";
        }
        if ("aws-xray-recorder-sdk-aws-sdk".equals(a) || "aws-xray-recorder-sdk-aws-sdk-instrumentor".equals(a)) {
            return "X-Ray SDK v1 instrumentation";
        }
        return null;
    }

    /**
     * The v1 artifacts each resolved direct dependency brings in, by group:artifact:version, from every build marker as
     * it was before the run: for declarations a build file's own marker does not resolve, such as those of a root
     * {@code subprojects} block, which only the subprojects' markers resolve.
     */
    private static void recordTransitives(Collection<List<ResolvedDependency>> directLists, Accumulator acc) {
        for (List<ResolvedDependency> directs : directLists) {
            for (ResolvedDependency direct : directs) {
                String key = direct.getGroupId() + ":" + direct.getArtifactId() + ":" + direct.getVersion();
                if (acc.transitives.containsKey(key)) {
                    continue;
                }
                Set<String> v1 = new TreeSet<>();
                for (ResolvedDependency d : reachable(direct.getDependencies())) {
                    if (v1Reason(d) != null) {
                        v1.add(d.getGroupId() + ":" + d.getArtifactId() + ":" + d.getVersion());
                    }
                }
                acc.transitives.put(key, v1);
                acc.versions.computeIfAbsent(direct.getGroupId() + ":" + direct.getArtifactId(), k -> ConcurrentHashMap.newKeySet())
                        .add(direct.getVersion());
            }
        }
    }

    private static Collection<ResolvedDependency> reachable(List<ResolvedDependency> start) {
        Map<String, ResolvedDependency> all = new LinkedHashMap<>();
        Deque<ResolvedDependency> queue = new ArrayDeque<>(start);
        while (!queue.isEmpty()) {
            ResolvedDependency r = queue.poll();
            if (all.putIfAbsent(r.getGroupId() + ":" + r.getArtifactId(), r) == null) {
                queue.addAll(r.getDependencies());
            }
        }
        return all.values();
    }

    /**
     * The pom's resolution as of its final content. Recipes earlier in the run update the marker of the pom they edit
     * (when they update it at all), but never those of its modules, so poms edited in this run, and modules of a pom
     * refreshed here, are resolved again. Parents are visited before their modules.
     */
    private static MavenResolutionResult currentResolution(Xml.Document pom, Accumulator acc, ExecutionContext ctx) {
        MavenResolutionResult mrr = pom.getMarkers().findFirst(MavenResolutionResult.class).get();
        MavenResolutionResult parent = mrr.getParent() == null ? null : withRefreshedAncestors(mrr.getParent(), acc);
        if (parent == mrr.getParent() && !editedInThisRun(pom, acc)) {
            return mrr;
        }
        MavenResolutionResult start = parent == mrr.getParent() ? mrr : mrr.withParent(parent);
        Xml.Document updated = (Xml.Document) new UpdateMavenModel<ExecutionContext>()
                .visitNonNull(pom.withMarkers(pom.getMarkers().setByType(start)), ctx);
        MavenResolutionResult result = updated.getMarkers().findFirst(MavenResolutionResult.class).get();
        if (result == start) {
            return mrr;
        }
        acc.refreshedPoms.put(pom.getSourcePath(), result.getPom().getRequested());
        return result;
    }

    private static MavenResolutionResult withRefreshedAncestors(MavenResolutionResult r, Accumulator acc) {
        MavenResolutionResult parent = r.getParent() == null ? null : withRefreshedAncestors(r.getParent(), acc);
        Pom refreshed = r.getPom().getRequested().getSourcePath() == null ? null :
                acc.refreshedPoms.get(r.getPom().getRequested().getSourcePath());
        MavenResolutionResult result = parent == r.getParent() ? r : r.withParent(parent);
        return refreshed == null ? result : result.withPom(result.getPom().withRequested(refreshed));
    }

    private static boolean editedInThisRun(Xml.Document pom, Accumulator acc) {
        Integer before = acc.pomsBefore.get(pom.getSourcePath());
        return before != null && before != pom.printAll().hashCode();
    }

    /**
     * Reports what the build file declares as it reads after the run's edits, rather than what its marker resolved before
     * them: markers of other modules are not refreshed when a parent pom, a root script or a sibling module changes. A
     * remnant is reported in the file that declares it, directly or through what it brings in.
     */
    private <T extends SourceFile> T findDeclared(T build, SourceFile resolved, Accumulator acc, ExecutionContext ctx) {
        String path = build.getSourcePath().toString();
        Map<UUID, String> marks = new HashMap<>();
        Set<String> reported = new HashSet<>();
        for (Declaration d : DeclaredDependencies.find(resolved)) {
            String ga = d.getGroupId() + ":" + d.getArtifactId();
            // What another module of the repository brings in is reported on that module's build file
            if (d.isManaged() || acc.modules.contains(ga)) {
                continue;
            }
            List<ResolvedDependency> brought = DeclaredDependencies.broughtIn(resolved, d);
            String version = d.getVersion() == null ? resolvedVersion(resolved, d) : d.getVersion();
            Set<String> v1 = new TreeSet<>();
            String reason = v1Reason(d.getGroupId(), d.getArtifactId(), version);
            if (reason != null && reported.add(ga)) {
                String item = ga + (version == null ? "" : ":" + version);
                remnants.insertRow(ctx, new AwsSdkV1Remnants.Row(path, "dependency", item,
                        "amazon-kinesis-client".equals(d.getArtifactId()) ? UNMIGRATABLE : MISSED, reason + ", declared directly"));
                v1.add(item);
            }
            Set<String> transitive = new TreeSet<>();
            if (!brought.isEmpty()) {
                for (ResolvedDependency r : brought) {
                    if (v1Reason(r) != null) {
                        transitive.add(r.getGroupId() + ":" + r.getArtifactId() + ":" + r.getVersion());
                    }
                }
            } else if (!resolvedByMarker(resolved, d)) {
                Set<String> known = version != null ? singleton(version) : acc.versions.getOrDefault(ga, emptySet());
                if (known.size() == 1) {
                    transitive.addAll(acc.transitives.getOrDefault(ga + ":" + known.iterator().next(), emptySet()));
                }
            }
            for (String t : transitive) {
                String[] gav = t.split(":", 3);
                if (reported.add(gav[0] + ":" + gav[1] + "<" + ga)) {
                    remnants.insertRow(ctx, new AwsSdkV1Remnants.Row(path, "dependency", t,
                            "amazon-kinesis-client".equals(gav[1]) ? UNMIGRATABLE : MISSED,
                            v1Reason(gav[0], gav[1], gav[2]) + ", brought in by " + ga));
                    v1.add(t);
                }
            }
            if (!v1.isEmpty()) {
                marks.put(d.getTreeId(), "AWS SDK v1 dependency: " + String.join(", ", v1));
            }
        }
        return DeclaredDependencies.mark(build, marks);
    }

    private static @Nullable String resolvedVersion(SourceFile resolved, Declaration d) {
        for (ResolvedDependency r : directs(resolved)) {
            if (r.getGroupId().equals(d.getGroupId()) && r.getArtifactId().equals(d.getArtifactId())) {
                return r.getVersion();
            }
        }
        return null;
    }

    private static boolean resolvedByMarker(SourceFile resolved, Declaration d) {
        return resolvedVersion(resolved, d) != null;
    }

    private static List<ResolvedDependency> directs(SourceFile build) {
        List<ResolvedDependency> directs = new ArrayList<>();
        MavenResolutionResult mrr = build.getMarkers().findFirst(MavenResolutionResult.class).orElse(null);
        if (mrr != null) {
            for (List<ResolvedDependency> scope : mrr.getDependencies().values()) {
                for (ResolvedDependency d : scope) {
                    if (d.isDirect()) {
                        directs.add(d);
                    }
                }
            }
        }
        GradleProject gp = build.getMarkers().findFirst(GradleProject.class).orElse(null);
        if (gp != null) {
            for (GradleDependencyConfiguration c : gp.getConfigurations()) {
                if (c.isCanBeResolved()) {
                    directs.addAll(c.getDirectResolved());
                }
            }
        }
        return directs;
    }

    private Xml.Document findInPom(Xml.Document pom, Accumulator acc, ExecutionContext ctx) {
        MavenResolutionResult mrr = currentResolution(pom, acc, ctx);
        return findDeclared(pom, pom.withMarkers(pom.getMarkers().setByType(mrr)), acc, ctx);
    }

    private JavaSourceFile findInGradle(JavaSourceFile build, Accumulator acc, ExecutionContext ctx) {
        return findDeclared(build, build, acc, ctx);
    }

    private SourceFile findInText(SourceFile s, Set<String> declared, ExecutionContext ctx) {
        String text = s.printAll();
        if (!text.contains("com.amazonaws.")) {
            return s;
        }
        Set<String> found = new TreeSet<>();
        Matcher m = V1_TEXT.matcher(text);
        while (m.find()) {
            String ref = m.group();
            if (AwsSdkV2Coordinates.isV1SdkReference(ref) && isV1(ref, declared)) {
                found.add(ref);
            }
        }
        if (found.isEmpty()) {
            return s;
        }
        for (String ref : found) {
            remnants.insertRow(ctx, new AwsSdkV1Remnants.Row(s.getSourcePath().toString(), "text", ref,
                    hasUnmigratableMarker(s) || AwsSdkV2Coordinates.isKclV1Type(ref) ? UNMIGRATABLE : MISSED,
                    "Textual reference to an AWS SDK v1 class, package or logger"));
        }
        return SearchResult.found(s, "AWS SDK v1 references: " + String.join(", ", found));
    }
}

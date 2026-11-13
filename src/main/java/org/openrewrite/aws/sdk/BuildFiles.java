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

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.maven.MavenTagInsertionComparator;
import org.openrewrite.maven.internal.InsertDependencyComparator;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.maven.tree.Scope;
import org.openrewrite.xml.AddToTagVisitor;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;

import static java.util.Collections.emptyList;

/**
 * Module and build-file helpers shared by the AWS SDK dependency recipes. A module is identified by the directory of
 * its build file, which works for Maven and Gradle without relying on {@code JavaProject} markers.
 */
final class BuildFiles {

    private static final Path ROOT = Paths.get("");

    private BuildFiles() {
    }

    /**
     * Without it {@code AddDependencyVisitor} also adds to {@code buildscript { dependencies {} }}, where
     * {@code implementation} does not exist.
     */
    static boolean isTopLevelDependencies(Cursor cursor) {
        if (cursor.getValue() instanceof J.Block) {
            return cursor.getParentOrThrow().getValue() instanceof JavaSourceFile;
        }
        return cursor.getParentOrThrow().firstEnclosing(J.MethodInvocation.class) == null;
    }

    static boolean isMavenPom(SourceFile s) {
        return s instanceof Xml.Document && s.getMarkers().findFirst(MavenResolutionResult.class).isPresent();
    }

    static boolean isGradleBuild(SourceFile s) {
        return s instanceof JavaSourceFile && s.getMarkers().findFirst(GradleProject.class).isPresent() &&
               s.getSourcePath().getFileName().toString().startsWith("build.gradle");
    }

    static boolean isBuildFile(SourceFile s) {
        return isMavenPom(s) || isGradleBuild(s);
    }

    static Path moduleDir(SourceFile buildFile) {
        Path parent = buildFile.getSourcePath().getParent();
        return parent == null ? ROOT : parent;
    }

    /**
     * The nearest enclosing module directory of a file, given the directories that contain build files.
     */
    static @Nullable Path owningModule(Path sourcePath, Set<Path> moduleDirs) {
        Path dir = sourcePath.getParent();
        while (dir != null) {
            if (moduleDirs.contains(dir)) {
                return dir;
            }
            dir = dir.getParent();
        }
        return moduleDirs.contains(ROOT) ? ROOT : null;
    }

    static boolean isTestSource(SourceFile s) {
        Optional<JavaSourceSet> sourceSet = s.getMarkers().findFirst(JavaSourceSet.class);
        if (sourceSet.isPresent()) {
            return sourceSet.get().getName().toLowerCase(Locale.ROOT).contains("test");
        }
        String path = s.getSourcePath().toString().replace('\\', '/');
        return path.contains("/src/test/") || path.startsWith("src/test/");
    }

    static boolean declaresMavenDependency(Xml.Document pom, String groupId, String artifactId) {
        Xml.Tag deps = pom.getRoot().getChild("dependencies").orElse(null);
        if (deps == null) {
            return false;
        }
        for (Xml.Tag d : deps.getChildren("dependency")) {
            if (groupId.equals(d.getChildValue("groupId").orElse(null)) &&
                artifactId.equals(d.getChildValue("artifactId").orElse(null))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inserts a dependency into {@code <dependencies>} in Maven's conventional order, creating the section if needed.
     * The caller is responsible for updating the Maven model afterwards.
     */
    static Xml.Document addMavenDependency(Xml.Document pom, String groupId, String artifactId, @Nullable String version,
                                           @Nullable String scope, ExecutionContext ctx) {
        if (declaresMavenDependency(pom, groupId, artifactId)) {
            return pom;
        }
        Xml.Tag root = pom.getRoot();
        if (!root.getChild("dependencies").isPresent()) {
            pom = (Xml.Document) new AddToTagVisitor<ExecutionContext>(root, Xml.Tag.build("<dependencies/>"),
                    new MavenTagInsertionComparator(root.getContent() == null ? emptyList() : root.getContent()))
                    .visitNonNull(pom, ctx);
            root = pom.getRoot();
        }
        Xml.Tag dependencies = root.getChild("dependencies").get();
        Xml.Tag dependency = Xml.Tag.build(
                "\n<dependency>\n" +
                "<groupId>" + groupId + "</groupId>\n" +
                "<artifactId>" + artifactId + "</artifactId>\n" +
                (version == null ? "" : "<version>" + version + "</version>\n") +
                (scope == null || "compile".equals(scope) ? "" : "<scope>" + scope + "</scope>\n") +
                "</dependency>");
        return (Xml.Document) new AddToTagVisitor<ExecutionContext>(dependencies, dependency,
                new InsertDependencyComparator(dependencies.getContent() == null ? emptyList() : dependencies.getContent(), dependency))
                .visitNonNull(pom, ctx);
    }

    /**
     * Whether a Gradle project declares the dependency in any configuration.
     */
    static boolean declaresGradleDependency(GradleProject gp, String groupId, String artifactId) {
        for (GradleDependencyConfiguration c : gp.getConfigurations()) {
            if (c.findRequestedDependency(groupId, artifactId) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The resolved dependency graphs of a module's main and test compile classpaths.
     */
    static Map<Boolean, List<ResolvedDependency>> directResolvedByTest(SourceFile buildFile) {
        Map<Boolean, List<ResolvedDependency>> result = new HashMap<>();
        Optional<MavenResolutionResult> mrr = buildFile.getMarkers().findFirst(MavenResolutionResult.class);
        if (mrr.isPresent()) {
            result.put(false, ListUtils.filter(mrr.get().getDependencies().getOrDefault(Scope.Compile, emptyList()), ResolvedDependency::isDirect));
            result.put(true, ListUtils.filter(mrr.get().getDependencies().getOrDefault(Scope.Test, emptyList()), ResolvedDependency::isDirect));
            return result;
        }
        Optional<GradleProject> gp = buildFile.getMarkers().findFirst(GradleProject.class);
        if (gp.isPresent()) {
            GradleDependencyConfiguration main = gp.get().getConfiguration("compileClasspath");
            GradleDependencyConfiguration test = gp.get().getConfiguration("testCompileClasspath");
            result.put(false, main == null ? emptyList() : main.getDirectResolved());
            result.put(true, test == null ? emptyList() : test.getDirectResolved());
        }
        return result;
    }

    /**
     * Every resolved dependency reachable from the roots, visiting each coordinate once.
     */
    static Map<String, ResolvedDependency> reachable(List<ResolvedDependency> roots, Predicate<ResolvedDependency> enter) {
        Map<String, ResolvedDependency> seen = new LinkedHashMap<>();
        Deque<ResolvedDependency> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            ResolvedDependency d = stack.pop();
            String ga = d.getGroupId() + ":" + d.getArtifactId();
            if (seen.containsKey(ga) || !enter.test(d)) {
                continue;
            }
            seen.put(ga, d);
            stack.addAll(d.getDependencies());
        }
        return seen;
    }
}

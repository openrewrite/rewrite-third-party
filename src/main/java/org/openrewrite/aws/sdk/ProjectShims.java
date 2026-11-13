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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Heavyweight;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.Markup;
import org.openrewrite.marker.SearchResult;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

/**
 * Places a generated helper class into each source set whose Java files need it. The helper goes into the package of the
 * first such file, so it needs no extra import in most projects. A test source set reuses its project's main helper.
 */
final class ProjectShims implements AwsMigrationScope.Limitable {

    private @Nullable AwsMigrationScope scope;
    private final Map<String, Placement> placements = new TreeMap<>();
    private final Set<Path> existingSources = new HashSet<>();
    private final Map<String, Set<Path>> requirersByProject = new HashMap<>();

    static final class Placement {
        final Path sourceRoot;
        final String packageName;
        final Path samplePath;
        // Accumulators must not hold trees or classpaths, so only lightweight markers and the source set identity are kept
        final List<Marker> markers;
        final @Nullable UUID sourceSetId;
        final String sourceSetName;
        final String project;
        final boolean main;

        Placement(Path sourceRoot, String packageName, J.CompilationUnit sample, String project, String sourceSetName) {
            this.sourceRoot = sourceRoot;
            this.packageName = packageName;
            this.samplePath = sample.getSourcePath();
            List<Marker> light = new ArrayList<>();
            for (Marker m : sample.getMarkers().getMarkers()) {
                if (!(m instanceof Heavyweight) && !(m instanceof SearchResult) && !(m instanceof Markup)) {
                    light.add(m);
                }
            }
            this.markers = light;
            this.sourceSetId = sample.getMarkers().findFirst(JavaSourceSet.class).map(JavaSourceSet::getId).orElse(null);
            this.sourceSetName = sourceSetName;
            this.project = project;
            this.main = "main".equals(sourceSetName);
        }

        Markers toMarkers() {
            List<Marker> all = new ArrayList<>(markers);
            if (sourceSetId != null) {
                all.add(new JavaSourceSet(sourceSetId, sourceSetName, emptyList(), emptyMap()));
            }
            return Markers.build(all);
        }
    }

    @Override
    public void limitTo(AwsMigrationScope scope) {
        this.scope = scope;
    }

    /**
     * Whether a helper required by this source is generated: always, unless a scope was given.
     */
    boolean inScope(Path requirer) {
        return scope == null || scope.includes(requirer);
    }

    void recordExisting(SourceFile sourceFile) {
        existingSources.add(sourceFile.getSourcePath());
    }

    /**
     * Records that this Java file needs the helper.
     */
    void require(J.CompilationUnit cu) {
        if (!cu.getSourcePath().toString().endsWith(".java")) {
            return;
        }
        requirersByProject.computeIfAbsent(projectName(cu), k -> new HashSet<>()).add(cu.getSourcePath());
        String key = key(cu);
        Placement existing = placements.get(key);
        if (existing != null && existing.samplePath.compareTo(cu.getSourcePath()) <= 0) {
            return;
        }
        String pkg = cu.getPackageDeclaration() == null ? "" :
                cu.getPackageDeclaration().getPackageName();
        Path dir = cu.getSourcePath().getParent();
        Path root = dir;
        if (!pkg.isEmpty() && dir != null) {
            int segments = pkg.split("\\.").length;
            for (int i = 0; i < segments && root != null; i++) {
                root = root.getParent();
            }
        }
        if (root == null) {
            root = Paths.get("");
        }
        placements.put(key, new Placement(root, pkg, cu, projectName(cu), sourceSet(cu)));
    }

    boolean isEmpty() {
        return placements.isEmpty();
    }

    /**
     * The fully qualified name of the helper visible to this file, or null if none was placed for it.
     */
    @Nullable
    String fqn(J.CompilationUnit cu, String simpleName) {
        Placement p = placements.get(key(cu));
        if (p == null || !p.main) {
            Placement main = placements.get(projectName(cu) + "|main");
            if (main != null) {
                p = main;
            }
        }
        if (p == null) {
            return null;
        }
        return p.packageName.isEmpty() ? simpleName : p.packageName + "." + simpleName;
    }

    /**
     * The helper placed for a project, preferring its main source set; for files of other projects that share that helper.
     */
    @Nullable
    String fqnForProject(String project, String simpleName) {
        Placement p = placements.get(project + "|main");
        if (p == null) {
            for (Placement candidate : placements.values()) {
                if (candidate.project.equals(project)) {
                    p = candidate;
                    break;
                }
            }
        }
        return p == null ? null : p.packageName.isEmpty() ? simpleName : p.packageName + "." + simpleName;
    }

    /**
     * Drops the placements of all other projects.
     */
    void retainProjects(Set<String> projects) {
        placements.values().removeIf(p -> !projects.contains(p.project));
    }

    /**
     * Generates one helper per placement, skipping test source sets whose project has a main helper.
     *
     * @param source produces the helper's source text for a package name
     */
    List<SourceFile> generate(String simpleName, Function<String, String> source, ExecutionContext ctx) {
        return generate(simpleName, source, AwsV2Parser.parser(ctx), ctx);
    }

    /**
     * @param parser parses the helper, for helpers that use v2 service types
     */
    List<SourceFile> generate(String simpleName, Function<String, String> source, JavaParser.Builder<?, ?> parser, ExecutionContext ctx) {
        Set<String> projectsWithMain = new HashSet<>();
        Set<String> projectsInScope = new HashSet<>();
        for (Map.Entry<String, Set<Path>> requirers : requirersByProject.entrySet()) {
            for (Path requirer : requirers.getValue()) {
                if (inScope(requirer)) {
                    projectsInScope.add(requirers.getKey());
                    break;
                }
            }
        }
        for (Placement p : placements.values()) {
            if (p.main) {
                projectsWithMain.add(p.project);
            }
        }
        List<SourceFile> generated = new ArrayList<>();
        for (Placement p : placements.values()) {
            if (!p.main && projectsWithMain.contains(p.project) || !projectsInScope.contains(p.project)) {
                continue;
            }
            Path path = p.sourceRoot;
            if (!p.packageName.isEmpty()) {
                for (String segment : p.packageName.split("\\.")) {
                    path = path.resolve(segment);
                }
            }
            path = path.resolve(simpleName + ".java");
            if (existingSources.contains(path)) {
                continue;
            }
            Optional<SourceFile> parsed = parser.build()
                    .parse(ctx, source.apply(p.packageName))
                    .findFirst();
            if (parsed.isPresent() && parsed.get() instanceof J.CompilationUnit) {
                J.CompilationUnit cu = (J.CompilationUnit) parsed.get();
                generated.add(cu.withId(Tree.randomId())
                        .withSourcePath(path)
                        .withMarkers(p.toMarkers()));
                existingSources.add(path);
            }
        }
        return generated;
    }

    static String packageLine(String packageName) {
        return packageName.isEmpty() ? "" : "package " + packageName + ";\n\n";
    }

    private static String key(J.CompilationUnit cu) {
        return projectName(cu) + "|" + sourceSet(cu);
    }

    static String projectName(J.CompilationUnit cu) {
        return cu.getMarkers().findFirst(JavaProject.class).map(JavaProject::getProjectName).orElse("");
    }

    private static String sourceSet(J.CompilationUnit cu) {
        return cu.getMarkers().findFirst(JavaSourceSet.class).map(JavaSourceSet::getName).orElse("main");
    }
}

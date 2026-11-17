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
import org.openrewrite.SourceFile;
import org.openrewrite.Validated;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.java.TypeMatcher;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.maven.tree.Dependency;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.semver.Semver;
import org.openrewrite.semver.VersionComparator;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.emptyList;
import static org.openrewrite.internal.StringUtils.matchesGlob;

/**
 * The modules, and the sources within them, that a composition migrates, given by the libraries they depend on. Generated
 * files are not subject to preconditions, so a recipe that generates helpers for a composition limited by a precondition
 * gets the same limit as this scope. It collects what it needs from the build markers and type attribution of the scanned
 * sources.
 */
public final class AwsMigrationScope {

    public static final String ENTRY_DESCRIPTION = "Entries are `groupId:artifactId[:version]` glob patterns, with an optional " +
                                                   "version selector such as `2.x`. An entry followed by ` using <type pattern>` " +
                                                   "limits it, in the modules that match it, to the sources that use matching " +
                                                   "types.";

    public static final String GENERATE_HELPERS = "Whether to generate the helper classes that the changes use. Compositions " +
                                                  "that limit where helpers are generated set it to `false` and generate " +
                                                  "them with `org.openrewrite.aws.sdk.GenerateAwsSdkV2Helpers`. Defaults to `true`.";

    private final List<Entry> only;
    private final List<Entry> except;
    // module -> indexes of the entries its build depends on, `only` entries first
    private final Map<String, Set<Integer>> moduleEntries = new ConcurrentHashMap<>();
    // source -> indexes of the `only` entries whose types it uses
    private final Map<Path, Set<Integer>> usingSources = new ConcurrentHashMap<>();

    /**
     * The accumulator of a recipe that generates helpers, which only generates them for the sources in a scope once given
     * one.
     */
    public interface Limitable {
        void limitTo(AwsMigrationScope scope);
    }

    private AwsMigrationScope(List<Entry> only, List<Entry> except) {
        this.only = only;
        this.except = except;
    }

    /**
     * @param only   the modules in scope, or every module when null or empty
     * @param except the modules out of scope, even if {@code only} includes them
     */
    public static AwsMigrationScope of(@Nullable List<String> only, @Nullable List<String> except) {
        return new AwsMigrationScope(parse(only), parse(except));
    }

    public static Validated<Object> validate(String option, @Nullable List<String> entries) {
        Validated<Object> v = Validated.none();
        if (entries != null) {
            for (String entry : entries) {
                Entry e = Entry.parse(entry);
                v = v.and(Validated.test(option, "must be `groupId:artifactId[:version][ using <type pattern>]`", entry,
                        x -> e != null));
                if (e != null && e.version != null) {
                    v = v.and(Semver.validate(e.version, null));
                }
            }
        }
        return v;
    }

    public boolean isEverything() {
        return only.isEmpty() && except.isEmpty();
    }

    /**
     * Records the dependencies of a build file and which entries' types a source uses.
     */
    public void scan(SourceFile sourceFile) {
        if (isEverything()) {
            return;
        }
        String module = moduleOf(sourceFile.getSourcePath());
        List<Entry> all = new ArrayList<>(only);
        all.addAll(except);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).matches(sourceFile)) {
                moduleEntries.computeIfAbsent(module, k -> ConcurrentHashMap.newKeySet()).add(i);
            }
        }
        if (sourceFile instanceof JavaSourceFile) {
            Set<Integer> using = new HashSet<>();
            for (JavaType type : ((JavaSourceFile) sourceFile).getTypesInUse().getTypesInUse()) {
                if (type instanceof JavaType.FullyQualified) {
                    for (int i = 0; i < only.size(); i++) {
                        TypeMatcher matcher = only.get(i).using;
                        if (matcher != null && matcher.matches(type)) {
                            using.add(i);
                        }
                    }
                }
            }
            if (!using.isEmpty()) {
                usingSources.put(sourceFile.getSourcePath(), using);
            }
        }
    }

    /**
     * Whether a source, or a build file, is in scope.
     */
    public boolean includes(Path sourcePath) {
        return includes(sourcePath, true);
    }

    /**
     * Whether the module of a path is in scope, regardless of the types its sources use.
     */
    public boolean includesModuleOf(Path path) {
        return includes(path, false);
    }

    private boolean includes(Path path, boolean bySource) {
        if (isEverything()) {
            return true;
        }
        Set<Integer> entries = moduleEntries.getOrDefault(moduleOf(path), Collections.emptySet());
        for (int i = only.size(); i < only.size() + except.size(); i++) {
            if (entries.contains(i)) {
                return false;
            }
        }
        if (only.isEmpty()) {
            return true;
        }
        Set<Integer> using = usingSources.getOrDefault(path, Collections.emptySet());
        for (int i = 0; i < only.size(); i++) {
            if (entries.contains(i) && (only.get(i).using == null || !bySource || using.contains(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The directory of the build file a source file belongs to, derived from its path so that build files, which carry no
     * {@code JavaProject} marker, group with their sources.
     */
    public static String moduleOf(Path sourcePath) {
        String path = sourcePath.toString().replace('\\', '/');
        int src = path.startsWith("src/") ? 0 : path.indexOf("/src/");
        if (src >= 0) {
            return path.substring(0, src);
        }
        int slash = path.lastIndexOf('/');
        String dir = slash < 0 ? "" : path.substring(0, slash);
        // version catalogs live in <root>/gradle
        if ("gradle".equals(dir) || dir.endsWith("/gradle")) {
            return dir.length() == "gradle".length() ? "" : dir.substring(0, dir.length() - "/gradle".length());
        }
        return dir;
    }

    private static List<Entry> parse(@Nullable List<String> entries) {
        if (entries == null) {
            return emptyList();
        }
        List<Entry> parsed = new ArrayList<>();
        for (String entry : entries) {
            Entry e = Entry.parse(entry);
            if (e != null) {
                parsed.add(e);
            }
        }
        return parsed;
    }

    private static final class Entry {
        final String groupId;
        final String artifactId;
        final @Nullable String version;
        final @Nullable VersionComparator versionComparator;
        final @Nullable TypeMatcher using;

        private Entry(String groupId, String artifactId, @Nullable String version, @Nullable TypeMatcher using) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.versionComparator = version == null ? null : Semver.validate(version, null).getValue();
            this.using = using;
        }

        static @Nullable Entry parse(String entry) {
            String coordinates = entry.trim();
            TypeMatcher using = null;
            int u = coordinates.indexOf(" using ");
            if (u >= 0) {
                using = new TypeMatcher(coordinates.substring(u + " using ".length()).trim(), true);
                coordinates = coordinates.substring(0, u).trim();
            }
            String[] parts = coordinates.split(":");
            if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
                return null;
            }
            return new Entry(parts[0], parts[1], parts.length == 3 ? parts[2] : null, using);
        }

        boolean matches(SourceFile sourceFile) {
            Optional<MavenResolutionResult> maven = sourceFile.getMarkers().findFirst(MavenResolutionResult.class);
            if (maven.isPresent()) {
                for (Dependency d : maven.get().getPom().getRequestedDependencies()) {
                    if (matches(d.getGroupId(), d.getArtifactId(), null)) {
                        return true;
                    }
                }
                for (List<ResolvedDependency> scope : maven.get().getDependencies().values()) {
                    for (ResolvedDependency d : scope) {
                        if (matches(d.getGroupId(), d.getArtifactId(), d.getVersion())) {
                            return true;
                        }
                    }
                }
            }
            Optional<GradleProject> gradle = sourceFile.getMarkers().findFirst(GradleProject.class);
            if (gradle.isPresent()) {
                for (GradleDependencyConfiguration conf : gradle.get().getConfigurations()) {
                    for (Dependency d : conf.getRequested()) {
                        if (matches(d.getGroupId(), d.getArtifactId(), null)) {
                            return true;
                        }
                    }
                    for (ResolvedDependency d : conf.getResolved()) {
                        if (matches(d.getGroupId(), d.getArtifactId(), d.getVersion())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * A requested dependency matches only when no version is required, since its version may be managed elsewhere.
         */
        private boolean matches(@Nullable String g, String a, @Nullable String v) {
            return matchesGlob(g == null ? "" : g, groupId) && matchesGlob(a, artifactId) &&
                   (versionComparator == null || v != null && versionComparator.isValid(null, v));
        }
    }
}

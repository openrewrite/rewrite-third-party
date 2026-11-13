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
package org.openrewrite.aws.build;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.semver.Semver;
import org.openrewrite.semver.VersionComparator;

import java.util.*;

import static org.openrewrite.internal.StringUtils.matchesGlob;

/**
 * Like {@code DependencyInsight}, but reads build files as earlier recipes in the run left them; see
 * {@link DeclaredDependencies}.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class FindDeclaredDependency extends ScanningRecipe<Set<String>> {

    @Option(displayName = "Group pattern", description = "Glob for the group ID.", example = "org.springframework.cloud")
    String groupIdPattern;

    @Option(displayName = "Artifact pattern", description = "Glob for the artifact ID, or several separated by commas.",
            example = "spring-cloud-aws*,spring-cloud-starter-aws*")
    String artifactIdPattern;

    @Option(displayName = "Version", description = "A version selector such as `2.x`. Declarations without a version " +
                                                    "they resolve themselves are not matched when it is set.",
            example = "2.x", required = false)
    @Nullable
    String version;

    String displayName = "Find declared dependencies after the run's edits";

    @Override
    public String getInstanceNameSuffix() {
        return "`" + groupIdPattern + ":" + artifactIdPattern + (version == null ? "" : ":" + version) + "`";
    }

    String description = "Marks the build file declarations that still bring a dependency: dependencies, managed versions, BOMs, " +
           "platforms and version catalog entries, and dependencies that brought it transitively when the build was " +
           "resolved and still read the same. Each is marked in the file that declares it, as that file reads after " +
           "earlier recipes in the run, so dependencies a parent pom, root `subprojects` block or catalog no longer " +
           "declares are not reported in child modules. Dependencies on the project's own groups are not followed, " +
           "since the module declaring the match is marked itself.";

    @Override
    public Validated<Object> validate() {
        Validated<Object> v = super.validate();
        if (version != null) {
            v = v.and(Semver.validate(version, null));
        }
        return v;
    }

    @Override
    public Set<String> getInitialValue(ExecutionContext ctx) {
        return new HashSet<>();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Set<String> ownGroups) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    tree.getMarkers().findFirst(MavenResolutionResult.class)
                            .ifPresent(mrr -> ownGroups.add(mrr.getPom().getGroupId()));
                    tree.getMarkers().findFirst(GradleProject.class)
                            .ifPresent(gp -> ownGroups.add(gp.getGroup()));
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Set<String> ownGroups) {
        VersionComparator versions = version == null ? null : Semver.validate(version, null).getValue();
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile) || !DeclaredDependencies.isBuildFile((SourceFile) tree)) {
                    return tree;
                }
                SourceFile sf = (SourceFile) tree;
                Map<UUID, Set<String>> found = new LinkedHashMap<>();
                for (DeclaredDependencies.Declaration d : DeclaredDependencies.find(sf)) {
                    if (matches(d.getGroupId(), d.getArtifactId(), d.getVersion(), versions)) {
                        found.computeIfAbsent(d.getTreeId(), k -> new LinkedHashSet<>()).add(d.coordinates());
                    } else if (!ownGroups.contains(d.getGroupId())) {
                        for (ResolvedDependency r : DeclaredDependencies.broughtIn(sf, d)) {
                            if (matches(r.getGroupId(), r.getArtifactId(), r.getVersion(), versions)) {
                                found.computeIfAbsent(d.getTreeId(), k -> new LinkedHashSet<>())
                                        .add(r.getGroupId() + ":" + r.getArtifactId() + ":" + r.getVersion() +
                                             " via " + d.getGroupId() + ":" + d.getArtifactId());
                            }
                        }
                    }
                }
                Map<UUID, String> descriptions = new HashMap<>();
                found.forEach((id, matched) -> descriptions.put(id, String.join(", ", matched)));
                return DeclaredDependencies.mark(sf, descriptions);
            }
        };
    }

    private boolean matches(String groupId, String artifactId, @Nullable String v, @Nullable VersionComparator versions) {
        if (!matchesGlob(groupId, groupIdPattern) || versions != null && (v == null || !versions.isValid(null, v))) {
            return false;
        }
        for (String glob : artifactIdPattern.split(",")) {
            if (matchesGlob(artifactId, glob.trim())) {
                return true;
            }
        }
        return false;
    }
}

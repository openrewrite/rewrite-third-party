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
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.semver.LatestRelease;

import java.util.ArrayList;
import java.util.List;

/**
 * Precondition that tells Maven and Gradle builds on the {@code jakarta.jms} namespace apart from those on
 * {@code javax.jms}. It reads the build file's current model, so inside the Spring Boot 3 chain it already sees the
 * upgraded Boot version.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class FindBuildsByJmsNamespace extends Recipe {

    @Option(displayName = "Jakarta",
            description = "Find builds on `jakarta.jms` when true, builds on `javax.jms` when false.")
    boolean jakarta;

    @Getter
    final String displayName = "Find builds by JMS namespace";

    @Getter
    final String description = "Marks Maven and Gradle build files whose dependencies put them on the `jakarta.jms` " +
            "(Jakarta Messaging 3+, Spring Boot 3+) or the `javax.jms` namespace.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile s = (SourceFile) tree;
                Boolean onJakarta = isOnJakarta(s);
                return onJakarta != null && onJakarta == jakarta ? SearchResult.found(s) : s;
            }
        };
    }

    static @Nullable Boolean isOnJakarta(SourceFile s) {
        List<ResolvedDependency> resolved = new ArrayList<>();
        MavenResolutionResult mrr = s.getMarkers().findFirst(MavenResolutionResult.class).orElse(null);
        if (mrr != null) {
            ResolvedPom pom = mrr.getPom();
            String managedJms = pom.getManagedVersion("jakarta.jms", "jakarta.jms-api", null, null);
            String managedBoot = pom.getManagedVersion("org.springframework.boot", "spring-boot", null, null);
            if (atLeast(managedJms, "3") || atLeast(managedBoot, "3")) {
                return true;
            }
            for (List<ResolvedDependency> deps : mrr.getDependencies().values()) {
                resolved.addAll(deps);
            }
        } else {
            GradleProject gp = s.getMarkers().findFirst(GradleProject.class).orElse(null);
            if (gp == null) {
                return null;
            }
            for (GradleDependencyConfiguration c : gp.getConfigurations()) {
                if (c.isCanBeResolved()) {
                    resolved.addAll(c.getResolved());
                }
            }
        }
        for (ResolvedDependency d : resolved) {
            if ("jakarta.jms".equals(d.getGroupId()) && "jakarta.jms-api".equals(d.getArtifactId()) && atLeast(d.getVersion(), "3") ||
                "org.springframework.boot".equals(d.getGroupId()) && "spring-boot".equals(d.getArtifactId()) && atLeast(d.getVersion(), "3")) {
                return true;
            }
        }
        return false;
    }

    private static boolean atLeast(@Nullable String version, String min) {
        return version != null && new LatestRelease(null).compare(null, version, min) >= 0;
    }
}

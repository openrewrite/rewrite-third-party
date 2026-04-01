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
package org.openrewrite.recipe.quarkus.internal;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeRun;
import org.openrewrite.SourceFile;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.maven.MavenParser;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the generated Quarkus aggregation recipes from
 * {@code quarkus-consolidated.yml} do not run on projects whose
 * {@code io.quarkus.platform:quarkus-bom} version is at or above
 * the recipe's target version.
 */
class QuarkusPreconditionTest {

    /**
     * Build an Environment that includes both the consolidated recipes from
     * {@code META-INF/rewrite/} and the individual quarkus update recipes
     * from the {@code quarkus-updates/} path inside the quarkus-update-recipes JAR.
     */
    private static Recipe loadConsolidatedRecipe(String recipeName) {
        try {
            Environment.Builder builder = Environment.builder().scanRuntimeClasspath();

            // The quarkus-update-recipes JAR stores its YAML recipes under quarkus-updates/
            // instead of META-INF/rewrite/, so they're not picked up by scanRuntimeClasspath().
            URL marker = QuarkusPreconditionTest.class.getClassLoader()
                    .getResource("quarkus-updates/core/3.0.alpha1.yaml");
            if (marker != null) {
                String jarPath = marker.getPath().substring("file:".length(), marker.getPath().indexOf("!"));
                try (JarFile jar = new JarFile(jarPath)) {
                    jar.stream()
                            .filter(e -> e.getName().startsWith("quarkus-updates/") && e.getName().endsWith(".yaml"))
                            .forEach(entry -> {
                                try (InputStream is = jar.getInputStream(entry)) {
                                    builder.load(new YamlResourceLoader(
                                            is, URI.create(entry.getName()), new Properties(),
                                            (ClassLoader) null, emptyList()));
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
                }
            }

            return builder.build().activateRecipes(recipeName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load consolidated recipe: " + recipeName, e);
        }
    }

    @Test
    void doesNotRunWhenQuarkusVersionIsAtTarget() {
        Recipe recipe = loadConsolidatedRecipe("org.openrewrite.quarkus.MigrateToQuarkus_v3_17_0");

        // A project on quarkus-bom 3.17.0 should NOT be modified by MigrateToQuarkus_v3_17_0,
        // because the precondition version: (,3.17.0) excludes 3.17.0 itself
        List<SourceFile> sources = MavenParser.builder().build().parse(
                new InMemoryExecutionContext(Throwable::printStackTrace),
                """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>test</artifactId>
                            <version>1.0</version>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>io.quarkus.platform</groupId>
                                        <artifactId>quarkus-bom</artifactId>
                                        <version>3.17.0</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                        </project>
                        """).toList();

        RecipeRun result = recipe.run(
                new org.openrewrite.internal.InMemoryLargeSourceSet(sources),
                new InMemoryExecutionContext(Throwable::printStackTrace));

        assertThat(result.getChangeset().getAllResults())
                .as("MigrateToQuarkus_v3_17_0 should not modify a project already on quarkus-bom 3.17.0")
                .isEmpty();
    }
}

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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Verifies that a {@code ModuleHasDependency} precondition with a version range
 * prevents recipes from running when the module's dependency version is at or above
 * the target. This is the same pattern used by the generated Quarkus aggregation recipes.
 */
class QuarkusPreconditionTest implements RewriteTest {

    private static final String GROUP = "jakarta.data";
    private static final String ARTIFACT = "jakarta.data-api";

    private static final String POM_WITH_PROPERTY = """
        <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>test</artifactId>
            <version>1.0</version>
            <properties>
                <migrated>false</migrated>
            </properties>
            <dependencies>
                <dependency>
                    <groupId>jakarta.data</groupId>
                    <artifactId>jakarta.data-api</artifactId>
                    <version>1.0.1</version>
                </dependency>
            </dependencies>
        </project>
        """;

    private static final String POM_WITH_PROPERTY_MIGRATED = """
        <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>test</artifactId>
            <version>1.0</version>
            <properties>
                <migrated>true</migrated>
            </properties>
            <dependencies>
                <dependency>
                    <groupId>jakarta.data</groupId>
                    <artifactId>jakarta.data-api</artifactId>
                    <version>1.0.1</version>
                </dependency>
            </dependencies>
        </project>
        """;

    private static String migrationRecipeYaml(String targetVersion) {
        return """
            type: specs.openrewrite.org/v1beta/recipe
            name: test.MigrateToVersion_%s
            displayName: Test migrate to %s
            description: Test.
            preconditions:
              - org.openrewrite.java.dependencies.search.ModuleHasDependency:
                  groupIdPattern: %s
                  artifactIdPattern: %s
                  version: (,%s)
            recipeList:
              - org.openrewrite.maven.ChangePropertyValue:
                  key: migrated
                  newValue: "true"
            """.formatted(
            targetVersion.replace(".", "_"),
            targetVersion,
            GROUP, ARTIFACT,
            targetVersion);
    }

    private static String migrationRecipeName(String targetVersion) {
        return "test.MigrateToVersion_" + targetVersion.replace(".", "_");
    }

    @Nested
    class YamlPrecondition {
        @Test
        void runsWhenVersionBelowTarget() {
            // 1.0.1 IS less than 1.1.0, so recipe should run and change the property
            String version = "1.1.0";
            rewriteRun(
                spec -> spec.recipeFromYaml(migrationRecipeYaml(version), migrationRecipeName(version)),
                mavenProject("project",
                    pomXml(POM_WITH_PROPERTY, POM_WITH_PROPERTY_MIGRATED))
            );
        }

        @Test
        void doesNotRunWhenVersionAtTarget() {
            // 1.0.1 is NOT less than 1.0.1, so recipe should not run
            String version = "1.0.1";
            rewriteRun(
                spec -> spec.recipeFromYaml(migrationRecipeYaml(version), migrationRecipeName(version)),
                mavenProject("project",
                    pomXml(POM_WITH_PROPERTY))
            );
        }

        @Test
        void doesNotRunWhenVersionAboveTarget() {
            // 1.0.1 is NOT less than 1.0.0, so recipe should not run
            String version = "1.0.0";
            rewriteRun(
                spec -> spec.recipeFromYaml(migrationRecipeYaml(version), migrationRecipeName(version)),
                mavenProject("project",
                    pomXml(POM_WITH_PROPERTY))
            );
        }
    }
}

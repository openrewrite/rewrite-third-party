/*
 * Copyright 2025 the original author or authors.
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.openrewrite.recipe.quarkus.internal.AggregateQuarkusUpdates.*;

class AggregateQuarkusUpdatesTest {

    private static final AggregateQuarkusUpdates.Version v1_2 = new AggregateQuarkusUpdates.Version(1, 2, 0);
    private static final AggregateQuarkusUpdates.Version v1_2_3 = new AggregateQuarkusUpdates.Version(1, 2, 3);
    private static final AggregateQuarkusUpdates.Version v1_3 = new AggregateQuarkusUpdates.Version(1, 3, 0);
    private static final AggregateQuarkusUpdates.Version v1_3_2 = new AggregateQuarkusUpdates.Version(1, 3, 2);
    private static final AggregateQuarkusUpdates.Version v1_5_5 = new AggregateQuarkusUpdates.Version(1, 5, 5);

    private static final Path RECIPES_PATH = Path.of("quarkus-updates/recipes/src/main/resources/quarkus-updates");

    @Nested
    class ExtractRecipeNames {
        @Test
        void quarkus39() {
            Path quarkus39 = RECIPES_PATH.resolve("core/3.9.alpha1.yaml");
            assumeTrue(Files.isReadable(quarkus39));
            assertThat(extractRecipeNames(quarkus39))
              .containsExactlyInAnyOrder(
                "io.quarkus.updates.core.quarkus39.RemovePanacheAnnotationProcessor",
                "io.quarkus.updates.core.quarkus39.SyncHibernateJpaModelgenVersionWithBOM",
                "io.quarkus.updates.core.quarkus39.Relocations",
                "io.quarkus.updates.core.quarkus39.UpdateConfigRoots",
                "io.quarkus.updates.core.quarkus39.GraalSDK"
              );
        }

        @Test
        void minoSkipsUpdateProperties() {
            Path mino38Recipe = RECIPES_PATH.resolve("io.quarkiverse.minio/quarkus-minio/3.8.yaml");
            assumeTrue(Files.isReadable(mino38Recipe));
            assertThat(extractRecipeNames(mino38Recipe)).containsExactly("io.quarkus.updates.minio.minio38.UpdateAll");
        }
    }

    @Test
    void readRecipesFromCamelQuarkusModule() throws Exception {
        assumeTrue(Files.isDirectory(RECIPES_PATH));
        AggregateQuarkusUpdates.Extension camelQuarkus = new AggregateQuarkusUpdates.Extension("org.apache.camel.quarkus", "camel-quarkus");
        Map<AggregateQuarkusUpdates.Version, VersionRecipes> recipes = recipesDefinedInQuarkusRepo(RECIPES_PATH);
        Map.of(
          new AggregateQuarkusUpdates.Version(3, 0, 0), List.of("io.quarkus.updates.camel.camel40.CamelQuarkusMigrationRecipe", "org.openrewrite.java.camel.migrate.removedExtensions"),
          new AggregateQuarkusUpdates.Version(3, 8, 0), List.of("io.quarkus.updates.camel.camel44.CamelQuarkusMigrationRecipe"),
          new AggregateQuarkusUpdates.Version(3, 15, 0), List.of("io.quarkus.updates.camel.camel47.CamelQuarkusMigrationRecipe"),
          new AggregateQuarkusUpdates.Version(3, 17, 0), List.of("io.quarkus.updates.camel.camel49.CamelQuarkusMigrationRecipe"),
          new AggregateQuarkusUpdates.Version(3, 18, 0), List.of("io.quarkus.updates.camel.camel410.CamelQuarkusMigrationRecipe"),
          new AggregateQuarkusUpdates.Version(3, 20, 1), List.of("io.quarkus.updates.camel.camel410_4.CamelQuarkusMigrationRecipe"),
          new AggregateQuarkusUpdates.Version(3, 22, 0), List.of("io.quarkus.updates.camel.camel411.CamelQuarkusMigrationRecipe"),
          new AggregateQuarkusUpdates.Version(3, 24, 0), List.of("io.quarkus.updates.camel.camel412.CamelQuarkusMigrationRecipe"),
          new AggregateQuarkusUpdates.Version(3, 25, 0), List.of("io.quarkus.updates.camel.camel413.CamelQuarkusMigrationRecipe"),
          new AggregateQuarkusUpdates.Version(3, 26, 0), List.of("io.quarkus.updates.camel.camel414.CamelQuarkusMigrationRecipe")
        ).forEach((version, camelRecipes) -> {
            assertThat(recipes.get(version).extensions()).containsEntry(camelQuarkus, camelRecipes);
            assertThat(recipes.get(version).core()).doesNotContainAnyElementsOf(camelRecipes);
        });
    }

    @Test
    void keepExtensionRecipesApartFromCore() throws Exception {
        assumeTrue(Files.isDirectory(RECIPES_PATH));
        VersionRecipes v3_39 = recipesDefinedInQuarkusRepo(RECIPES_PATH).get(new AggregateQuarkusUpdates.Version(3, 39, 0));
        assertThat(v3_39.core()).noneMatch(r -> r.startsWith("io.quarkus.updates.cxf.") || r.startsWith("io.quarkus.updates.camel."));
        assertThat(v3_39.extensions().get(new AggregateQuarkusUpdates.Extension("io.quarkiverse.cxf", "quarkus-cxf")))
          .contains("io.quarkus.updates.cxf.cxf339.AddQuarkusJacksonIfUsed");
    }

    @Nested
    class CreateRecipe {
        private static VersionRecipes core(String... recipes) {
            VersionRecipes versionRecipes = new VersionRecipes();
            versionRecipes.add(null, List.of(recipes));
            return versionRecipes;
        }

        @Test
        void withPredecessor() {
            String recipe = AggregateQuarkusUpdates.createRecipe(v1_2_3, v1_2, core("org.test.r1", "org.test.r2", "org.test.r3"));
            assertThat(recipe)
              //language=YAML
              .isEqualTo("""
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.quarkus.MigrateToQuarkus_v1_2_3
                displayName: Quarkus Updates Aggregate 1.2.3
                description: Quarkus update recipes to upgrade your application to 1.2.3.
                preconditions:
                  - org.openrewrite.java.dependencies.search.RepositoryHasDependency:
                      groupIdPattern: io.quarkus
                      artifactIdPattern: quarkus-core
                      version: (,1.2.3)
                recipeList:
                  - org.openrewrite.java.dependencies.UpgradeDependencyVersion:
                      groupId: io.quarkus.platform
                      artifactId: quarkus-bom
                      newVersion: 1.2.x
                  - org.openrewrite.java.dependencies.UpgradeDependencyVersion:
                      groupId: io.quarkus
                      artifactId: quarkus-bom
                      newVersion: 1.2.x
                  - org.openrewrite.maven.UpgradePluginVersion:
                      groupId: io.quarkus*
                      artifactId: quarkus-maven-plugin
                      newVersion: 1.2.x
                  - org.openrewrite.gradle.plugins.UpgradePluginVersion:
                      pluginIdPattern: io.quarkus
                      newVersion: 1.2.x
                  - org.openrewrite.quarkus.UpgradeQuarkusGradleProperties:
                      newVersion: 1.2.x
                  - org.openrewrite.quarkus.QuarkusUpdates_v1_2_3

                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.quarkus.QuarkusUpdates_v1_2_3
                displayName: Quarkus update recipes up to 1.2.3
                description: The Quarkus update recipes of 1.2.3 and the versions before it, without upgrading the Quarkus platform itself.
                preconditions:
                  - org.openrewrite.java.dependencies.search.RepositoryHasDependency:
                      groupIdPattern: io.quarkus
                      artifactIdPattern: quarkus-core
                      version: (,1.2.3)
                recipeList:
                  - org.openrewrite.quarkus.QuarkusUpdates_v1_2_0
                  - org.test.r1
                  - org.test.r2
                  - org.test.r3

                """);
        }

        @Test
        void noPredecessor() {
            String recipe = AggregateQuarkusUpdates.createRecipe(v1_2_3, null, core("org.test.r1", "org.test.r2", "org.test.r3"));
            assertThat(recipe)
              //language=YAML
              .isEqualTo("""
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.quarkus.MigrateToQuarkus_v1_2_3
                displayName: Quarkus Updates Aggregate 1.2.3
                description: Quarkus update recipes to upgrade your application to 1.2.3.
                preconditions:
                  - org.openrewrite.java.dependencies.search.RepositoryHasDependency:
                      groupIdPattern: io.quarkus
                      artifactIdPattern: quarkus-core
                      version: (,1.2.3)
                recipeList:
                  - org.openrewrite.java.dependencies.UpgradeDependencyVersion:
                      groupId: io.quarkus.platform
                      artifactId: quarkus-bom
                      newVersion: 1.2.x
                  - org.openrewrite.java.dependencies.UpgradeDependencyVersion:
                      groupId: io.quarkus
                      artifactId: quarkus-bom
                      newVersion: 1.2.x
                  - org.openrewrite.maven.UpgradePluginVersion:
                      groupId: io.quarkus*
                      artifactId: quarkus-maven-plugin
                      newVersion: 1.2.x
                  - org.openrewrite.gradle.plugins.UpgradePluginVersion:
                      pluginIdPattern: io.quarkus
                      newVersion: 1.2.x
                  - org.openrewrite.quarkus.UpgradeQuarkusGradleProperties:
                      newVersion: 1.2.x
                  - org.openrewrite.quarkus.QuarkusUpdates_v1_2_3

                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.quarkus.QuarkusUpdates_v1_2_3
                displayName: Quarkus update recipes up to 1.2.3
                description: The Quarkus update recipes of 1.2.3 and the versions before it, without upgrading the Quarkus platform itself.
                preconditions:
                  - org.openrewrite.java.dependencies.search.RepositoryHasDependency:
                      groupIdPattern: io.quarkus
                      artifactIdPattern: quarkus-core
                      version: (,1.2.3)
                recipeList:
                  - org.test.r1
                  - org.test.r2
                  - org.test.r3

                """);
        }

        @Test
        void extensionRecipesOnlyWhereTheExtensionIsUsed() {
            VersionRecipes recipes = core("org.test.r1");
            recipes.add(new AggregateQuarkusUpdates.Extension("io.quarkiverse.cxf", "quarkus-cxf"), List.of("org.test.cxf1", "org.test.cxf2"));
            String recipe = AggregateQuarkusUpdates.createRecipe(v1_2_3, v1_2, recipes);
            assertThat(recipe)
              //language=YAML
              .isEqualTo("""
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.quarkus.MigrateToQuarkus_v1_2_3
                displayName: Quarkus Updates Aggregate 1.2.3
                description: Quarkus update recipes to upgrade your application to 1.2.3.
                preconditions:
                  - org.openrewrite.java.dependencies.search.RepositoryHasDependency:
                      groupIdPattern: io.quarkus
                      artifactIdPattern: quarkus-core
                      version: (,1.2.3)
                recipeList:
                  - org.openrewrite.java.dependencies.UpgradeDependencyVersion:
                      groupId: io.quarkus.platform
                      artifactId: quarkus-bom
                      newVersion: 1.2.x
                  - org.openrewrite.java.dependencies.UpgradeDependencyVersion:
                      groupId: io.quarkus
                      artifactId: quarkus-bom
                      newVersion: 1.2.x
                  - org.openrewrite.maven.UpgradePluginVersion:
                      groupId: io.quarkus*
                      artifactId: quarkus-maven-plugin
                      newVersion: 1.2.x
                  - org.openrewrite.gradle.plugins.UpgradePluginVersion:
                      pluginIdPattern: io.quarkus
                      newVersion: 1.2.x
                  - org.openrewrite.quarkus.UpgradeQuarkusGradleProperties:
                      newVersion: 1.2.x
                  - org.openrewrite.quarkus.QuarkusUpdates_v1_2_3

                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.quarkus.QuarkusUpdates_v1_2_3
                displayName: Quarkus update recipes up to 1.2.3
                description: The Quarkus update recipes of 1.2.3 and the versions before it, without upgrading the Quarkus platform itself.
                preconditions:
                  - org.openrewrite.java.dependencies.search.RepositoryHasDependency:
                      groupIdPattern: io.quarkus
                      artifactIdPattern: quarkus-core
                      version: (,1.2.3)
                recipeList:
                  - org.openrewrite.quarkus.QuarkusUpdates_v1_2_0
                  - org.test.r1
                  - org.openrewrite.quarkus.QuarkusUpdates_v1_2_3_quarkus_cxf

                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.quarkus.QuarkusUpdates_v1_2_3_quarkus_cxf
                displayName: Quarkus update recipes of 1.2.3 for `quarkus-cxf`
                description: Update recipes for `io.quarkiverse.cxf:quarkus-cxf` 1.2.3, applied only where `io.quarkiverse.cxf:quarkus-cxf*` is used, as `quarkus update` does.
                preconditions:
                  - org.openrewrite.java.dependencies.search.RepositoryHasDependency:
                      groupIdPattern: io.quarkiverse.cxf
                      artifactIdPattern: quarkus-cxf*
                      version: (,1.2.3)
                recipeList:
                  - org.test.cxf1
                  - org.test.cxf2

                """);
        }
    }

    @Test
    void nameGeneration() {
        assertThat(recipeNameFor(v1_2)).isEqualTo("org.openrewrite.quarkus.MigrateToQuarkus_v1_2_0");
        assertThat(recipeNameFor(v1_2_3)).isEqualTo("org.openrewrite.quarkus.MigrateToQuarkus_v1_2_3");
        assertThat(updatesRecipeNameFor(v1_2_3)).isEqualTo("org.openrewrite.quarkus.QuarkusUpdates_v1_2_3");
        assertThat(updatesRecipeNameFor(v1_2_3, new AggregateQuarkusUpdates.Extension("org.apache.camel.quarkus", "camel-quarkus")))
          .isEqualTo("org.openrewrite.quarkus.QuarkusUpdates_v1_2_3_camel_quarkus");
    }

    @Nested
    class Version {
        @Test
        void parserVersion() {
            assertThat(AggregateQuarkusUpdates.Version.parse("1.2.3.yaml")).isEqualTo(v1_2_3);
            assertThat(AggregateQuarkusUpdates.Version.parse("1.5.5.yaml")).isEqualTo(v1_5_5);
        }

        @Test
        void foldVersions() {
            assertThat(AggregateQuarkusUpdates.Version.parse("1.2.alpha3.yaml")).isEqualTo(v1_2);
            assertThat(AggregateQuarkusUpdates.Version.parse("1.2.alpha.yaml")).isEqualTo(v1_2);
            assertThat(AggregateQuarkusUpdates.Version.parse("1.2.yaml")).isEqualTo(v1_2);
            assertThat(AggregateQuarkusUpdates.Version.parse("1.2.0.yaml")).isEqualTo(v1_2);
            assertThat(AggregateQuarkusUpdates.Version.parse("1.2.1.yaml")).isNotEqualTo(v1_2);
        }

        @Test
        void sortVersions() {
            assertThat(List.of(v1_3_2, v1_2, v1_3, v1_2_3).stream().sorted())
              .containsExactly(v1_2, v1_2_3, v1_3, v1_3_2);
        }
    }

}

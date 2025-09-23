package org.openrewrite.recipe.quarkus.internal;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.recipe.quarkus.internal.AggregateQuarkusUpdates.*;

class AggregateQuarkusUpdatesTest {

    private final AggregateQuarkusUpdates.Version v1_2 = new AggregateQuarkusUpdates.Version(1, 2, 0);
    private final AggregateQuarkusUpdates.Version v1_2_3 = new AggregateQuarkusUpdates.Version(1, 2, 3);
    private final AggregateQuarkusUpdates.Version v1_3 = new AggregateQuarkusUpdates.Version(1, 3, 0);
    private final AggregateQuarkusUpdates.Version v1_3_2 = new AggregateQuarkusUpdates.Version(1, 3, 2);
    private final AggregateQuarkusUpdates.Version v1_5_5 = new AggregateQuarkusUpdates.Version(1, 5, 5);


    @Nested
    @Disabled("Only possible to execute if quarkus submodule is present")
    class ExtractRecipeNames {
        private final Path quarkus39 = Path.of("quarkus-updates/recipes/src/main/resources/quarkus-updates/core/3.9.alpha1.yaml");
        private final Path mino38Recipe = Path.of("quarkus-updates/recipes/src/main/resources/quarkus-updates/io.quarkiverse.minio/quarkus-minio/3.8.yaml");

        @Test
        void quarkus39() {
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
            assertThat(extractRecipeNames(mino38Recipe)).containsExactly("io.quarkus.updates.minio.minio38.UpdateAll");
        }
    }

    @Test
    void readRecipesFromCamlQuarkusModule() throws IOException {
        Path camelQuarkusDir = Path.of("quarkus-updates/recipes/src/main/resources/quarkus-updates/org.apache.camel.quarkus");
        assertThat(recipesDefinedInQuarkusRepo(camelQuarkusDir))
          .containsExactlyInAnyOrderEntriesOf(
            Map.of(
              new AggregateQuarkusUpdates.Version(3, 0, 0), List.of("io.quarkus.updates.camel.camel40.CamelQuarkusMigrationRecipe", "org.openrewrite.java.camel.migrate.removedExtensions"),
              new AggregateQuarkusUpdates.Version(3, 8, 0), List.of("io.quarkus.updates.camel.camel44.CamelQuarkusMigrationRecipe"),
              new AggregateQuarkusUpdates.Version(3, 15, 0), List.of("io.quarkus.updates.camel.camel47.CamelQuarkusMigrationRecipe"),
              new AggregateQuarkusUpdates.Version(3, 17, 0), List.of("io.quarkus.updates.camel.camel49.CamelQuarkusMigrationRecipe"),
              new AggregateQuarkusUpdates.Version(3, 18, 0), List.of("io.quarkus.updates.camel.camel410.CamelQuarkusMigrationRecipe"),
              new AggregateQuarkusUpdates.Version(3, 20, 1), List.of("io.quarkus.updates.camel.camel410.CamelQuarkusMigrationRecipe"),
              new AggregateQuarkusUpdates.Version(3, 22, 0), List.of("io.quarkus.updates.camel.camel411.CamelQuarkusMigrationRecipe"),
              new AggregateQuarkusUpdates.Version(3, 24, 0), List.of("io.quarkus.updates.camel.camel412.CamelQuarkusMigrationRecipe"),
              new AggregateQuarkusUpdates.Version(3, 25, 0), List.of("io.quarkus.updates.camel.camel413.CamelQuarkusMigrationRecipe"),
              new AggregateQuarkusUpdates.Version(3, 26, 0), List.of("io.quarkus.updates.camel.camel414.CamelQuarkusMigrationRecipe")));
    }

    @Nested
    class CreateRecipe {
        @Test
        void withPredecessor() {
            String recipe = AggregateQuarkusUpdates.createRecipe(v1_2_3, v1_2, List.of("org.test.r1", "org.test.r2", "org.test.r3"));
            assertThat(recipe)
              //language=YAML
              .isEqualTo("""
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.quarkus.MigrateToQuarkus_v1_2_3
                displayName: Quarkus Updates Aggregate 1.2.3
                description: Quarkus update recipes to upgrade your application to 1.2.3.
                recipeList:
                  - org.openrewrite.quarkus.MigrateToQuarkus_v1_2_0
                  - org.test.r1
                  - org.test.r2
                  - org.test.r3

                """);
        }

        @Test
        void noPredecessor() {
            String recipe = AggregateQuarkusUpdates.createRecipe(v1_2_3, null, List.of("org.test.r1", "org.test.r2", "org.test.r3"));
            assertThat(recipe)
              //language=YAML
              .isEqualTo("""
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: org.openrewrite.quarkus.MigrateToQuarkus_v1_2_3
                displayName: Quarkus Updates Aggregate 1.2.3
                description: Quarkus update recipes to upgrade your application to 1.2.3.
                recipeList:
                  - org.test.r1
                  - org.test.r2
                  - org.test.r3

                """);
        }
    }

    @Test
    void nameGeneration() {
        assertThat(recipeNameFor(v1_2)).isEqualTo("org.openrewrite.quarkus.MigrateToQuarkus_v1_2_0");
        assertThat(recipeNameFor(v1_2_3)).isEqualTo("org.openrewrite.quarkus.MigrateToQuarkus_v1_2_3");
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

package org.openrewrite.recipe.quarkus.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AggregateQuarkusUpdates {

    private static final Path input = Path.of("quarkus-updates/recipes/src/main/resources/quarkus-updates/core");
    private static final Path output = Path.of("src/main/resources/META-INF/rewrite/quarkus-consolidated.yml");

    public static void main(String[] args) throws IOException {
        System.out.printf("Starting aggregation of Quarkus update recipes from %s to %s%n", input.toAbsolutePath(), output.toAbsolutePath());

        if (!input.toFile().exists()) {
            throw new IllegalStateException("quarkusio/quarkus-updates.git is not available under 'quarkus-updates', lease clone here temporarily");
        }

        System.out.printf("Reading recipes from %s%n", input.toAbsolutePath());
        SortedMap<Version, Set<String>> sortedByVersion = recipesDefinedInQuarkusRepo();

        System.out.printf(
          "Found %s different versions and %s recipes in total%n",
          sortedByVersion.size(),
          sortedByVersion.values().stream().mapToInt(Set::size).sum());

        StringBuilder recipeYml = new StringBuilder();
        Version prior = null;
        for (Map.Entry<Version, Set<String>> entry : sortedByVersion.entrySet()) {
            Version current = entry.getKey();
            Set<String> recipes = entry.getValue();
            System.out.printf("\t%s has %d recipes%n", current, recipes.size());
            recipeYml.append(createRecipe(current, prior, recipes));
            prior = current;
        }

        Files.writeString(output, recipeYml);

        System.out.printf("Wrote aggregating recipes to %s", output.toAbsolutePath());
    }

    private static SortedMap<Version, Set<String>> recipesDefinedInQuarkusRepo() {
        try (var children = Files.list(input)) {
            return children
              .collect(Collectors.toMap(
                p -> Version.parse(p.getFileName().toString()),
                AggregateQuarkusUpdates::extractRecipeVersions,
                (a, b) -> {
                    a.addAll(b);
                    return a;
                },
                java.util.TreeMap::new));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static String recipeNameFor(Version version) {
        return "org.openrewrite.quarkus.Quarkus_v%s_%s_%s".formatted(version.major, version.minor, version.patch);
    }

    static String createRecipe(Version version, @Nullable Version priorVersion, Set<String> recipeList) {
        if (recipeList.isEmpty()) {
            return "";
        }

        return
          // language=YAML
          """
            ---
            type: specs.openrewrite.org/v1beta/recipe
            name: %s
            displayName: Quarkus Updates Aggregate %s
            description: Quarkus update recipes to upgrade your application to %s.
            recipeList: %s%s
            """.formatted(
            recipeNameFor(version),
            version,
            version,
            priorVersion != null ? "\n\t- " + recipeNameFor(priorVersion) : "",
            recipeList.stream().collect(Collectors.joining("\n\t- ", "\n\t- ", "\n")));
    }

    /// Parse the defined recipe names from a given file using `type: specs.openrewrite.org/v1beta/recipe\nname: ([\.\w]*)``
    static Set<String> extractRecipeVersions(Path file) {
        Pattern compile = Pattern.compile("type: specs.openrewrite.org/v1beta/recipe\\nname: ([\\.\\w]*)");
        try {
            return compile.matcher(Files.readString(file))
              .results()
              .map(r -> r.group(1))
              .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    record Version(int major, int minor, @Nullable String patch) implements Comparable<Version> {
        /**
         * The recipe name of a Quarkus update recipe contains the target version.
         */
        public static Version parse(String version) {
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            ;
            String maybeMinorVersion = Objects.equals(parts[2], "yaml") ? null : parts[2];
            return new Version(major, minor, maybeMinorVersion);
        }

        @Override
        public int compareTo(Version other) {
            if (this.major != other.major) {
                return Integer.compare(this.major, other.major);
            }
            if (this.minor != other.minor) {
                return Integer.compare(this.minor, other.minor);
            }

            if (this.patch == null && other.patch == null) {
                return 0;
            }
            if (this.patch == null) {
                return -1;
            }
            if (other.patch == null) {
                return 1;
            }

            return CharSequence.compare(this.patch, other.patch);
        }

        @Override
        public @NotNull String toString() {
            return patch != null ? major + "." + minor + "." + patch : major + "." + minor;
        }
    }
}

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every recipe in {@code quarkus-consolidated.yml} has a
 * {@code ModuleHasDependency} precondition checking for
 * {@code io.quarkus.platform:quarkus-bom} with an exclusive upper-bound
 * version range, so migrations only run from the user's current version onwards.
 */
class QuarkusPreconditionTest {

    private static final Path CONSOLIDATED_YML =
            Path.of("src/main/resources/META-INF/rewrite/quarkus-consolidated.yml");

    @Test
    void allRecipesHaveModuleHasDependencyPrecondition() throws IOException {
        assertThat(CONSOLIDATED_YML).exists();
        String content = Files.readString(CONSOLIDATED_YML);

        // Find all recipe blocks: each starts with "name: org.openrewrite.quarkus.MigrateToQuarkus_vX_Y_Z"
        Pattern recipePattern = Pattern.compile(
                "name: (org\\.openrewrite\\.quarkus\\.MigrateToQuarkus_v(\\d+_\\d+_\\d+))");
        Matcher matcher = recipePattern.matcher(content);

        int recipeCount = 0;
        while (matcher.find()) {
            String recipeName = matcher.group(1);
            String expectedVersion = matcher.group(2).replace("_", ".");

            // Find the block for this recipe (from this name: to the next --- or end of file)
            int blockStart = matcher.start();
            int blockEnd = content.indexOf("\n---", blockStart);
            if (blockEnd == -1) {
                blockEnd = content.length();
            }
            String block = content.substring(blockStart, blockEnd);

            assertThat(block)
                    .as("Recipe %s should have ModuleHasDependency precondition", recipeName)
                    .contains("org.openrewrite.java.dependencies.search.ModuleHasDependency:");
            assertThat(block)
                    .as("Recipe %s should check io.quarkus.platform group", recipeName)
                    .contains("groupIdPattern: io.quarkus.platform");
            assertThat(block)
                    .as("Recipe %s should check quarkus-bom artifact", recipeName)
                    .contains("artifactIdPattern: quarkus-bom");
            assertThat(block)
                    .as("Recipe %s should have version range (,%s)", recipeName, expectedVersion)
                    .contains("version: (," + expectedVersion + ")");

            recipeCount++;
        }

        assertThat(recipeCount).as("Should have found multiple recipes in consolidated YAML").isGreaterThan(1);
    }
}

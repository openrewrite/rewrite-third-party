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
package org.openrewrite.recipe.picnic.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratePicnicAggregationTest {

    private static final Path PICNIC_YML = Path.of("src/main/resources/META-INF/rewrite/picnic.yml");

    @Test
    void picnicYmlMatchesPicnicJarContents() throws Exception {
        List<String> recipes = GeneratePicnicAggregation.findRulesRecipesOnClasspath();
        assertThat(recipes)
          .as("Expected to find Picnic *RulesRecipes classes on the test classpath")
          .isNotEmpty();

        String expected = GeneratePicnicAggregation.renderYaml(recipes);
        String actual = Files.readString(PICNIC_YML);
        assertThat(actual)
          .as("%s is out of sync with the Picnic error-prone-contrib jar; run `./gradlew generatePicnicAggregation` to regenerate.", PICNIC_YML)
          .isEqualTo(expected);
    }
}

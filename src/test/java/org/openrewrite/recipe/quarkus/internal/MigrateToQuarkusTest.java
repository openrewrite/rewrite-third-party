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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RewriteTest;

class MigrateToQuarkusTest implements RewriteTest {

    @Disabled("AWS defines a broken method matcher, see https://github.com/aws/aws-sdk-java-v2/pull/6438")
    @Test
    void loadRecipe() {
        rewriteRun(
          // Merely load the recipe to trigger validation logic
          spec -> spec.recipeFromResources("org.openrewrite.quarkus.MigrateToQuarkus_v3_0")
        );
    }
}

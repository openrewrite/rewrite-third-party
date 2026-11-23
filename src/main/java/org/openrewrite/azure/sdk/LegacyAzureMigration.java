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
package org.openrewrite.azure.sdk;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;

import java.util.Arrays;
import java.util.List;

/**
 * The Java edits of {@code org.openrewrite.azure.sdk.MigrateAzureSdkLegacyToCurrent}, in its order, for recipes that
 * need to know what the migration will make of a source before it runs.
 */
final class LegacyAzureMigration {

    private LegacyAzureMigration() {
    }

    static List<Recipe> javaRecipes() {
        return Arrays.asList(
                new MigrateAzureStorageBlob(),
                new ChangeLegacyAzureTypes()
        );
    }

    static Tree simulate(Tree source, ExecutionContext ctx) {
        Tree t = source;
        for (Recipe recipe : javaRecipes()) {
            Tree after = recipe.getVisitor().visit(t, ctx);
            if (after != null) {
                t = after;
            }
        }
        return t;
    }
}

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
package org.openrewrite.aws.sdk.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class AwsSdkV1Remnants extends DataTable<AwsSdkV1Remnants.Row> {

    public static final String PREREQUISITE = "prerequisite";
    public static final String UNMIGRATABLE = "unmigratable";
    public static final String MISSED = "missed";

    /**
     * Markers whose message starts with this prefix denote a site that a recipe deliberately left on v1, so finders
     * classify it as {@link #UNMIGRATABLE} rather than {@link #MISSED}.
     */
    public static final String UNMIGRATABLE_MARKER_PREFIX = "AWS SDK v1 remains";

    public AwsSdkV1Remnants(Recipe recipe) {
        super(recipe,
                "AWS SDK v1 remnants",
                "AWS SDK for Java v1 types, dependencies and references that remain after the migration, and why.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The file containing the remnant.")
        String sourcePath;

        @Column(displayName = "Kind",
                description = "`type`, `dependency`, `text` or `migration-comment` (a TODO left by AWS's v2-migration).")
        String kind;

        @Column(displayName = "Item",
                description = "The v1 type, the dependency coordinates, or the matched text.")
        String item;

        @Column(displayName = "Reason",
                description = "`prerequisite` (module skipped until a prerequisite migration runs), " +
                        "`unmigratable` (deliberately left and marked in a migrated module) or `missed` (anything else).")
        String reason;

        @Column(displayName = "Detail",
                description = "What needs to be done, or how the remnant is reached.")
        String detail;
    }
}

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
package org.openrewrite.mongodb.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class MongoDriverMigrationDecisions extends DataTable<MongoDriverMigrationDecisions.Row> {

    public MongoDriverMigrationDecisions(Recipe recipe) {
        super(recipe, "MongoDB driver migration decisions",
                "For each module that uses the legacy MongoDB API, whether it moved to the driver 5 API or kept the legacy " +
                "API, and for each repository whether its driver moved to 5, with the reasons for what stayed.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Module",
                description = "The project the decision is about, or empty for the repository's driver.")
        String module;

        @Column(displayName = "Outcome",
                description = "`Converted to the driver 5 API`, `Kept the legacy API`, `Added methods driver 5 requires`, " +
                              "`Driver moved to 5` or `Driver not moved to 5`.")
        String outcome;

        @Column(displayName = "Source path",
                description = "The file that holds the reason, when one does.")
        String sourcePath;

        @Column(displayName = "Reason",
                description = "Why the module kept the legacy API or the driver stayed, or what was done.")
        String reason;
    }
}

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

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

@JsonIgnoreType
public class S3ResidualChanges extends DataTable<S3ResidualChanges.Row> {

    public S3ResidualChanges(Recipe recipe) {
        super(recipe, "S3 residual migrations",
                "S3 v1 calls that AWS's v2 migration does not handle, how each was migrated, and any behaviour that differs or was fixed.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The source file containing the call.")
        String sourcePath;

        @Column(displayName = "Pattern",
                description = "The v1 pattern that was found.")
        String pattern;

        @Column(displayName = "Outcome",
                description = "`migrated`, `bug fixed` or `unmigrated` (marked in the source for manual migration).")
        String outcome;

        @Column(displayName = "Note",
                description = "What changed, or why the site was not migrated.")
        String note;
    }
}

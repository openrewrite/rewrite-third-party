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
package org.openrewrite.azure.sdk.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class LegacyAzureSdkRemnants extends DataTable<LegacyAzureSdkRemnants.Row> {

    public LegacyAzureSdkRemnants(Recipe recipe) {
        super(recipe,
                "Legacy Azure SDK remnants",
                "Uses of the legacy Azure SDK for Java and ADAL4J that the migration could not rewrite, and what to do with them.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The file containing the remnant.")
        String sourcePath;

        @Column(displayName = "Kind",
                description = "`call` (a method or constructor), `constant` (an enum constant or static field), `type` " +
                              "(a type without a current counterpart) or `supertype` (a legacy type a class extends or " +
                              "implements, whose source the migration leaves as it is).")
        String kind;

        @Column(displayName = "Item",
                description = "The legacy method, constant or type.")
        String item;

        @Column(displayName = "Detail",
                description = "Why it was left, or what replaces it in the current Azure SDK.")
        String detail;
    }
}

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

public class AwsSdkV2DependencyChanges extends DataTable<AwsSdkV2DependencyChanges.Row> {

    public AwsSdkV2DependencyChanges(Recipe recipe) {
        super(recipe,
                "AWS SDK v2 dependency changes",
                "Build and configuration changes made while moving a project to AWS SDK for Java v2.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The build or configuration file that changed.")
        String sourcePath;

        @Column(displayName = "Change",
                description = "`added`, `removed`, `removed-unused`, `bom-added`, `bom-aligned`, `version-removed`, " +
                        "`preserved-transitive` or `logging-key-removed`.")
        String change;

        @Column(displayName = "Item",
                description = "Dependency coordinates or property key.")
        String item;

        @Column(displayName = "Detail",
                description = "Why the change was made.")
        String detail;
    }
}

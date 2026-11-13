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
package org.openrewrite.aws.signing.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class AwsRequestSigningBehaviourChanges extends DataTable<AwsRequestSigningBehaviourChanges.Row> {

    public AwsRequestSigningBehaviourChanges(Recipe recipe) {
        super(recipe, "AWS request signing behaviour changes",
                "Every bug fix (F) and unavoidable deviation (V) between awslabs' `AWSRequestSigningApacheInterceptor` " +
                "and the generated AWS SDK v2 `AwsSigV4RequestInterceptor`, one row per migrated construction site.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The file containing the migrated construction site.")
        String sourcePath;

        @Column(displayName = "Site",
                description = "The construction site as it read before the migration.")
        String site;

        @Column(displayName = "Id",
                description = "`F1`..`F5` for fixed defects, `V1`..`V4` for unavoidable deviations.")
        String id;

        @Column(displayName = "Kind",
                description = "`fix` or `deviation`.")
        String kind;

        @Column(displayName = "Old behaviour",
                description = "What the awslabs interceptor did.")
        String oldBehaviour;

        @Column(displayName = "New behaviour",
                description = "What the generated interceptor does.")
        String newBehaviour;

        @Column(displayName = "User impact",
                description = "What a user of the signed client may observe.")
        String impact;
    }
}

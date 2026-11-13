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
package org.openrewrite.aws.sdk;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class AwsCredentialChainSwaps extends DataTable<AwsCredentialChainSwaps.Row> {

    public AwsCredentialChainSwaps(Recipe recipe) {
        super(recipe, "AWS credential chains swapped for the legacy v1 order",
                "Uses of AWS SDK v1's default credentials chain that were pointed at a generated `LegacyDefaultCredentialsChain` " +
                "instead of v2's `DefaultCredentialsProvider`, because the repository references the `AWS_ACCESS_KEY`/`AWS_SECRET_KEY` " +
                "environment variables that only v1 reads.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The file whose credentials chain was swapped.")
        String sourcePath;

        @Column(displayName = "Original expression",
                description = "The v1 expression that created or referenced the default chain.")
        String original;

        @Column(displayName = "Replacement",
                description = "The fully qualified name of the generated chain used instead.")
        String replacement;

        @Column(displayName = "Evidence",
                description = "The file that references the v1-only environment variable aliases.")
        String evidence;
    }
}

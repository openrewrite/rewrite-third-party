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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;

import java.util.Collection;
import java.util.Collections;

/**
 * v1's {@code TableUtils} has no v2 counterpart and v2-migration renames it to a type that does not exist, so its calls go to
 * a generated helper with the same methods built on the v2 client. Calls the DynamoDB mapper migration already moved are not
 * seen here.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateDynamoDbTableUtils extends ScanningRecipe<ProjectShims> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    private static final String V1_TABLE_UTILS = "com.amazonaws.services.dynamodbv2.util.TableUtils";

    @Getter
    final String displayName = "Migrate AWS SDK v1 DynamoDB `TableUtils`";

    @Getter
    final String description = "Runs before `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. Calls to v1's `TableUtils` " +
            "(`createTableIfNotExists`, `deleteTableIfExists`, `waitUntilExists`, `waitUntilActive`) and its " +
            "`TableNeverTransitionedToStateException` move to a generated `AwsTableUtils` with the same methods and polling on the v2 " +
            "`DynamoDbClient`.";

    @Override
    public ProjectShims getInitialValue(ExecutionContext ctx) {
        return new ProjectShims();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(ProjectShims shims) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    shims.recordExisting((SourceFile) tree);
                }
                if (tree instanceof J.CompilationUnit && new UsesType<>(V1_TABLE_UTILS, true).visit(tree, ctx) != tree) {
                    shims.require((J.CompilationUnit) tree);
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(ProjectShims shims, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        return shims.generate(AwsTableUtilsShim.SHIM, AwsTableUtilsShim::source, ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(ProjectShims shims) {
        return JavaOnly.check(new UsesType<>(V1_TABLE_UTILS, true), new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof J.CompilationUnit)) {
                    return tree;
                }
                String shim = shims.fqn((J.CompilationUnit) tree, AwsTableUtilsShim.SHIM);
                return shim == null ? tree : new ChangeType(V1_TABLE_UTILS, shim, true).getVisitor().visit(tree, ctx);
            }
        });
    }
}

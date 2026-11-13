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
package org.openrewrite.aws.sdk.search;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

public class FindAwsSdkV1MigrationTargets extends Recipe {

    @Getter
    final String displayName = "Find inputs to AWS's SDK v1 to v2 migration";

    @Getter
    final String description = "Marks Java sources that use `com.amazonaws` types, and the Maven and Gradle build files that declare " +
            "`com.amazonaws` artifacts the migration renames. Used as a precondition because AWS's recipes fail or half-migrate on other languages and " +
            "can throw on sources already on SDK v2.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> usesV1 = new UsesType<>("com.amazonaws..*", false);
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile sf = (SourceFile) tree;
                String path = sf.getSourcePath().toString().replace('\\', '/');
                if (tree instanceof J.CompilationUnit && path.endsWith(".java")) {
                    return usesV1.visit(tree, ctx);
                }
                // AWS's type changes otherwise make invisible edits to build files without SDK v1, which never stabilize
                return isBuildFile(path) && sf.printAll().contains("com.amazonaws") ? SearchResult.found(tree) : tree;
            }
        };
    }

    static boolean isBuildFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return "pom.xml".equals(name) || name.endsWith(".gradle") || name.endsWith(".gradle.kts") ||
               name.endsWith(".versions.toml");
    }
}

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

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

/**
 * Limits a visitor to Java compilation units. Kotlin sources are left to the Kotlin step of the migration, since these
 * visitors build Java templates and generate Java helpers.
 */
final class JavaOnly {

    private JavaOnly() {
    }

    static TreeVisitor<?, ExecutionContext> check(TreeVisitor<?, ExecutionContext> precondition,
                                                  TreeVisitor<?, ExecutionContext> visitor) {
        return Preconditions.check(Preconditions.and(javaSources(), precondition), visitor);
    }

    // Kotlin, JavaScript and Python trees are also JavaSourceFiles, so this checks for J.CompilationUnit
    static TreeVisitor<?, ExecutionContext> javaSources() {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                return tree instanceof J.CompilationUnit ? SearchResult.found(tree) : tree;
            }
        };
    }
}

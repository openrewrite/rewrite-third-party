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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.aws.sdk.AwsMigrationScope;
import org.openrewrite.marker.SearchResult;

import java.util.List;

/**
 * The precondition of a composition that migrates only some modules, matching the sources for which
 * {@code org.openrewrite.aws.sdk.GenerateAwsSdkV2Helpers} with the same options generates helpers.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class FindSourcesInAwsMigrationScope extends ScanningRecipe<AwsMigrationScope> {

    @Option(displayName = "Only modules depending on",
            description = "Finds only the sources of modules that depend on one of these libraries. " +
                          AwsMigrationScope.ENTRY_DESCRIPTION + " Every module is in scope when not set.",
            example = "*:spring-data-dynamodb using com.amazonaws.services.dynamodbv2..*",
            required = false)
    @Nullable
    List<String> onlyModulesDependingOn;

    @Option(displayName = "Except modules depending on",
            description = "Finds no sources of modules that depend on one of these libraries. " +
                          AwsMigrationScope.ENTRY_DESCRIPTION,
            example = "org.springframework.cloud:spring-cloud-*aws*",
            required = false)
    @Nullable
    List<String> exceptModulesDependingOn;

    String displayName = "Find sources in the scope of an AWS migration";

    String description = "Marks the sources and build files of the modules that depend on the given libraries.";

    @Override
    public Validated<Object> validate() {
        return super.validate()
                .and(AwsMigrationScope.validate("onlyModulesDependingOn", onlyModulesDependingOn))
                .and(AwsMigrationScope.validate("exceptModulesDependingOn", exceptModulesDependingOn));
    }

    @Override
    public AwsMigrationScope getInitialValue(ExecutionContext ctx) {
        return AwsMigrationScope.of(onlyModulesDependingOn, exceptModulesDependingOn);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(AwsMigrationScope scope) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    scope.scan((SourceFile) tree);
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(AwsMigrationScope scope) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile && scope.includes(((SourceFile) tree).getSourcePath())) {
                    return SearchResult.found(tree);
                }
                return tree;
            }
        };
    }
}

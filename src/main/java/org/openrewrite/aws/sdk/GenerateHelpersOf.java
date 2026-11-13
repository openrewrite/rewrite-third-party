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
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;

import java.util.Collection;
import java.util.List;

/**
 * Runs only the scan and the generation of a recipe that generates helpers, limited to a scope; the recipe itself runs in
 * the composition with {@code generateHelpers: false}, so that it makes its edits in their place and order.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class GenerateHelpersOf<T> extends ScanningRecipe<GenerateHelpersOf.Accumulator<T>> {

    ScanningRecipe<T> delegate;

    @Nullable
    List<String> onlyModulesDependingOn;

    @Nullable
    List<String> exceptModulesDependingOn;

    @Override
    public String getName() {
        return delegate.getName() + ".helpers";
    }

    @Override
    public String getDisplayName() {
        return "Generate the helpers of " + delegate.getDisplayName();
    }

    @Override
    public String getDescription() {
        return "Generates the helper classes that `" + delegate.getName() + "` uses, in the modules in scope.";
    }

    public static class Accumulator<T> {
        final AwsMigrationScope scope;
        final T delegate;

        Accumulator(AwsMigrationScope scope, T delegate) {
            this.scope = scope;
            this.delegate = delegate;
        }
    }

    @Override
    public Accumulator<T> getInitialValue(ExecutionContext ctx) {
        AwsMigrationScope scope = AwsMigrationScope.of(onlyModulesDependingOn, exceptModulesDependingOn);
        T acc = delegate.getInitialValue(ctx);
        if (!(acc instanceof AwsMigrationScope.Limitable)) {
            throw new IllegalStateException(delegate.getName() + " cannot limit where it generates helpers");
        }
        ((AwsMigrationScope.Limitable) acc).limitTo(scope);
        return new Accumulator<>(scope, acc);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator<T> acc) {
        TreeVisitor<?, ExecutionContext> scanner = delegate.getScanner(acc.delegate);
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    acc.scope.scan((SourceFile) tree);
                    if (scanner.isAcceptable((SourceFile) tree, ctx)) {
                        scanner.visit(tree, ctx, getCursor());
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator<T> acc, Collection<SourceFile> generatedInThisCycle,
                                                     ExecutionContext ctx) {
        return delegate.generate(acc.delegate, generatedInThisCycle, ctx);
    }
}

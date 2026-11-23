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
package org.openrewrite.azure.sdk;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.NameTree;
import org.openrewrite.staticanalysis.UnnecessaryCatch;

import java.util.Map;

@Value
@EqualsAndHashCode(callSuper = false)
public class ChangeLegacyAzureTypes extends Recipe {

    @Getter
    final String displayName = "Retype legacy Azure SDK types to their current counterparts";

    @Getter
    final String description = "Changes each legacy Azure SDK type that has a current counterpart, such as " +
                               "`CloudBlobContainer` to `BlobContainerClient`, in declarations, generics, casts and " +
                               "imports. Runs after the calls on those types have been rewritten, since the members " +
                               "of the two differ.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return sourceFile instanceof J.CompilationUnit;
            }

            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                Tree t = tree;
                for (Map.Entry<String, String> mapping : LegacyAzureTypes.mappings().entrySet()) {
                    if (t instanceof J.CompilationUnit &&
                        new UsesType<>(mapping.getKey(), true).visit(t, ctx) != t) {
                        t = new ChangeType(mapping.getKey(), mapping.getValue(), true).getVisitor().visit(t, ctx);
                    }
                }
                if (t != tree) {
                    // The current SDK throws no checked exceptions, so catches of the `URISyntaxException`,
                    // `InvalidKeyException` and `IOException` the legacy calls declared no longer compile. Only now,
                    // once `StorageException` is retyped to an unchecked exception, so that its catches stay.
                    t = new UnnecessaryCatch(false, false).getVisitor().visit(t, ctx);
                    t = new SingleAlternativeMultiCatch().visit(t, ctx);
                }
                return t;
            }
        };
    }

    /**
     * A multi-catch that {@code UnnecessaryCatch} left with one alternative is the plain catch the parser would make.
     */
    private static class SingleAlternativeMultiCatch extends JavaIsoVisitor<ExecutionContext> {
        @Override
        public J.Try.Catch visitCatch(J.Try.Catch _catch, ExecutionContext ctx) {
            J.Try.Catch c = super.visitCatch(_catch, ctx);
            J.VariableDeclarations parameter = c.getParameter().getTree();
            if (parameter.getTypeExpression() instanceof J.MultiCatch) {
                J.MultiCatch multiCatch = (J.MultiCatch) parameter.getTypeExpression();
                if (multiCatch.getAlternatives().size() == 1) {
                    NameTree alternative = multiCatch.getAlternatives().get(0);
                    parameter = parameter.withTypeExpression(alternative.withPrefix(multiCatch.getPrefix()));
                    if (alternative.getType() != null) {
                        parameter = parameter.withVariables(ListUtils.map(parameter.getVariables(), v ->
                                v.withType(alternative.getType()).withName(v.getName().withType(alternative.getType()))));
                    }
                    c = c.withParameter(c.getParameter().withTree(parameter));
                }
            }
            return c;
        }
    }
}

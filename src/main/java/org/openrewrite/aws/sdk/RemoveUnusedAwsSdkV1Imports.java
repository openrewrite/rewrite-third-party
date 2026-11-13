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

import lombok.Getter;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;

import java.util.HashSet;
import java.util.Set;

public class RemoveUnusedAwsSdkV1Imports extends Recipe {

    @Getter
    final String displayName = "Remove unused AWS SDK v1 imports";

    @Getter
    final String description = "Removes single-type imports of `com.amazonaws` SDK classes that the file never refers to. Such an " +
            "import is all that would keep the file on SDK v1 once the referencing code is migrated or was never there.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(new UsesType<>("com.amazonaws..*", true), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                // Compared by name rather than type, so that a file parsed without its classpath keeps every import it uses
                Set<String> referenced = new HashSet<>();
                new JavaIsoVisitor<Set<String>>() {
                    @Override
                    public J.Import visitImport(J.Import _import, Set<String> names) {
                        return _import;
                    }

                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, Set<String> names) {
                        names.add(identifier.getSimpleName());
                        return identifier;
                    }
                }.visit(cu, referenced);
                J.CompilationUnit c = cu.withImports(ListUtils.map(cu.getImports(), (i, imp) -> {
                    String type = imp.getTypeName();
                    return !imp.isStatic() && !"*".equals(imp.getQualid().getSimpleName()) && AwsSdkV2Coordinates.isV1SdkType(type) &&
                           !referenced.contains(imp.getQualid().getSimpleName()) ? null : imp;
                }));
                if (c != cu && !c.getImports().isEmpty() && !cu.getImports().isEmpty() &&
                    c.getImports().get(0) != cu.getImports().get(0)) {
                    // Keep the first import's position when it was the one removed
                    c = c.withImports(ListUtils.mapFirst(c.getImports(), imp -> imp.withPrefix(cu.getImports().get(0).getPrefix())));
                }
                return c;
            }
        });
    }
}

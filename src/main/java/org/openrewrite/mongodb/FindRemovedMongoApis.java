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
package org.openrewrite.mongodb;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindRemovedMongoApis extends Recipe {

    @Option(displayName = "Include replaceable uses",
            description = "Also mark the uses that `MigrateMongoJavaDriver3To5` replaces. By default only the uses that need " +
                          "a hand migration are marked.",
            required = false)
    @Nullable
    Boolean includeReplaceable;

    @Getter
    final String displayName = "Find MongoDB Java driver 3 API that driver 5 removed";

    @Getter
    final String description = "Marks each use of the public API of `mongo-java-driver` 3.12 that the driver 5 artifacts " +
            "(`bson`, `mongodb-driver-core`, `mongodb-driver-sync` and `mongodb-driver-legacy`) no longer have, such as " +
            "`DB.eval`, `DBCollection.group`, `Mongo.getAddress` and `com.mongodb.util.JSON`. These keep " +
            "`MigrateMongoJavaDriver3To5` from moving the driver to 5 until they are migrated by hand.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof J.CompilationUnit)) {
                    return tree;
                }
                Map<UUID, String> found = new HashMap<>();
                new RemovedApiFinder() {
                    @Override
                    void found(J t, String description, boolean fixable) {
                        if (!fixable || Boolean.TRUE.equals(includeReplaceable)) {
                            found.merge(t.getId(), "Removed in driver 5: " + description +
                                                   (fixable ? ", which MigrateMongoJavaDriver3To5 replaces" : ""),
                                    (a, b) -> a);
                        }
                    }
                }.visit(tree, ctx);
                if (found.isEmpty()) {
                    return tree;
                }
                return new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public @Nullable J postVisit(J t, ExecutionContext p) {
                        String description = found.get(t.getId());
                        return description == null ? t : SearchResult.found(t, description);
                    }
                }.visit(tree, ctx);
            }
        };
    }
}

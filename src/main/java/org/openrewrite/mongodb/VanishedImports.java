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

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.openrewrite.mongodb.MongoTypes.*;

/**
 * Removes the imports that nothing uses and that driver 5 cannot resolve: of a type or package that driver 5 removed,
 * and when the build moves to {@code mongodb-driver-sync}, of a type or package only {@code mongodb-driver-legacy}
 * has. Driver 3 compiled them, so they are easy to miss, and one of them is enough to fail the build.
 */
final class VanishedImports extends JavaIsoVisitor<ExecutionContext> {

    private static final Set<String> REMOVED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.mongodb.async", "com.mongodb.binding", "com.mongodb.connection.netty", "com.mongodb.diagnostics.logging",
            "com.mongodb.operation", "com.mongodb.util", "org.bson.util"));

    private static final Set<String> LEGACY_ARTIFACT_PACKAGES = new HashSet<>(Arrays.asList(
            "com.mongodb.gridfs", "com.mongodb.client.jndi"));

    private final boolean withoutLegacyArtifact;

    VanishedImports(boolean withoutLegacyArtifact) {
        this.withoutLegacyArtifact = withoutLegacyArtifact;
    }

    @Override
    public J.@Nullable Import visitImport(J.Import anImport, ExecutionContext ctx) {
        if (anImport.isStatic()) {
            return anImport;
        }
        String pkg = anImport.getPackageName();
        if ("*".equals(anImport.getQualid().getSimpleName())) {
            if (vanishedPackage(pkg) && !usesPackage(pkg)) {
                return null;
            }
            return anImport;
        }
        String type = anImport.getTypeName();
        if (vanishedPackage(pkg) || RemovedApi.get().isRemovedType(type) || REMOVED.contains(type) ||
            withoutLegacyArtifact && (LEGACY_ARTIFACT_ONLY.contains(type) || CONVERTED.contains(type))) {
            maybeRemoveImport(type);
        }
        return anImport;
    }

    private boolean vanishedPackage(String pkg) {
        return REMOVED_PACKAGES.contains(pkg) || withoutLegacyArtifact && LEGACY_ARTIFACT_PACKAGES.contains(pkg);
    }

    private boolean usesPackage(String pkg) {
        J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
        for (JavaType t : cu.getTypesInUse().getTypesInUse()) {
            String fqn = fqn(t);
            if (fqn != null && fqn.startsWith(pkg + ".") && fqn.indexOf('.', pkg.length() + 1) < 0) {
                return true;
            }
        }
        return false;
    }
}

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
import org.openrewrite.java.tree.J;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.emptySet;

/**
 * Chooses the project that holds a generated helper when values of it cross project boundaries: a project that calls another
 * project's methods taking or returning the helper type must use that project's class so the types match.
 */
class ShimRoots implements AwsMigrationScope.Limitable {
    final ProjectShims shims = new ProjectShims();
    final Set<String> producerProjects = ConcurrentHashMap.newKeySet();
    final Map<String, String> producerTypes = new ConcurrentHashMap<>();
    final Map<String, Set<String>> references = new ConcurrentHashMap<>();

    @Override
    public void limitTo(AwsMigrationScope scope) {
        shims.limitTo(scope);
    }

    void producer(J.CompilationUnit cu) {
        String project = ProjectShims.projectName(cu);
        producerProjects.add(project);
        shims.require(cu);
        for (J.ClassDeclaration c : cu.getClasses()) {
            if (c.getType() != null) {
                producerTypes.put(c.getType().getFullyQualifiedName(), project);
            }
        }
    }

    void reference(String project, String declaringType) {
        references.computeIfAbsent(project, k -> ConcurrentHashMap.newKeySet()).add(declaringType);
    }

    /**
     * The project whose generated class this project uses: follows calls into other projects' producers until none is left.
     */
    @Nullable
    String root(String project) {
        Set<String> seen = new HashSet<>();
        String current = project;
        while (seen.add(current)) {
            String next = null;
            for (String type : new TreeSet<>(references.getOrDefault(current, emptySet()))) {
                String owner = producerTypes.get(type.contains("$") ? type.substring(0, type.indexOf('$')) : type);
                if (owner != null && !owner.equals(current)) {
                    next = owner;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            current = next;
        }
        return producerProjects.contains(current) ? current : null;
    }

    /**
     * Keeps only the placements of root projects and returns them.
     */
    Set<String> retainRoots() {
        Set<String> roots = new HashSet<>();
        Set<String> projects = new HashSet<>(producerProjects);
        projects.addAll(references.keySet());
        for (String p : projects) {
            String root = root(p);
            if (root != null) {
                roots.add(root);
            }
        }
        shims.retainProjects(roots);
        return roots;
    }

    @Nullable
    String shimFor(J.CompilationUnit cu, String simpleName) {
        String root = root(ProjectShims.projectName(cu));
        return root == null ? null : shims.fqnForProject(root, simpleName);
    }
}

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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The public API of {@code mongo-java-driver} 3.12 that driver 5 no longer has, read from
 * {@code META-INF/rewrite/mongodb/removed-api.tsv}. Each member is listed under every class that had it, inherited
 * or declared, so a use is looked up under the type of the expression it is called on.
 */
final class RemovedApi {

    private static final String RESOURCE = "/META-INF/rewrite/mongodb/removed-api.tsv";

    private static volatile @Nullable RemovedApi instance;

    private final Set<String> types = new HashSet<>();
    private final Map<String, List<List<String>>> members = new HashMap<>();

    static RemovedApi get() {
        RemovedApi r = instance;
        if (r == null) {
            synchronized (RemovedApi.class) {
                r = instance;
                if (r == null) {
                    r = new RemovedApi();
                    instance = r;
                }
            }
        }
        return r;
    }

    private RemovedApi() {
        try (InputStream in = RemovedApi.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                if ("T".equals(f[0])) {
                    types.add(f[1]);
                } else {
                    List<String> params = f[3].isEmpty() ? Collections.emptyList() : Arrays.asList(f[3].split(","));
                    members.computeIfAbsent(f[0] + "|" + f[1] + "|" + f[2], k -> new ArrayList<>()).add(params);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean isRemovedType(@Nullable String fqn) {
        return fqn != null && types.contains(fqn);
    }

    /**
     * Whether a method, constructor ({@code <init>}) or field of a class is gone. {@code params} are the erased
     * parameter types of the use; a type variable of the declaration was erased to {@code java.lang.Object} in the
     * table, and matches any type.
     */
    boolean isRemoved(char kind, String fqn, String name, List<String> params) {
        List<List<String>> overloads = members.get(kind + "|" + fqn + "|" + name);
        if (overloads == null) {
            return false;
        }
        if (kind == 'F') {
            return true;
        }
        for (List<String> overload : overloads) {
            if (overload.size() != params.size()) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < overload.size(); i++) {
                String declared = overload.get(i);
                if (!declared.equals(params.get(i)) && !"java.lang.Object".equals(declared)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
}

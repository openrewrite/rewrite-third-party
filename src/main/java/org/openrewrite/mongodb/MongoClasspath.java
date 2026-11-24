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
import org.openrewrite.SourceFile;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.semver.LatestRelease;

import java.util.*;

/**
 * What a source set's classpath says about the MongoDB driver it compiles against and the libraries bound to it.
 */
final class MongoClasspath {

    private MongoClasspath() {
    }

    private static final Set<String> DRIVER_ARTIFACTS = new HashSet<>(Arrays.asList(
            "mongo-java-driver", "mongodb-driver", "mongodb-driver-core", "mongodb-driver-sync", "mongodb-driver-legacy"));

    /**
     * Libraries that are compiled against the legacy API or a driver older than 5, so that the driver cannot move to 5
     * underneath them. Group and artifact, and the first version that works with driver 5, or {@code null} if none does.
     */
    private static final String[][] BOUND_LIBRARIES = {
            {"org.springframework.data", "spring-data-mongodb", "4.3.0"},
            {"org.mongodb.morphia", "morphia", null},
            {"dev.morphia.morphia", "core", "2.4.0"},
            {"dev.morphia.morphia", "morphia-core", "2.4.0"},
            {"org.jongo", "jongo", null},
            {"org.mongojack", "mongojack", "5.0.0"},
            {"com.github.mongobee", "mongobee", null},
            {"org.mongeez", "mongeez", null},
            {"io.mongock", "mongock-driver-mongodb-v3", null},
            {"org.apache.camel", "camel-mongodb", null},
            {"org.apache.camel", "camel-mongodb3", null},
            {"org.apache.camel", "camel-mongodb-gridfs", null},
            {"org.hibernate.ogm", "hibernate-ogm-mongodb", null},
            {"com.impetus.kundera.client", "kundera-mongo", null},
            {"com.github.fakemongo", "fongo", null},
            {"org.mongodb.mongo-hadoop", "mongo-hadoop-core", null},
            {"org.mongodb.spark", "mongo-spark-connector_2.11", null},
            {"org.mongodb.spark", "mongo-spark-connector_2.12", "10.3.0"},
    };

    static @Nullable JavaSourceSet sourceSet(SourceFile sourceFile) {
        return sourceFile.getMarkers().findFirst(JavaSourceSet.class).orElse(null);
    }

    static String moduleKey(SourceFile sourceFile) {
        JavaProject project = sourceFile.getMarkers().findFirst(JavaProject.class).orElse(null);
        return project == null ? "" : project.getProjectName();
    }

    /**
     * The highest MongoDB driver version on the source set's classpath, or {@code null} when the classpath does not
     * record versions.
     */
    static @Nullable String driverVersion(SourceFile sourceFile) {
        JavaSourceSet sourceSet = sourceSet(sourceFile);
        if (sourceSet == null) {
            return null;
        }
        String best = null;
        LatestRelease latest = new LatestRelease(null);
        for (String gav : sourceSet.getGavToTypes().keySet()) {
            String[] parts = gav.split(":");
            if (parts.length >= 3 && "org.mongodb".equals(parts[0]) && DRIVER_ARTIFACTS.contains(parts[1])) {
                if (best == null || latest.compare(null, best, parts[2]) < 0) {
                    best = parts[2];
                }
            }
        }
        return best;
    }

    /**
     * Libraries on the classpath that keep the driver from moving to 5, as {@code group:artifact:version}.
     */
    static List<String> boundLibraries(SourceFile sourceFile) {
        JavaSourceSet sourceSet = sourceSet(sourceFile);
        if (sourceSet == null) {
            return Collections.emptyList();
        }
        List<String> bound = new ArrayList<>();
        LatestRelease latest = new LatestRelease(null);
        for (String gav : sourceSet.getGavToTypes().keySet()) {
            String[] parts = gav.split(":");
            if (parts.length < 3) {
                continue;
            }
            for (String[] lib : BOUND_LIBRARIES) {
                if (lib[0].equals(parts[0]) && lib[1].equals(parts[1]) &&
                    (lib[2] == null || latest.compare(null, parts[2], lib[2]) < 0)) {
                    bound.add(gav);
                }
            }
        }
        return bound;
    }

    /**
     * Whether a driver version has all of the API the conversion produces, the last of which, the UUID representation
     * of {@code MongoClientSettings}, arrived in 3.12.
     */
    static boolean hasModernApi(@Nullable String version) {
        return version != null && new LatestRelease(null).compare(null, version, "3.12.0") >= 0;
    }

    static boolean isAtLeast(@Nullable String version, String minimum) {
        return version != null && new LatestRelease(null).compare(null, version, minimum) >= 0;
    }
}

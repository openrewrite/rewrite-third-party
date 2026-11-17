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
import software.amazon.awssdk.v2migration.internal.utils.NamingConversionUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableList;

/**
 * Maven coordinates of AWS SDK for Java v2 artifacts, derived from the dependency mapping that AWS's
 * {@code software.amazon.awssdk.v2migration.UpgradeSdkDependencies} ships, so that the version and artifact names
 * always agree with the types v2-migration renames to.
 */
public final class AwsSdkV2Coordinates {

    public static final String GROUP_ID = "software.amazon.awssdk";
    public static final String BOM_ARTIFACT_ID = "bom";
    public static final String V1_GROUP_ID = "com.amazonaws";

    // Used only if v2-migration's mapping cannot be read from the classpath.
    private static final String FALLBACK_VERSION = "2.57.5";

    private static final String MAPPING_RESOURCE = "META-INF/rewrite/upgrade-sdk-dependencies.yml";

    /**
     * Packages under {@code com.amazonaws} that belong to libraries built on top of the SDK rather than to SDK v1
     * itself. Kinesis Client Library 1.x is deliberately absent: it pins SDK v1.
     */
    public static final List<String> NON_SDK_PACKAGES = unmodifiableList(Arrays.asList(
            "com.amazonaws.services.lambda.runtime",
            "com.amazonaws.lambda",
            "com.amazonaws.serverless",
            "com.amazonaws.secretsmanager.caching",
            "com.amazonaws.xray"
    ));

    /**
     * X-Ray packages that do instrument SDK v1 and so are remnants even though X-Ray itself is not.
     */
    public static final List<String> V1_BOUND_NON_SDK_PACKAGES = singletonList(
            "com.amazonaws.xray.handlers"
    );

    public static final List<String> KCL_V1_PACKAGES = unmodifiableList(Arrays.asList(
            "com.amazonaws.services.kinesis.clientlibrary",
            "com.amazonaws.services.kinesis.leases",
            "com.amazonaws.services.kinesis.metrics",
            "com.amazonaws.services.kinesis.multilang"
    ));

    /**
     * The first package segment under {@code com.amazonaws} of every package shipped by the {@code aws-java-sdk-*}
     * artifacts. Strings like CloudTrail's JSON target prefix {@code com.amazonaws.cloudtrail.v20131101} or VPC endpoint
     * service names share the root but name no v1 package.
     */
    private static final Set<String> V1_SDK_ROOT_PACKAGES = new HashSet<>(Arrays.asList(
            "adapters", "annotation", "arn", "auth", "cache", "client", "endpointdiscovery", "endpoints", "event",
            "global", "handlers", "http", "internal", "jmespath", "jmx", "log", "metrics", "monitoring", "partitions",
            "profile", "protocol", "regions", "retry", "sdk", "services", "thirdparty", "transform", "util", "waiters"
    ));

    private static final String KPL_V0_PACKAGE = "com.amazonaws.services.kinesis.producer";

    private static volatile @Nullable Mapping mapping;

    private AwsSdkV2Coordinates() {
    }

    /**
     * The single v2 version used for {@code software.amazon.awssdk:bom}: the one v2-migration pins its renamed
     * artifacts to.
     */
    public static String version() {
        return mapping().version;
    }

    /**
     * @return the v2 artifact that v2-migration renames a v1 artifact to, or null if it has no mapping.
     */
    public static @Nullable String v2ArtifactForV1Artifact(String v1ArtifactId) {
        return mapping().v1ToV2.get(v1ArtifactId);
    }

    public static boolean isKnownV2Artifact(String artifactId) {
        return mapping().v2Artifacts.contains(artifactId);
    }

    public static boolean isV1SdkArtifact(@Nullable String groupId, @Nullable String artifactId) {
        return V1_GROUP_ID.equals(groupId) && artifactId != null &&
               ("aws-java-sdk".equals(artifactId) || artifactId.startsWith("aws-java-sdk-"));
    }

    /**
     * @return whether a {@code com.amazonaws} type belongs to SDK v1 (or a v1-bound part of a library) as opposed to a
     * library that merely shares the package root.
     */
    public static boolean isV1SdkType(String fqn) {
        if (!fqn.startsWith("com.amazonaws.")) {
            return false;
        }
        for (String p : V1_BOUND_NON_SDK_PACKAGES) {
            if (inPackage(fqn, p)) {
                return true;
            }
        }
        for (String p : NON_SDK_PACKAGES) {
            if (inPackage(fqn, p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a dotted name found in free text can denote a v1 type or package: unlike a resolved type, text may hold
     * identifiers that merely start with {@code com.amazonaws.}.
     */
    public static boolean isV1SdkReference(String name) {
        if (!isV1SdkType(name)) {
            return false;
        }
        String rest = name.substring("com.amazonaws.".length());
        int dot = rest.indexOf('.');
        String first = dot < 0 ? rest : rest.substring(0, dot);
        // isV1SdkType has already admitted only the v1-bound part of X-Ray
        return Character.isUpperCase(first.charAt(0)) || V1_SDK_ROOT_PACKAGES.contains(first) || "xray".equals(first);
    }

    public static boolean isKclV1Type(String fqn) {
        for (String p : KCL_V1_PACKAGES) {
            if (inPackage(fqn, p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The v2 artifact a module needs once a v1 type it references has been migrated, or null if the type has no SDK v2
     * counterpart artifact (non-SDK libraries, KCL, KPL).
     */
    public static @Nullable String v2ArtifactForV1Type(String fqn) {
        if (!isV1SdkType(fqn) || isKclV1Type(fqn) || inPackage(fqn, KPL_V0_PACKAGE) || inPackage(fqn, "com.amazonaws.xray")) {
            return null;
        }
        if (fqn.startsWith("com.amazonaws.services.")) {
            String v2 = NamingConversionUtils.getV2ModelPackageWildCardEquivalent(fqn.replace('$', '.'));
            String prefix = "software.amazon.awssdk.services.";
            if (!v2.startsWith(prefix)) {
                return null;
            }
            String rest = v2.substring(prefix.length());
            int dot = rest.indexOf('.');
            String service = dot < 0 ? rest : rest.substring(0, dot);
            return isKnownV2Artifact(service) ? service : null;
        }
        if (inPackage(fqn, "com.amazonaws.auth")) {
            return "auth";
        }
        if (inPackage(fqn, "com.amazonaws.regions")) {
            return "regions";
        }
        if ("com.amazonaws.ClientConfiguration".equals(fqn)) {
            return "apache-client";
        }
        return "aws-core";
    }

    /**
     * Whether a v2 artifact is brought in by any v2 service artifact, so it need not be declared when one is.
     */
    public static boolean isCoreArtifact(String artifactId) {
        return "aws-core".equals(artifactId) || "auth".equals(artifactId) || "regions".equals(artifactId) ||
               "sdk-core".equals(artifactId) || "utils".equals(artifactId);
    }

    /**
     * Kinesis Producer Library types, whose package 1.x keeps although it is built on SDK v2.
     */
    public static boolean isKplType(String fqn) {
        return inPackage(fqn, KPL_V0_PACKAGE);
    }

    static boolean inPackage(String fqn, String pkg) {
        return fqn.equals(pkg) || fqn.startsWith(pkg + ".");
    }

    private static Mapping mapping() {
        Mapping m = mapping;
        if (m == null) {
            synchronized (AwsSdkV2Coordinates.class) {
                m = mapping;
                if (m == null) {
                    m = load();
                    mapping = m;
                }
            }
        }
        return m;
    }

    private static Mapping load() {
        Mapping m = new Mapping();
        try {
            Enumeration<URL> resources = AwsSdkV2Coordinates.class.getClassLoader().getResources(MAPPING_RESOURCE);
            while (resources.hasMoreElements()) {
                if (parse(resources.nextElement(), m)) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // fall through to the fallback version; artifact checks then accept nothing unknown
        }
        if (m.version == null) {
            m.version = FALLBACK_VERSION;
        }
        for (String core : Arrays.asList("aws-core", "auth", "regions", "sdk-core", "utils", "apache-client",
                "netty-nio-client", "url-connection-client", "s3-transfer-manager", "s3-event-notifications",
                "dynamodb-enhanced", "imds", "iam-policy-builder")) {
            m.v2Artifacts.add(core);
        }
        return m;
    }

    private static boolean parse(URL url, Mapping m) throws IOException {
        boolean found = false;
        try (InputStream is = url.openStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String oldArtifact = null;
            boolean oldIsV1 = false;
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("name:") && t.endsWith("UpgradeSdkDependencies")) {
                    found = true;
                } else if (t.startsWith("oldGroupId:")) {
                    oldIsV1 = V1_GROUP_ID.equals(value(t));
                } else if (t.startsWith("oldArtifactId:")) {
                    oldArtifact = value(t);
                } else if (t.startsWith("newArtifactId:") || t.startsWith("artifactId:")) {
                    String v2 = value(t);
                    m.v2Artifacts.add(v2);
                    if (oldArtifact != null && oldIsV1) {
                        m.v1ToV2.putIfAbsent(oldArtifact, v2);
                    }
                    oldArtifact = null;
                } else if ((t.startsWith("newVersion:") || t.startsWith("version:")) && m.version == null) {
                    m.version = value(t);
                }
            }
        }
        return found;
    }

    private static String value(String line) {
        return line.substring(line.indexOf(':') + 1).trim();
    }

    private static class Mapping {
        @Nullable
        String version;

        final Map<String, String> v1ToV2 = new HashMap<>();
        final Set<String> v2Artifacts = new HashSet<>();
    }
}

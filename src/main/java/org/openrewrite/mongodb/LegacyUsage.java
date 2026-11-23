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
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the scan of a repository found about its use of the legacy MongoDB API, and the decision of which modules move
 * to the driver 5 API. A module converts only when it, and every module whose legacy-typed declarations it shares,
 * uses nothing the conversion cannot translate.
 */
final class LegacyUsage {

    /**
     * A use of the legacy API that the conversion cannot translate.
     */
    static final class Blocker {
        final String module;
        final String sourcePath;
        final String reason;

        Blocker(String module, String sourcePath, String reason) {
            this.module = module;
            this.sourcePath = sourcePath;
            this.reason = reason;
        }
    }

    /**
     * A call, override or constructor whose declaring type is outside the driver, and whose signature mentions a
     * legacy type. It blocks the module unless the declaring type is declared in the repository itself.
     */
    static final class ForeignUse {
        final String module;
        final String sourcePath;
        final String declaringType;
        final String reason;

        ForeignUse(String module, String sourcePath, String declaringType, String reason) {
            this.module = module;
            this.sourcePath = sourcePath;
            this.declaringType = declaringType;
            this.reason = reason;
        }
    }

    final Set<String> modulesUsingLegacyApi = ConcurrentHashMap.newKeySet();
    final Map<String, String> declaredTypeModule = new ConcurrentHashMap<>();
    final List<Blocker> blockers = Collections.synchronizedList(new ArrayList<>());

    /**
     * What the conversion cannot translate only in the code as it is, before the removed API is replaced, which is the
     * code the conversion sees when the driver stays where it is.
     */
    final List<Blocker> blockersUnlessUpgraded = Collections.synchronizedList(new ArrayList<>());

    /**
     * What the conversion cannot translate only once the removed API is replaced, which is the code the conversion
     * sees when the driver moves to 5.
     */
    final List<Blocker> blockersIfUpgraded = Collections.synchronizedList(new ArrayList<>());
    final List<ForeignUse> foreignUses = Collections.synchronizedList(new ArrayList<>());
    final Map<String, Set<String>> injectedLegacyTypes = new ConcurrentHashMap<>();
    final Set<String> producedLegacyTypes = ConcurrentHashMap.newKeySet();
    final Map<String, String> driverVersionByModule = new ConcurrentHashMap<>();

    /**
     * The versions of the driver and its parts that build files resolve.
     */
    final Set<String> resolvedDriverVersions = ConcurrentHashMap.newKeySet();
    final Set<String> repositoryBlockers = ConcurrentHashMap.newKeySet();
    volatile boolean usesUuid;

    /**
     * The lowest Java release any source compiles for. Driver 5 needs Java 8.
     */
    volatile int minJavaRelease = Integer.MAX_VALUE;

    /**
     * Whether a build file of the repository declares a MongoDB driver artifact that the dependency migration moves to 5.
     */
    volatile boolean declaresDriver;

    /**
     * Libraries on the classpath that keep the driver from moving to 5, as {@code group:artifact:version}.
     */
    final Set<String> boundLibraries = ConcurrentHashMap.newKeySet();

    /**
     * Uses of driver 3 API that driver 5 lacks and that no recipe replaces, which keep the driver from moving to 5.
     */
    final List<Blocker> removedApiUses = Collections.synchronizedList(new ArrayList<>());

    private volatile @Nullable Map<String, List<String>> decision;

    /**
     * Module to the reasons it keeps the legacy API. A module that uses the legacy API and is absent converts.
     */
    Map<String, List<String>> blockedModules() {
        Map<String, List<String>> d = decision;
        if (d == null) {
            synchronized (this) {
                d = decision;
                if (d == null) {
                    d = decide();
                    decision = d;
                }
            }
        }
        return d;
    }

    /**
     * Whether the repository's driver moves to 5: a build file declares it, no library bound to an older driver is on
     * the classpath, no source uses an API that driver 5 lacks and no recipe replaces, and the driver is 3.0 or newer,
     * whose API is the one these recipes know.
     */
    boolean upgradesDriver() {
        return declaresDriver && boundLibraries.isEmpty() && removedApiUses.isEmpty() && whyNotUpgraded() == null;
    }

    /**
     * Why the driver stays where it is, or {@code null}.
     */
    @Nullable String whyNotUpgraded() {
        String newer = driverNewerThan3();
        if (newer != null) {
            return "compiles against MongoDB driver " + newer + ", newer than the 3.x these recipes migrate from";
        }
        if (!boundLibraries.isEmpty()) {
            return "uses " + String.join(", ", new TreeSet<>(boundLibraries)) + ", which needs a driver older than 5";
        }
        if (!removedApiUses.isEmpty()) {
            Set<String> uses = new TreeSet<>();
            for (Blocker b : removedApiUses) {
                uses.add(b.reason);
            }
            return "uses " + String.join(", ", uses) + ", which driver 5 removed and no recipe replaces";
        }
        if (minJavaRelease < 8) {
            return "compiles for Java " + minJavaRelease + ", and driver 5 needs Java 8";
        }
        for (String version : driverVersionByModule.values()) {
            if (!MongoClasspath.isAtLeast(version, "3.0")) {
                return "compiles against MongoDB driver " + version + ", older than the 3.x these recipes migrate from";
            }
        }
        if (!declaresDriver) {
            return "declares no MongoDB driver in a build file this migration can change";
        }
        return null;
    }

    /**
     * The newest driver that a module compiles against when it is 4.0 or newer, or {@code null}. Such a repository has
     * already left driver 3, and the API that driver 4 added and driver 5 removed is not what these recipes know, so
     * they leave it alone.
     */
    @Nullable String driverNewerThan3() {
        String newest = null;
        List<String> versions = new ArrayList<>(driverVersionByModule.values());
        versions.addAll(resolvedDriverVersions);
        for (String version : versions) {
            if (MongoClasspath.isAtLeast(version, "4.0") && (newest == null || MongoClasspath.isAtLeast(version, newest))) {
                newest = version;
            }
        }
        return newest;
    }

    /**
     * Whether a module keeps the legacy API, so that the build keeps {@code mongodb-driver-legacy}.
     */
    boolean keepsLegacyApi() {
        for (String module : modulesUsingLegacyApi) {
            if (!converts(module)) {
                return true;
            }
        }
        return false;
    }

    boolean converts(String module) {
        return modulesUsingLegacyApi.contains(module) && !blockedModules().containsKey(module);
    }

    private Map<String, List<String>> decide() {
        Map<String, String> parent = new HashMap<>();
        for (String m : modulesUsingLegacyApi) {
            parent.put(m, m);
        }
        Map<String, List<String>> reasons = new TreeMap<>();
        List<Blocker> all = new ArrayList<>(blockers);
        all.addAll(upgradesDriver() ? blockersIfUpgraded : blockersUnlessUpgraded);
        for (Blocker b : all) {
            reasons.computeIfAbsent(b.module, k -> new ArrayList<>()).add(b.sourcePath + ": " + b.reason);
        }
        for (ForeignUse use : foreignUses) {
            String owner = declaredTypeModule.get(use.declaringType);
            if (owner == null) {
                reasons.computeIfAbsent(use.module, k -> new ArrayList<>()).add(use.sourcePath + ": " + use.reason);
            } else if (!owner.equals(use.module)) {
                union(parent, owner, use.module);
            }
        }
        for (Map.Entry<String, Set<String>> e : injectedLegacyTypes.entrySet()) {
            for (String type : e.getValue()) {
                if (!producedLegacyTypes.contains(type)) {
                    reasons.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                            .add("injects a `" + simpleName(type) + "` that no method of the repository produces, so a library or framework creates it");
                }
            }
        }
        if (!repositoryBlockers.isEmpty()) {
            for (String m : modulesUsingLegacyApi) {
                reasons.computeIfAbsent(m, k -> new ArrayList<>()).addAll(repositoryBlockers);
            }
        }
        String newer = driverNewerThan3();
        if (newer != null) {
            for (String m : modulesUsingLegacyApi) {
                reasons.computeIfAbsent(m, k -> new ArrayList<>())
                        .add("is in a repository that compiles against MongoDB driver " + newer + ", newer than the 3.x these recipes migrate from");
            }
        } else if (!upgradesDriver()) {
            for (String m : modulesUsingLegacyApi) {
                String version = driverVersionByModule.get(m);
                if (!MongoClasspath.hasModernApi(version)) {
                    reasons.computeIfAbsent(m, k -> new ArrayList<>())
                            .add("stays on MongoDB driver " + (version == null ? "of unknown version" : version) +
                                 ", older than the 3.12 whose API the conversion produces, because the repository " + whyNotUpgraded());
                }
            }
        }
        // A blocked module blocks every module it shares legacy-typed declarations with
        Map<String, List<String>> byRoot = new HashMap<>();
        for (Map.Entry<String, List<String>> e : reasons.entrySet()) {
            if (parent.containsKey(e.getKey())) {
                byRoot.computeIfAbsent(find(parent, e.getKey()), k -> new ArrayList<>()).add(e.getKey());
            }
        }
        Map<String, List<String>> result = new TreeMap<>(reasons);
        for (String m : modulesUsingLegacyApi) {
            if (!result.containsKey(m)) {
                List<String> blockedPeers = byRoot.get(find(parent, m));
                if (blockedPeers != null) {
                    result.put(m, Collections.singletonList("shares legacy-typed declarations with module " +
                                                            String.join(", ", blockedPeers) + ", which keeps the legacy API"));
                }
            }
        }
        return result;
    }

    private static String find(Map<String, String> parent, String m) {
        String p = parent.get(m);
        if (p == null) {
            parent.put(m, m);
            return m;
        }
        while (!p.equals(parent.get(p))) {
            p = parent.get(p);
        }
        return p;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    static String simpleName(String fqn) {
        String s = fqn.substring(fqn.lastIndexOf('.') + 1);
        return s.replace('$', '.');
    }

    static String describe(JavaType.@Nullable Method method) {
        if (method == null) {
            return "an unresolved method";
        }
        return "`" + simpleName(method.getDeclaringType().getFullyQualifiedName()) + "." + method.getName() + "`";
    }

    static boolean isJdkOrCollectionLibrary(String fqn) {
        return fqn.startsWith("java.") || fqn.startsWith("javax.") || fqn.startsWith("jdk.") ||
               fqn.startsWith("com.google.common.") || fqn.startsWith("org.apache.commons.collections") ||
               fqn.startsWith("org.apache.commons.lang") || fqn.startsWith("kotlin.");
    }

    static boolean isDriverType(String fqn) {
        return fqn.startsWith("com.mongodb.") || fqn.startsWith("org.bson.");
    }

    static String line(J tree) {
        return tree.printTrimmed().split("\n", 2)[0];
    }

    static JavaType.@Nullable FullyQualified declaringType(JavaType.@Nullable Method m) {
        return m == null ? null : TypeUtils.asFullyQualified(m.getDeclaringType());
    }
}

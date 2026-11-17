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
package org.openrewrite.aws.signing;

import org.jspecify.annotations.Nullable;
import org.openrewrite.SourceFile;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

final class AwsRequestSigning {

    static final String INTERCEPTOR = "com.amazonaws.http.AWSRequestSigningApacheInterceptor";
    static final String INTERCEPTOR_PACKAGE = "com.amazonaws.http";
    static final String GROUP_ID = "com.github.awslabs";
    static final String ARTIFACT_ID = "aws-request-signing-apache-interceptor";
    static final String SHIM_NAME = "AwsSigV4RequestInterceptor";
    static final String INREACH_INTERCEPTOR = "vc.inreach.aws.request.AWSSigningRequestInterceptor";
    static final String INREACH_SIGNER = "vc.inreach.aws.request.AWSSigner";
    static final String INREACH_GROUP_ID = "vc.inreach.aws";
    static final String INREACH_ARTIFACT_ID = "aws-signing-request-interceptor";

    static final String V2_GROUP_ID = "software.amazon.awssdk";
    static final String V2_AUTH = "auth";
    static final String V2_BOM = "bom";
    // The first release with AwsV4HttpSigner
    static final String V2_MIN_SIGNER_VERSION = "2.21.0";

    // JitPack builds of awslabs' interceptor that predate its fix for POSTs without a body (awslabs PR #8)
    static final Set<String> POST_BUG_VERSIONS = new HashSet<>(Arrays.asList("b3772780da", "deb7941e85"));

    static final String[] SHIM_CLASSPATH = {"httpclient-4", "httpcore-4", "auth-2", "http-auth-aws-2", "http-auth-spi-2",
            "http-client-spi-2", "identity-spi-2", "utils-2"};

    private AwsRequestSigning() {
    }

    static String projectKey(SourceFile sf) {
        return sf.getMarkers().findFirst(JavaProject.class).map(JavaProject::getProjectName).orElse("");
    }

    static boolean isTest(SourceFile sf) {
        return sf.getMarkers().findFirst(JavaSourceSet.class).map(s -> s.getName().toLowerCase().contains("test"))
                .orElseGet(() -> sf.getSourcePath().toString().replace('\\', '/').contains("/test/"));
    }

    static String packageName(JavaSourceFile cu) {
        return cu.getPackageDeclaration() == null ? "" : cu.getPackageDeclaration().getPackageName();
    }

    static boolean isKotlin(SourceFile sf) {
        String path = sf.getSourcePath().toString();
        return path.endsWith(".kt") || path.endsWith(".kts");
    }

    /**
     * The source root of a file, mapped from {@code src/<set>/kotlin} to {@code src/<set>/java} since the shim is Java.
     */
    static Path javaSourceRoot(JavaSourceFile cu) {
        Path parent = cu.getSourcePath().getParent();
        if (parent == null) {
            return Paths.get("");
        }
        String pkg = packageName(cu);
        Path root = parent;
        if (!pkg.isEmpty()) {
            Path pkgPath = Paths.get(pkg.replace('.', '/'));
            if (parent.endsWith(pkgPath)) {
                for (int i = 0; i < pkgPath.getNameCount() && root != null; i++) {
                    root = root.getParent();
                }
            }
        }
        if (root == null) {
            return Paths.get("");
        }
        Path last = root.getFileName();
        if (last != null && "kotlin".equals(last.toString())) {
            Path set = root.getParent();
            return set == null ? Paths.get("java") : set.resolve("java");
        }
        return root;
    }

    /**
     * The longest package shared by every call site; the shim is public, so any one of them would do.
     */
    static String commonPackage(Collection<String> packages) {
        String[] common = null;
        for (String pkg : new TreeSet<>(packages)) {
            if (pkg.isEmpty()) {
                continue;
            }
            String[] segments = pkg.split("\\.");
            if (common == null) {
                common = segments;
                continue;
            }
            int n = 0;
            while (n < common.length && n < segments.length && common[n].equals(segments[n])) {
                n++;
            }
            common = Arrays.copyOf(common, n);
        }
        if (common != null && common.length > 0) {
            return String.join(".", common);
        }
        for (String pkg : new TreeSet<>(packages)) {
            if (!pkg.isEmpty()) {
                return pkg;
            }
        }
        return "awssigning";
    }

    static boolean declaresInterceptor(JavaSourceFile cu) {
        for (J.ClassDeclaration cd : cu.getClasses()) {
            if (cd.getType() != null && INTERCEPTOR.equals(cd.getType().getFullyQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    static boolean isInterceptorPackageInfo(JavaSourceFile cu) {
        return cu.getSourcePath().getFileName() != null &&
               "package-info.java".equals(cu.getSourcePath().getFileName().toString()) &&
               INTERCEPTOR_PACKAGE.equals(packageName(cu));
    }

    /**
     * The GAV of the jar that provides the interceptor type to this source file's source set, if any.
     */
    static @Nullable String interceptorProvider(SourceFile sf) {
        JavaSourceSet sourceSet = sf.getMarkers().findFirst(JavaSourceSet.class).orElse(null);
        if (sourceSet == null) {
            return null;
        }
        for (Map.Entry<String, List<JavaType.FullyQualified>> gav : gavToTypes(sourceSet).entrySet()) {
            for (JavaType.FullyQualified type : gav.getValue()) {
                if (INTERCEPTOR.equals(type.getFullyQualifiedName())) {
                    return gav.getKey();
                }
            }
        }
        return null;
    }

    /**
     * The source set's gavToTypes keyed {@code group:artifact:version}; CLI-built LSTs prefix keys with the ecosystem (e.g. {@code maven:}).
     */
    static Map<String, List<JavaType.FullyQualified>> gavToTypes(JavaSourceSet sourceSet) {
        Map<String, List<JavaType.FullyQualified>> gavToTypes = new HashMap<>();
        for (Map.Entry<String, List<JavaType.FullyQualified>> e : sourceSet.getGavToTypes().entrySet()) {
            String key = e.getKey();
            if (key.split(":").length > 3) {
                key = key.substring(key.indexOf(':') + 1);
            }
            gavToTypes.merge(key, e.getValue(), (a, b) -> a);
        }
        return gavToTypes;
    }

    static boolean isAwslabsGav(String gav) {
        return gav.startsWith(GROUP_ID + ":" + ARTIFACT_ID + ":");
    }

    /**
     * Compares dotted numeric versions, ignoring qualifiers.
     */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? leadingInt(x[i]) : 0;
            int yi = i < y.length ? leadingInt(y[i]) : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }

    private static int leadingInt(String s) {
        int n = 0;
        for (int i = 0; i < s.length() && Character.isDigit(s.charAt(i)); i++) {
            n = n * 10 + (s.charAt(i) - '0');
        }
        return n;
    }
}

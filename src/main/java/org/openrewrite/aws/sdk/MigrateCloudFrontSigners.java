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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * v2's {@code CloudFrontUtilities} signs the same policies but takes request objects and returns {@code Cookie} header
 * values, and v2 keeps {@code SignerUtils} internal, so generated classes keep v1's static API on top of it.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateCloudFrontSigners extends ScanningRecipe<MigrateCloudFrontSigners.Accumulator> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    private static final String V1 = "com.amazonaws.services.cloudfront.";
    static final String V1_COOKIE_SIGNER = V1 + "CloudFrontCookieSigner";
    static final String V1_URL_SIGNER = V1 + "CloudFrontUrlSigner";
    static final String V1_SIGNER_UTILS = V1 + "util.SignerUtils";
    static final String COOKIE_SIGNER = "CloudFrontCookieSigner";
    static final String URL_SIGNER = "CloudFrontUrlSigner";
    static final String SIGNER_UTILS = "CloudFrontSignerUtils";

    @Getter
    final String displayName = "Migrate AWS SDK v1 CloudFront URL and cookie signing";

    @Getter
    final String description = "Replaces `CloudFrontUrlSigner`, `CloudFrontCookieSigner` and `SignerUtils` with generated classes " +
                               "that have the same API: signed URLs and canned- and custom-policy cookies are produced by v2's " +
                               "`CloudFrontUtilities`, and URLs for a caller-built policy, policy building and key loading are " +
                               "done as v1 did.";

    public static class Accumulator implements AwsMigrationScope.Limitable {
        final ProjectShims shims = new ProjectShims();

        @Override
        public void limitTo(AwsMigrationScope scope) {
            shims.limitTo(scope);
        }
        final Set<String> projects = new TreeSet<>();
        boolean cookieSigner;
        boolean urlSigner;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    acc.shims.recordExisting((SourceFile) tree);
                }
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    boolean cookie = uses(cu, V1_COOKIE_SIGNER);
                    boolean url = uses(cu, V1_URL_SIGNER);
                    if (cookie || url || uses(cu, V1_SIGNER_UTILS)) {
                        acc.shims.require(cu);
                        acc.projects.add(ProjectShims.projectName(cu));
                        acc.cookieSigner |= cookie;
                        acc.urlSigner |= url;
                    }
                }
                return tree;
            }
        };
    }

    // Also true for nested types such as `CloudFrontCookieSigner$CookiesForCustomPolicy` or `SignerUtils$Protocol`
    private static boolean uses(J.CompilationUnit cu, String fqn) {
        for (JavaType type : cu.getTypesInUse().getTypesInUse()) {
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
            if (fq != null && (fq.getFullyQualifiedName().equals(fqn) || fq.getFullyQualifiedName().startsWith(fqn + "$"))) {
                return true;
            }
        }
        for (JavaType.Method method : cu.getTypesInUse().getUsedMethods()) {
            if (method.getDeclaringType().getFullyQualifiedName().equals(fqn)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        // The signers are parsed against the utils class of every package it is placed in, so their Protocol is typed
        Set<String> utilsSources = new LinkedHashSet<>();
        for (String project : acc.projects) {
            String fqn = acc.shims.fqnForProject(project, SIGNER_UTILS);
            if (fqn != null) {
                utilsSources.add(source(SIGNER_UTILS, fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : ""));
            }
        }
        JavaParser.Builder<?, ?> parser = JavaParser.fromJavaVersion()
                .classpathFromResources(ctx, "cloudfront-2", "sdk-core-2", "http-client-spi-2", "utils-2")
                .dependsOn(utilsSources.toArray(new String[0]));
        List<SourceFile> generated = new ArrayList<>(acc.shims.generate(SIGNER_UTILS, pkg -> source(SIGNER_UTILS, pkg), ctx));
        if (acc.cookieSigner) {
            generated.addAll(acc.shims.generate(COOKIE_SIGNER, pkg -> source(COOKIE_SIGNER, pkg), parser, ctx));
        }
        if (acc.urlSigner) {
            generated.addAll(acc.shims.generate(URL_SIGNER, pkg -> source(URL_SIGNER, pkg), parser, ctx));
        }
        return generated;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return JavaOnly.check(new UsesType<>("com.amazonaws.services.cloudfront..*", false), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                String utils = acc.shims.fqn(cu, SIGNER_UTILS);
                if (utils == null) {
                    return cu;
                }
                String pkg = utils.contains(".") ? utils.substring(0, utils.lastIndexOf('.') + 1) : "";
                String cookie = pkg + COOKIE_SIGNER;
                // v1's cookie classes nest under SignedCookies' outer class; the shim's sit directly in CloudFrontCookieSigner
                doAfterVisit(new ChangeType(V1_COOKIE_SIGNER + "$SignedCookies$CookieKeyValuePair", V1_COOKIE_SIGNER + "$CookieKeyValuePair", true).getVisitor());
                doAfterVisit(new ChangeType(V1_COOKIE_SIGNER, cookie, true).getVisitor());
                doAfterVisit(new ChangeType(V1_SIGNER_UTILS, utils, true).getVisitor());
                doAfterVisit(new ChangeType(V1_URL_SIGNER, pkg + URL_SIGNER, true).getVisitor());
                // Outer classes first, so `Outer.Nested` references keep their form; these then take directly imported nested types
                doAfterVisit(new ChangeType(V1_SIGNER_UTILS + "$Protocol", utils + "$Protocol", true).getVisitor());
                for (String nested : new String[]{"$CookiesForCustomPolicy", "$CookiesForCannedPolicy", "$SignedCookies", "$CookieKeyValuePair"}) {
                    doAfterVisit(new ChangeType(V1_COOKIE_SIGNER + nested, cookie + nested, true).getVisitor());
                }
                return cu;
            }
        });
    }

    static String source(String name, String pkg) {
        try (InputStream in = MigrateCloudFrontSigners.class.getResourceAsStream("/META-INF/rewrite/aws-sdk/" + name + ".java.template")) {
            return StringUtils.readFully(Objects.requireNonNull(in), StandardCharsets.UTF_8)
                    .replace("__PACKAGE__", ProjectShims.packageLine(pkg));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

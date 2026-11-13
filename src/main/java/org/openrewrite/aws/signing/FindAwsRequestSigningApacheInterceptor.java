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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.aws.signing.table.AwsRequestSigningRemnants;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.java.dependencies.FindDependency;
import org.openrewrite.java.search.FindTypes;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.tree.Xml;

import java.util.HashMap;
import java.util.Map;

import static org.openrewrite.aws.signing.AwsRequestSigning.*;

/**
 * Run after the migration, next to the AWS SDK v1 remnant finder: anything it reports still depends on awslabs'
 * interceptor, and so on AWS SDK v1.
 */
public class FindAwsRequestSigningApacheInterceptor extends ScanningRecipe<Map<String, String>> {

    private static final XPathMatcher DEPENDENCY = new XPathMatcher("/project/dependencies/dependency");

    transient AwsRequestSigningRemnants remnants = new AwsRequestSigningRemnants(this);

    @Getter
    final String displayName = "Find awslabs' `AWSRequestSigningApacheInterceptor` and vc.inreach's `AWSSigningRequestInterceptor`";

    @Getter
    final String description = "Finds what still depends on awslabs' `aws-request-signing-apache-interceptor`: references to " +
            "`com.amazonaws.http.AWSRequestSigningApacheInterceptor` in Java and Kotlin, vendored copies of it and their " +
            "`package-info.java`, declarations of `com.github.awslabs:aws-request-signing-apache-interceptor`, and " +
            "system-scope jars that provide the class, as well as references to `vc.inreach.aws` types and declarations of " +
            "`vc.inreach.aws:aws-signing-request-interceptor`, which also bring AWS SDK v1.";

    @Override
    public Map<String, String> getInitialValue(ExecutionContext ctx) {
        // project -> group:artifact of a jar other than awslabs' that provides the interceptor
        return new HashMap<>();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Map<String, String> carriers) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    String provider = interceptorProvider((JavaSourceFile) tree);
                    if (provider != null && !isAwslabsGav(provider)) {
                        String[] gav = provider.split(":");
                        if (gav.length >= 2) {
                            carriers.putIfAbsent(projectKey((SourceFile) tree), gav[0] + ":" + gav[1]);
                        }
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Map<String, String> carriers) {
        TreeVisitor<?, ExecutionContext> findTypes = new FindTypes(INTERCEPTOR, false).getVisitor();
        TreeVisitor<?, ExecutionContext> findDependency = new FindDependency(GROUP_ID, ARTIFACT_ID, null, null, null).getVisitor();
        TreeVisitor<?, ExecutionContext> findInreachTypes = new FindTypes(INREACH_GROUP_ID + "..*", false).getVisitor();
        TreeVisitor<?, ExecutionContext> findInreachDependency = new FindDependency(INREACH_GROUP_ID, INREACH_ARTIFACT_ID, null, null, null).getVisitor();
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile sf = (SourceFile) tree;
                if (!(sf instanceof JavaSourceFile) && !(sf instanceof Xml.Document)) {
                    return sf;
                }
                String path = sf.getSourcePath().toString();
                if (sf.getMarkers().findFirst(MavenResolutionResult.class).isPresent() ||
                    sf.getMarkers().findFirst(GradleProject.class).isPresent()) {
                    Tree t = findDependency.visit(sf, ctx);
                    if (t != sf) {
                        remnants.insertRow(ctx, new AwsRequestSigningRemnants.Row(path, "dependency", GROUP_ID + ":" + ARTIFACT_ID));
                    }
                    Tree beforeInreach = t;
                    t = findInreachDependency.visit(t, ctx);
                    if (t != beforeInreach) {
                        remnants.insertRow(ctx, new AwsRequestSigningRemnants.Row(path, "dependency", INREACH_GROUP_ID + ":" + INREACH_ARTIFACT_ID));
                    }
                    String carrier = carriers.get(projectKey(sf));
                    if (carrier != null && t instanceof Xml.Document) {
                        t = findCarrier((Xml.Document) t, carrier, ctx);
                    }
                    return t;
                }
                if (!(sf instanceof JavaSourceFile)) {
                    return sf;
                }
                JavaSourceFile cu = (JavaSourceFile) sf;
                if (declaresInterceptor(cu)) {
                    remnants.insertRow(ctx, new AwsRequestSigningRemnants.Row(path, "vendored copy", INTERCEPTOR));
                    return SearchResult.found(cu, "Vendored copy of awslabs' AWSRequestSigningApacheInterceptor");
                }
                if (isInterceptorPackageInfo(cu)) {
                    remnants.insertRow(ctx, new AwsRequestSigningRemnants.Row(path, "vendored copy", "package-info of " + INTERCEPTOR_PACKAGE));
                    return SearchResult.found(cu, "package-info.java of the vendored com.amazonaws.http package");
                }
                Tree t = findTypes.visit(cu, ctx);
                if (t != cu) {
                    remnants.insertRow(ctx, new AwsRequestSigningRemnants.Row(path, "type reference", INTERCEPTOR));
                }
                Tree beforeInreach = t;
                t = findInreachTypes.visit(t, ctx);
                if (t != beforeInreach) {
                    remnants.insertRow(ctx, new AwsRequestSigningRemnants.Row(path, "type reference", INREACH_GROUP_ID));
                }
                return t;
            }
        };
    }

    private Xml.Document findCarrier(Xml.Document pom, String carrier, ExecutionContext ctx) {
        return (Xml.Document) new MavenIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext c) {
                Xml.Tag t = super.visitTag(tag, c);
                String ga = t.getChildValue("groupId").orElse("") + ":" + t.getChildValue("artifactId").orElse("");
                if (DEPENDENCY.matches(getCursor()) && carrier.equals(ga)) {
                    remnants.insertRow(c, new AwsRequestSigningRemnants.Row(pom.getSourcePath().toString(), "system jar",
                            carrier + " provides " + INTERCEPTOR));
                    return SearchResult.found(t, "Provides awslabs' AWSRequestSigningApacheInterceptor");
                }
                return t;
            }
        }.visitNonNull(pom, ctx);
    }
}

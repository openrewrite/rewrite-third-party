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

import lombok.Getter;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

public class MigrateAwsRegionResiduals extends Recipe {

    private static final String V1_REGION = "com.amazonaws.regions.Region";
    private static final String V1_REGION_UTILS = "com.amazonaws.regions.RegionUtils";
    private static final String V1_CHAIN = "com.amazonaws.regions.DefaultAwsRegionProviderChain";
    private static final String V2_CHAIN = "software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain";

    private static final MethodMatcher CHAIN_GET_REGION = new MethodMatcher("com.amazonaws.regions.AwsRegionProvider getRegion()", true);
    private static final MethodMatcher REGION_UTILS_GET_REGION = new MethodMatcher(V1_REGION_UTILS + " getRegion(String)");
    private static final MethodMatcher REGION_GET_REGION = new MethodMatcher(V1_REGION + " getRegion(com.amazonaws.regions.Regions)");
    private static final MethodMatcher REGION_GET_NAME = new MethodMatcher(V1_REGION + " getName()");
    private static final MethodMatcher REGION_ANY = new MethodMatcher(V1_REGION + " *(..)");
    private static final MethodMatcher REGION_UTILS_ANY = new MethodMatcher(V1_REGION_UTILS + " *(..)");
    private static final MethodMatcher CURRENT_REGION = new MethodMatcher("com.amazonaws.regions.Regions getCurrentRegion()");

    @Getter
    final String displayName = "Migrate AWS SDK v1 `Region`, `RegionUtils` and `DefaultAwsRegionProviderChain`";

    @Getter
    final String description = "v2-migration maps only the `Regions` enum. This maps v1 `Region` and `RegionUtils.getRegion(name)` to v2 " +
                               "`Region`/`Region.of(name)`, `Region.getRegion(Regions.X)` to the enum constant, `getName()` to `id()`, and v1 " +
                               "`DefaultAwsRegionProviderChain` to v2's, whose `getRegion()` returns a `Region`, so `.id()` keeps the `String`. " +
                               "Both chains read the environment, system properties, profile and instance metadata, and both throw when no region " +
                               "is found. Other `Region`/`RegionUtils` methods and `Regions.getCurrentRegion()` are marked.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(Preconditions.or(
                        new UsesType<>(V1_REGION, false),
                        new UsesType<>(V1_REGION_UTILS, false),
                        new UsesType<>(V1_CHAIN, false),
                        new UsesMethod<>(CURRENT_REGION)),
                new JavaVisitor<ExecutionContext>() {
                    @Override
                    public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                        J c = super.visitCompilationUnit(cu, ctx);
                        doAfterVisit(new ChangeType(V1_CHAIN, V2_CHAIN, true).getVisitor());
                        doAfterVisit(new ChangeType(V1_REGION, "software.amazon.awssdk.regions.Region", true).getVisitor());
                        return c;
                    }

                    @Override
                    public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        J j = super.visitMethodInvocation(method, ctx);
                        if (!(j instanceof J.MethodInvocation)) {
                            return j;
                        }
                        J.MethodInvocation m = (J.MethodInvocation) j;
                        if (CHAIN_GET_REGION.matches(m) && m.getSelect() != null && TypeUtils.isOfClassType(m.getSelect().getType(), V1_CHAIN)) {
                            return JavaTemplate.builder("#{any(" + V2_CHAIN + ")}.getRegion().id()")
                                    .javaParser(AwsV2Parser.parser(ctx))
                                    .build()
                                    .apply(getCursor(), m.getCoordinates().replace(), m.getSelect());
                        }
                        if (REGION_UTILS_GET_REGION.matches(m)) {
                            maybeRemoveImport(V1_REGION_UTILS);
                            maybeAddImport("software.amazon.awssdk.regions.Region");
                            return JavaTemplate.builder("Region.of(#{any(String)})")
                                    .imports("software.amazon.awssdk.regions.Region")
                                    .javaParser(AwsV2Parser.parser(ctx))
                                    .build()
                                    .apply(getCursor(), m.getCoordinates().replace(), m.getArguments().get(0));
                        }
                        if (REGION_GET_REGION.matches(m)) {
                            // v2-migration turns the Regions constant into the equivalent v2 Region
                            return m.getArguments().get(0).withPrefix(m.getPrefix());
                        }
                        if (REGION_GET_NAME.matches(m) && m.getSelect() != null) {
                            return JavaTemplate.builder("#{any(software.amazon.awssdk.regions.Region)}.id()")
                                    .javaParser(AwsV2Parser.parser(ctx))
                                    .build()
                                    .apply(getCursor(), m.getCoordinates().replace(), m.getSelect());
                        }
                        if (REGION_ANY.matches(m) || REGION_UTILS_ANY.matches(m)) {
                            return SearchResult.found(m, "AWS SDK v1 region method with no v2 equivalent");
                        }
                        if (CURRENT_REGION.matches(m)) {
                            return SearchResult.found(m, "Regions.getCurrentRegion() returns null off EC2; v2 has no equivalent, use " +
                                                         "software.amazon.awssdk.regions.providers.InstanceProfileRegionProvider, which throws instead");
                        }
                        return m;
                    }
                });
    }
}

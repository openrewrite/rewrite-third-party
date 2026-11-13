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
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

public class MigrateAnonymousAndContainerAwsCredentials extends Recipe {

    private static final String ANONYMOUS = "com.amazonaws.auth.AnonymousAWSCredentials";
    private static final String CONTAINER_WRAPPER = "com.amazonaws.auth.EC2ContainerCredentialsProviderWrapper";
    private static final MethodMatcher NEW_ANONYMOUS = new MethodMatcher(ANONYMOUS + " <constructor>(..)");
    private static final MethodMatcher NEW_STATIC_PROVIDER = new MethodMatcher("com.amazonaws.auth.AWSStaticCredentialsProvider <constructor>(com.amazonaws.auth.AWSCredentials)");
    private static final MethodMatcher NEW_CONTAINER_WRAPPER = new MethodMatcher(CONTAINER_WRAPPER + " <constructor>()");

    @Getter
    final String displayName = "Migrate AWS SDK v1 anonymous and EC2/container credentials";

    @Getter
    final String description = "v2-migration has no mapping for `AnonymousAWSCredentials` or `EC2ContainerCredentialsProviderWrapper`. " +
                               "`new AWSStaticCredentialsProvider(new AnonymousAWSCredentials())` becomes `AnonymousCredentialsProvider.create()`, " +
                               "and the wrapper becomes a chain of `ContainerCredentialsProvider` and `InstanceProfileCredentialsProvider`, " +
                               "which resolves the same source the wrapper selected from the container environment variables.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(Preconditions.or(new UsesType<>(ANONYMOUS, false), new UsesType<>(CONTAINER_WRAPPER, false)),
                new JavaVisitor<ExecutionContext>() {
                    @Override
                    public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                        // The whole static-provider expression is replaced before its anonymous argument is visited
                        if (NEW_STATIC_PROVIDER.matches(newClass) && isNewAnonymous(newClass.getArguments().get(0))) {
                            J.NewClass n = newClass;
                            maybeRemoveImport(ANONYMOUS);
                            maybeRemoveImport("com.amazonaws.auth.AWSStaticCredentialsProvider");
                            maybeAddImport("software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider");
                            return JavaTemplate.builder("AnonymousCredentialsProvider.create()")
                                    .imports("software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider")
                                    .javaParser(AwsV2Parser.parser(ctx))
                                    .build()
                                    .apply(getCursor(), n.getCoordinates().replace());
                        }
                        J j = super.visitNewClass(newClass, ctx);
                        if (!(j instanceof J.NewClass)) {
                            return j;
                        }
                        J.NewClass n = (J.NewClass) j;
                        if (NEW_ANONYMOUS.matches(n)) {
                            maybeRemoveImport(ANONYMOUS);
                            maybeAddImport("software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider");
                            return JavaTemplate.builder("AnonymousCredentialsProvider.create().resolveCredentials()")
                                    .imports("software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider")
                                    .javaParser(AwsV2Parser.parser(ctx))
                                    .build()
                                    .apply(getCursor(), n.getCoordinates().replace());
                        }
                        if (NEW_CONTAINER_WRAPPER.matches(n)) {
                            maybeAddImport("software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain");
                            maybeAddImport("software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider");
                            maybeAddImport("software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider");
                            doAfterVisit(new ChangeType(CONTAINER_WRAPPER,
                                    "software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain", true).getVisitor());
                            return JavaTemplate.builder("AwsCredentialsProviderChain.of(ContainerCredentialsProvider.create(), InstanceProfileCredentialsProvider.create())")
                                    .imports("software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain",
                                            "software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider",
                                            "software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider")
                                    .javaParser(AwsV2Parser.parser(ctx))
                                    .build()
                                    .apply(getCursor(), n.getCoordinates().replace());
                        }
                        return n;
                    }

                    private boolean isNewAnonymous(Expression e) {
                        return e instanceof J.NewClass && NEW_ANONYMOUS.matches((J.NewClass) e);
                    }
                });
    }
}

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
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.ChangeMethodName;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.RemoveAnnotationVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;

import java.util.List;

import static java.util.Collections.singletonList;

public class MigrateAwsCredentialsProviderImplementations extends Recipe {

    private static final String PROVIDER = "com.amazonaws.auth.AWSCredentialsProvider";
    private static final MethodMatcher REFRESH = new MethodMatcher(PROVIDER + " refresh()", true);
    private static final AnnotationMatcher OVERRIDE = new AnnotationMatcher("@java.lang.Override");

    @Getter
    final String displayName = "Migrate AWS SDK v1 `AWSCredentialsProvider` implementations and `refresh()` calls";

    @Getter
    final String description = "Renames `getCredentials()` to `resolveCredentials()` on every v1 credentials provider, including user classes " +
                               "that implement `AWSCredentialsProvider`, which v2-migration retypes to `AwsCredentialsProvider` but does not rename. " +
                               "v2 providers refresh on their own and have no `refresh()`: a `refresh()` implementation loses its `@Override`, " +
                               "and `refresh()` statements on SDK provider types are removed.";

    @Override
    public List<Recipe> getRecipeList() {
        return singletonList(new ChangeMethodName(PROVIDER + " getCredentials()", "resolveCredentials", true, null));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(new UsesType<>(PROVIDER, true), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = super.visitBlock(block, ctx);
                return b.withStatements(ListUtils.map(b.getStatements(), s -> isSdkRefreshCall(s) ? null : s));
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                // Kept even when empty, as callers typed as the implementing class still call it
                if (REFRESH.matches(md.getMethodType())) {
                    return (J.MethodDeclaration) new RemoveAnnotationVisitor(OVERRIDE).visitNonNull(md, ctx, getCursor().getParentOrThrow());
                }
                return md;
            }

            private boolean isSdkRefreshCall(Statement s) {
                if (!(s instanceof J.MethodInvocation) || !REFRESH.matches((J.MethodInvocation) s)) {
                    return false;
                }
                JavaType.Method mt = ((J.MethodInvocation) s).getMethodType();
                return mt != null && mt.getDeclaringType().getFullyQualifiedName().startsWith("com.amazonaws.");
            }
        });
    }
}

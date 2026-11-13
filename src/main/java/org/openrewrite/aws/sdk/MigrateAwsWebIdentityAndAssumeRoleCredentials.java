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
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import static org.openrewrite.aws.sdk.AwsCredentialsProviderFactories.*;

public class MigrateAwsWebIdentityAndAssumeRoleCredentials extends Recipe {

    @Getter
    final String displayName = "Migrate AWS SDK v1 web identity token and assume role credentials providers";

    @Getter
    final String description = "v2-migration only renames `WebIdentityTokenCredentialsProvider` and `STSAssumeRoleSessionCredentialsProvider`, " +
                               "whose v2 counterparts have no public constructors. Their constructors, factories and builders become " +
                               "`WebIdentityTokenFileCredentialsProvider.create()` or its builder, and a `StsAssumeRoleCredentialsProvider` " +
                               "built from an `StsClient` and an `AssumeRoleRequest`.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(Preconditions.or(new UsesType<>(V1_WEB_IDENTITY, false), new UsesType<>(V1_ASSUME_ROLE, false)),
                new JavaVisitor<ExecutionContext>() {
                    @Override
                    public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                        J j = super.visitNewClass(newClass, ctx);
                        return j instanceof J.NewClass ? convert((J.NewClass) j, ctx) : j;
                    }

                    @Override
                    public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        J j = super.visitMethodInvocation(method, ctx);
                        return j instanceof J.MethodInvocation ? convert((J.MethodInvocation) j, ctx) : j;
                    }

                    private J convert(Expression e, ExecutionContext ctx) {
                        Conversion conversion = AwsCredentialsProviderFactories.convert(e, false);
                        if (conversion == null) {
                            return e;
                        }
                        for (String fqn : conversion.imports) {
                            maybeAddImport(fqn);
                        }
                        maybeRemoveImport(V1_WEB_IDENTITY);
                        maybeRemoveImport(V1_ASSUME_ROLE);
                        return JavaTemplate.builder(conversion.code)
                                .imports(conversion.imports.toArray(new String[0]))
                                .javaParser(parser(ctx))
                                .build()
                                .apply(getCursor(), e.getCoordinates().replace(), conversion.args.toArray());
                    }
                });
    }

    private static JavaParser.Builder<?, ?> parser(ExecutionContext ctx) {
        return JavaParser.fromJavaVersion().classpathFromResources(ctx,
                "auth-2", "identity-spi-2", "regions-2", "sdk-core-2", "utils-2", "aws-core-2", "sts-2");
    }
}

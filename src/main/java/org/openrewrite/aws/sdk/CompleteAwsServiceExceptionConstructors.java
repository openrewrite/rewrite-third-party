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
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.regex.Pattern;

/**
 * v1 service exceptions are constructed from a message, while v2's only have a builder, and v2-migration keeps the
 * constructor call.
 */
public class CompleteAwsServiceExceptionConstructors extends Recipe {

    private static final Pattern V2_EXCEPTION = Pattern.compile("software\\.amazon\\.awssdk\\.services\\.[a-z0-9]+\\.model\\.[A-Za-z0-9]+Exception");

    @Getter
    final String displayName = "Build AWS SDK v2 service exceptions instead of constructing them";

    @Getter
    final String description = "Runs after `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. Replaces `new XException(message)` " +
            "of a v2 service model exception with `XException.builder().message(message).build()`.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>("software.amazon.awssdk.services..model..*", true), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J.NewClass n = (J.NewClass) super.visitNewClass(newClass, ctx);
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(n.getType());
                if (type == null || n.getClazz() == null || n.getBody() != null || n.getEnclosing() != null ||
                    n.getArguments().size() != 1 || !V2_EXCEPTION.matcher(type.getFullyQualifiedName()).matches()) {
                    return n;
                }
                Expression message = n.getArguments().get(0);
                if (!TypeUtils.isString(message.getType())) {
                    return n;
                }
                return JavaTemplate.builder(n.getClazz().printTrimmed(getCursor()) + ".builder().message(#{any(java.lang.String)}).build()")
                        .build()
                        .apply(getCursor(), n.getCoordinates().replace(), message);
            }
        });
    }
}

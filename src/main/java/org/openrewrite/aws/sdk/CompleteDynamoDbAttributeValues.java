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
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * v2-migration derives a v2 accessor by lower-casing the v1 name, which gives the keyword {@code null} for {@code NULL}, and it
 * leaves the {@code isX} getters and method references untouched.
 */
public class CompleteDynamoDbAttributeValues extends Recipe {

    private static final String ATTRIBUTE_VALUE = "software.amazon.awssdk.services.dynamodb.model.AttributeValue";

    private static final Map<String, String> INVOCATIONS = new HashMap<>();
    private static final Map<String, String> REFERENCES = new HashMap<>();

    static {
        INVOCATIONS.put("null", "nul");
        INVOCATIONS.put("isNULL", "nul");
        INVOCATIONS.put("getNULL", "nul");
        INVOCATIONS.put("isBOOL", "bool");
        INVOCATIONS.put("getBOOL", "bool");
        REFERENCES.putAll(INVOCATIONS);
        for (String field : new String[]{"S", "N", "B", "SS", "NS", "BS", "M", "L"}) {
            REFERENCES.put("get" + field, field.toLowerCase());
        }
    }

    @Getter
    final String displayName = "Complete the migration of DynamoDB `AttributeValue` accessors";

    @Getter
    final String description = "Runs after `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. Renames the `null(..)` it derives " +
            "from v1 `withNULL(..)` and `getNULL()` to v2's `nul(..)`, the `isNULL()` and `isBOOL()` getters to `nul()` and `bool()`, " +
            "and method references such as `AttributeValue::getS` to their v2 accessors.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>(ATTRIBUTE_VALUE, true), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                String v2 = INVOCATIONS.get(m.getSimpleName());
                if (v2 != null && isAttributeValueOrBuilder(m.getSelect())) {
                    m = m.withName(m.getName().withSimpleName(v2));
                    return m.getMethodType() == null ? m : m.withMethodType(m.getMethodType().withName(v2));
                }
                return m;
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference memberRef, ExecutionContext ctx) {
                J.MemberReference r = super.visitMemberReference(memberRef, ctx);
                String v2 = REFERENCES.get(r.getReference().getSimpleName());
                if (v2 != null && isAttributeValueOrBuilder(r.getContaining())) {
                    r = r.withReference(r.getReference().withSimpleName(v2));
                    return r.getMethodType() == null ? r : r.withMethodType(r.getMethodType().withName(v2));
                }
                return r;
            }
        });
    }

    private static boolean isAttributeValueOrBuilder(@Nullable Expression e) {
        if (e == null) {
            return false;
        }
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(e.getType());
        if (type != null && (ATTRIBUTE_VALUE.equals(type.getFullyQualifiedName()) ||
                             (ATTRIBUTE_VALUE + "$Builder").equals(type.getFullyQualifiedName()) ||
                             (ATTRIBUTE_VALUE + ".Builder").equals(type.getFullyQualifiedName()))) {
            return true;
        }
        // the builder chains v2-migration writes are not always type attributed
        return e instanceof J.MethodInvocation && isAttributeValueOrBuilder(((J.MethodInvocation) e).getSelect());
    }
}

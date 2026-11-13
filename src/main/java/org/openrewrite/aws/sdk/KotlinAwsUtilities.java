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

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.KotlinTemplate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kotlin side of {@link MigrateAwsSdkV1IoAndStringUtils} for {@code com.amazonaws.util.StringUtils}, with the same v2
 * {@code StringUtils} targets so Java and Kotlin end identical. Shared with the DynamoDB migration, which owns Kotlin files
 * that use DynamoDB.
 */
public final class KotlinAwsUtilities {

    private static final String V1_STRING_UTILS = "com.amazonaws.util.StringUtils";
    private static final String V2_STRING_UTILS = "software.amazon.awssdk.utils.StringUtils";

    // One-argument v1 methods with an identical v2 method
    private static final Set<String> SAME_IN_V2 = new HashSet<>(Arrays.asList("trim", "lowerCase", "upperCase"));

    private KotlinAwsUtilities() {
    }

    /**
     * @return the replacement, or null when the call is not a mapped v1 {@code StringUtils} call.
     */
    public static @Nullable J rewrite(JavaVisitor<ExecutionContext> visitor, J.MethodInvocation m, ExecutionContext ctx) {
        JavaType.Method mt = m.getMethodType();
        List<org.openrewrite.java.tree.Expression> args = AwsClientChain.arguments(m);
        if (mt == null || !mt.hasFlags(Flag.Static) || !TypeUtils.isOfClassType(mt.getDeclaringType(), V1_STRING_UTILS) || args.size() != 1) {
            return null;
        }
        String code;
        switch (m.getSimpleName()) {
            case "isNullOrEmpty":
                code = "StringUtils.isEmpty(#{any()})";
                break;
            case "hasValue":
                code = "!StringUtils.isEmpty(#{any()})";
                break;
            default:
                if (!SAME_IN_V2.contains(m.getSimpleName())) {
                    return null;
                }
                code = "StringUtils." + m.getSimpleName() + "(#{any()})";
        }
        J result = KotlinTemplate.builder(code)
                .imports(V2_STRING_UTILS)
                .parser(KotlinParser.builder().classpathFromResources(ctx, "utils"))
                .build()
                .apply(visitor.getCursor(), m.getCoordinates().replace(), args.get(0));
        visitor.maybeRemoveImport(V1_STRING_UTILS);
        visitor.maybeAddImport(V2_STRING_UTILS);
        return result.withPrefix(m.getPrefix());
    }
}

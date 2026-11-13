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

import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.KotlinTemplate;

import java.util.*;

import static java.util.Collections.*;

/**
 * The v1 to v2 credential mappings for Kotlin sources, which AWS's v2-migration does not process. They mirror the Java mappings
 * in v2-migration's {@code change-auth-types.yml} and {@code NewClassToStaticFactory}, so Java and Kotlin end up identical.
 * Shared with the request-signing interceptor migration.
 */
public final class KotlinAwsCredentialMappings {

    private static final String V1 = "com.amazonaws.auth.";
    private static final String V2 = "software.amazon.awssdk.auth.credentials.";
    private static final MethodMatcher INSTANCE_PROFILE_GET_INSTANCE = new MethodMatcher(V1 + "InstanceProfileCredentialsProvider getInstance()");

    @Value
    public static class ConstructorMapping {
        String v1Type;
        int arguments;
        String v2Type;
        String factory;
    }

    public static final List<ConstructorMapping> CONSTRUCTORS = unmodifiableList(Arrays.asList(
            new ConstructorMapping(V1 + "BasicAWSCredentials", 2, V2 + "AwsBasicCredentials", "create"),
            new ConstructorMapping(V1 + "BasicSessionCredentials", 3, V2 + "AwsSessionCredentials", "create"),
            new ConstructorMapping(V1 + "AWSStaticCredentialsProvider", 1, V2 + "StaticCredentialsProvider", "create"),
            new ConstructorMapping("com.amazonaws.internal.StaticCredentialsProvider", 1, V2 + "StaticCredentialsProvider", "create"),
            new ConstructorMapping(V1 + "DefaultAWSCredentialsProviderChain", 0, V2 + "DefaultCredentialsProvider", "create"),
            new ConstructorMapping(V1 + "EnvironmentVariableCredentialsProvider", 0, V2 + "EnvironmentVariableCredentialsProvider", "create"),
            new ConstructorMapping(V1 + "SystemPropertiesCredentialsProvider", 0, V2 + "SystemPropertyCredentialsProvider", "create"),
            new ConstructorMapping(V1 + "profile.ProfileCredentialsProvider", 0, V2 + "ProfileCredentialsProvider", "create"),
            new ConstructorMapping(V1 + "profile.ProfileCredentialsProvider", 1, V2 + "ProfileCredentialsProvider", "create"),
            new ConstructorMapping(V1 + "ContainerCredentialsProvider", 0, V2 + "ContainerCredentialsProvider", "create"),
            new ConstructorMapping(V1 + "InstanceProfileCredentialsProvider", 0, V2 + "InstanceProfileCredentialsProvider", "create"),
            new ConstructorMapping(V1 + "WebIdentityTokenCredentialsProvider", 0, V2 + "WebIdentityTokenFileCredentialsProvider", "create")));

    public static final Map<String, String> TYPES;

    static {
        Map<String, String> types = new LinkedHashMap<>();
        types.put(V1 + "AWSCredentials", V2 + "AwsCredentials");
        types.put(V1 + "AWSSessionCredentials", V2 + "AwsSessionCredentials");
        types.put(V1 + "AWSCredentialsProvider", V2 + "AwsCredentialsProvider");
        types.put(V1 + "BasicAWSCredentials", V2 + "AwsBasicCredentials");
        types.put(V1 + "BasicSessionCredentials", V2 + "AwsSessionCredentials");
        types.put(V1 + "AWSStaticCredentialsProvider", V2 + "StaticCredentialsProvider");
        types.put("com.amazonaws.internal.StaticCredentialsProvider", V2 + "StaticCredentialsProvider");
        types.put(V1 + "DefaultAWSCredentialsProviderChain", V2 + "DefaultCredentialsProvider");
        types.put(V1 + "EnvironmentVariableCredentialsProvider", V2 + "EnvironmentVariableCredentialsProvider");
        types.put(V1 + "SystemPropertiesCredentialsProvider", V2 + "SystemPropertyCredentialsProvider");
        types.put(V1 + "profile.ProfileCredentialsProvider", V2 + "ProfileCredentialsProvider");
        types.put(V1 + "ContainerCredentialsProvider", V2 + "ContainerCredentialsProvider");
        types.put(V1 + "InstanceProfileCredentialsProvider", V2 + "InstanceProfileCredentialsProvider");
        types.put(V1 + "EC2ContainerCredentialsProviderWrapper", V2 + "AwsCredentialsProviderChain");
        types.put(AwsCredentialsProviderFactories.V1_WEB_IDENTITY, AwsCredentialsProviderFactories.V2_WEB_IDENTITY);
        types.put(AwsCredentialsProviderFactories.V1_ASSUME_ROLE, AwsCredentialsProviderFactories.V2_ASSUME_ROLE);
        TYPES = unmodifiableMap(types);
    }

    private KotlinAwsCredentialMappings() {
    }

    /**
     * Rewrites a v1 credentials constructor call, factory or builder chain to its v2 factory or builder.
     * The caller visits the enclosing Kotlin file and must apply {@link #changeTypes()} after it.
     *
     * @return the replacement, or null when the expression is not a mapped v1 credentials construction.
     */
    public static @Nullable J rewrite(JavaVisitor<ExecutionContext> visitor, Expression expression, ExecutionContext ctx) {
        String code = null;
        List<Expression> args = new ArrayList<>();
        Set<String> imports = new LinkedHashSet<>();
        if (expression instanceof J.NewClass) {
            J.NewClass newClass = (J.NewClass) expression;
            args = AwsClientChain.arguments(newClass.getArguments());
            if (TypeUtils.isOfClassType(newClass.getType(), V1 + "AWSStaticCredentialsProvider") && args.size() == 1 &&
                args.get(0) instanceof J.NewClass && TypeUtils.isOfClassType(args.get(0).getType(), V1 + "AnonymousAWSCredentials")) {
                imports.add(V2 + "AnonymousCredentialsProvider");
                code = "AnonymousCredentialsProvider.create()";
                args = emptyList();
            } else if (TypeUtils.isOfClassType(newClass.getType(), V1 + "EC2ContainerCredentialsProviderWrapper") && args.isEmpty()) {
                // As in MigrateAnonymousAndContainerAwsCredentials: the same sources the wrapper chose between
                imports.addAll(Arrays.asList(V2 + "AwsCredentialsProviderChain", V2 + "ContainerCredentialsProvider",
                        V2 + "InstanceProfileCredentialsProvider"));
                code = "AwsCredentialsProviderChain.of(ContainerCredentialsProvider.create(), InstanceProfileCredentialsProvider.create())";
            } else {
                for (ConstructorMapping mapping : CONSTRUCTORS) {
                    if (TypeUtils.isOfClassType(newClass.getType(), mapping.getV1Type()) && args.size() == mapping.getArguments()) {
                        imports.add(mapping.getV2Type());
                        code = AwsClientChain.simpleName(mapping.getV2Type()) + "." + mapping.getFactory() + "(" +
                               String.join(", ", nCopies(args.size(), "#{any()}")) + ")";
                        break;
                    }
                }
            }
        } else if (expression instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) expression;
            JavaType.Method mt = m.getMethodType();
            if (mt != null && "getInstance".equals(m.getSimpleName()) &&
                TypeUtils.isOfClassType(mt.getDeclaringType(), V1 + "DefaultAWSCredentialsProviderChain")) {
                imports.add(V2 + "DefaultCredentialsProvider");
                code = "DefaultCredentialsProvider.create()";
                args = emptyList();
            } else if (INSTANCE_PROFILE_GET_INSTANCE.matches(m)) {
                imports.add(V2 + "InstanceProfileCredentialsProvider");
                code = "InstanceProfileCredentialsProvider.create()";
                args = emptyList();
            }
        }
        if (code == null) {
            AwsCredentialsProviderFactories.Conversion conversion = AwsCredentialsProviderFactories.convert(expression, true);
            if (conversion != null) {
                code = conversion.code;
                args = conversion.args;
                imports.addAll(conversion.imports);
            }
        }
        if (code == null) {
            return null;
        }
        J result = KotlinTemplate.builder(code)
                .imports(imports.toArray(new String[0]))
                .parser(KotlinParser.builder().classpathFromResources(ctx, "auth", "identity-spi", "sdk-core", "utils", "aws-core", "regions", "sts"))
                .build()
                .apply(visitor.getCursor(), expression.getCoordinates().replace(), args.toArray());
        for (String fqn : imports) {
            visitor.maybeAddImport(fqn);
        }
        visitor.maybeRemoveImport(V1 + "AnonymousAWSCredentials");
        visitor.maybeRemoveImport(AwsCredentialsProviderFactories.V1_WEB_IDENTITY);
        visitor.maybeRemoveImport(AwsCredentialsProviderFactories.V1_ASSUME_ROLE);
        return result.withPrefix(expression.getPrefix());
    }

    /**
     * Renames the remaining v1 credential types, e.g. in declarations and parameters.
     */
    public static List<ChangeType> changeTypes() {
        List<ChangeType> changes = new ArrayList<>();
        TYPES.forEach((v1, v2) -> changes.add(new ChangeType(v1, v2, null)));
        return changes;
    }
}

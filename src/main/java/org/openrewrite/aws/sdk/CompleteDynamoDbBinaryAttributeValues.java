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
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import static java.util.Collections.singletonList;

/**
 * v2-migration converts {@code withB} and {@code getB}, but passes the {@code ByteBuffer}s of {@code setB}, {@code withBS} and
 * {@code setBS} to v2's {@code SdkBytes} parameters unchanged, and returns v2's {@code List<SdkBytes>} where {@code getBS}
 * returned {@code ByteBuffer}s.
 */
public class CompleteDynamoDbBinaryAttributeValues extends Recipe {

    private static final String ATTRIBUTE_VALUE = "software.amazon.awssdk.services.dynamodb.model.AttributeValue";
    private static final String BYTE_BUFFER = "java.nio.ByteBuffer";
    private static final String SDK_BYTES = "software.amazon.awssdk.core.SdkBytes";

    @Getter
    final String displayName = "Complete the migration of binary DynamoDB `AttributeValue`s";

    @Getter
    final String description = "Runs after `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. Wraps the `ByteBuffer`s that v1's " +
            "`setB`, `withBS` and `setBS` took in v2's `SdkBytes.fromByteBuffer`, and converts the `SdkBytes` that v2's `bs()` " +
            "returns back to the `ByteBuffer`s v1's `getBS()` returned.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>(ATTRIBUTE_VALUE, true), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J j = super.visitMethodInvocation(method, ctx);
                if (!(j instanceof J.MethodInvocation)) {
                    return j;
                }
                J.MethodInvocation m = (J.MethodInvocation) j;
                if (!isAttributeValueOrBuilder(m.getSelect())) {
                    return m;
                }
                boolean noArguments = m.getArguments().isEmpty() || m.getArguments().get(0) instanceof J.Empty;
                if ("b".equals(m.getSimpleName()) && !noArguments && m.getArguments().size() == 1 &&
                    TypeUtils.isOfClassType(m.getArguments().get(0).getType(), BYTE_BUFFER)) {
                    return m.withArguments(ListUtils.map(m.getArguments(), a -> fromByteBuffer(a, ctx)));
                }
                if (!"bs".equals(m.getSimpleName())) {
                    return m;
                }
                if (noArguments) {
                    if (m.getMethodType() != null && isCollectionOfByteBuffers(m.getMethodType().getReturnType())) {
                        maybeAddImport(SDK_BYTES);
                        maybeAddImport("java.util.stream.Collectors");
                        // typed as v2's getter, so that the next cycle leaves the converted call alone
                        J.MethodInvocation v2 = m.withMethodType(m.getMethodType().withReturnType(new JavaType.Parameterized(null,
                                JavaType.ShallowClass.build("java.util.List"), singletonList(JavaType.ShallowClass.build(SDK_BYTES)))));
                        return template("#{any(java.util.List)}.stream().map(SdkBytes::asByteBuffer).collect(Collectors.toList())", ctx)
                                .apply(getCursor(), m.getCoordinates().replace(), v2);
                    }
                    return m;
                }
                if (m.getArguments().stream().allMatch(a -> TypeUtils.isOfClassType(a.getType(), BYTE_BUFFER))) {
                    return m.withArguments(ListUtils.map(m.getArguments(), a -> fromByteBuffer(a, ctx)));
                }
                Expression arg = m.getArguments().get(0);
                if (m.getArguments().size() == 1 && isCollectionOfByteBuffers(arg.getType())) {
                    maybeAddImport(SDK_BYTES);
                    maybeAddImport("java.util.stream.Collectors");
                    Expression converted = template("#{any(java.util.Collection)}.stream().map(SdkBytes::fromByteBuffer).collect(Collectors.toList())", ctx)
                            .apply(new Cursor(getCursor(), arg), arg.getCoordinates().replace(), arg);
                    return m.withArguments(ListUtils.mapFirst(m.getArguments(), a -> converted.withPrefix(a.getPrefix())));
                }
                return m;
            }

            private Expression fromByteBuffer(Expression arg, ExecutionContext ctx) {
                maybeAddImport(SDK_BYTES);
                Expression converted = template("SdkBytes.fromByteBuffer(#{any(java.nio.ByteBuffer)})", ctx)
                        .apply(new Cursor(getCursor(), arg), arg.getCoordinates().replace(), arg);
                return converted.withPrefix(arg.getPrefix());
            }
        });
    }

    private static JavaTemplate template(String code, ExecutionContext ctx) {
        return JavaTemplate.builder(code)
                .imports(SDK_BYTES, "java.util.stream.Collectors")
                .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "sdk-core"))
                .build();
    }

    private static boolean isCollectionOfByteBuffers(@Nullable JavaType type) {
        JavaType.Parameterized p = TypeUtils.asParameterized(type);
        return p != null && TypeUtils.isAssignableTo("java.util.Collection", p) && p.getTypeParameters().size() == 1 &&
               TypeUtils.isOfClassType(elementType(p.getTypeParameters().get(0)), BYTE_BUFFER);
    }

    private static @Nullable JavaType elementType(JavaType type) {
        if (type instanceof JavaType.GenericTypeVariable && ((JavaType.GenericTypeVariable) type).getBounds().size() == 1) {
            return ((JavaType.GenericTypeVariable) type).getBounds().get(0);
        }
        return type;
    }

    private static boolean isAttributeValueOrBuilder(@Nullable Expression e) {
        if (e == null) {
            return false;
        }
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(e.getType());
        if (type != null && (ATTRIBUTE_VALUE.equals(type.getFullyQualifiedName()) ||
                             (ATTRIBUTE_VALUE + "$Builder").equals(type.getFullyQualifiedName()))) {
            return true;
        }
        // the builder chains v2-migration writes are not always type attributed
        return e instanceof J.MethodInvocation && isAttributeValueOrBuilder(((J.MethodInvocation) e).getSelect());
    }
}

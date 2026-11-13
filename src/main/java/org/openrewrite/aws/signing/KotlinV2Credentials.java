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

import org.jspecify.annotations.Nullable;
import org.openrewrite.Tree;
import org.openrewrite.aws.sdk.KotlinAwsCredentialMappings;
import org.openrewrite.aws.sdk.KotlinAwsCredentialMappings.ConstructorMapping;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;

/**
 * Builds the v2 form of Kotlin credential expressions from {@link KotlinAwsCredentialMappings}'s
 * table without a template, since the interceptor rewrite also maps credentials nested in arguments.
 */
final class KotlinV2Credentials {

    private static final String V1 = "com.amazonaws.auth.";
    private static final String V2 = "software.amazon.awssdk.auth.credentials.";

    private KotlinV2Credentials() {
    }

    /**
     * Maps a v1 credentials expression, and the v1 credentials constructed in its arguments, to its v2 form. Returns the
     * expression unchanged when it is not a known v1 constructor. {@code addImport} receives each v2 type used.
     */
    static Expression toV2(Expression e, Consumer<String> addImport) {
        if (e instanceof J.NewClass) {
            J.NewClass nc = (J.NewClass) e;
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(nc.getType());
            String v2 = type == null || nc.getBody() != null ? null : v2Type(type.getFullyQualifiedName(), nc.getArguments());
            if (v2 != null) {
                List<Expression> args = new ArrayList<>();
                for (Expression arg : nc.getArguments()) {
                    args.add(toV2(arg, addImport));
                }
                addImport.accept(v2);
                return staticCreate(nc, v2, args);
            }
        } else if (e instanceof J.MethodInvocation) {
            // DefaultAWSCredentialsProviderChain.getInstance()
            J.MethodInvocation m = (J.MethodInvocation) e;
            if ("getInstance".equals(m.getSimpleName()) && m.getMethodType() != null &&
                TypeUtils.isOfClassType(m.getMethodType().getDeclaringType(), V1 + "DefaultAWSCredentialsProviderChain") &&
                m.getSelect() instanceof J.Identifier) {
                addImport.accept(V2 + "DefaultCredentialsProvider");
                J.Identifier select = (J.Identifier) m.getSelect();
                JavaType.ShallowClass v2 = JavaType.ShallowClass.build(V2 + "DefaultCredentialsProvider");
                JavaType.Method create = method(v2, emptyList());
                return m.withSelect(select.withSimpleName("DefaultCredentialsProvider").withType(v2))
                        .withName(m.getName().withSimpleName("create").withType(create))
                        .withMethodType(create);
            }
        }
        return e;
    }

    static boolean maps(@Nullable Expression e) {
        if (e instanceof J.NewClass) {
            J.NewClass nc = (J.NewClass) e;
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(nc.getType());
            return type != null && v2Type(type.getFullyQualifiedName(), nc.getArguments()) != null;
        }
        return e instanceof J.MethodInvocation && "getInstance".equals(((J.MethodInvocation) e).getSimpleName()) &&
               ((J.MethodInvocation) e).getMethodType() != null &&
               TypeUtils.isOfClassType(((J.MethodInvocation) e).getMethodType().getDeclaringType(), V1 + "DefaultAWSCredentialsProviderChain");
    }

    private static @Nullable String v2Type(String v1Type, List<Expression> arguments) {
        int count = 0;
        for (Expression arg : arguments) {
            if (!(arg instanceof J.Empty)) {
                count++;
            }
        }
        for (ConstructorMapping mapping : KotlinAwsCredentialMappings.CONSTRUCTORS) {
            if (mapping.getV1Type().equals(v1Type) && mapping.getArguments() == count) {
                return mapping.getV2Type();
            }
        }
        return null;
    }

    private static J.MethodInvocation staticCreate(J.NewClass nc, String v2, List<Expression> args) {
        JavaType.ShallowClass type = JavaType.ShallowClass.build(v2);
        List<JavaType> parameterTypes = new ArrayList<>();
        for (Expression arg : args) {
            if (!(arg instanceof J.Empty)) {
                parameterTypes.add(arg.getType() == null ? JavaType.Unknown.getInstance() : arg.getType());
            }
        }
        J.Identifier select = new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(),
                type.getClassName(), type, null);
        JavaType.Method create = method(type, parameterTypes);
        J.Identifier name = new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), "create", create, null);
        JContainer<Expression> arguments = nc.getPadding().getArguments();
        List<JRightPadded<Expression>> padded = arguments.getPadding().getElements();
        List<JRightPadded<Expression>> mapped = new ArrayList<>(padded.size());
        for (int i = 0; i < padded.size(); i++) {
            mapped.add(padded.get(i).withElement(args.get(i)));
        }
        return new J.MethodInvocation(Tree.randomId(), nc.getPrefix(), nc.getMarkers(), JRightPadded.build(select), null,
                name, JContainer.build(arguments.getBefore(), mapped, arguments.getMarkers()), create);
    }

    private static JavaType.Method method(JavaType.FullyQualified type, List<JavaType> parameterTypes) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parameterTypes.size(); i++) {
            names.add("arg" + i);
        }
        return new JavaType.Method(null, Flag.Public.getBitMask() | Flag.Static.getBitMask(), type, "create", type,
                names, parameterTypes, emptyList(), emptyList(), null, null);
    }
}

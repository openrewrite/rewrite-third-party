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
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.MethodCall;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * The v2 form of v1 credentials providers whose v2 counterparts are only created through factories and builders:
 * {@code WebIdentityTokenCredentialsProvider} and {@code STSAssumeRoleSessionCredentialsProvider}. Shared by the Java and
 * Kotlin recipes, so both produce the same v2 code.
 */
final class AwsCredentialsProviderFactories {

    static final String V1_WEB_IDENTITY = "com.amazonaws.auth.WebIdentityTokenCredentialsProvider";
    static final String V2_WEB_IDENTITY = "software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider";
    static final String V1_ASSUME_ROLE = "com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider";
    static final String V2_ASSUME_ROLE = "software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider";

    private static final String V2_CREDENTIALS = "software.amazon.awssdk.auth.credentials.";
    private static final String STS_CLIENT = "software.amazon.awssdk.services.sts.StsClient";
    private static final String ASSUME_ROLE_REQUEST = "software.amazon.awssdk.services.sts.model.AssumeRoleRequest";

    private static final MethodMatcher NEW_WEB_IDENTITY = new MethodMatcher(V1_WEB_IDENTITY + " <constructor>()");
    private static final MethodMatcher WEB_IDENTITY_CREATE = new MethodMatcher(V1_WEB_IDENTITY + " create()");
    private static final MethodMatcher WEB_IDENTITY_BUILDER = new MethodMatcher(V1_WEB_IDENTITY + " builder()");
    private static final MethodMatcher WEB_IDENTITY_OPTION = new MethodMatcher(V1_WEB_IDENTITY + ".Builder *(java.lang.String)");
    private static final MethodMatcher WEB_IDENTITY_BUILD = new MethodMatcher(V1_WEB_IDENTITY + ".Builder build()");
    private static final MethodMatcher NEW_ASSUME_ROLE = new MethodMatcher(V1_ASSUME_ROLE + " <constructor>(..)");
    private static final MethodMatcher NEW_ASSUME_ROLE_BUILDER = new MethodMatcher(V1_ASSUME_ROLE + ".Builder <constructor>(java.lang.String, java.lang.String)");
    private static final MethodMatcher ASSUME_ROLE_OPTION = new MethodMatcher(V1_ASSUME_ROLE + ".Builder with*(*)");
    private static final MethodMatcher ASSUME_ROLE_BUILD = new MethodMatcher(V1_ASSUME_ROLE + ".Builder build()");

    static final class Conversion {
        final String code;
        final List<Expression> args;
        final Set<String> imports;

        Conversion(String code, List<Expression> args, Set<String> imports) {
            this.code = code;
            this.args = args;
            this.imports = imports;
        }
    }

    private AwsCredentialsProviderFactories() {
    }

    /**
     * @return the v2 code for a complete v1 provider construction, or null when the expression is not one this maps.
     */
    static @Nullable Conversion convert(Expression e, boolean kotlin) {
        if (!(e instanceof J.NewClass) && !(e instanceof J.MethodInvocation)) {
            return null;
        }
        Conversion webIdentity = webIdentity(e, kotlin);
        return webIdentity != null ? webIdentity : assumeRole(e, kotlin);
    }

    private static @Nullable Conversion webIdentity(Expression e, boolean kotlin) {
        if (NEW_WEB_IDENTITY.matches(e) && !hasBody(e) || WEB_IDENTITY_CREATE.matches(e)) {
            return new Conversion("WebIdentityTokenFileCredentialsProvider.create()", Collections.emptyList(), Collections.singleton(V2_WEB_IDENTITY));
        }
        if (!WEB_IDENTITY_BUILD.matches(e)) {
            return null;
        }
        LinkedList<J.MethodInvocation> options = new LinkedList<>();
        Expression select = ((J.MethodInvocation) e).getSelect();
        while (WEB_IDENTITY_OPTION.matches(select)) {
            options.addFirst((J.MethodInvocation) select);
            select = ((J.MethodInvocation) select).getSelect();
        }
        if (!WEB_IDENTITY_BUILDER.matches(select)) {
            return null;
        }
        Set<String> imports = new LinkedHashSet<>(Collections.singleton(V2_WEB_IDENTITY));
        if (options.isEmpty()) {
            return new Conversion("WebIdentityTokenFileCredentialsProvider.create()", Collections.emptyList(), imports);
        }
        StringBuilder code = new StringBuilder("WebIdentityTokenFileCredentialsProvider" + AwsClientChain.whitespaceBefore((J.MethodInvocation) select) + ".builder()");
        List<Expression> args = new ArrayList<>();
        for (J.MethodInvocation option : options) {
            code.append(AwsClientChain.whitespaceBefore(option));
            switch (option.getSimpleName()) {
                case "roleArn":
                case "roleSessionName":
                    code.append('.').append(option.getSimpleName()).append('(').append(p(kotlin, "java.lang.String")).append(')');
                    break;
                case "webIdentityTokenFile":
                    // v1 takes the path as a String, v2 as a Path
                    imports.add("java.nio.file.Paths");
                    code.append(".webIdentityTokenFile(Paths.get(").append(p(kotlin, "java.lang.String")).append("))");
                    break;
                default:
                    return null;
            }
            args.add(option.getArguments().get(0));
        }
        code.append(AwsClientChain.whitespaceBefore((J.MethodInvocation) e)).append(".build()");
        return new Conversion(code.toString(), args, imports);
    }

    /**
     * v1 assumes the role with an STS client using the default credentials chain unless long-lived credentials are given,
     * which a v2 {@code StsClient} also does. v1 built that client with the legacy constructor, which always calls the
     * global STS endpoint, so the v2 client is pinned to {@code Region.AWS_GLOBAL} rather than the environment's region.
     */
    private static @Nullable Conversion assumeRole(Expression e, boolean kotlin) {
        Expression longLived = null;
        List<Expression> request = new ArrayList<>();
        StringBuilder requestOptions = new StringBuilder();
        Expression roleArn;
        Expression sessionName;
        if (NEW_ASSUME_ROLE.matches(e) && !hasBody(e)) {
            JavaType.Method ctor = ((MethodCall) e).getMethodType();
            List<Expression> ctorArgs = AwsClientChain.arguments(((MethodCall) e).getArguments());
            if (ctor == null || ctorArgs.size() == 3 && !isCredentials(ctor.getParameterTypes().get(0))) {
                return null;
            }
            if (ctorArgs.size() == 3) {
                longLived = ctorArgs.get(0);
            } else if (ctorArgs.size() != 2) {
                return null;
            }
            roleArn = ctorArgs.get(ctorArgs.size() - 2);
            sessionName = ctorArgs.get(ctorArgs.size() - 1);
        } else {
            if (!ASSUME_ROLE_BUILD.matches(e)) {
                return null;
            }
            LinkedList<J.MethodInvocation> options = new LinkedList<>();
            Expression select = ((J.MethodInvocation) e).getSelect();
            while (ASSUME_ROLE_OPTION.matches(select)) {
                options.addFirst((J.MethodInvocation) select);
                select = ((J.MethodInvocation) select).getSelect();
            }
            if (!NEW_ASSUME_ROLE_BUILDER.matches(select) || hasBody(select)) {
                return null;
            }
            List<Expression> builderArgs = AwsClientChain.arguments(((MethodCall) select).getArguments());
            roleArn = builderArgs.get(0);
            sessionName = builderArgs.get(1);
            for (J.MethodInvocation option : options) {
                Expression optionArg = option.getArguments().get(0);
                switch (option.getSimpleName()) {
                    case "withLongLivedCredentials":
                    case "withLongLivedCredentialsProvider":
                        longLived = optionArg;
                        continue;
                    case "withRoleSessionDurationSeconds":
                        requestOptions.append(".durationSeconds(").append(p(kotlin, "java.lang.Integer")).append(')');
                        break;
                    case "withExternalId":
                        requestOptions.append(".externalId(").append(p(kotlin, "java.lang.String")).append(')');
                        break;
                    case "withScopeDownPolicy":
                        requestOptions.append(".policy(").append(p(kotlin, "java.lang.String")).append(')');
                        break;
                    case "withSourceIdentity":
                        requestOptions.append(".sourceIdentity(").append(p(kotlin, "java.lang.String")).append(')');
                        break;
                    default:
                        return null;
                }
                request.add(optionArg);
            }
        }
        Set<String> imports = new LinkedHashSet<>(Arrays.asList(V2_ASSUME_ROLE, STS_CLIENT, ASSUME_ROLE_REQUEST, "software.amazon.awssdk.regions.Region"));
        List<Expression> args = new ArrayList<>();
        String stsClient = "StsClient.builder().region(Region.AWS_GLOBAL).build()";
        if (longLived != null) {
            if (TypeUtils.isAssignableTo(AwsClientChain.V1_CREDENTIALS, longLived.getType())) {
                imports.add(V2_CREDENTIALS + "StaticCredentialsProvider");
                stsClient = "StsClient.builder().region(Region.AWS_GLOBAL).credentialsProvider(StaticCredentialsProvider.create(" +
                            p(kotlin, V2_CREDENTIALS + "AwsCredentials") + ")).build()";
            } else {
                stsClient = "StsClient.builder().region(Region.AWS_GLOBAL).credentialsProvider(" + p(kotlin, V2_CREDENTIALS + "AwsCredentialsProvider") + ").build()";
            }
            args.add(longLived);
        }
        args.add(roleArn);
        args.add(sessionName);
        args.addAll(request);
        String code = "StsAssumeRoleCredentialsProvider.builder()" +
                      ".stsClient(" + stsClient + ")" +
                      ".refreshRequest(AssumeRoleRequest.builder()" +
                      ".roleArn(" + p(kotlin, "java.lang.String") + ")" +
                      ".roleSessionName(" + p(kotlin, "java.lang.String") + ")" + requestOptions + ".build())" +
                      ".build()";
        return new Conversion(code, args, imports);
    }

    private static boolean isCredentials(JavaType type) {
        return TypeUtils.isOfClassType(type, AwsClientChain.V1_CREDENTIALS) || TypeUtils.isOfClassType(type, AwsClientChain.V1_CREDENTIALS_PROVIDER);
    }

    private static boolean hasBody(@Nullable Expression e) {
        return e instanceof J.NewClass && ((J.NewClass) e).getBody() != null;
    }

    private static String p(boolean kotlin, String type) {
        return kotlin ? "#{any()}" : "#{any(" + type + ")}";
    }
}

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
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.HashMap;
import java.util.Map;

public class MigrateAwsSdkGlobalConfiguration extends Recipe {

    private static final String GLOBAL = "com.amazonaws.SDKGlobalConfiguration";
    private static final String CBOR = "AWS_CBOR_DISABLE_SYSTEM_PROPERTY";
    private static final MethodMatcher SET_PROPERTY = new MethodMatcher("java.lang.System setProperty(String, String)");
    private static final MethodMatcher CLEAR_PROPERTY = new MethodMatcher("java.lang.System clearProperty(String)");

    // v1 constants whose v2 SdkSystemSetting has the same property or environment variable name
    private static final Map<String, String> SAME_NAME = new HashMap<>();

    static {
        SAME_NAME.put("ACCESS_KEY_SYSTEM_PROPERTY", "AWS_ACCESS_KEY_ID.property()");
        SAME_NAME.put("SESSION_TOKEN_SYSTEM_PROPERTY", "AWS_SESSION_TOKEN.property()");
        SAME_NAME.put("AWS_REGION_SYSTEM_PROPERTY", "AWS_REGION.property()");
        SAME_NAME.put("ACCESS_KEY_ENV_VAR", "AWS_ACCESS_KEY_ID.environmentVariable()");
        SAME_NAME.put("ALTERNATE_SECRET_KEY_ENV_VAR", "AWS_SECRET_ACCESS_KEY.environmentVariable()");
        SAME_NAME.put("AWS_SESSION_TOKEN_ENV_VAR", "AWS_SESSION_TOKEN.environmentVariable()");
        SAME_NAME.put("AWS_REGION_ENV_VAR", "AWS_REGION.environmentVariable()");
        SAME_NAME.put("AWS_WEB_IDENTITY_ENV_VAR", "AWS_WEB_IDENTITY_TOKEN_FILE.environmentVariable()");
        SAME_NAME.put("AWS_ROLE_ARN_ENV_VAR", "AWS_ROLE_ARN.environmentVariable()");
        SAME_NAME.put("AWS_ROLE_SESSION_NAME_ENV_VAR", "AWS_ROLE_SESSION_NAME.environmentVariable()");
        SAME_NAME.put("AWS_EC2_METADATA_DISABLED_ENV_VAR", "AWS_EC2_METADATA_DISABLED.environmentVariable()");
    }

    // The value of every other v1 constant, inlined where v2 has no setting of the same name, so the code keeps reading what v1 read
    private static final Map<String, String> VALUES = new HashMap<>();

    static {
        String[][] values = {
                {"DISABLE_CERT_CHECKING_SYSTEM_PROPERTY", "com.amazonaws.sdk.disableCertChecking"}, {"DEFAULT_METRICS_SYSTEM_PROPERTY", "com.amazonaws.sdk.enableDefaultMetrics"},
                {"ACCESS_KEY_SYSTEM_PROPERTY", "aws.accessKeyId"}, {"SECRET_KEY_SYSTEM_PROPERTY", "aws.secretKey"},
                {"AWS_ACCOUNT_ID_SYSTEM_PROPERTY", "aws.accountId"}, {"SESSION_TOKEN_SYSTEM_PROPERTY", "aws.sessionToken"},
                {"AWS_REGION_SYSTEM_PROPERTY", "aws.region"}, {"EC2_METADATA_SERVICE_OVERRIDE_SYSTEM_PROPERTY", "com.amazonaws.sdk.ec2MetadataServiceEndpointOverride"},
                {"EC2_METADATA_SERVICE_OVERRIDE_ENV_VAR", "AWS_EC2_METADATA_SERVICE_ENDPOINT"}, {"AWS_METADATA_SERVICE_TIMEOUT_ENV_VAR", "AWS_METADATA_SERVICE_TIMEOUT"},
                {"RETRY_THROTTLING_SYSTEM_PROPERTY", "com.amazonaws.sdk.enableThrottledRetry"}, {"REGIONS_FILE_OVERRIDE_SYSTEM_PROPERTY", "com.amazonaws.regions.RegionUtils.fileOverride"},
                {"DISABLE_REMOTE_REGIONS_FILE_SYSTEM_PROPERTY", "com.amazonaws.regions.RegionUtils.disableRemote"}, {"ENABLE_S3_SIGV4_SYSTEM_PROPERTY", "com.amazonaws.services.s3.enableV4"},
                {"ENFORCE_S3_SIGV4_SYSTEM_PROPERTY", "com.amazonaws.services.s3.enforceV4"}, {"DISABLE_S3_IMPLICIT_GLOBAL_CLIENTS_SYSTEM_PROPERTY", "com.amazonaws.services.s3.disableImplicitGlobalClients"},
                {"ENABLE_IN_REGION_OPTIMIZED_MODE", "com.amazonaws.sdk.enableInRegionOptimizedMode"}, {"DEFAULT_S3_STREAM_BUFFER_SIZE", "com.amazonaws.sdk.s3.defaultStreamBufferSize"},
                {"PROFILING_SYSTEM_PROPERTY", "com.amazonaws.sdk.enableRuntimeProfiling"}, {"DEFAULT_AWS_CSM_HOST", "127.0.0.1"},
                {"DEFAULT_AWS_CSM_CLIENT_ID", ""}, {"AWS_CSM_ENABLED_SYSTEM_PROPERTY", "com.amazonaws.sdk.csm.enabled"},
                {"AWS_CSM_HOST_SYSTEM_PROPERTY", "com.amazonaws.sdk.csm.host"}, {"AWS_CSM_PORT_SYSTEM_PROPERTY", "com.amazonaws.sdk.csm.port"},
                {"AWS_CSM_CLIENT_ID_SYSTEM_PROPERTY", "com.amazonaws.sdk.csm.clientId"}, {"ACCESS_KEY_ENV_VAR", "AWS_ACCESS_KEY_ID"},
                {"ALTERNATE_ACCESS_KEY_ENV_VAR", "AWS_ACCESS_KEY"}, {"SECRET_KEY_ENV_VAR", "AWS_SECRET_KEY"},
                {"ALTERNATE_SECRET_KEY_ENV_VAR", "AWS_SECRET_ACCESS_KEY"}, {"AWS_ACCOUNT_ID_ENV_VAR", "AWS_ACCOUNT_ID"},
                {"AWS_SESSION_TOKEN_ENV_VAR", "AWS_SESSION_TOKEN"}, {"AWS_WEB_IDENTITY_ENV_VAR", "AWS_WEB_IDENTITY_TOKEN_FILE"},
                {"AWS_ROLE_ARN_ENV_VAR", "AWS_ROLE_ARN"}, {"AWS_ROLE_SESSION_NAME_ENV_VAR", "AWS_ROLE_SESSION_NAME"},
                {"AWS_REGION_ENV_VAR", "AWS_REGION"}, {"AWS_CONFIG_FILE_ENV_VAR", "AWS_CONFIG_FILE"},
                {"AWS_CBOR_DISABLE_ENV_VAR", "AWS_CBOR_DISABLE"}, {"AWS_CBOR_DISABLE_SYSTEM_PROPERTY", "com.amazonaws.sdk.disableCbor"},
                {"AWS_ION_BINARY_DISABLE_ENV_VAR", "AWS_ION_BINARY_DISABLE"}, {"AWS_ION_BINARY_DISABLE_SYSTEM_PROPERTY", "com.amazonaws.sdk.disableIonBinary"},
                {"AWS_EC2_METADATA_DISABLED_ENV_VAR", "AWS_EC2_METADATA_DISABLED"}, {"AWS_EC2_METADATA_DISABLED_SYSTEM_PROPERTY", "com.amazonaws.sdk.disableEc2Metadata"},
                {"AWS_EC2_METADATA_V1_DISABLED_ENV_VAR", "AWS_EC2_METADATA_V1_DISABLED"}, {"AWS_EC2_METADATA_V1_DISABLED_SYSTEM_PROPERTY", "com.amazonaws.sdk.disableEc2MetadataV1"},
                {"AWS_EC2_METADATA_V1_DISABLED_PROFILE_PROPERTY", "ec2_metadata_v1_disabled"}, {"AWS_CSM_ENABLED_ENV_VAR", "AWS_CSM_ENABLED"},
                {"AWS_CSM_HOST_ENV_VAR", "AWS_CSM_HOST"}, {"AWS_CSM_PORT_ENV_VAR", "AWS_CSM_PORT"},
                {"AWS_CSM_CLIENT_ID_ENV_VAR", "AWS_CSM_CLIENT_ID"}, {"AWS_RETRY_MODE_SYSTEM_PROPERTY", "com.amazonaws.sdk.retryMode"},
                {"AWS_RETRY_MODE_ENV_VAR", "AWS_RETRY_MODE"}, {"AWS_ACCOUNT_ID_ENDPOINT_MODE_SYSTEM_PROPERTY", "com.amazonaws.sdk.accountIdEndpointMode"},
                {"AWS_ACCOUNT_ID_ENDPOINT_MODE_ENV_VAR", "AWS_ACCOUNT_ID_ENDPOINT_MODE"}, {"AWS_MAX_ATTEMPTS_SYSTEM_PROPERTY", "com.amazonaws.sdk.maxAttempts"},
                {"AWS_MAX_ATTEMPTS_ENV_VAR", "AWS_MAX_ATTEMPTS"}
        };
        for (String[] v : values) {
            VALUES.put(v[0], v[1]);
        }
    }

    // v1's checks of its own settings, rewritten with the same property names and parsing
    private static final Map<String, String> CHECKS = new HashMap<>();

    static {
        CHECKS.put("isInRegionOptimizedModeEnabled", enabled(property("com.amazonaws.sdk.enableInRegionOptimizedMode")));
        CHECKS.put("isCertCheckingDisabled", enabled(property("com.amazonaws.sdk.disableCertChecking")));
        CHECKS.put("isCborDisabled", enabled(property("com.amazonaws.sdk.disableCbor")) + " || " + enabled(env("AWS_CBOR_DISABLE")));
        CHECKS.put("isIonBinaryDisabled", enabled(property("com.amazonaws.sdk.disableIonBinary")) + " || " +
                                          enabled(env("AWS_ION_BINARY_DISABLE")));
        CHECKS.put("isEc2MetadataDisabled", "Boolean.parseBoolean(" + property("com.amazonaws.sdk.disableEc2Metadata") + ") || " +
                                            "Boolean.parseBoolean(" + env("AWS_EC2_METADATA_DISABLED") + ")");
        CHECKS.put("isEc2MetadataV1Disabled", "Boolean.parseBoolean(" + property("com.amazonaws.sdk.disableEc2MetadataV1") + ") || " +
                                              "Boolean.parseBoolean(" + env("AWS_EC2_METADATA_V1_DISABLED") + ")");
    }

    private static String property(String name) {
        return "System.getProperty(\"" + name + "\")";
    }

    private static String env(String name) {
        return "System.getenv(\"" + name + "\")";
    }

    // v1's isPropertyEnabled: set and not "false"
    private static String enabled(String read) {
        return "(" + read + " != null && !\"false\".equalsIgnoreCase(" + read + "))";
    }

    private static final MethodMatcher CHECK = new MethodMatcher(GLOBAL + " is*()");
    private static final MethodMatcher GET_TIME_OFFSET = new MethodMatcher(GLOBAL + " getGlobalTimeOffset()");
    private static final MethodMatcher SET_TIME_OFFSET = new MethodMatcher(GLOBAL + " setGlobalTimeOffset(int)");

    @Getter
    final String displayName = "Migrate AWS SDK v1 `SDKGlobalConfiguration` settings";

    @Getter
    final String description = "v1's `com.amazonaws.sdk.disableCbor` property becomes v2's `aws.cborEnabled` with the value inverted at the " +
                               "`System.setProperty` call site (v1 treats any value but `false` as disabled). Constants whose v2 " +
                               "`SdkSystemSetting` has the same name map to it; the others are inlined as the property or variable name they hold, " +
                               "and v1's `isXxx()` checks become the same `System.getProperty`/`System.getenv` tests. v2 keeps no global clock " +
                               "offset, so `setGlobalTimeOffset` is dropped and `getGlobalTimeOffset()` becomes `0`.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(new UsesType<>(GLOBAL, false), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (SET_PROPERTY.matches(method) && isConstant(method.getArguments().get(0), CBOR)) {
                    Expression value = method.getArguments().get(1);
                    removeImports();
                    if (value instanceof J.Literal && ((J.Literal) value).getValue() instanceof String) {
                        String inverted = String.valueOf("false".equalsIgnoreCase((String) ((J.Literal) value).getValue()));
                        return template("System.setProperty(SdkSystemSetting.CBOR_ENABLED.property(), \"" + inverted + "\")", ctx)
                                .apply(getCursor(), method.getCoordinates().replace());
                    }
                    return template("System.setProperty(SdkSystemSetting.CBOR_ENABLED.property(), String.valueOf(\"false\".equalsIgnoreCase(#{any(String)})))", ctx)
                            .apply(getCursor(), method.getCoordinates().replace(), value);
                }
                if (CHECK.matches(method) && CHECKS.containsKey(method.getSimpleName())) {
                    removeImports();
                    String check = CHECKS.get(method.getSimpleName());
                    // Parenthesized when it is an `||` inside a larger expression, so it keeps its precedence there
                    Object parent = getCursor().getParentTreeCursor().getValue();
                    if (check.contains("||") && !(parent instanceof J.Return || parent instanceof J.ControlParentheses ||
                                                  parent instanceof J.VariableDeclarations.NamedVariable || parent instanceof J.MethodInvocation)) {
                        check = "(" + check + ")";
                    }
                    return JavaTemplate.apply(check, getCursor(), method.getCoordinates().replace());
                }
                if (GET_TIME_OFFSET.matches(method)) {
                    // v2 corrects clock skew per client and keeps no global offset, so none is ever reported
                    removeImports();
                    return new J.Literal(Tree.randomId(), method.getPrefix(), Markers.EMPTY, 0, "0", null, JavaType.Primitive.Int);
                }
                if (SET_TIME_OFFSET.matches(method) && getCursor().getParentTreeCursor().getValue() instanceof J.Block) {
                    // v2 detects clock skew from each response and adjusts that client's signing time itself
                    removeImports();
                    return null;
                }
                if (CLEAR_PROPERTY.matches(method) && isConstant(method.getArguments().get(0), CBOR)) {
                    removeImports();
                    return template("System.clearProperty(SdkSystemSetting.CBOR_ENABLED.property())", ctx)
                            .apply(getCursor(), method.getCoordinates().replace());
                }
                return super.visitMethodInvocation(method, ctx);
            }

            @Override
            public J visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                J.FieldAccess f = (J.FieldAccess) super.visitFieldAccess(fieldAccess, ctx);
                return constant(f, f.getName().getFieldType(), ctx);
            }

            @Override
            public J visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                Object parent = getCursor().getParentTreeCursor().getValue();
                if (parent instanceof J.FieldAccess || parent instanceof J.Import) {
                    return identifier;
                }
                return constant(identifier, identifier.getFieldType(), ctx);
            }

            private J constant(Expression e, JavaType.@Nullable Variable field, ExecutionContext ctx) {
                if (field == null || !TypeUtils.isOfClassType(field.getOwner(), GLOBAL)) {
                    return e;
                }
                String mapped = SAME_NAME.get(field.getName());
                if (mapped == null) {
                    removeImports();
                    if ("DEFAULT_AWS_CSM_PORT".equals(field.getName())) {
                        return new J.Literal(Tree.randomId(), e.getPrefix(), Markers.EMPTY, 31000, "31000", null, JavaType.Primitive.Int);
                    }
                    String value = VALUES.get(field.getName());
                    return value == null ? e :
                            new J.Literal(Tree.randomId(), e.getPrefix(), Markers.EMPTY, value, "\"" + value + "\"", null, JavaType.Primitive.String);
                }
                removeImports();
                return template("SdkSystemSetting." + mapped, ctx).apply(getCursor(), e.getCoordinates().replace());
            }

            private boolean isConstant(Expression e, String name) {
                JavaType.Variable field = e instanceof J.FieldAccess ? ((J.FieldAccess) e).getName().getFieldType() :
                        e instanceof J.Identifier ? ((J.Identifier) e).getFieldType() : null;
                return field != null && name.equals(field.getName()) && TypeUtils.isOfClassType(field.getOwner(), GLOBAL);
            }

            private void removeImports() {
                maybeRemoveImport(GLOBAL);
                maybeRemoveImport(GLOBAL + ".*");
                for (String name : VALUES.keySet()) {
                    maybeRemoveImport(GLOBAL + "." + name);
                }
                maybeRemoveImport(GLOBAL + ".DEFAULT_AWS_CSM_PORT");
                maybeRemoveImport(GLOBAL + "." + CBOR);
                maybeAddImport("software.amazon.awssdk.core.SdkSystemSetting");
            }

            private JavaTemplate template(String code, ExecutionContext ctx) {
                return JavaTemplate.builder(code)
                        .imports("software.amazon.awssdk.core.SdkSystemSetting")
                        .javaParser(AwsV2Parser.parser(ctx))
                        .build();
            }
        });
    }
}

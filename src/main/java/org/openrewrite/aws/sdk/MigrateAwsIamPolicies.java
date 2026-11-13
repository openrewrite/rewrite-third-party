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
import org.openrewrite.Tree;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.SearchResult;

import java.util.*;

// v1 action enums, principals and conditions carry the strings v2's builders take, so they are resolved to those strings here
public class MigrateAwsIamPolicies extends Recipe {

    private static final String PKG = "com.amazonaws.auth.policy.";
    private static final String STATEMENT = PKG + "Statement";
    private static final String POLICY = PKG + "Policy";
    private static final String V2_PKG = "software.amazon.awssdk.policybuilder.iam.";

    private static final MethodMatcher NEW_STATEMENT = new MethodMatcher(STATEMENT + " <constructor>(" + STATEMENT + ".Effect)");
    private static final MethodMatcher NEW_POLICY = new MethodMatcher(POLICY + " <constructor>(..)");
    private static final MethodMatcher NEW_RESOURCE = new MethodMatcher(PKG + "Resource <constructor>(String)");
    private static final MethodMatcher TO_JSON = new MethodMatcher(POLICY + " toJson()");
    private static final MethodMatcher FROM_JSON = new MethodMatcher(POLICY + " fromJson(String)");

    private static final String CONDITIONS = PKG + "conditions.";
    private static final String PRINCIPAL = PKG + "Principal";
    private static final MethodMatcher GET_ACTION_NAME = new MethodMatcher(PKG + "Action getActionName()", true);
    private static final MethodMatcher NEW_PRINCIPAL = new MethodMatcher(PRINCIPAL + " <constructor>(..)");
    private static final MethodMatcher NEW_CONDITION = new MethodMatcher(CONDITIONS + "*Condition <constructor>(..)");
    private static final MethodMatcher CONDITION_FACTORY = new MethodMatcher(CONDITIONS + "ConditionFactory new*Condition(..)");

    static final Map<String, String> S3_ACTIONS = new HashMap<>();

    // Prefix of each v1 action enum; every constant other than All*Actions is named after its action
    static final Map<String, String> ACTION_PREFIXES = new HashMap<>();

    // Ids of v1's Principal.Services and Principal.WebIdentityProviders constants
    static final Map<String, String> PRINCIPAL_IDS = new HashMap<>();

    // ConditionFactory's condition keys
    static final Map<String, String> CONDITION_KEYS = new HashMap<>();

    static {
        String[][] prefixes = {
                {"AutoScalingActions", "autoscaling"}, {"AutoscalingActions", "application-autoscaling"}, {"BudgetsActions", "budgets"},
                {"CloudFormationActions", "cloudformation"}, {"CloudFrontActions", "cloudfront"}, {"CloudWatchActions", "cloudwatch"},
                {"CodebuildActions", "codebuild"}, {"CodecommitActions", "codecommit"}, {"CodepipelineActions", "codepipeline"},
                {"DevicefarmActions", "devicefarm"}, {"DirectConnectActions", "directconnect"}, {"DiscoveryActions", "discovery"},
                {"DynamoDBv2Actions", "dynamodb"}, {"EC2Actions", "ec2"}, {"ElastiCacheActions", "elasticache"},
                {"ElasticBeanstalkActions", "elasticbeanstalk"}, {"ElasticLoadBalancingActions", "elasticloadbalancing"},
                {"ElasticMapReduceActions", "elasticmapreduce"}, {"GameliftActions", "gamelift"}, {"GlacierActions", "glacier"},
                {"IdentityManagementActions", "iam"}, {"KMSActions", "kms"}, {"OrganizationsActions", "organizations"},
                {"PinpointActions", "pinpoint"}, {"PollyActions", "polly"}, {"RDSActions", "rds"}, {"RedshiftActions", "redshift"},
                {"Route53Actions", "route53"}, {"SNSActions", "sns"}, {"SQSActions", "sqs"}, {"SecurityTokenServiceActions", "sts"},
                {"SimpleDBActions", "sdb"}, {"SimpleEmailServiceActions", "ses"}, {"SwfActions", "swf"}, {"WafRegionalActions", "waf-regional"}
        };
        for (String[] p : prefixes) {
            ACTION_PREFIXES.put(p[0], p[1]);
        }
        String[][] keys = {
                {"CURRENT_TIME_CONDITION_KEY", "aws:CurrentTime"}, {"SECURE_TRANSPORT_CONDITION_KEY", "aws:SecureTransport"},
                {"SOURCE_IP_CONDITION_KEY", "aws:SourceIp"}, {"USER_AGENT_CONDITION_KEY", "aws:UserAgent"},
                {"EPOCH_TIME_CONDITION_KEY", "aws:EpochTime"}, {"REFERER_CONDITION_KEY", "aws:Referer"},
                {"SOURCE_ARN_CONDITION_KEY", "aws:SourceArn"}
        };
        for (String[] k : keys) {
            CONDITION_KEYS.put(k[0], k[1]);
        }
        String[][] ids = {
                {"AmazonApiGateway", "apigateway.amazonaws.com"}, {"AWSDataPipeline", "datapipeline.amazonaws.com"},
                {"AmazonElasticTranscoder", "elastictranscoder.amazonaws.com"}, {"AmazonEC2", "ec2.amazonaws.com"},
                {"AWSOpsWorks", "opsworks.amazonaws.com"}, {"AWSCloudHSM", "cloudhsm.amazonaws.com"}, {"AllServices", "*"},
                {"Facebook", "graph.facebook.com"}, {"Google", "accounts.google.com"}, {"Amazon", "www.amazon.com"}, {"AllProviders", "*"}
        };
        for (String[] id : ids) {
            PRINCIPAL_IDS.put(id[0], id[1]);
        }
    }

    static {
        String[][] actions = {
                {"AllS3Actions", "s3:*"}, {"GetObject", "s3:GetObject"}, {"GetObjectVersion", "s3:GetObjectVersion"},
                {"PutObject", "s3:PutObject"}, {"GetObjectAcl", "s3:GetObjectAcl"}, {"GetObjectVersionAcl", "s3:GetObjectVersionAcl"},
                {"SetObjectAcl", "s3:PutObjectAcl"}, {"SetObjectVersionAcl", "s3:PutObjectAclVersion"}, {"DeleteObject", "s3:DeleteObject"},
                {"DeleteObjectVersion", "s3:DeleteObjectVersion"}, {"ListMultipartUploadParts", "s3:ListMultipartUploadParts"},
                {"AbortMultipartUpload", "s3:AbortMultipartUpload"}, {"RestoreObject", "s3:RestoreObject"}, {"CreateBucket", "s3:CreateBucket"},
                {"DeleteBucket", "s3:DeleteBucket"}, {"ListObjects", "s3:ListBucket"}, {"ListObjectVersions", "s3:ListBucketVersions"},
                {"ListBuckets", "s3:ListAllMyBuckets"}, {"ListBucketMultipartUploads", "s3:ListBucketMultipartUploads"},
                {"GetBucketAcl", "s3:GetBucketAcl"}, {"SetBucketAcl", "s3:PutBucketAcl"},
                {"GetBucketCrossOriginConfiguration", "s3:GetBucketCORS"}, {"SetBucketCrossOriginConfiguration", "s3:PutBucketCORS"},
                {"GetBucketVersioningConfiguration", "s3:GetBucketVersioning"}, {"SetBucketVersioningConfiguration", "s3:PutBucketVersioning"},
                {"GetBucketRequesterPays", "s3:GetBucketRequestPayment"}, {"SetBucketRequesterPays", "s3:PutBucketRequestPayment"},
                {"GetBucketLocation", "s3:GetBucketLocation"}, {"GetBucketPolicy", "s3:GetBucketPolicy"}, {"SetBucketPolicy", "s3:PutBucketPolicy"},
                {"DeleteBucketPolicy", "s3:DeleteBucketPolicy"}, {"GetBucketNotificationConfiguration", "s3:GetBucketNotification"},
                {"SetBucketNotificationConfiguration", "s3:PutBucketNotification"}, {"GetBucketLogging", "s3:GetBucketLogging"},
                {"SetBucketLogging", "s3:PutBucketLogging"}, {"GetBucketTagging", "s3:GetBucketTagging"}, {"SetBucketTagging", "s3:PutBucketTagging"},
                {"GetBucketWebsiteConfiguration", "s3:GetBucketWebsite"}, {"SetBucketWebsiteConfiguration", "s3:PutBucketWebsite"},
                {"DeleteBucketWebsiteConfiguration", "s3:DeleteBucketWebsite"}, {"GetBucketLifecycleConfiguration", "s3:GetLifecycleConfiguration"},
                {"SetBucketLifecycleConfiguration", "s3:PutLifecycleConfiguration"}
        };
        for (String[] a : actions) {
            S3_ACTIONS.put(a[0], a[1]);
        }
    }

    static final String UNSUPPORTED = "AWS SDK v1 IAM policy shape not converted to software.amazon.awssdk.policybuilder.iam";

    @Getter
    final String displayName = "Migrate AWS SDK v1 IAM policy builder to `iam-policy-builder`";

    @Getter
    final String description = "Converts `com.amazonaws.auth.policy` statements and policies built with `new Statement(Effect)`, " +
                               "`withActions(S3Actions.X)`, `withResources(new Resource(arn))`, `withId` and `new Policy().withStatements(..)` " +
                               "to v2's `IamStatement`/`IamPolicy` builders, which serialize the same policy with `toJson()`. " +
                               "Principals, `Condition`s and `ConditionFactory` conditions become `addPrincipal`/`addCondition` with the " +
                               "same type, key and value strings, and every v1 action enum becomes its action name.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(new UsesType<>(PKG + "*", false), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                J c = super.visitCompilationUnit(cu, ctx);
                doAfterVisit(new ChangeType(STATEMENT, V2_PKG + "IamStatement", true).getVisitor());
                doAfterVisit(new ChangeType(POLICY, V2_PKG + "IamPolicy", true).getVisitor());
                return c;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (GET_ACTION_NAME.matches(method) && method.getSelect() != null) {
                    String action = action(method.getSelect());
                    if (action != null) {
                        removeImports(method.getSelect());
                        return literal(method, action);
                    }
                }
                J j = super.visitMethodInvocation(method, ctx);
                if (!(j instanceof J.MethodInvocation)) {
                    return j;
                }
                J.MethodInvocation m = (J.MethodInvocation) j;
                if (TO_JSON.matches(m) || FROM_JSON.matches(m)) {
                    return m;
                }
                return convertChain(m, ctx);
            }

            @Override
            public J visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                String key = conditionKey(fieldAccess);
                return key == null ? super.visitFieldAccess(fieldAccess, ctx) : literal(fieldAccess, key);
            }

            @Override
            public J visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                Object parent = getCursor().getParentTreeCursor().getValue();
                if (parent instanceof J.FieldAccess || parent instanceof J.Import) {
                    return identifier;
                }
                String key = conditionKey(identifier);
                return key == null ? identifier : literal(identifier, key);
            }

            private @Nullable String conditionKey(Expression e) {
                JavaType.Variable field = field(e);
                if (field == null || !TypeUtils.isOfClassType(field.getOwner(), CONDITIONS + "ConditionFactory")) {
                    return null;
                }
                String key = CONDITION_KEYS.get(field.getName());
                if (key != null) {
                    maybeRemoveImport(CONDITIONS + "ConditionFactory");
                    maybeRemoveImport(CONDITIONS + "ConditionFactory." + field.getName());
                }
                return key;
            }

            private J.Literal literal(Expression replaced, String value) {
                return new J.Literal(Tree.randomId(), replaced.getPrefix(), Markers.EMPTY, value,
                        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"", null, JavaType.Primitive.String);
            }

            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J j = super.visitNewClass(newClass, ctx);
                return j instanceof J.NewClass ? convertChain((J.NewClass) j, ctx) : j;
            }

            private J convertChain(Expression outer, ExecutionContext ctx) {
                boolean statement = TypeUtils.isOfClassType(outer.getType(), STATEMENT);
                boolean policy = TypeUtils.isOfClassType(outer.getType(), POLICY);
                if (!statement && !policy || isSelectOfParent(outer)) {
                    return outer;
                }
                List<J.MethodInvocation> calls = new ArrayList<>();
                Expression base = outer;
                while (base instanceof J.MethodInvocation) {
                    calls.add(0, (J.MethodInvocation) base);
                    base = ((J.MethodInvocation) base).getSelect();
                }
                List<Object> params = new ArrayList<>();
                StringBuilder code = new StringBuilder();
                if (statement) {
                    if (!(base instanceof J.NewClass) || !NEW_STATEMENT.matches(base)) {
                        return SearchResult.found(outer, UNSUPPORTED);
                    }
                    String effect = effect(((J.NewClass) base).getArguments().get(0));
                    if (effect == null) {
                        return SearchResult.found(outer, UNSUPPORTED);
                    }
                    code.append("IamStatement.builder().effect(IamEffect.").append(effect).append(")");
                } else {
                    if (!(base instanceof J.NewClass) || !NEW_POLICY.matches(base)) {
                        return SearchResult.found(outer, UNSUPPORTED);
                    }
                    code.append("IamPolicy.builder()");
                    List<Expression> args = ((J.NewClass) base).getArguments();
                    if (!(args.get(0) instanceof J.Empty)) {
                        if (args.size() != 1) {
                            return SearchResult.found(outer, UNSUPPORTED);
                        }
                        code.append(".id(#{any(String)})");
                        params.add(args.get(0));
                    }
                }
                for (J.MethodInvocation call : calls) {
                    String name = call.getSimpleName();
                    if ("withId".equals(name)) {
                        code.append(statement ? ".sid(#{any(String)})" : ".id(#{any(String)})");
                        params.add(call.getArguments().get(0));
                    } else if (statement && "withActions".equals(name)) {
                        for (Expression a : call.getArguments()) {
                            String action = action(a);
                            if (action == null) {
                                return SearchResult.found(outer, UNSUPPORTED);
                            }
                            code.append(".addAction(\"").append(action).append("\")");
                        }
                    } else if (statement && "withResources".equals(name)) {
                        for (Expression r : call.getArguments()) {
                            if (!(r instanceof J.NewClass) || !NEW_RESOURCE.matches(r)) {
                                return SearchResult.found(outer, UNSUPPORTED);
                            }
                            code.append(".addResource(#{any(String)})");
                            params.add(((J.NewClass) r).getArguments().get(0));
                        }
                    } else if (statement && "withPrincipals".equals(name)) {
                        for (Expression p : call.getArguments()) {
                            if (!principal(p, code, params)) {
                                return SearchResult.found(outer, UNSUPPORTED);
                            }
                        }
                    } else if (statement && "withConditions".equals(name)) {
                        for (Expression c : call.getArguments()) {
                            if (!condition(c, code, params)) {
                                return SearchResult.found(outer, UNSUPPORTED);
                            }
                        }
                    } else if (!statement && "withStatements".equals(name)) {
                        for (Expression s : call.getArguments()) {
                            if (s.getType() instanceof JavaType.Array) {
                                // v1's withStatements(Statement...) replaced the statements, as the builder's statements(..) does
                                code.append(".statements(Arrays.asList(#{any(").append(V2_PKG).append("IamStatement[])}))");
                                params.add(s);
                                maybeAddImport("java.util.Arrays");
                                continue;
                            }
                            code.append(".addStatement(#{any(").append(V2_PKG).append("IamStatement)})");
                            params.add(s);
                        }
                    } else {
                        return SearchResult.found(outer, UNSUPPORTED);
                    }
                }
                code.append(".build()");
                for (String type : Arrays.asList(STATEMENT, STATEMENT + ".Effect", POLICY, PKG + "Resource", PRINCIPAL,
                        PRINCIPAL + ".Services", PRINCIPAL + ".WebIdentityProviders", CONDITIONS + "ConditionFactory")) {
                    maybeRemoveImport(type);
                }
                for (J.MethodInvocation call : calls) {
                    for (Expression arg : call.getArguments()) {
                        removeImports(arg);
                    }
                }
                maybeAddImport(V2_PKG + (statement ? "IamStatement" : "IamPolicy"));
                if (statement) {
                    maybeAddImport(V2_PKG + "IamEffect");
                }
                return JavaTemplate.builder(code.toString())
                        .imports(V2_PKG + "IamStatement", V2_PKG + "IamEffect", V2_PKG + "IamPolicy", "java.util.Arrays",
                                "java.time.ZoneOffset", "java.time.format.DateTimeFormatter")
                        .javaParser(AwsV2Parser.parser(ctx))
                        .build()
                        .apply(getCursor(), outer.getCoordinates().replace(), params.toArray());
            }

            private boolean isSelectOfParent(Expression e) {
                Object parent = getCursor().getParentTreeCursor().getValue();
                // Only intermediate links of a chain are skipped, so `new Policy().withStatements(..).toJson()` still converts.
                // By id, as the parent still holds the original select rather than this visitor's copy of it.
                return parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() != null &&
                       ((J.MethodInvocation) parent).getSelect().getId().equals(e.getId()) &&
                       (TypeUtils.isOfClassType(((J.MethodInvocation) parent).getType(), STATEMENT) ||
                        TypeUtils.isOfClassType(((J.MethodInvocation) parent).getType(), POLICY));
            }

            private @Nullable String effect(Expression e) {
                JavaType.Variable field = field(e);
                if (field == null || !TypeUtils.isOfClassType(field.getOwner(), STATEMENT + ".Effect")) {
                    return null;
                }
                return "Allow".equals(field.getName()) ? "ALLOW" : "Deny".equals(field.getName()) ? "DENY" : null;
            }

            private @Nullable String action(Expression e) {
                JavaType.Variable field = field(e);
                JavaType.FullyQualified owner = field == null ? null : TypeUtils.asFullyQualified(field.getOwner());
                if (owner == null || !owner.getFullyQualifiedName().startsWith(PKG + "actions.")) {
                    return null;
                }
                String enumName = owner.getClassName();
                if ("S3Actions".equals(enumName)) {
                    return S3_ACTIONS.get(field.getName());
                }
                String prefix = ACTION_PREFIXES.get(enumName);
                if (prefix == null) {
                    return null;
                }
                return field.getName().startsWith("All") && field.getName().endsWith("Actions") ? prefix + ":*" : prefix + ":" + field.getName();
            }

            // Appends `.addPrincipal(type, id)` with v1's provider and id for the principal, or returns false
            private boolean principal(Expression e, StringBuilder code, List<Object> params) {
                JavaType.Variable field = field(e);
                if (field != null && TypeUtils.isOfClassType(field.getOwner(), PRINCIPAL)) {
                    String[] known = {"AllUsers", "AWS", "AllServices", "Service", "AllWebProviders", "Federated", "All", "*"};
                    for (int i = 0; i < known.length; i += 2) {
                        if (known[i].equals(field.getName())) {
                            code.append(".addPrincipal(\"").append(known[i + 1]).append("\", \"*\")");
                            return true;
                        }
                    }
                    return false;
                }
                if (!(e instanceof J.NewClass) || !NEW_PRINCIPAL.matches(e)) {
                    return false;
                }
                List<Expression> args = ((J.NewClass) e).getArguments();
                if (args.size() == 1) {
                    Expression arg = args.get(0);
                    String id = PRINCIPAL_IDS.get(enumConstant(arg, PRINCIPAL + ".Services"));
                    if (id != null) {
                        code.append(".addPrincipal(\"Service\", \"").append(id).append("\")");
                        return true;
                    }
                    id = PRINCIPAL_IDS.get(enumConstant(arg, PRINCIPAL + ".WebIdentityProviders"));
                    if (id != null) {
                        code.append(".addPrincipal(\"Federated\", \"").append(id).append("\")");
                        return true;
                    }
                    if (!TypeUtils.isString(arg.getType())) {
                        return false;
                    }
                    return principalId("AWS", arg, true, code, params);
                }
                if (!(args.get(0) instanceof J.Literal) || !(((J.Literal) args.get(0)).getValue() instanceof String)) {
                    return false;
                }
                String provider = (String) ((J.Literal) args.get(0)).getValue();
                boolean strip = "AWS".equals(provider);
                if (args.size() == 3) {
                    if (!(args.get(2) instanceof J.Literal) || !(((J.Literal) args.get(2)).getValue() instanceof Boolean)) {
                        return false;
                    }
                    strip = (Boolean) ((J.Literal) args.get(2)).getValue();
                }
                return principalId(provider, args.get(1), strip, code, params);
            }

            private boolean principalId(String provider, Expression id, boolean strip, StringBuilder code, List<Object> params) {
                code.append(".addPrincipal(\"").append(provider).append("\", ");
                if (id instanceof J.Literal && ((J.Literal) id).getValue() instanceof String) {
                    String value = (String) ((J.Literal) id).getValue();
                    code.append('"').append(strip ? value.replace("-", "") : value).append("\")");
                    return true;
                }
                code.append(strip ? "#{any(String)}.replace(\"-\", \"\"))" : "#{any(String)})");
                params.add(id);
                return true;
            }

            // Appends `.addCondition(operator, key, value)` with the strings v1's condition serialized, or returns false
            private boolean condition(Expression e, StringBuilder code, List<Object> params) {
                List<Expression> args;
                String type;
                String key = null;
                Expression value;
                if (e instanceof J.MethodInvocation && CONDITION_FACTORY.matches(e)) {
                    args = ((J.MethodInvocation) e).getArguments();
                    switch (((J.MethodInvocation) e).getSimpleName()) {
                        case "newSourceArnCondition":
                            return addCondition("ArnLike", "aws:SourceArn", args.get(0), code, params);
                        case "newSecureTransportCondition":
                            code.append(".addCondition(\"Bool\", \"aws:SecureTransport\", \"true\")");
                            return true;
                        case "newUserAgentCondition":
                            key = "aws:UserAgent";
                            break;
                        case "newRefererCondition":
                            key = "aws:Referer";
                            break;
                        default:
                            return false;
                    }
                    type = enumConstant(args.get(0), null);
                    value = args.get(1);
                    return type != null && addCondition(type, key, value, code, params);
                }
                if (!(e instanceof J.NewClass) || !NEW_CONDITION.matches(e) || ((J.NewClass) e).getBody() != null) {
                    return false;
                }
                args = ((J.NewClass) e).getArguments();
                JavaType.FullyQualified conditionType = TypeUtils.asFullyQualified(((J.NewClass) e).getType());
                switch (conditionType == null ? "" : conditionType.getClassName()) {
                    case "IpAddressCondition":
                        if (args.size() == 1) {
                            return addCondition("IpAddress", "aws:SourceIp", args.get(0), code, params);
                        }
                        type = enumConstant(args.get(0), null);
                        return type != null && addCondition(type, "aws:SourceIp", args.get(1), code, params);
                    case "BooleanCondition":
                        if (args.get(1) instanceof J.Literal) {
                            return addCondition("Bool", args.get(0), String.valueOf(((J.Literal) args.get(1)).getValue()), code, params);
                        }
                        return false;
                    case "StringCondition":
                    case "ArnCondition":
                    case "NumericCondition":
                        if (args.size() != 3 || !TypeUtils.isString(args.get(2).getType())) {
                            return false;
                        }
                        type = enumConstant(args.get(0), null);
                        if (type == null) {
                            return false;
                        }
                        code.append(".addCondition(\"").append(type).append("\", #{any(String)}, #{any(String)})");
                        params.add(args.get(1));
                        params.add(args.get(2));
                        return true;
                    case "DateCondition":
                        type = enumConstant(args.get(0), null);
                        if (type == null) {
                            return false;
                        }
                        // v1 wrote the date as an ISO 8601 UTC timestamp with milliseconds
                        code.append(".addCondition(\"").append(type).append("\", \"aws:CurrentTime\", " +
                                    "DateTimeFormatter.ofPattern(\"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'\")" +
                                    ".withZone(ZoneOffset.UTC).format(#{any(java.util.Date)}.toInstant()))");
                        params.add(args.get(1));
                        maybeAddImport("java.time.ZoneOffset");
                        maybeAddImport("java.time.format.DateTimeFormatter");
                        return true;
                    default:
                        return false;
                }
            }

            private boolean addCondition(String type, String key, Expression value, StringBuilder code, List<Object> params) {
                code.append(".addCondition(\"").append(type).append("\", \"").append(key).append("\", #{any(String)})");
                params.add(value);
                return true;
            }

            private boolean addCondition(String type, Expression key, String value, StringBuilder code, List<Object> params) {
                code.append(".addCondition(\"").append(type).append("\", #{any(String)}, \"").append(value).append("\")");
                params.add(key);
                return true;
            }

            // The name of an enum constant, optionally only of the given enum type
            private @Nullable String enumConstant(Expression e, @Nullable String enumType) {
                JavaType.Variable field = field(e);
                if (field == null || !(field.getOwner() instanceof JavaType.FullyQualified) ||
                    ((JavaType.FullyQualified) field.getOwner()).getKind() != JavaType.FullyQualified.Kind.Enum ||
                    enumType != null && !TypeUtils.isOfClassType(field.getOwner(), enumType)) {
                    return null;
                }
                return field.getName();
            }

            private void removeImports(Expression e) {
                Set<String> types = new HashSet<>();
                new JavaVisitor<Set<String>>() {
                    @Override
                    public J visitIdentifier(J.Identifier identifier, Set<String> found) {
                        JavaType.FullyQualified owner = identifier.getFieldType() == null ? null :
                                TypeUtils.asFullyQualified(identifier.getFieldType().getOwner());
                        if (owner != null) {
                            found.add(owner.getFullyQualifiedName());
                            found.add(owner.getFullyQualifiedName() + "." + identifier.getSimpleName());
                        }
                        JavaType.FullyQualified type = TypeUtils.asFullyQualified(identifier.getType());
                        if (type != null) {
                            found.add(type.getFullyQualifiedName());
                        }
                        return identifier;
                    }
                }.visit(e, types);
                for (String type : types) {
                    if (type.startsWith(PKG)) {
                        maybeRemoveImport(type);
                    }
                }
            }

            private JavaType.@Nullable Variable field(Expression e) {
                return e instanceof J.FieldAccess ? ((J.FieldAccess) e).getName().getFieldType() :
                        e instanceof J.Identifier ? ((J.Identifier) e).getFieldType() : null;
            }
        });
    }
}

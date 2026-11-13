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
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

import static java.util.Collections.emptyList;
import static org.openrewrite.Tree.randomId;

/**
 * v2's {@code software.amazon.awssdk.core.retry.RetryPolicy} has the same parts as v1's: a retry condition, a backoff strategy and a
 * retry count, and its {@code RetryPolicyContext} carries the original request, exception and attempt count v1 passed separately.
 */
public class MigrateAwsRetryPolicies extends Recipe {

    private static final String V1_RETRY_POLICY = "com.amazonaws.retry.RetryPolicy";
    private static final String V1_RETRY_CONDITION = "com.amazonaws.retry.RetryPolicy$RetryCondition";
    private static final String V1_BACKOFF = "com.amazonaws.retry.RetryPolicy$BackoffStrategy";
    private static final String V1_PREDEFINED = "com.amazonaws.retry.PredefinedRetryPolicies";
    private static final String V1_DEFAULT_CONDITION = "com.amazonaws.retry.PredefinedRetryPolicies$SDKDefaultRetryCondition";
    // Renamed in place because the ChangeType registered for this file retypes RetryPolicy before a ChangeMethodName could match
    private static final MethodMatcher GET_RETRY_CONDITION = new MethodMatcher(V1_RETRY_POLICY + " getRetryCondition()");
    private static final MethodMatcher GET_BACKOFF_STRATEGY = new MethodMatcher(V1_RETRY_POLICY + " getBackoffStrategy()");
    private static final MethodMatcher GET_MAX_ERROR_RETRY = new MethodMatcher(V1_RETRY_POLICY + " getMaxErrorRetry()");

    private static final String V2_RETRY_POLICY = "software.amazon.awssdk.core.retry.RetryPolicy";
    private static final String V2_RETRY_MODE = "software.amazon.awssdk.core.retry.RetryMode";
    private static final String V2_CONTEXT = "software.amazon.awssdk.core.retry.RetryPolicyContext";
    private static final String V2_RETRY_CONDITION = "software.amazon.awssdk.core.retry.conditions.RetryCondition";
    private static final String V2_BACKOFF = "software.amazon.awssdk.core.retry.backoff.BackoffStrategy";
    private static final String V2_FULL_JITTER = "software.amazon.awssdk.core.retry.backoff.FullJitterBackoffStrategy";

    // v1's DynamoDB defaults: 10 retries, 25 ms base delay; throttling backoff is the same as the legacy default
    private static final String DYNAMODB_POLICY = "RetryPolicy.builder(RetryMode.LEGACY).numRetries(%s).backoffStrategy(" +
            "FullJitterBackoffStrategy.builder().baseDelay(Duration.ofMillis(25)).maxBackoffTime(Duration.ofSeconds(20)).build()).build()";

    private static final Map<String, String> PARAMETER_ACCESSORS = new HashMap<>();

    static {
        PARAMETER_ACCESSORS.put("com.amazonaws.AmazonWebServiceRequest", "originalRequest");
        PARAMETER_ACCESSORS.put("com.amazonaws.AmazonClientException", "exception");
        PARAMETER_ACCESSORS.put("int", "retriesAttempted");
    }

    @Getter
    final String displayName = "Migrate AWS SDK v1 retry policies";

    @Getter
    final String description = "Runs before `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`, which does not map " +
            "`com.amazonaws.retry`. Converts `RetryPolicy`, its `RetryCondition` and `BackoffStrategy` (including user " +
            "implementations, which now take a `RetryPolicyContext`) and `PredefinedRetryPolicies` to their v2 " +
            "`software.amazon.awssdk.core.retry` equivalents. v1's default policies become v2's `LEGACY` retry mode, which has the " +
            "same retry count and backoff.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(new UsesType<>("com.amazonaws.retry..*", false), new JavaVisitor<ExecutionContext>() {

            @Override
            public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                // Registered first, so imports added while visiting see the v2 names and are not duplicated
                // Nested types first, since changing RetryPolicy would also move its nested types
                doAfterVisit(new ChangeType(V1_RETRY_CONDITION, V2_RETRY_CONDITION, true).getVisitor());
                doAfterVisit(new ChangeType(V1_BACKOFF, V2_BACKOFF, true).getVisitor());
                doAfterVisit(new ChangeType(V1_DEFAULT_CONDITION, V2_RETRY_CONDITION, true).getVisitor());
                doAfterVisit(new ChangeType(V1_RETRY_POLICY, V2_RETRY_POLICY, true).getVisitor());
                // By now only Javadoc refers to PredefinedRetryPolicies, whose policies are v2 RetryPolicy factories
                doAfterVisit(new ChangeType(V1_PREDEFINED, V2_RETRY_POLICY, true).getVisitor());
                return super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = (J.ClassDeclaration) super.visitClassDeclaration(classDecl, ctx);
                // v2's default condition is an interface's static factory, so a subclass of v1's default implements the interface
                if (cd.getExtends() != null && TypeUtils.isOfClassType(cd.getExtends().getType(), V1_DEFAULT_CONDITION)) {
                    TypeTree ext = cd.getExtends();
                    J.ClassDeclaration.Padding padding = cd.getPadding();
                    List<TypeTree> impls = new ArrayList<>(cd.getImplements() == null ? emptyList() : cd.getImplements());
                    impls.add(0, (TypeTree) ext.withPrefix(Space.SINGLE_SPACE));
                    cd = padding.withImplements(JContainer.build(Space.SINGLE_SPACE,
                            JRightPadded.withElements(cd.getPadding().getImplements() == null ? emptyList() :
                                    cd.getPadding().getImplements().getPadding().getElements(), impls), Markers.EMPTY));
                    cd = cd.withExtends(null);
                }
                return cd;
            }

            @Override
            public J visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = (J.MethodDeclaration) super.visitMethodDeclaration(method, ctx);
                Map<String, String> accessors = accessors(md, getCursor());
                if (accessors == null) {
                    return md;
                }
                boolean backoff = "delayBeforeNextRetry".equals(md.getSimpleName());
                List<String> paramNames = md.getMethodType().getParameterNames();

                J.Block body = (J.Block) new ParameterReferences(accessors, paramNames).visitNonNull(md.getBody(), ctx,
                        new Cursor(getCursor().getParentOrThrow(), md));
                if (backoff) {
                    body = (J.Block) new MillisReturns().visitNonNull(body, ctx, new Cursor(getCursor().getParentOrThrow(), md));
                }
                md = md.withBody(body);
                md = JavaTemplate.builder(V2_CONTEXT.substring(V2_CONTEXT.lastIndexOf('.') + 1) + " context")
                        .imports(V2_CONTEXT)
                        .javaParser(AwsV2Parser.parser(ctx))
                        .build()
                        .apply(new Cursor(getCursor().getParentOrThrow(), md), md.getCoordinates().replaceParameters());
                JavaType.Method mt = md.getMethodType();
                if (backoff) {
                    JavaType duration = JavaType.ShallowClass.build("java.time.Duration");
                    TypeTree returnType = md.getReturnTypeExpression();
                    md = md.withReturnTypeExpression(new J.Identifier(randomId(),
                                    returnType == null ? Space.SINGLE_SPACE : returnType.getPrefix(), Markers.EMPTY, emptyList(),
                                    "Duration", duration, null))
                            .withName(md.getName().withSimpleName("computeDelayBeforeNextRetry"));
                    if (mt != null) {
                        mt = mt.withName("computeDelayBeforeNextRetry").withReturnType(duration);
                    }
                    maybeAddImport("java.time.Duration");
                }
                if (mt != null) {
                    md = md.withMethodType(mt).withName(md.getName().withType(mt));
                }
                maybeRemoveImport("com.amazonaws.AmazonWebServiceRequest");
                maybeAddImport(V2_CONTEXT);
                maybeRemoveImport("com.amazonaws.AmazonClientException");
                return md;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J j = super.visitMethodInvocation(method, ctx);
                if (!(j instanceof J.MethodInvocation)) {
                    return j;
                }
                J.MethodInvocation m = (J.MethodInvocation) j;
                JavaType.Method mt = m.getMethodType();
                if (mt == null) {
                    return m;
                }
                JavaType.FullyQualified declaring = mt.getDeclaringType();
                if (TypeUtils.isOfClassType(declaring, V1_PREDEFINED)) {
                    String replacement = predefinedCall(m);
                    if (replacement != null) {
                        maybeRemoveImport(V1_PREDEFINED);
                        return template(replacement, m, ctx, m.getArguments().stream()
                                .filter(a -> !(a instanceof J.Empty)).toArray());
                    }
                    return m;
                }
                if (m.getSelect() == null) {
                    return m;
                }
                boolean isSuper = m.getSelect() instanceof J.Identifier && "super".equals(((J.Identifier) m.getSelect()).getSimpleName());
                boolean shouldRetry = "shouldRetry".equals(m.getSimpleName()) && m.getArguments().size() == 3 &&
                                      TypeUtils.isAssignableTo(V1_RETRY_CONDITION, declaring);
                boolean delay = "delayBeforeNextRetry".equals(m.getSimpleName()) && m.getArguments().size() == 3 && !isSuper &&
                                TypeUtils.isAssignableTo(V1_BACKOFF, declaring);
                if (shouldRetry || delay) {
                    List<Object> args = new ArrayList<>();
                    String receiver = "#{any(" + (shouldRetry ? V2_RETRY_CONDITION : V2_BACKOFF) + ")}";
                    if (isSuper) {
                        receiver = "RetryCondition.defaultRetryCondition()";
                    } else {
                        args.add(m.getSelect());
                    }
                    String context;
                    if (isPassThrough(m, getCursor())) {
                        context = "#{any(" + V2_CONTEXT + ")}";
                        args.add(contextIdentifier());
                    } else {
                        context = "RetryPolicyContext.builder().originalRequest(#{any(software.amazon.awssdk.core.SdkRequest)})" +
                                  ".exception(#{any(software.amazon.awssdk.core.exception.SdkException)})" +
                                  ".retriesAttempted(#{any(int)}).build()";
                        args.addAll(m.getArguments());
                    }
                    return template(receiver + (shouldRetry ? ".shouldRetry(" + context + ")" :
                            ".computeDelayBeforeNextRetry(" + context + ").toMillis()"), m, ctx, args.toArray());
                }
                String renamed = GET_RETRY_CONDITION.matches(m) ? "retryCondition" :
                        GET_BACKOFF_STRATEGY.matches(m) ? "backoffStrategy" :
                        GET_MAX_ERROR_RETRY.matches(m) ? "numRetries" : null;
                if (renamed != null) {
                    JavaType.Method r = mt.withName(renamed);
                    return m.withName(m.getName().withSimpleName(renamed).withType(r)).withMethodType(r);
                }
                return m;
            }

            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J j = super.visitNewClass(newClass, ctx);
                if (!(j instanceof J.NewClass)) {
                    return j;
                }
                J.NewClass n = (J.NewClass) j;
                if (n.getBody() == null && TypeUtils.isOfClassType(n.getType(), V1_RETRY_POLICY) && n.getArguments().size() >= 3) {
                    // v2 has no separate client-level retry count for the policy to defer to, so the fourth argument has no equivalent
                    return template("RetryPolicy.builder(RetryMode.LEGACY).retryCondition(#{any(" + V2_RETRY_CONDITION + ")})" +
                                    ".backoffStrategy(#{any(" + V2_BACKOFF + ")})" +
                                    ".numRetries(#{any(int)}).build()", n, ctx,
                            n.getArguments().get(0), n.getArguments().get(1), n.getArguments().get(2));
                }
                return n;
            }

            @Override
            public J visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                J j = super.visitFieldAccess(fieldAccess, ctx);
                if (j instanceof J.FieldAccess && TypeUtils.isOfClassType(((J.FieldAccess) j).getTarget().getType(), V1_PREDEFINED)) {
                    String replacement = predefinedField(((J.FieldAccess) j).getSimpleName());
                    if (replacement != null) {
                        maybeRemoveImport(V1_PREDEFINED);
                        return template(replacement, (J.FieldAccess) j, ctx);
                    }
                }
                return j;
            }

            private J template(String code, Expression replaced, ExecutionContext ctx, Object... args) {
                for (String i : Arrays.asList(V2_RETRY_POLICY, V2_RETRY_MODE, V2_CONTEXT, V2_RETRY_CONDITION, V2_BACKOFF, V2_FULL_JITTER,
                        "java.time.Duration")) {
                    if (code.contains(i.substring(i.lastIndexOf('.') + 1) + ".")) {
                        maybeAddImport(i);
                    }
                }
                return JavaTemplate.builder(code)
                        .imports(V2_RETRY_POLICY, V2_RETRY_MODE, V2_CONTEXT, V2_RETRY_CONDITION, V2_BACKOFF, V2_FULL_JITTER,
                                "java.time.Duration")
                        .javaParser(AwsV2Parser.parser(ctx))
                        .build()
                        .apply(getCursor(), replaced.getCoordinates().replace(), args);
            }
        });
    }

    private static @Nullable String predefinedCall(J.MethodInvocation m) {
        switch (m.getSimpleName()) {
            case "getDefaultRetryPolicy":
                return "RetryPolicy.forRetryMode(RetryMode.LEGACY)";
            case "getDynamoDBDefaultRetryPolicy":
                return String.format(DYNAMODB_POLICY, "10");
            case "getDefaultRetryPolicyWithCustomMaxRetries":
                return "RetryPolicy.builder(RetryMode.LEGACY).numRetries(#{any(int)}).build()";
            case "getDynamoDBDefaultRetryPolicyWithCustomMaxRetries":
                return String.format(DYNAMODB_POLICY, "#{any(int)}");
            default:
                return null;
        }
    }

    private static @Nullable String predefinedField(String name) {
        switch (name) {
            case "DEFAULT":
                return "RetryPolicy.forRetryMode(RetryMode.LEGACY)";
            case "DYNAMODB_DEFAULT":
                return String.format(DYNAMODB_POLICY, "10");
            case "NO_RETRY_POLICY":
                return "RetryPolicy.none()";
            case "DEFAULT_RETRY_CONDITION":
                return "RetryCondition.defaultRetryCondition()";
            case "DEFAULT_BACKOFF_STRATEGY":
                return "BackoffStrategy.defaultStrategy()";
            case "DYNAMODB_DEFAULT_BACKOFF_STRATEGY":
                return "FullJitterBackoffStrategy.builder().baseDelay(Duration.ofMillis(25)).maxBackoffTime(Duration.ofSeconds(20)).build()";
            case "DEFAULT_MAX_ERROR_RETRY":
                return "3";
            case "DYNAMODB_DEFAULT_MAX_ERROR_RETRY":
                return "10";
            default:
                return null;
        }
    }

    /**
     * The {@code context} accessor for each of v1's three parameters, when {@code md} implements v1's condition or backoff
     * strategy and can be converted.
     */
    private static @Nullable Map<String, String> accessors(J.@Nullable MethodDeclaration md, Cursor cursor) {
        J.ClassDeclaration enclosing = cursor.firstEnclosing(J.ClassDeclaration.class);
        JavaType.FullyQualified owner = enclosing == null ? null : enclosing.getType();
        if (md == null || owner == null || md.getBody() == null || md.getParameters().size() != 3 || md.getMethodType() == null) {
            return null;
        }
        boolean condition = "shouldRetry".equals(md.getSimpleName()) && TypeUtils.isAssignableTo(V1_RETRY_CONDITION, owner);
        boolean backoff = "delayBeforeNextRetry".equals(md.getSimpleName()) && TypeUtils.isAssignableTo(V1_BACKOFF, owner);
        if (!condition && !backoff) {
            return null;
        }
        Map<String, String> accessors = new LinkedHashMap<>();
        List<JavaType> paramTypes = md.getMethodType().getParameterTypes();
        List<String> paramNames = md.getMethodType().getParameterNames();
        for (int i = 0; i < 3; i++) {
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(paramTypes.get(i));
            String key = fq != null ? fq.getFullyQualifiedName() : String.valueOf(paramTypes.get(i));
            String accessor = PARAMETER_ACCESSORS.get(key);
            if (accessor == null || isReassigned(md.getBody(), paramNames.get(i))) {
                return null;
            }
            accessors.put(paramNames.get(i), accessor);
        }
        return accessors;
    }

    /**
     * Inside a converted method, a call passing the method's own three parameters passes its context on unchanged.
     */
    private static boolean isPassThrough(J.MethodInvocation m, Cursor cursor) {
        Cursor mdCursor = cursor.dropParentUntil(t -> t instanceof J.MethodDeclaration || t instanceof J.ClassDeclaration ||
                                                      t instanceof J.Lambda || t == Cursor.ROOT_VALUE);
        if (!(mdCursor.getValue() instanceof J.MethodDeclaration)) {
            return false;
        }
        Map<String, String> accessors = accessors(mdCursor.getValue(), mdCursor);
        if (accessors == null) {
            return false;
        }
        List<String> names = new ArrayList<>(accessors.keySet());
        for (int i = 0; i < 3; i++) {
            Expression arg = m.getArguments().get(i);
            if (!(arg instanceof J.Identifier) || !names.get(i).equals(((J.Identifier) arg).getSimpleName())) {
                return false;
            }
        }
        return true;
    }

    private static J.Identifier contextIdentifier() {
        JavaType.FullyQualified contextType = JavaType.ShallowClass.build(V2_CONTEXT);
        return new J.Identifier(randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), "context", contextType,
                new JavaType.Variable(null, 0, "context", null, contextType, emptyList()));
    }

    private static boolean isReassigned(J.Block body, String name) {
        boolean[] found = {false};
        new JavaVisitor<Integer>() {
            @Override
            public J visitAssignment(J.Assignment assignment, Integer p) {
                found[0] |= assignment.getVariable() instanceof J.Identifier &&
                            name.equals(((J.Identifier) assignment.getVariable()).getSimpleName());
                return super.visitAssignment(assignment, p);
            }

            @Override
            public J visitUnary(J.Unary unary, Integer p) {
                found[0] |= unary.getExpression() instanceof J.Identifier && name.equals(((J.Identifier) unary.getExpression()).getSimpleName()) &&
                            unary.getOperator() != J.Unary.Type.Not && unary.getOperator() != J.Unary.Type.Negative &&
                            unary.getOperator() != J.Unary.Type.Positive && unary.getOperator() != J.Unary.Type.Complement;
                return super.visitUnary(unary, p);
            }
        }.visit(body, 0);
        return found[0];
    }

    /**
     * Replaces each reference to one of v1's three parameters with the matching {@code context} accessor.
     */
    private static class ParameterReferences extends JavaVisitor<ExecutionContext> {
        private final Map<String, String> accessors;
        private final List<String> names;

        ParameterReferences(Map<String, String> accessors, List<String> names) {
            this.accessors = accessors;
            this.names = names;
        }

        @Override
        public J visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
            String accessor = accessors.get(identifier.getSimpleName());
            Object parent = getCursor().getParentTreeCursor().getValue();
            if (accessor == null || identifier.getFieldType() == null || identifier.getFieldType().getOwner() instanceof JavaType.FullyQualified ||
                parent instanceof J.FieldAccess && ((J.FieldAccess) parent).getName() == identifier ||
                parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getName() == identifier) {
                return identifier;
            }
            J.Identifier context = contextIdentifier();
            JavaType.FullyQualified contextType = (JavaType.FullyQualified) context.getType();
            JavaType returnType = "retriesAttempted".equals(accessor) ? JavaType.Primitive.Int :
                    JavaType.ShallowClass.build("originalRequest".equals(accessor) ?
                            "software.amazon.awssdk.core.SdkRequest" : "software.amazon.awssdk.core.exception.SdkException");
            return LstFactory.noArgCall(context, accessor, contextType, returnType).withPrefix(identifier.getPrefix());
        }
    }

    /**
     * v1 returned the delay in milliseconds, v2 returns a {@code Duration}.
     */
    private static class MillisReturns extends JavaVisitor<ExecutionContext> {
        @Override
        public J visitLambda(J.Lambda lambda, ExecutionContext ctx) {
            return lambda;
        }

        @Override
        public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
            return newClass;
        }

        @Override
        public J visitReturn(J.Return _return, ExecutionContext ctx) {
            if (_return.getExpression() == null) {
                return _return;
            }
            Expression millis = JavaTemplate.builder("Duration.ofMillis(#{any(long)})")
                    .imports("java.time.Duration")
                    .build()
                    .apply(new Cursor(getCursor(), _return.getExpression()), _return.getExpression().getCoordinates().replace(),
                            _return.getExpression());
            return _return.withExpression(millis);
        }
    }
}

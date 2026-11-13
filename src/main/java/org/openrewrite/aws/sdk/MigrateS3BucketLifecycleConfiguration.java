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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;

/**
 * v1 code fetches a bucket's lifecycle configuration, edits its rule list in place and saves it. v2-migration renames the types
 * but v2's lifecycle model is immutable and its rule type has another name, so such code keeps a generated mutable
 * {@code BucketLifecycleConfiguration} that is converted to and from v2's model when it is fetched and saved.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateS3BucketLifecycleConfiguration extends ScanningRecipe<MigrateS3BucketLifecycleConfiguration.Accumulator> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    private static final String V1_CONFIGURATION = "com.amazonaws.services.s3.model.BucketLifecycleConfiguration";
    private static final String V1_RULE = V1_CONFIGURATION + "$Rule";
    private static final String V1_LIFECYCLE = "com.amazonaws.services.s3.model.lifecycle.";
    private static final String V1_S3 = "com.amazonaws.services.s3.AmazonS3";

    private static final Pattern SUPPORTED_RULE = Pattern.compile("(get|set|with)(Id|Prefix|Filter|Status|ExpirationInDays|" +
            "ExpirationDate|NoncurrentVersionExpirationInDays|ExpiredObjectDeleteMarker)|isExpiredObjectDeleteMarker|equals|hashCode|toString");
    private static final Pattern SUPPORTED_CONFIGURATION = Pattern.compile("(get|set|with)Rules|equals|hashCode|toString");

    @Getter
    final String displayName = "Migrate AWS SDK v1 S3 bucket lifecycle configurations";

    @Getter
    final String description = "Runs before `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. A v1 " +
            "`BucketLifecycleConfiguration` and its `Rule`s become a generated mutable `BucketLifecycleConfiguration` with v1's " +
            "accessors; `getBucketLifecycleConfiguration` and `setBucketLifecycleConfiguration` go through its `get` and `set`, which " +
            "convert to and from v2's `LifecycleRule`s, and a prefix `LifecycleFilter` becomes a v2 `LifecycleRuleFilter`. Projects " +
            "using rule settings it does not carry, such as transitions, are left to v2-migration.";

    public static class Accumulator extends ShimRoots {
        final Set<String> unsupportedProjects = ConcurrentHashMap.newKeySet();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    acc.shims.recordExisting((SourceFile) tree);
                }
                if (!(tree instanceof J.CompilationUnit) || !((J.CompilationUnit) tree).getSourcePath().toString().endsWith(".java") ||
                    new UsesType<>(V1_CONFIGURATION, true).visit(tree, ctx) == tree) {
                    return tree;
                }
                J.CompilationUnit cu = (J.CompilationUnit) tree;
                String project = ProjectShims.projectName(cu);
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        JavaType.Method mt = method.getMethodType();
                        if (mt != null) {
                            if (TypeUtils.isOfClassType(mt.getDeclaringType(), V1_RULE) && !SUPPORTED_RULE.matcher(method.getSimpleName()).matches() ||
                                TypeUtils.isOfClassType(mt.getDeclaringType(), V1_CONFIGURATION) && !SUPPORTED_CONFIGURATION.matcher(method.getSimpleName()).matches() ||
                                mt.getDeclaringType().getFullyQualifiedName().startsWith(V1_LIFECYCLE)) {
                                acc.unsupportedProjects.add(project);
                            }
                            if (!mt.getDeclaringType().getFullyQualifiedName().startsWith("com.amazonaws.") && signatureHasConfiguration(mt)) {
                                acc.reference(project, mt.getDeclaringType().getFullyQualifiedName());
                            }
                        }
                        return super.visitMethodInvocation(method, ctx);
                    }

                    @Override
                    public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                        if (TypeUtils.isOfClassType(newClass.getType(), V1_LIFECYCLE + "LifecycleFilter") && !isPrefixFilter(newClass)) {
                            acc.unsupportedProjects.add(project);
                        }
                        if (TypeUtils.isOfClassType(newClass.getType(), V1_LIFECYCLE + "LifecyclePrefixPredicate") &&
                            !(getCursor().getParentTreeCursor().getValue() instanceof J.NewClass)) {
                            acc.unsupportedProjects.add(project);
                        }
                        return super.visitNewClass(newClass, ctx);
                    }
                }.visit(cu, ctx);
                acc.producer(cu);
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        acc.producerProjects.removeAll(acc.unsupportedProjects);
        acc.retainRoots();
        return acc.shims.generate(S3BucketLifecycleShim.SHIM, S3BucketLifecycleShim::source, MigrateS3Residuals.v2Parser(ctx), ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return JavaOnly.check(new UsesType<>(V1_CONFIGURATION, true), new JavaVisitor<ExecutionContext>() {
            @Nullable
            String shim;

            @Override
            public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                shim = acc.shimFor(cu, S3BucketLifecycleShim.SHIM);
                if (shim == null) {
                    return cu;
                }
                J.CompilationUnit c = (J.CompilationUnit) super.visitCompilationUnit(cu, ctx);
                doAfterVisit(new ChangeType(V1_CONFIGURATION, shim, true).getVisitor());
                return c;
            }

            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J j = super.visitNewClass(newClass, ctx);
                if (j instanceof J.NewClass && isPrefixFilter((J.NewClass) j)) {
                    J.NewClass predicate = (J.NewClass) ((J.NewClass) j).getArguments().get(0);
                    maybeRemoveImport(V1_LIFECYCLE + "LifecycleFilter");
                    maybeRemoveImport(V1_LIFECYCLE + "LifecyclePrefixPredicate");
                    maybeAddImport("software.amazon.awssdk.services.s3.model.LifecycleRuleFilter");
                    return JavaTemplate.builder("LifecycleRuleFilter.builder().prefix(#{any(String)}).build()")
                            .javaParser(MigrateS3Residuals.v2Parser(ctx))
                            .imports("software.amazon.awssdk.services.s3.model.LifecycleRuleFilter")
                            .build()
                            .apply(getCursor(), newClass.getCoordinates().replace(), predicate.getArguments().get(0));
                }
                return j;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J j = super.visitMethodInvocation(method, ctx);
                if (!(j instanceof J.MethodInvocation)) {
                    return j;
                }
                J.MethodInvocation m = (J.MethodInvocation) j;
                JavaType.Method mt = m.getMethodType();
                Expression select = m.getSelect();
                if (mt == null || select == null || !TypeUtils.isAssignableTo(V1_S3, mt.getDeclaringType()) ||
                    V2MigrationScope.isMockitoStubbedOrVerified(getCursor())) {
                    return m;
                }
                if ("getBucketLifecycleConfiguration".equals(m.getSimpleName()) && m.getArguments().size() == 1 &&
                    TypeUtils.isString(mt.getParameterTypes().get(0))) {
                    return helper("get", mt.getReturnType(), Arrays.asList(select, m.getArguments().get(0)),
                            Arrays.asList(select.getType(), mt.getParameterTypes().get(0)), m.getPrefix());
                }
                if ("setBucketLifecycleConfiguration".equals(m.getSimpleName()) && m.getArguments().size() == 2) {
                    return helper("set", JavaType.Primitive.Void, Arrays.asList(select, m.getArguments().get(0), m.getArguments().get(1)),
                            Arrays.asList(select.getType(), mt.getParameterTypes().get(0), mt.getParameterTypes().get(1)), m.getPrefix());
                }
                return m;
            }

            private J.MethodInvocation helper(String name, @Nullable JavaType returnType, List<Expression> args,
                                              List<@Nullable JavaType> paramTypes, Space prefix) {
                String shimFqn = Objects.requireNonNull(shim);
                StringJoiner placeholders = new StringJoiner(", ");
                for (int i = 0; i < args.size(); i++) {
                    placeholders.add("#{any()}");
                }
                J call = JavaTemplate.builder(S3BucketLifecycleShim.SHIM + "." + name + "(" + placeholders + ")")
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(ProjectShims.packageLine(packageOf(shimFqn)) +
                                "public class " + S3BucketLifecycleShim.SHIM + " {\n" +
                                "    public static <T> T get(Object... args) { return null; }\n" +
                                "    public static void set(Object... args) { }\n" +
                                "}"))
                        .imports(shimFqn)
                        .build()
                        .apply(getCursor(), ((Expression) getCursor().getValue()).getCoordinates().replace(), args.toArray());
                maybeAddImport(shimFqn, false);
                // The template cannot attribute v1 types, so the call keeps them for v2-migration to retype
                List<String> names = new ArrayList<>();
                List<JavaType> types = new ArrayList<>();
                for (int i = 0; i < paramTypes.size(); i++) {
                    names.add("arg" + i);
                    JavaType t = paramTypes.get(i);
                    types.add(t == null ? JavaType.Unknown.getInstance() : t);
                }
                JavaType.Method type = new JavaType.Method(null, Flag.Public.getBitMask() | Flag.Static.getBitMask(),
                        JavaType.ShallowClass.build(shimFqn), name, returnType, names, types, emptyList(), emptyList(), null, null);
                J.MethodInvocation invocation = (J.MethodInvocation) call;
                return invocation.withMethodType(type).withName(invocation.getName().withType(type)).withPrefix(prefix);
            }
        });
    }

    private static boolean isPrefixFilter(J.NewClass filter) {
        return TypeUtils.isOfClassType(filter.getType(), V1_LIFECYCLE + "LifecycleFilter") && filter.getArguments().size() == 1 &&
               filter.getArguments().get(0) instanceof J.NewClass &&
               TypeUtils.isOfClassType(((J.NewClass) filter.getArguments().get(0)).getType(), V1_LIFECYCLE + "LifecyclePrefixPredicate") &&
               ((J.NewClass) filter.getArguments().get(0)).getArguments().size() == 1;
    }

    private static boolean signatureHasConfiguration(JavaType.Method mt) {
        return isV1(mt.getReturnType()) || mt.getParameterTypes().stream().anyMatch(MigrateS3BucketLifecycleConfiguration::isV1);
    }

    private static boolean isV1(@Nullable JavaType type) {
        return TypeUtils.isOfClassType(type, V1_CONFIGURATION) || TypeUtils.isOfClassType(type, V1_RULE);
    }

    private static String packageOf(String fqn) {
        return fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
    }
}

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
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MigrateAwsProfilesConfigFile extends Recipe {

    private static final String V1_CONFIG_FILE = "com.amazonaws.auth.profile.ProfilesConfigFile";
    private static final String V1_PROVIDER = "com.amazonaws.auth.profile.ProfileCredentialsProvider";
    private static final String V2_SUPPLIER = "software.amazon.awssdk.profiles.ProfileFileSupplier";
    private static final String V2_PROFILE_FILE = "software.amazon.awssdk.profiles.ProfileFile";
    private static final String V2_PROVIDER = "software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider";

    private static final MethodMatcher CONFIG_FILE_PATH = new MethodMatcher(V1_CONFIG_FILE + " <constructor>(String)");
    private static final MethodMatcher CONFIG_FILE_FILE = new MethodMatcher(V1_CONFIG_FILE + " <constructor>(java.io.File)");
    private static final MethodMatcher PROVIDER_WITH_FILE = new MethodMatcher(V1_PROVIDER + " <constructor>(" + V1_CONFIG_FILE + ", String)");
    private static final MethodMatcher REFRESH = new MethodMatcher(V1_PROVIDER + " setRefresh*IntervalNanos(long)");

    // The profiles module is not on the recipe's parser classpath; these stubs are enough to attribute the templates
    private static final String[] STUBS = {
            "package software.amazon.awssdk.profiles;\n" +
            "public final class ProfileFile {\n" +
            "    public enum Type { CONFIGURATION, CREDENTIALS }\n" +
            "}",
            "package software.amazon.awssdk.profiles;\n" +
            "public interface ProfileFileSupplier extends java.util.function.Supplier<ProfileFile> {\n" +
            "    static ProfileFileSupplier reloadWhenModified(java.nio.file.Path path, ProfileFile.Type type) { return null; }\n" +
            "}"
    };

    @Getter
    final String displayName = "Migrate AWS SDK v1 `ProfilesConfigFile`";

    @Getter
    final String description = "Runs before `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`, which does not map " +
            "`ProfilesConfigFile`. A profiles file becomes a `ProfileFileSupplier` that reloads the file when it changes, and " +
            "`ProfileCredentialsProvider` built from one becomes the v2 provider's builder. v1's timed refresh setters are removed, " +
            "since the supplier already rereads a changed file.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(new UsesType<>(V1_CONFIG_FILE, false), new JavaVisitor<ExecutionContext>() {

            @Override
            public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                doAfterVisit(new ChangeType(V1_CONFIG_FILE, V2_SUPPLIER, true).getVisitor());
                if (!hasOtherProviderConstructors(cu)) {
                    // Otherwise v2-migration still has v1 constructors to convert, and changes the type itself
                    doAfterVisit(new ChangeType(V1_PROVIDER, V2_PROVIDER, true).getVisitor());
                }
                return super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J j = super.visitNewClass(newClass, ctx);
                if (!(j instanceof J.NewClass)) {
                    return j;
                }
                J.NewClass n = (J.NewClass) j;
                if (CONFIG_FILE_PATH.matches(n)) {
                    return supplier("Paths.get(#{any(String)})", n, ctx);
                }
                if (CONFIG_FILE_FILE.matches(n)) {
                    return supplier("#{any(java.io.File)}.toPath()", n, ctx);
                }
                if (PROVIDER_WITH_FILE.matches(n)) {
                    // A null file means the default profiles file in v2's builder too
                    return template("ProfileCredentialsProvider.builder().profileFile(#{any(" + V2_SUPPLIER + ")})" +
                                    ".profileName(#{any(String)}).build()", n, ctx,
                            n.getArguments().get(0), n.getArguments().get(1));
                }
                return n;
            }

            @Override
            public @Nullable J visitIf(J.If iff, ExecutionContext ctx) {
                // Drop an `if` that only guarded the refresh setters
                if (iff.getElsePart() == null && iff.getThenPart() instanceof J.Block && isNullCheck(iff.getIfCondition().getTree())) {
                    List<Statement> then = ((J.Block) iff.getThenPart()).getStatements();
                    if (!then.isEmpty() && then.stream().allMatch(MigrateAwsProfilesConfigFile::isRefresh)) {
                        return null;
                    }
                }
                return super.visitIf(iff, ctx);
            }

            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = (J.Block) super.visitBlock(block, ctx);
                return b.withStatements(ListUtils.map(b.getStatements(), s -> isRefresh(s) ? null : s));
            }

            private J supplier(String path, J.NewClass n, ExecutionContext ctx) {
                maybeAddImport(V2_SUPPLIER);
                maybeAddImport(V2_PROFILE_FILE);
                maybeAddImport("java.nio.file.Paths");
                return template("ProfileFileSupplier.reloadWhenModified(" + path + ", ProfileFile.Type.CREDENTIALS)", n, ctx,
                        n.getArguments().get(0));
            }

            private J template(String code, J.NewClass n, ExecutionContext ctx, Object... args) {
                return JavaTemplate.builder(code)
                        .imports(V2_SUPPLIER, V2_PROFILE_FILE, V2_PROVIDER, "java.nio.file.Paths")
                        .javaParser(AwsV2Parser.parser(ctx).dependsOn(STUBS))
                        .build()
                        .apply(getCursor(), n.getCoordinates().replace(), args);
            }
        });
    }

    private static boolean isRefresh(Statement s) {
        return s instanceof J.MethodInvocation && REFRESH.matches((J.MethodInvocation) s);
    }

    private static boolean hasOtherProviderConstructors(J.CompilationUnit cu) {
        AtomicBoolean found = new AtomicBoolean();
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, AtomicBoolean f) {
                if (TypeUtils.isOfClassType(newClass.getType(), V1_PROVIDER) && !PROVIDER_WITH_FILE.matches(newClass)) {
                    f.set(true);
                }
                return super.visitNewClass(newClass, f);
            }
        }.visit(cu, found);
        return found.get();
    }

    private static boolean isNullCheck(Expression condition) {
        if (!(condition instanceof J.Binary) || ((J.Binary) condition).getOperator() != J.Binary.Type.NotEqual) {
            return false;
        }
        J.Binary b = (J.Binary) condition;
        Expression other = J.Literal.isLiteralValue(b.getRight(), null) ? b.getLeft() :
                J.Literal.isLiteralValue(b.getLeft(), null) ? b.getRight() : null;
        return other instanceof J.Identifier || other instanceof J.FieldAccess ||
               other instanceof J.MethodInvocation && ((J.MethodInvocation) other).getArguments().stream().allMatch(J.Empty.class::isInstance);
    }
}

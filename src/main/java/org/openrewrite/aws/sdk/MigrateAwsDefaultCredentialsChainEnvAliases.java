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
import org.openrewrite.binary.Binary;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.quark.Quark;
import org.openrewrite.remote.Remote;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;

// v1's chain is kept only where the aliases are referenced, so other projects get v2's own default chain
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAwsDefaultCredentialsChainEnvAliases extends ScanningRecipe<MigrateAwsDefaultCredentialsChainEnvAliases.Accumulator> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    static final String V1_CHAIN = "com.amazonaws.auth.DefaultAWSCredentialsProviderChain";
    static final String SHIM = "LegacyDefaultCredentialsChain";
    private static final Pattern ALIAS = Pattern.compile("\\bAWS_(ACCESS|SECRET)_KEY\\b");
    private static final MethodMatcher NEW_CHAIN = new MethodMatcher(V1_CHAIN + " <constructor>()");
    private static final MethodMatcher GET_INSTANCE = new MethodMatcher(V1_CHAIN + " getInstance()");

    transient AwsCredentialChainSwaps swaps = new AwsCredentialChainSwaps(this);

    @Getter
    final String displayName = "Keep AWS SDK v1's default credentials chain where its environment variable aliases are used";

    @Getter
    final String description = "When the repository references the `AWS_ACCESS_KEY` or `AWS_SECRET_KEY` environment variables, which only " +
                               "AWS SDK v1 reads, `DefaultAWSCredentialsProviderChain` is replaced with a generated `LegacyDefaultCredentialsChain`. " +
                               "It implements v2's `AwsCredentialsProvider` with v1's order and names: environment variables with aliases, " +
                               "v1 system properties, web identity, profile, then container or instance profile. Every swap is reported.";

    public static class Accumulator implements AwsMigrationScope.Limitable {
        final ProjectShims shims = new ProjectShims();

        @Override
        public void limitTo(AwsMigrationScope scope) {
            shims.limitTo(scope);
        }

        @Nullable
        String evidence;
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
                if (!(tree instanceof SourceFile) || tree instanceof Binary || tree instanceof Quark || tree instanceof Remote) {
                    return tree;
                }
                SourceFile sourceFile = (SourceFile) tree;
                acc.shims.recordExisting(sourceFile);
                if (tree instanceof J.CompilationUnit && MigratePropertiesFileAwsCredentialsProviders.uses((J.CompilationUnit) tree, V1_CHAIN, ctx)) {
                    acc.shims.require((J.CompilationUnit) tree);
                }
                if (acc.evidence == null && hasAlias(sourceFile)) {
                    acc.evidence = sourceFile.getSourcePath().toString();
                }
                return tree;
            }
        };
    }

    // In code only string literals count, so a field that happens to be named AWS_ACCESS_KEY is not evidence
    private static boolean hasAlias(SourceFile sourceFile) {
        if (sourceFile instanceof JavaSourceFile) {
            boolean[] found = {false};
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.Literal visitLiteral(J.Literal literal, Integer p) {
                    if (literal.getValue() instanceof String && ALIAS.matcher((String) literal.getValue()).find()) {
                        found[0] = true;
                    }
                    return literal;
                }
            }.visit(sourceFile, 0);
            return found[0];
        }
        // Some LST types (e.g. HCL comments over the Moderne CLI's remote trees) cannot be printed; they are not evidence
        try {
            return ALIAS.matcher(sourceFile.printAll()).find();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        if (acc.evidence == null) {
            return emptyList();
        }
        return acc.shims.generate(SHIM, MigrateAwsDefaultCredentialsChainEnvAliases::source, ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return JavaOnly.check(new UsesType<>(V1_CHAIN, false), new JavaIsoVisitor<ExecutionContext>() {
            @Nullable
            String shim;

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                if (acc.evidence == null) {
                    return cu;
                }
                shim = acc.shims.fqn(cu, SHIM);
                if (shim == null) {
                    return cu;
                }
                J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);
                doAfterVisit(new ChangeType(V1_CHAIN, shim, true).getVisitor());
                return c;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                if (NEW_CHAIN.matches(newClass)) {
                    report(newClass, ctx);
                }
                return super.visitNewClass(newClass, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (GET_INSTANCE.matches(method)) {
                    report(method, ctx);
                }
                return super.visitMethodInvocation(method, ctx);
            }

            private void report(J j, ExecutionContext ctx) {
                if (shim == null) {
                    return;
                }
                swaps.insertRow(ctx, new AwsCredentialChainSwaps.Row(
                        getCursor().firstEnclosingOrThrow(J.CompilationUnit.class).getSourcePath().toString(),
                        j.printTrimmed(getCursor().getParentOrThrow()),
                        String.valueOf(shim),
                        String.valueOf(acc.evidence)));
            }
        });
    }

    static String source(String pkg) {
        return ProjectShims.packageLine(pkg) +
               "import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;\n" +
               "import software.amazon.awssdk.auth.credentials.AwsCredentials;\n" +
               "import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;\n" +
               "import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;\n" +
               "import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;\n" +
               "import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;\n" +
               "import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;\n" +
               "import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;\n" +
               "import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;\n" +
               "import software.amazon.awssdk.core.exception.SdkClientException;\n" +
               "\n" +
               "import java.util.Arrays;\n" +
               "\n" +
               "/**\n" +
               " * AWS SDK v1's default credentials chain on SDK v2. Unlike v2's {@code DefaultCredentialsProvider} it reads environment\n" +
               " * variables before system properties, accepts the {@code AWS_ACCESS_KEY} and {@code AWS_SECRET_KEY} aliases, and reads the\n" +
               " * v1 system property {@code aws.secretKey}.\n" +
               " */\n" +
               "public class LegacyDefaultCredentialsChain implements AwsCredentialsProvider {\n" +
               "\n" +
               "    private static final LegacyDefaultCredentialsChain INSTANCE = new LegacyDefaultCredentialsChain();\n" +
               "\n" +
               "    private final AwsCredentialsProvider delegate;\n" +
               "\n" +
               "    public LegacyDefaultCredentialsChain() {\n" +
               "        AwsCredentialsProvider environment = LegacyDefaultCredentialsChain::fromEnvironment;\n" +
               "        AwsCredentialsProvider systemProperties = LegacyDefaultCredentialsChain::fromSystemProperties;\n" +
               "        this.delegate = AwsCredentialsProviderChain.builder()\n" +
               "                .reuseLastProviderEnabled(true)\n" +
               "                .credentialsProviders(Arrays.<AwsCredentialsProvider>asList(\n" +
               "                        environment,\n" +
               "                        systemProperties,\n" +
               "                        WebIdentityTokenFileCredentialsProvider.create(),\n" +
               "                        ProfileCredentialsProvider.create(),\n" +
               "                        containerOrInstanceProfile()))\n" +
               "                .build();\n" +
               "    }\n" +
               "\n" +
               "    public static LegacyDefaultCredentialsChain getInstance() {\n" +
               "        return INSTANCE;\n" +
               "    }\n" +
               "\n" +
               "    @Override\n" +
               "    public AwsCredentials resolveCredentials() {\n" +
               "        return delegate.resolveCredentials();\n" +
               "    }\n" +
               "\n" +
               "    private static AwsCredentials fromEnvironment() {\n" +
               "        String accessKey = System.getenv(\"AWS_ACCESS_KEY_ID\");\n" +
               "        if (accessKey == null) {\n" +
               "            accessKey = System.getenv(\"AWS_ACCESS_KEY\");\n" +
               "        }\n" +
               "        String secretKey = System.getenv(\"AWS_SECRET_KEY\");\n" +
               "        if (secretKey == null) {\n" +
               "            secretKey = System.getenv(\"AWS_SECRET_ACCESS_KEY\");\n" +
               "        }\n" +
               "        return credentials(accessKey, secretKey, System.getenv(\"AWS_SESSION_TOKEN\"), System.getenv(\"AWS_ACCOUNT_ID\"),\n" +
               "                \"Unable to load AWS credentials from environment variables \" +\n" +
               "                \"(AWS_ACCESS_KEY_ID (or AWS_ACCESS_KEY) and AWS_SECRET_KEY (or AWS_SECRET_ACCESS_KEY))\");\n" +
               "    }\n" +
               "\n" +
               "    private static AwsCredentials fromSystemProperties() {\n" +
               "        return credentials(System.getProperty(\"aws.accessKeyId\"), System.getProperty(\"aws.secretKey\"),\n" +
               "                System.getProperty(\"aws.sessionToken\"), System.getProperty(\"aws.accountId\"),\n" +
               "                \"Unable to load AWS credentials from Java system properties (aws.accessKeyId and aws.secretKey)\");\n" +
               "    }\n" +
               "\n" +
               "    private static AwsCredentials credentials(String accessKey, String secretKey, String sessionToken, String accountId,\n" +
               "                                              String missing) {\n" +
               "        accessKey = trim(accessKey);\n" +
               "        secretKey = trim(secretKey);\n" +
               "        sessionToken = trim(sessionToken);\n" +
               "        accountId = trim(accountId);\n" +
               "        if (isNullOrEmpty(accessKey) || isNullOrEmpty(secretKey)) {\n" +
               "            throw SdkClientException.create(missing);\n" +
               "        }\n" +
               "        if (isNullOrEmpty(sessionToken)) {\n" +
               "            return AwsBasicCredentials.builder().accessKeyId(accessKey).secretAccessKey(secretKey).accountId(accountId).build();\n" +
               "        }\n" +
               "        return AwsSessionCredentials.builder().accessKeyId(accessKey).secretAccessKey(secretKey).sessionToken(sessionToken)\n" +
               "                .accountId(accountId).build();\n" +
               "    }\n" +
               "\n" +
               "    // v1 picks the container or instance profile source once, from the container environment variables\n" +
               "    private static AwsCredentialsProvider containerOrInstanceProfile() {\n" +
               "        try {\n" +
               "            if (System.getenv(\"AWS_CONTAINER_CREDENTIALS_RELATIVE_URI\") != null ||\n" +
               "                System.getenv(\"AWS_CONTAINER_CREDENTIALS_FULL_URI\") != null) {\n" +
               "                return ContainerCredentialsProvider.create();\n" +
               "            }\n" +
               "        } catch (SecurityException ignored) {\n" +
               "        }\n" +
               "        return InstanceProfileCredentialsProvider.create();\n" +
               "    }\n" +
               "\n" +
               "    private static String trim(String value) {\n" +
               "        return value == null ? null : value.trim();\n" +
               "    }\n" +
               "\n" +
               "    private static boolean isNullOrEmpty(String value) {\n" +
               "        return value == null || value.isEmpty();\n" +
               "    }\n" +
               "\n" +
               "    @Override\n" +
               "    public String toString() {\n" +
               "        return getClass().getSimpleName();\n" +
               "    }\n" +
               "}\n";
    }
}

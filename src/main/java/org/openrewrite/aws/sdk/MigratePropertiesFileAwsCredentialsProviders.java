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
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * v2 has no properties-file credentials providers, so a minimal copy of v1's is generated into the project (tier 3).
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigratePropertiesFileAwsCredentialsProviders extends ScanningRecipe<MigratePropertiesFileAwsCredentialsProviders.Accumulator> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    static final String V1_FILE = "com.amazonaws.auth.PropertiesFileCredentialsProvider";
    static final String V1_CLASSPATH = "com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider";
    static final String FILE = "PropertiesFileCredentialsProvider";
    static final String CLASSPATH = "ClasspathPropertiesFileCredentialsProvider";

    @Getter
    final String displayName = "Migrate AWS SDK v1 properties-file credentials providers";

    @Getter
    final String description = "Replaces `PropertiesFileCredentialsProvider` and `ClasspathPropertiesFileCredentialsProvider`, which have no " +
                               "v2 counterpart, with copies generated into the project that implement v2's `AwsCredentialsProvider`. " +
                               "They read the same `accessKey`, `secretKey` and `accountId` properties on every call, as v1 does.";

    public static class Accumulator implements AwsMigrationScope.Limitable {
        final ProjectShims file = new ProjectShims();
        final ProjectShims classpath = new ProjectShims();

        @Override
        public void limitTo(AwsMigrationScope scope) {
            file.limitTo(scope);
            classpath.limitTo(scope);
        }
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
                    acc.file.recordExisting((SourceFile) tree);
                    acc.classpath.recordExisting((SourceFile) tree);
                }
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    if (uses(cu, V1_FILE, ctx)) {
                        acc.file.require(cu);
                    }
                    if (uses(cu, V1_CLASSPATH, ctx)) {
                        acc.classpath.require(cu);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        List<SourceFile> generated = new ArrayList<>(acc.file.generate(FILE, MigratePropertiesFileAwsCredentialsProviders::fileSource, ctx));
        generated.addAll(acc.classpath.generate(CLASSPATH, MigratePropertiesFileAwsCredentialsProviders::classpathSource, ctx));
        return generated;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return JavaOnly.check(Preconditions.or(new UsesType<>(V1_FILE, false), new UsesType<>(V1_CLASSPATH, false)),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                        String file = acc.file.fqn(cu, FILE);
                        if (file != null) {
                            doAfterVisit(new ChangeType(V1_FILE, file, true).getVisitor());
                        }
                        String classpath = acc.classpath.fqn(cu, CLASSPATH);
                        if (classpath != null) {
                            doAfterVisit(new ChangeType(V1_CLASSPATH, classpath, true).getVisitor());
                        }
                        return cu;
                    }
                });
    }

    static boolean uses(J.CompilationUnit cu, String fqn, ExecutionContext ctx) {
        return new UsesType<>(fqn, false).visit(cu, ctx) != cu;
    }

    static String fileSource(String pkg) {
        return ProjectShims.packageLine(pkg) +
               "import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;\n" +
               "import software.amazon.awssdk.auth.credentials.AwsCredentials;\n" +
               "import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;\n" +
               "import software.amazon.awssdk.core.exception.SdkClientException;\n" +
               "\n" +
               "import java.io.File;\n" +
               "import java.io.FileInputStream;\n" +
               "import java.io.FileNotFoundException;\n" +
               "import java.io.IOException;\n" +
               "import java.util.Properties;\n" +
               "\n" +
               "/**\n" +
               " * Copy of AWS SDK v1's {@code PropertiesFileCredentialsProvider} for SDK v2, which has no equivalent. Reads the\n" +
               " * {@code accessKey}, {@code secretKey} and optional {@code accountId} properties from the file on every call.\n" +
               " */\n" +
               "public class PropertiesFileCredentialsProvider implements AwsCredentialsProvider {\n" +
               "\n" +
               "    private final String credentialsFilePath;\n" +
               "\n" +
               "    public PropertiesFileCredentialsProvider(String credentialsFilePath) {\n" +
               "        if (credentialsFilePath == null) {\n" +
               "            throw new IllegalArgumentException(\"Credentials file path cannot be null\");\n" +
               "        }\n" +
               "        this.credentialsFilePath = credentialsFilePath;\n" +
               "    }\n" +
               "\n" +
               "    @Override\n" +
               "    public AwsCredentials resolveCredentials() {\n" +
               "        try {\n" +
               "            File file = new File(credentialsFilePath);\n" +
               "            if (!file.exists()) {\n" +
               "                throw new FileNotFoundException(\"File doesn't exist:  \" + file.getAbsolutePath());\n" +
               "            }\n" +
               "            Properties properties = new Properties();\n" +
               "            FileInputStream stream = new FileInputStream(file);\n" +
               "            try {\n" +
               "                properties.load(stream);\n" +
               "            } finally {\n" +
               "                try {\n" +
               "                    stream.close();\n" +
               "                } catch (IOException ignored) {\n" +
               "                }\n" +
               "            }\n" +
               "            String accessKey = properties.getProperty(\"accessKey\");\n" +
               "            String secretKey = properties.getProperty(\"secretKey\");\n" +
               "            if (accessKey == null || secretKey == null) {\n" +
               "                throw new IllegalArgumentException(\"The specified file (\" + file.getAbsolutePath() +\n" +
               "                        \") doesn't contain the expected properties 'accessKey' and 'secretKey'.\");\n" +
               "            }\n" +
               "            return AwsBasicCredentials.builder()\n" +
               "                    .accessKeyId(accessKey)\n" +
               "                    .secretAccessKey(secretKey)\n" +
               "                    .accountId(properties.getProperty(\"accountId\"))\n" +
               "                    .build();\n" +
               "        } catch (IOException e) {\n" +
               "            throw SdkClientException.create(\"Unable to load AWS credentials from the \" + credentialsFilePath + \" file\", e);\n" +
               "        }\n" +
               "    }\n" +
               "\n" +
               "    @Override\n" +
               "    public String toString() {\n" +
               "        return getClass().getSimpleName() + \"(\" + credentialsFilePath + \")\";\n" +
               "    }\n" +
               "}\n";
    }

    static String classpathSource(String pkg) {
        return ProjectShims.packageLine(pkg) +
               "import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;\n" +
               "import software.amazon.awssdk.auth.credentials.AwsCredentials;\n" +
               "import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;\n" +
               "import software.amazon.awssdk.core.exception.SdkClientException;\n" +
               "\n" +
               "import java.io.IOException;\n" +
               "import java.io.InputStream;\n" +
               "import java.util.Properties;\n" +
               "\n" +
               "/**\n" +
               " * Copy of AWS SDK v1's {@code ClasspathPropertiesFileCredentialsProvider} for SDK v2, which has no equivalent. Reads the\n" +
               " * {@code accessKey}, {@code secretKey} and optional {@code accountId} properties from the classpath resource on every call.\n" +
               " */\n" +
               "public class ClasspathPropertiesFileCredentialsProvider implements AwsCredentialsProvider {\n" +
               "\n" +
               "    private final String credentialsFilePath;\n" +
               "\n" +
               "    public ClasspathPropertiesFileCredentialsProvider() {\n" +
               "        this(\"AwsCredentials.properties\");\n" +
               "    }\n" +
               "\n" +
               "    public ClasspathPropertiesFileCredentialsProvider(String credentialsFilePath) {\n" +
               "        if (credentialsFilePath == null) {\n" +
               "            throw new IllegalArgumentException(\"Credentials file path cannot be null\");\n" +
               "        }\n" +
               "        this.credentialsFilePath = credentialsFilePath.startsWith(\"/\") ? credentialsFilePath : \"/\" + credentialsFilePath;\n" +
               "    }\n" +
               "\n" +
               "    @Override\n" +
               "    public AwsCredentials resolveCredentials() {\n" +
               "        InputStream inputStream = getClass().getResourceAsStream(credentialsFilePath);\n" +
               "        if (inputStream == null) {\n" +
               "            throw SdkClientException.create(\"Unable to load AWS credentials from the \" + credentialsFilePath + \" file on the classpath\");\n" +
               "        }\n" +
               "        Properties properties = new Properties();\n" +
               "        try {\n" +
               "            properties.load(inputStream);\n" +
               "        } catch (IOException e) {\n" +
               "            throw SdkClientException.create(\"Unable to load AWS credentials from the \" + credentialsFilePath + \" file on the classpath\", e);\n" +
               "        } finally {\n" +
               "            try {\n" +
               "                inputStream.close();\n" +
               "            } catch (Exception ignored) {\n" +
               "            }\n" +
               "        }\n" +
               "        String accessKey = properties.getProperty(\"accessKey\");\n" +
               "        String secretKey = properties.getProperty(\"secretKey\");\n" +
               "        if (accessKey == null || secretKey == null) {\n" +
               "            throw new IllegalArgumentException(\"The specified properties data doesn't contain the expected properties 'accessKey' and 'secretKey'.\");\n" +
               "        }\n" +
               "        return AwsBasicCredentials.builder()\n" +
               "                .accessKeyId(accessKey)\n" +
               "                .secretAccessKey(secretKey)\n" +
               "                .accountId(properties.getProperty(\"accountId\"))\n" +
               "                .build();\n" +
               "    }\n" +
               "\n" +
               "    @Override\n" +
               "    public String toString() {\n" +
               "        return getClass().getSimpleName() + \"(\" + credentialsFilePath + \")\";\n" +
               "    }\n" +
               "}\n";
    }
}

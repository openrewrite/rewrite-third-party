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
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

// v2's Ec2MetadataClient throws where v1 logged and returned null, and has no typed documents, so a generated class keeps v1's API
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateEc2MetadataUtils extends ScanningRecipe<ProjectShims> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    static final String V1 = "com.amazonaws.util.EC2MetadataUtils";
    static final String SHIM = "Ec2MetadataUtils";

    @Getter
    final String displayName = "Migrate AWS SDK v1 `EC2MetadataUtils`";

    @Getter
    final String description = "Replaces `com.amazonaws.util.EC2MetadataUtils` with a generated `Ec2MetadataUtils` that has the same static " +
                               "methods, caching and null-on-failure behaviour, implemented with v2's `Ec2MetadataClient` (`imds`), " +
                               "including the `InstanceInfo`, `IAMInfo`, `IAMSecurityCredential` and `NetworkInterface` documents.";

    @Override
    public ProjectShims getInitialValue(ExecutionContext ctx) {
        return new ProjectShims();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(ProjectShims acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    acc.recordExisting((SourceFile) tree);
                }
                if (tree instanceof J.CompilationUnit && MigratePropertiesFileAwsCredentialsProviders.uses((J.CompilationUnit) tree, V1, ctx)) {
                    acc.require((J.CompilationUnit) tree);
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(ProjectShims acc, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        return acc.generate(SHIM, MigrateEc2MetadataUtils::source,
                JavaParser.fromJavaVersion().classpathFromResources(ctx, "imds-2", "sdk-core-2", "http-client-spi-2", "utils-2"), ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(ProjectShims acc) {
        return JavaOnly.check(new UsesType<>(V1, false), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                String shim = acc.fqn(cu, SHIM);
                if (shim != null) {
                    doAfterVisit(new ChangeType(V1, shim, true).getVisitor());
                }
                return cu;
            }
        });
    }

    static String source(String pkg) {
        try (InputStream in = MigrateEc2MetadataUtils.class.getResourceAsStream("/META-INF/rewrite/aws-sdk/" + SHIM + ".java.template")) {
            return StringUtils.readFully(Objects.requireNonNull(in), StandardCharsets.UTF_8)
                    .replace("__PACKAGE__", ProjectShims.packageLine(pkg));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

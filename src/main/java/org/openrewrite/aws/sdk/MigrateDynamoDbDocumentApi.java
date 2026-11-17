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
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * v2 has no Document API, and v2-migration moves its types to a package that does not exist. The Enhanced client's
 * documents differ in shape, so the calls go to a generated class with v1's API on the v2 client instead.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateDynamoDbDocumentApi extends ScanningRecipe<ProjectShims> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    static final String SHIM = "DynamoDbDocumentApi";
    private static final String DOCUMENT = "com.amazonaws.services.dynamodbv2.document.";

    // the v1 types the generated class has, by simple name
    private static final Map<String, String> TYPES = new LinkedHashMap<>();

    static {
        for (String type : Arrays.asList("DynamoDB", "Table", "Index", "Item", "PrimaryKey", "KeyAttribute", "ItemCollection",
                "TableCollection", "QueryOutcome", "ScanOutcome", "GetItemOutcome", "PutItemOutcome", "UpdateItemOutcome",
                "DeleteItemOutcome", "DeleteTableOutcome", "TableWriteItems", "BatchWriteItemOutcome", "ItemUtils",
                "IncompatibleTypeException", "Expected", "RangeKeyCondition", "KeyConditions", "AttributeUpdate", "QueryFilter",
                "ScanFilter")) {
            TYPES.put(type, DOCUMENT + type);
        }
        for (String type : Arrays.asList("GetItemSpec", "PutItemSpec", "DeleteItemSpec", "UpdateItemSpec", "QuerySpec", "ScanSpec")) {
            TYPES.put(type, DOCUMENT + "spec." + type);
        }
        for (String type : Arrays.asList("ValueMap", "NameMap")) {
            TYPES.put(type, DOCUMENT + "utils." + type);
        }
        TYPES.put("Filter", DOCUMENT + "internal.Filter");
    }

    String displayName = "Migrate the AWS SDK v1 DynamoDB Document API";

    String description = "Runs before `software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2`. Moves the v1 Document API " +
                         "(`DynamoDB`, `Table`, `Item`, `PrimaryKey`, the item specs and outcomes, `ItemCollection`, `ValueMap`, " +
                         "`NameMap`, `TableWriteItems`, `ItemUtils`, the legacy `Expected`, `RangeKeyCondition`, `AttributeUpdate`, " +
                         "`QueryFilter` and `ScanFilter` conditions and `Item`'s JSON conversions) to a generated `DynamoDbDocumentApi` " +
                         "with the same API and item values on the v2 `DynamoDbClient`, which still takes the legacy conditions.";

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
                if (tree instanceof J.CompilationUnit && usesDocumentApi((J.CompilationUnit) tree)) {
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
        return acc.generate(SHIM, MigrateDynamoDbDocumentApi::source,
                JavaParser.fromJavaVersion().classpathFromResources(ctx, "dynamodb-2", "aws-core-2", "sdk-core-2", "utils-2"), ctx);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(ProjectShims acc) {
        return JavaOnly.check(new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                return tree instanceof J.CompilationUnit && usesDocumentApi((J.CompilationUnit) tree) ?
                        org.openrewrite.marker.SearchResult.found(tree) : tree;
            }
        }, new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                String shim = acc.fqn(cu, SHIM);
                if (shim != null) {
                    for (Map.Entry<String, String> type : TYPES.entrySet()) {
                        doAfterVisit(new ChangeType(type.getValue(), shim + "$" + type.getKey(), true).getVisitor());
                    }
                }
                return cu;
            }
        });
    }

    private static boolean usesDocumentApi(J.CompilationUnit cu) {
        for (JavaType type : cu.getTypesInUse().getTypesInUse()) {
            if (type instanceof JavaType.FullyQualified && TYPES.containsValue(((JavaType.FullyQualified) type).getFullyQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    static String source(String pkg) {
        try (InputStream in = MigrateDynamoDbDocumentApi.class.getResourceAsStream("/META-INF/rewrite/aws-sdk/" + SHIM + ".java.template")) {
            return StringUtils.readFully(Objects.requireNonNull(in), StandardCharsets.UTF_8)
                    .replace("__PACKAGE__", ProjectShims.packageLine(pkg));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

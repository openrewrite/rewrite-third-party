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
package org.openrewrite.azure.sdk.search;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.azure.sdk.LegacyAzureTypes;
import org.openrewrite.azure.sdk.table.LegacyAzureSdkRemnants;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.SearchResult;

import java.util.LinkedHashMap;
import java.util.Map;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindAzureSdkLegacyRemnants extends Recipe {

    private static final Map<String, String> GUIDES = new LinkedHashMap<>();

    static {
        String sdk = "https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/";
        GUIDES.put("com.microsoft.azure.storage.blob", sdk + "storage/azure-storage-blob/migrationGuides/V8_V12.md");
        GUIDES.put("com.microsoft.azure.storage.queue", sdk + "storage/azure-storage-queue/migrationGuides/V8_V12.md");
        GUIDES.put("com.microsoft.azure.storage.file", sdk + "storage/azure-storage-file-share/migrationGuides/V8_V12.md");
        GUIDES.put("com.microsoft.azure.storage.table", sdk + "tables/azure-data-tables/migration_guide.md");
        GUIDES.put("com.microsoft.azure.storage", sdk + "storage/azure-storage-blob/migrationGuides/V8_V12.md");
        GUIDES.put("com.microsoft.aad.adal4j", "https://learn.microsoft.com/entra/msal/java/advanced/migrate-adal-msal-java");
        GUIDES.put("com.microsoft.azure.eventhubs", sdk + "eventhubs/azure-messaging-eventhubs/migration-guide.md");
        GUIDES.put("com.microsoft.azure.eventprocessorhost", sdk + "eventhubs/azure-messaging-eventhubs/migration-guide.md");
        GUIDES.put("com.microsoft.azure.servicebus", sdk + "servicebus/azure-messaging-servicebus/migration-guide.md");
        GUIDES.put("com.microsoft.azure.keyvault", sdk + "keyvault/azure-security-keyvault-secrets/migration_guide.md");
        GUIDES.put("com.microsoft.azure.documentdb", "https://learn.microsoft.com/azure/cosmos-db/nosql/migrate-java-v4-sdk");
        GUIDES.put("com.microsoft.azure.cosmosdb", "https://learn.microsoft.com/azure/cosmos-db/nosql/migrate-java-v4-sdk");
        GUIDES.put("com.microsoft.azure.management", sdk + "resourcemanager/docs/MIGRATION_GUIDE.md");
        GUIDES.put("com.microsoft.azure.credentials", sdk + "resourcemanager/docs/MIGRATION_GUIDE.md");
        GUIDES.put("org.apache.spark.eventhubs", "https://github.com/Azure/azure-event-hubs-spark");
        GUIDES.put("com.microsoft.azure", "https://learn.microsoft.com/azure/developer/java/sdk/");
        GUIDES.put("com.microsoft.rest", "https://learn.microsoft.com/azure/developer/java/sdk/");
    }

    @Getter
    final String displayName = "Find legacy Azure SDK uses left to migrate";

    @Getter
    final String description = "Marks the calls, constants and types of the legacy Azure SDK for Java (`com.microsoft.azure`) " +
                               "and ADAL4J that have no automatic migration to the current Azure SDK and MSAL4J, and lists " +
                               "them in the `LegacyAzureSdkRemnants` data table with the migration guide that covers them. " +
                               "Run on its own, it lists every legacy use; after the migration, what it left.";

    @Getter
    transient LegacyAzureSdkRemnants remnants = new LegacyAzureSdkRemnants(this);

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.Import visitImport(J.Import _import, ExecutionContext ctx) {
                return _import;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);
                // A supertype the migration has a counterpart for is no remnant as a type, but a class extending or
                // implementing it is left as it is, so it is marked here. One without a counterpart is marked as a type.
                if (c.getExtends() != null && isMappedLegacy(c.getExtends().getType())) {
                    c = c.withExtends(supertype(c.getExtends(), ctx));
                }
                if (c.getImplements() != null) {
                    c = c.withImplements(ListUtils.map(c.getImplements(), i -> isMappedLegacy(i.getType()) ? supertype(i, ctx) : i));
                }
                return c;
            }

            private TypeTree supertype(TypeTree supertype, ExecutionContext ctx) {
                String type = fqn(supertype.getType());
                return report(supertype, "supertype", type + " as a supertype", type, ctx);
            }

            private boolean isMappedLegacy(@Nullable JavaType type) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                return fq != null && LegacyAzureTypes.isLegacy(fq) && LegacyAzureTypes.current(fq.getFullyQualifiedName()) != null;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                return mark(m, m.getMethodType(), ctx);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J.NewClass n = super.visitNewClass(newClass, ctx);
                return mark(n, n.getConstructorType(), ctx);
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference memberRef, ExecutionContext ctx) {
                J.MemberReference r = super.visitMemberReference(memberRef, ctx);
                return mark(r, r.getMethodType(), ctx);
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                J.FieldAccess f = super.visitFieldAccess(fieldAccess, ctx);
                JavaType.Variable field = f.getName().getFieldType();
                if (field != null && LegacyAzureTypes.isLegacy(field.getOwner())) {
                    String owner = fqn(field.getOwner());
                    if ("class".equals(field.getName()) && LegacyAzureTypes.current(owner) != null) {
                        // A class literal is retyped with its type
                        return f;
                    }
                    return report(f, "constant", owner + "." + field.getName(), owner, ctx);
                }
                if (field == null && isTypeReference() && isUnmappedLegacy(f.getType())) {
                    String type = fqn(f.getType());
                    return report(f, "type", type, type, ctx);
                }
                return f;
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                J.Identifier i = super.visitIdentifier(identifier, ctx);
                if (i.getFieldType() == null && isTypeReference() && isUnmappedLegacy(i.getType()) &&
                    !(getCursor().getParentTreeCursor().getValue() instanceof J.FieldAccess)) {
                    String type = fqn(i.getType());
                    return report(i, "type", type, type, ctx);
                }
                return i;
            }

            @Override
            public J.TypeCast visitTypeCast(J.TypeCast typeCast, ExecutionContext ctx) {
                J.TypeCast t = super.visitTypeCast(typeCast, ctx);
                return conversion(t, t.getExpression().getType(), t.getClazz().getTree().getType(), ctx);
            }

            @Override
            public J.InstanceOf visitInstanceOf(J.InstanceOf instanceOf, ExecutionContext ctx) {
                J.InstanceOf i = super.visitInstanceOf(instanceOf, ctx);
                return conversion(i, i.getExpression().getType(), i.getClazz() instanceof TypedTree ? ((TypedTree) i.getClazz()).getType() : null, ctx);
            }

            /**
             * A cast or {@code instanceof} between two legacy types whose counterparts differ, such as a listed
             * {@code ListBlobItem} to a {@code CloudBlob}, relates types that are unrelated once retyped.
             */
            private <J2 extends J> J2 conversion(J2 tree, @Nullable JavaType from, @Nullable JavaType to, ExecutionContext ctx) {
                JavaType.FullyQualified fromType = TypeUtils.asFullyQualified(from);
                JavaType.FullyQualified toType = TypeUtils.asFullyQualified(to);
                if (fromType == null || toType == null || !LegacyAzureTypes.isLegacy(fromType) || !LegacyAzureTypes.isLegacy(toType)) {
                    return tree;
                }
                String fromCurrent = LegacyAzureTypes.current(fromType.getFullyQualifiedName());
                String toCurrent = LegacyAzureTypes.current(toType.getFullyQualifiedName());
                if (fromCurrent == null || toCurrent == null || fromCurrent.equals(toCurrent)) {
                    // An unmapped type is a remnant of its own
                    return tree;
                }
                String item = fqn(fromType) + " as " + fqn(toType);
                return report(tree, "type", item, fqn(toType), ctx);
            }

            private boolean isTypeReference() {
                Object parent = getCursor().getParentTreeCursor().getValue();
                return !(parent instanceof J.MethodInvocation || parent instanceof J.NewClass ||
                         parent instanceof J.MemberReference || parent instanceof J.FieldAccess &&
                         ((J.FieldAccess) parent).getName().getFieldType() != null ||
                         parent instanceof J.Package);
            }

            private boolean isUnmappedLegacy(@Nullable JavaType type) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                return fq != null && !(type instanceof JavaType.Unknown) &&
                       LegacyAzureTypes.isLegacy(fq) && LegacyAzureTypes.current(fq.getFullyQualifiedName()) == null;
            }

            private <J2 extends J> J2 mark(J2 tree, JavaType.@Nullable Method type, ExecutionContext ctx) {
                if (type == null) {
                    return tree;
                }
                // A call into a library built on the legacy SDK keeps its source as it is
                boolean library = LegacyAzureTypes.isBuiltOnLegacy(type.getDeclaringType());
                if (!library && !LegacyAzureTypes.isLegacy(type.getDeclaringType())) {
                    return tree;
                }
                String owner = fqn(type.getDeclaringType());
                String item = owner + ("<constructor>".equals(type.getName()) ? "" : "." + type.getName());
                return report(tree, library ? "library" : "call", item, owner, ctx);
            }

            private <J2 extends J> J2 report(J2 tree, String kind, String item, String owner, ExecutionContext ctx) {
                String guide = guide(owner);
                SourceFile source = getCursor().firstEnclosing(SourceFile.class);
                if (source != null) {
                    remnants.insertRow(ctx, new LegacyAzureSdkRemnants.Row(source.getSourcePath().toString(), kind, item,
                            "No automatic migration. See " + guide));
                }
                return SearchResult.found(tree, "Legacy Azure SDK: migrate `" + item + "` by hand, see " + guide);
            }
        };
    }

    private static String fqn(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq == null ? String.valueOf(type) : fq.getFullyQualifiedName().replace('$', '.');
    }

    static String guide(String type) {
        for (Map.Entry<String, String> g : GUIDES.entrySet()) {
            if (type.startsWith(g.getKey() + ".")) {
                return g.getValue();
            }
        }
        return "https://learn.microsoft.com/azure/developer/java/sdk/";
    }
}

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
package org.openrewrite.azure.sdk;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

import static org.openrewrite.azure.sdk.CallRewrite.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAzureStorageBlob extends Recipe {

    static final String S = "com.microsoft.azure.storage.";
    static final String B = S + "blob.";
    private static final String V12 = "com.azure.storage.blob.";
    private static final String NO_OPTIONS = "Its request options and operation context are `null`.";

    @Getter
    final String displayName = "Migrate Azure Storage Blob v8 to v12";

    @Getter
    final String description = "Rewrites calls on the legacy `com.microsoft.azure.storage` blob client " +
                               "(`CloudStorageAccount`, `CloudBlobClient`, `CloudBlobContainer`, `CloudBlockBlob`, ...) to " +
                               "`com.azure:azure-storage-blob` 12.x, keeping v8's semantics where they differ: uploads " +
                               "overwrite, downloads to a file overwrite, `downloadAttributes()` still reaches the service, " +
                               "`Date` results stay `Date`. A storage account becomes its connection string. Calls that carry " +
                               "request options, operation contexts or access conditions other than `null` are left for " +
                               "`FindAzureSdkLegacyRemnants` to report.";

    static final List<CallRewrite> RULES = Arrays.asList(
            // Storage account and credentials
            call(S + "CloudStorageAccount parse(String)", "$0"),
            call(S + "CloudStorageAccount getDevelopmentStorageAccount()", "\"UseDevelopmentStorage=true\""),
            call(S + "CloudStorageAccount createCloudBlobClient()",
                    "new com.azure.storage.blob.BlobServiceClientBuilder().connectionString($r).buildClient()"),
            call(S + "CloudStorageAccount createCloudQueueClient()",
                    "new com.azure.storage.queue.QueueServiceClientBuilder().connectionString($r)" +
                    ".messageEncoding(com.azure.storage.queue.QueueMessageEncoding.BASE64).buildClient()"),
            call(S + "CloudStorageAccount createCloudTableClient()",
                    "new com.azure.data.tables.TableServiceClientBuilder().connectionString($r).buildClient()"),
            call(S + "CloudStorageAccount createCloudFileClient()",
                    "new com.azure.storage.file.share.ShareServiceClientBuilder().connectionString($r).buildClient()"),
            custom(S + "CloudStorageAccount <constructor>(" + S + "StorageCredentials, ..)", MigrateAzureStorageBlob::accountConnectionString),
            call(S + "StorageCredentialsAccountAndKey <constructor>(String, String)",
                    "new com.azure.storage.common.StorageSharedKeyCredential($0, $1)"),
            call(S + "StorageCredentialsSharedAccessSignature <constructor>(String)",
                    "new com.azure.core.credential.AzureSasCredential($0)"),
            call(S + "StorageException getHttpStatusCode()", "$r.getStatusCode()"),
            call(S + "StorageException getErrorCode()", "java.util.Objects.toString($r.getErrorCode(), null)"),

            // Service client
            call(B + "CloudBlobClient getContainerReference(String)", "$r.getBlobContainerClient($0)"),
            call(B + "CloudBlobClient getEndpoint()", "java.net.URI.create($r.getAccountUrl())"),
            call(B + "CloudBlobClient <constructor>(java.net.URI, " + S + "StorageCredentials)",
                    "new com.azure.storage.blob.BlobServiceClientBuilder().endpoint($0.toString()).credential($1).buildClient()"),

            // Container
            call(B + "CloudBlobContainer <constructor>(java.net.URI)",
                    "new com.azure.storage.blob.BlobContainerClientBuilder().endpoint($0.toString()).buildClient()"),
            call(B + "CloudBlobContainer <constructor>(java.net.URI, " + S + "StorageCredentials)",
                    "new com.azure.storage.blob.BlobContainerClientBuilder().endpoint($0.toString()).credential($1).buildClient()"),
            call(B + "CloudBlobContainer getName()", "$r.getBlobContainerName()"),
            call(B + "CloudBlobContainer getUri()", "java.net.URI.create($r.getBlobContainerUrl())"),
            call(B + "CloudBlobContainer getServiceClient()", "$r.getServiceClient()"),
            call(B + "CloudBlobContainer exists()", "$r.exists()"),
            call(B + "CloudBlobContainer exists(..)", "$r.exists()").when(argsNull(0, 1, 2)),
            call(B + "CloudBlobContainer create()", "$r.create()"),
            call(B + "CloudBlobContainer create(" + B + "BlobContainerPublicAccessType, ..)",
                    "$r.createWithResponse(null, $0, null, com.azure.core.util.Context.NONE)").when(argsNull(1, 2)),
            call(B + "CloudBlobContainer createIfNotExists()", "$r.createIfNotExists()"),
            call(B + "CloudBlobContainer createIfNotExists(" + B + "BlobContainerPublicAccessType, ..)", "$r.createIfNotExists()")
                    .when(argsNull(0, 1, 2)),
            call(B + "CloudBlobContainer createIfNotExists(" + B + "BlobContainerPublicAccessType, ..)",
                    "$r.createIfNotExistsWithResponse(new com.azure.storage.blob.options.BlobContainerCreateOptions()" +
                    ".setPublicAccessType($0), null, com.azure.core.util.Context.NONE).getValue()").when(argsNull(1, 2)),
            call(B + "CloudBlobContainer createIfNotExists(..)", "$r.createIfNotExists()").when(argsNull(0, 1)),
            call(B + "CloudBlobContainer delete()", "$r.delete()"),
            call(B + "CloudBlobContainer deleteIfExists()", "$r.deleteIfExists()"),
            call(B + "CloudBlobContainer getBlockBlobReference(String)", "$r.getBlobClient($0)"),
            call(B + "CloudBlobContainer getBlobReferenceFromServer(String)", "$r.getBlobClient($0)"),
            call(B + "CloudBlobContainer getAppendBlobReference(String)", "$r.getBlobClient($0).getAppendBlobClient()"),
            call(B + "CloudBlobContainer getPageBlobReference(String)", "$r.getBlobClient($0).getPageBlobClient()"),
            call(B + "CloudBlobContainer listBlobs()", "$r.listBlobsByHierarchy(null)"),
            call(B + "CloudBlobContainer listBlobs(String)", "$r.listBlobsByHierarchy($0)"),
            call(B + "CloudBlobContainer listBlobs(String, boolean)",
                    "$r.listBlobs(new com.azure.storage.blob.models.ListBlobsOptions().setPrefix($0), null)")
                    .when(c -> isLiteral(c.arg(1), true)),
            call(B + "CloudBlobContainer listBlobs(String, boolean)", "$r.listBlobsByHierarchy($0)")
                    .when(c -> isLiteral(c.arg(1), false)),
            call(B + "CloudBlobContainer downloadAttributes()", "$r.getProperties()"),
            call(B + "CloudBlobContainer getProperties()", "$r.getProperties()"),
            custom(B + "CloudBlobContainer getMetadata()", MigrateAzureStorageBlob::metadata),
            custom(B + "CloudBlobContainer setMetadata(java.util.HashMap)", MigrateAzureStorageBlob::setMetadata),
            delete(B + "CloudBlobContainer uploadMetadata()"),
            call(B + "CloudBlobContainer uploadPermissions(" + B + "BlobContainerPermissions)", "$r.setAccessPolicy($0, null)"),
            call(B + "CloudBlobContainer generateSharedAccessSignature(" + B + "SharedAccessBlobPolicy, String)",
                    "$r.generateSas($0)").when(argsNull(1)),
            custom(B + "BlobContainerPermissions <constructor>()", (c, r, ctx) -> nullLiteral(c.tree)),
            custom(B + "BlobContainerPermissions setPublicAccess(" + B + "BlobContainerPublicAccessType)",
                    MigrateAzureStorageBlob::assignPublicAccess),
            call(B + "BlobContainerProperties getEtag()", "$r.getETag()"),
            call(B + "BlobContainerProperties getLastModified()", "java.util.Date.from($r.getLastModified().toInstant())"),

            // Blobs
            call(B + "CloudBlockBlob <constructor>(java.net.URI)",
                    "new com.azure.storage.blob.BlobClientBuilder().endpoint($0.toString()).buildClient()"),
            call(B + "CloudBlockBlob <constructor>(java.net.URI, " + S + "StorageCredentials)",
                    "new com.azure.storage.blob.BlobClientBuilder().endpoint($0.toString()).credential($1).buildClient()"),
            call(B + "CloudBlob getName()", "$r.getBlobName()"),
            call(B + "CloudBlob getUri()", "java.net.URI.create($r.getBlobUrl())"),
            call(B + "CloudBlob getContainer()", "$r.getContainerClient()"),
            call(B + "CloudBlob getServiceClient()", "$r.getContainerClient().getServiceClient()"),
            call(B + "CloudBlob getSnapshotID()", "$r.getSnapshotId()"),
            call(B + "CloudBlob isSnapshot()", "$r.isSnapshot()"),
            call(B + "CloudBlob exists()", "$r.exists()"),
            call(B + "CloudBlob exists(..)", "$r.exists()").when(argsNull(0, 1, 2)),
            call(B + "CloudBlob delete()", "$r.delete()"),
            call(B + "CloudBlob deleteIfExists()", "$r.deleteIfExists()"),
            call(B + "CloudBlob openInputStream()", "$r.openInputStream()"),
            call(B + "CloudBlob openInputStream(..)", "$r.openInputStream()").when(argsNull(0, 1, 2)),
            call(B + "CloudBlob downloadAttributes()", "$r.getProperties()"),
            call(B + "CloudBlob getProperties()", "$r.getProperties()"),
            custom(B + "CloudBlob getMetadata()", MigrateAzureStorageBlob::metadata),
            custom(B + "CloudBlob setMetadata(java.util.HashMap)", MigrateAzureStorageBlob::setMetadata),
            delete(B + "CloudBlob uploadMetadata()"),
            call(B + "CloudBlob download(java.io.OutputStream)", "$r.downloadStream($0)"),
            call(B + "CloudBlob download(java.io.OutputStream, ..)", "$r.downloadStream($0)").when(argsNull(1, 2, 3)),
            call(B + "CloudBlob downloadToFile(String)", "$r.downloadToFile($0, true)"),
            call(B + "CloudBlob downloadToFile(String, ..)", "$r.downloadToFile($0, true)").when(argsNull(1, 2, 3)),
            call(B + "CloudBlob uploadFromFile(String)", "$r.uploadFromFile($0, true)"),
            call(B + "CloudBlob uploadFromFile(String, ..)", "$r.uploadFromFile($0, true)").when(argsNull(1, 2, 3)),
            custom(B + "CloudBlob uploadFromByteArray(byte[], int, int)", MigrateAzureStorageBlob::uploadBytes),
            custom(B + "CloudBlob uploadFromByteArray(byte[], int, int, ..)", MigrateAzureStorageBlob::uploadBytes)
                    .when(argsNull(3, 4, 5)),
            custom(B + "CloudBlob upload(java.io.InputStream, long)", MigrateAzureStorageBlob::uploadStream),
            custom(B + "CloudBlob upload(java.io.InputStream, long, ..)", MigrateAzureStorageBlob::uploadStream)
                    .when(argsNull(2, 3, 4)),
            call(B + "CloudBlockBlob uploadText(String)", "$r.upload(com.azure.core.util.BinaryData.fromString($0), true)"),
            call(B + "CloudBlockBlob uploadText(String, String, ..)",
                    "$r.upload(com.azure.core.util.BinaryData.fromString($0), true)").when(argsNull(1, 2, 3, 4)),
            call(B + "CloudBlockBlob uploadText(String, String, ..)",
                    "$r.upload(com.azure.core.util.BinaryData.fromBytes($0.getBytes($1)), true)").when(argsNull(2, 3, 4)),
            call(B + "CloudBlockBlob downloadText()", "$r.downloadContent().toString()"),
            call(B + "CloudBlockBlob downloadText(String, ..)", "$r.downloadContent().toString()").when(argsNull(0, 1, 2, 3)),
            call(B + "CloudBlockBlob downloadText(String, ..)", "new String($r.downloadContent().toBytes(), $0)")
                    .when(argsNull(1, 2, 3)),
            call(B + "CloudBlockBlob openOutputStream()", "$r.getBlockBlobClient().getBlobOutputStream(true)"),
            call(B + "CloudBlockBlob openOutputStream(..)", "$r.getBlockBlobClient().getBlobOutputStream(true)")
                    .when(argsNull(0, 1, 2)),
            call(B + "CloudBlob startCopy(java.net.URI)", "$r.beginCopy($0.toString(), null).poll().getValue().getCopyId()"),
            call(B + "CloudBlockBlob startCopy(" + B + "CloudBlockBlob)", "$r.beginCopy($0.getBlobUrl(), null).poll().getValue().getCopyId()"),
            call(B + "CloudBlob generateSharedAccessSignature(" + B + "SharedAccessBlobPolicy, String)",
                    "$r.generateSas($0)").when(argsNull(1)),
            call(B + "CloudAppendBlob createOrReplace()", "$r.create(true)"),

            // Blob properties
            call(B + "BlobProperties getLength()", "$r.getBlobSize()"),
            call(B + "BlobProperties getLastModified()", "java.util.Date.from($r.getLastModified().toInstant())"),
            call(B + "BlobProperties getCreatedTime()", "java.util.Date.from($r.getCreationTime().toInstant())"),
            call(B + "BlobProperties getEtag()", "$r.getETag()"),
            call(B + "BlobProperties getContentType()", "$r.getContentType()"),
            call(B + "BlobProperties getContentEncoding()", "$r.getContentEncoding()"),
            call(B + "BlobProperties getContentDisposition()", "$r.getContentDisposition()"),
            call(B + "BlobProperties getContentLanguage()", "$r.getContentLanguage()"),
            call(B + "BlobProperties getCacheControl()", "$r.getCacheControl()"),
            call(B + "BlobProperties getContentMD5()", "java.util.Base64.getEncoder().encodeToString($r.getContentMd5())"),
            call(B + "BlobProperties getBlobType()", "$r.getBlobType()"),

            // Shared access signatures
            call(B + "SharedAccessBlobPolicy <constructor>()", "new com.azure.storage.blob.sas.BlobServiceSasSignatureValues()"),
            call(S + "SharedAccessPolicy setSharedAccessExpiryTime(java.util.Date)",
                    "$r.setExpiryTime($0.toInstant().atOffset(java.time.ZoneOffset.UTC))"),
            call(S + "SharedAccessPolicy setSharedAccessStartTime(java.util.Date)",
                    "$r.setStartTime($0.toInstant().atOffset(java.time.ZoneOffset.UTC))"),
            custom(B + "SharedAccessBlobPolicy setPermissions(java.util.EnumSet)", MigrateAzureStorageBlob::sasPermissions)
    );

    static final Map<String, String> CONSTANTS;

    static {
        Map<String, String> c = new HashMap<>();
        c.put(B + "BlobContainerPublicAccessType.OFF", "null");
        c.put(B + "BlobContainerPublicAccessType.CONTAINER", V12 + "models.PublicAccessType.CONTAINER");
        c.put(B + "BlobContainerPublicAccessType.BLOB", V12 + "models.PublicAccessType.BLOB");
        c.put(B + "DeleteSnapshotsOption.NONE", "null");
        c.put(B + "DeleteSnapshotsOption.INCLUDE_SNAPSHOTS", V12 + "models.DeleteSnapshotsOptionType.INCLUDE");
        c.put(B + "DeleteSnapshotsOption.DELETE_SNAPSHOTS_ONLY", V12 + "models.DeleteSnapshotsOptionType.ONLY");
        for (String blobType : new String[]{"BLOCK_BLOB", "PAGE_BLOB", "APPEND_BLOB"}) {
            c.put(B + "BlobType." + blobType, V12 + "models.BlobType." + blobType);
        }
        for (String status : new String[]{"PENDING", "SUCCESS", "ABORTED", "FAILED"}) {
            c.put(B + "CopyStatus." + status, V12 + "models.CopyStatusType." + status);
        }
        CONSTANTS = Collections.unmodifiableMap(c);
    }

    private static final Map<String, String> SAS_PERMISSIONS;

    static {
        Map<String, String> p = new HashMap<>();
        p.put("READ", "setReadPermission");
        p.put("WRITE", "setWritePermission");
        p.put("DELETE", "setDeletePermission");
        p.put("LIST", "setListPermission");
        p.put("ADD", "setAddPermission");
        p.put("CREATE", "setCreatePermission");
        SAS_PERMISSIONS = Collections.unmodifiableMap(p);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>("com.microsoft.azure.storage..*", false), new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                Tree t = new ListBlobItemLoops().visit(tree, ctx);
                return new AzureCallRewriter(RULES, CONSTANTS).visit(t, ctx);
            }
        });
    }

    /**
     * {@code new CloudStorageAccount(new StorageCredentialsAccountAndKey(name, key), useHttps[, endpointSuffix])} becomes
     * the connection string the account would have printed.
     */
    private static @Nullable J accountConnectionString(Call call, AzureCallRewriter rewriter, ExecutionContext ctx) {
        if (call.args.isEmpty() || call.args.size() > 3 || !(call.arg(0) instanceof J.NewClass)) {
            return null;
        }
        J.NewClass credentials = (J.NewClass) call.arg(0);
        if (!TypeUtils.isOfClassType(credentials.getType(), S + "StorageCredentialsAccountAndKey") ||
            credentials.getArguments().size() != 2) {
            return null;
        }
        String protocol = "https";
        if (call.args.size() >= 2) {
            if (isLiteral(call.arg(1), false)) {
                protocol = "http";
            } else if (!isLiteral(call.arg(1), true)) {
                return null;
            }
        }
        StringBuilder template = new StringBuilder("\"DefaultEndpointsProtocol=" + protocol +
                                                   ";AccountName=\" + #{name:any(String)} + \";AccountKey=\" + #{key:any(String)}");
        List<Object> params = new ArrayList<>(credentials.getArguments());
        if (call.args.size() == 3) {
            template.append(" + \";EndpointSuffix=\" + #{suffix:any(String)}");
            params.add(call.arg(2));
        }
        return apply(rewriter, call, template.toString(), params, ctx);
    }

    /**
     * v8 kept metadata on the client until {@code downloadAttributes()}; v12 reads it with the properties. v8 returned a
     * {@code HashMap}, so a {@code HashMap} declaration gets a copy.
     */
    private static @Nullable J metadata(Call call, AzureCallRewriter rewriter, ExecutionContext ctx) {
        Object parent = call.cursor.getParentTreeCursor().getValue();
        boolean hashMap = parent instanceof J.VariableDeclarations.NamedVariable &&
                          TypeUtils.isOfClassType(((J.VariableDeclarations.NamedVariable) parent).getType(), "java.util.HashMap");
        return rewriter.template(hashMap ? "new java.util.HashMap<>($r.getProperties().getMetadata())" :
                "$r.getProperties().getMetadata()", call, ctx);
    }

    /**
     * v8 only sent metadata on {@code uploadMetadata()} or an upload, v12 sends it at once. Only a {@code setMetadata}
     * that is followed by {@code uploadMetadata()} on the same client in the same block keeps its meaning.
     */
    private static @Nullable J setMetadata(Call call, AzureCallRewriter rewriter, ExecutionContext ctx) {
        Cursor parent = call.cursor.getParentTreeCursor();
        if (!(parent.getValue() instanceof J.Block) || !(call.receiver instanceof J.Identifier)) {
            return null;
        }
        String receiver = ((J.Identifier) call.receiver).getSimpleName();
        boolean after = false;
        for (Statement s : ((J.Block) parent.getValue()).getStatements()) {
            if (s == call.cursor.getValue()) {
                after = true;
            } else if (after && s instanceof J.MethodInvocation) {
                J.MethodInvocation m = (J.MethodInvocation) s;
                if ("uploadMetadata".equals(m.getSimpleName()) && m.getSelect() instanceof J.Identifier &&
                    receiver.equals(((J.Identifier) m.getSelect()).getSimpleName())) {
                    return rewriter.template("$r.setMetadata($0)", call, ctx);
                }
            }
        }
        return null;
    }

    private static @Nullable J uploadStream(Call call, AzureCallRewriter rewriter, ExecutionContext ctx) {
        Expression length = call.arg(1);
        boolean unknown = length instanceof J.Unary && ((J.Unary) length).getOperator() == J.Unary.Type.Negative &&
                          isLiteral(((J.Unary) length).getExpression(), 1) || isLiteral(length, -1) || isLiteral(length, -1L);
        return rewriter.template(unknown ? "$r.upload($0, true)" : "$r.upload($0, $1, true)", call, ctx);
    }

    private static @Nullable J uploadBytes(Call call, AzureCallRewriter rewriter, ExecutionContext ctx) {
        Expression bytes = call.arg(0);
        Expression length = call.arg(2);
        if (isLiteral(call.arg(1), 0) && bytes instanceof J.Identifier && length instanceof J.FieldAccess &&
            "length".equals(((J.FieldAccess) length).getSimpleName()) &&
            ((J.FieldAccess) length).getTarget() instanceof J.Identifier &&
            ((J.Identifier) ((J.FieldAccess) length).getTarget()).getSimpleName().equals(((J.Identifier) bytes).getSimpleName())) {
            return rewriter.template("$r.upload(com.azure.core.util.BinaryData.fromBytes($0), true)", call, ctx);
        }
        return rewriter.template("$r.upload(new java.io.ByteArrayInputStream($0, $1, $2), true)", call, ctx);
    }

    /**
     * {@code BlobContainerPermissions} is retyped to the {@code PublicAccessType} it carried, so setting it assigns it.
     */
    private static @Nullable J assignPublicAccess(Call call, AzureCallRewriter rewriter, ExecutionContext ctx) {
        if (!(call.receiver instanceof J.Identifier) || !(call.cursor.getParentTreeCursor().getValue() instanceof J.Block)) {
            return null;
        }
        return apply(rewriter, call, "#{permissions:any(" + V12 + "models.PublicAccessType)} = #{access:any(" + V12 +
                                     "models.PublicAccessType)}", Arrays.asList(call.receiver, call.arg(0)), ctx);
    }

    private static @Nullable J sasPermissions(Call call, AzureCallRewriter rewriter, ExecutionContext ctx) {
        if (!(call.arg(0) instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation enumSet = (J.MethodInvocation) call.arg(0);
        if (!"of".equals(enumSet.getSimpleName()) || !TypeUtils.isOfClassType(enumSet.getType(), "java.util.EnumSet") &&
                                                     !(enumSet.getType() instanceof JavaType.Parameterized)) {
            return null;
        }
        StringBuilder permissions = new StringBuilder("new " + V12 + "sas.BlobSasPermission()");
        for (Expression permission : enumSet.getArguments()) {
            String name = permission instanceof J.FieldAccess ? ((J.FieldAccess) permission).getSimpleName() :
                    permission instanceof J.Identifier ? ((J.Identifier) permission).getSimpleName() : null;
            String setter = name == null ? null : SAS_PERMISSIONS.get(name);
            if (setter == null) {
                return null;
            }
            permissions.append('.').append(setter).append("(true)");
        }
        return apply(rewriter, call, "#{policy:any(" + V12 + "sas.BlobServiceSasSignatureValues)}.setPermissions(" + permissions + ")",
                Collections.singletonList(call.receiver), ctx);
    }

    private static J nullLiteral(J replaced) {
        return new J.Literal(Tree.randomId(), replaced.getPrefix(), Markers.EMPTY, null, "null", null, JavaType.Primitive.Null);
    }

    static J apply(AzureCallRewriter rewriter, Call call, String template, List<?> params, ExecutionContext ctx) {
        J result = JavaTemplate.builder(template)
                .javaParser(rewriter.parser(ctx))
                .build()
                .apply(new Cursor(call.cursor.getParent(), call.tree),
                        call.tree instanceof J.MethodInvocation ?
                                ((J.MethodInvocation) call.tree).getCoordinates().replace() :
                                ((J.NewClass) call.tree).getCoordinates().replace(),
                        params.toArray());
        rewriter.afterVisit(ShortenFullyQualifiedTypeReferences.modifyOnly(result));
        return result;
    }
}

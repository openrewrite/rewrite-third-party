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

/**
 * Source of the {@code ObjectMetadata} that {@link MigrateS3ObjectMetadata} generates. v1's metadata was a mutable header map
 * handed between requests and responses; v2 spreads it over immutable request builders and responses, so a mutable class with
 * v1's accessors carries the values and copies them to and from the v2 types.
 */
final class S3ObjectMetadataShim {

    static final String SHIM = "ObjectMetadata";

    private S3ObjectMetadataShim() {
    }

    // v1 accessor, header, type
    private static final String[][] HEADERS = {
            {"ContentType", "Content-Type", "String"},
            {"ContentEncoding", "Content-Encoding", "String"},
            {"ContentDisposition", "Content-Disposition", "String"},
            {"ContentLanguage", "Content-Language", "String"},
            {"CacheControl", "Cache-Control", "String"},
            {"ContentMD5", "Content-MD5", "String"},
            {"SSEAlgorithm", "x-amz-server-side-encryption", "String"},
            {"SSEAwsKmsKeyId", "x-amz-server-side-encryption-aws-kms-key-id", "String"},
            {"StorageClass", "x-amz-storage-class", "String"},
            {"ETag", "ETag", "String"},
            {"VersionId", "x-amz-version-id", "String"},
            {"LastModified", "Last-Modified", "Date"},
            {"HttpExpiresDate", "Expires", "Date"}
    };

    static String source(String pkg, boolean transferManager) {
        StringBuilder s = new StringBuilder(ProjectShims.packageLine(pkg));
        s.append("import java.io.ByteArrayOutputStream;\n" +
                 "import java.io.IOException;\n" +
                 "import java.io.InputStream;\n" +
                 "import java.io.UncheckedIOException;\n" +
                 "import java.time.Instant;\n" +
                 "import java.util.ArrayList;\n" +
                 "import java.util.Collections;\n" +
                 "import java.util.Date;\n" +
                 "import java.util.List;\n" +
                 "import java.util.Map;\n" +
                 "import java.util.TreeMap;\n" +
                 (transferManager ? "import java.util.concurrent.ExecutorService;\n" +
                                    "import java.util.concurrent.Executors;\n" : "") +
                 "import software.amazon.awssdk.core.ResponseInputStream;\n" +
                 (transferManager ? "import software.amazon.awssdk.core.async.AsyncRequestBody;\n" : "") +
                 "import software.amazon.awssdk.core.sync.RequestBody;\n" +
                 "import software.amazon.awssdk.services.s3.S3Client;\n" +
                 "import software.amazon.awssdk.services.s3.model.CopyObjectRequest;\n" +
                 "import software.amazon.awssdk.services.s3.model.AccessControlPolicy;\n" +
                 "import software.amazon.awssdk.services.s3.model.GetObjectResponse;\n" +
                 "import software.amazon.awssdk.services.s3.model.Grant;\n" +
                 "import software.amazon.awssdk.services.s3.model.Grantee;\n" +
                 "import software.amazon.awssdk.services.s3.model.HeadObjectRequest;\n" +
                 "import software.amazon.awssdk.services.s3.model.HeadObjectResponse;\n" +
                 "import software.amazon.awssdk.services.s3.model.MetadataDirective;\n" +
                 "import software.amazon.awssdk.services.s3.model.ObjectCannedACL;\n" +
                 "import software.amazon.awssdk.services.s3.model.Permission;\n" +
                 "import software.amazon.awssdk.services.s3.model.PutObjectRequest;\n" +
                 "import software.amazon.awssdk.services.s3.model.PutObjectResponse;\n" +
                 (transferManager ? "import software.amazon.awssdk.transfer.s3.S3TransferManager;\n" +
                                    "import software.amazon.awssdk.transfer.s3.model.FileDownload;\n" +
                                    "import software.amazon.awssdk.transfer.s3.model.Upload;\n" +
                                    "import software.amazon.awssdk.transfer.s3.model.UploadRequest;\n" : "") +
                 "\n" +
                 "/**\n" +
                 " * AWS SDK v1's mutable {@code ObjectMetadata}: the object's headers and user metadata. SDK v2 has no such type, so\n" +
                 " * this keeps v1's accessors and copies the values to v2 request builders and from v2 responses.\n" +
                 " */\n" +
                 "public class " + SHIM + " implements Cloneable {\n\n" +
                 "    public static final String AES_256_SERVER_SIDE_ENCRYPTION = \"AES256\";\n" +
                 "    public static final String KMS_SERVER_SIDE_ENCRYPTION = \"aws:kms\";\n\n" +
                 "    private Map<String, String> userMetadata = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);\n" +
                 "    private Map<String, Object> metadata = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);\n" +
                 "    private Date expirationTime;\n" +
                 "    private String expirationTimeRuleId;\n\n" +
                 "    public " + SHIM + "() {\n" +
                 "    }\n\n");
        for (String[] h : HEADERS) {
            s.append("    public ").append(h[2]).append(" get").append(h[0]).append("() {\n")
                    .append("        return (").append(h[2]).append(") metadata.get(\"").append(h[1]).append("\");\n")
                    .append("    }\n\n")
                    .append("    public void set").append(h[0]).append('(').append(h[2]).append(" value) {\n")
                    .append("        setHeader(\"").append(h[1]).append("\", value);\n")
                    .append("    }\n\n");
        }
        s.append("    public long getContentLength() {\n" +
                 "        Object length = metadata.get(\"Content-Length\");\n" +
                 "        return length == null ? 0 : ((Number) length).longValue();\n" +
                 "    }\n\n" +
                 "    public void setContentLength(long contentLength) {\n" +
                 "        metadata.put(\"Content-Length\", contentLength);\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * Set by S3 from the bucket's lifecycle rules; v1 did not send it either.\n" +
                 "     */\n" +
                 "    public Date getExpirationTime() {\n" +
                 "        return expirationTime;\n" +
                 "    }\n\n" +
                 "    public void setExpirationTime(Date expirationTime) {\n" +
                 "        this.expirationTime = expirationTime;\n" +
                 "    }\n\n" +
                 "    public String getExpirationTimeRuleId() {\n" +
                 "        return expirationTimeRuleId;\n" +
                 "    }\n\n" +
                 "    public void setExpirationTimeRuleId(String expirationTimeRuleId) {\n" +
                 "        this.expirationTimeRuleId = expirationTimeRuleId;\n" +
                 "    }\n\n" +
                 "    public void setHeader(String name, Object value) {\n" +
                 "        if (value == null) {\n" +
                 "            metadata.remove(name);\n" +
                 "        } else {\n" +
                 "            metadata.put(name, value);\n" +
                 "        }\n" +
                 "    }\n\n" +
                 "    public Map<String, Object> getRawMetadata() {\n" +
                 "        return Collections.unmodifiableMap(new TreeMap<>(metadata));\n" +
                 "    }\n\n" +
                 "    public Object getRawMetadataValue(String name) {\n" +
                 "        return metadata.get(name);\n" +
                 "    }\n\n" +
                 "    public Map<String, String> getUserMetadata() {\n" +
                 "        return userMetadata;\n" +
                 "    }\n\n" +
                 "    public void setUserMetadata(Map<String, String> userMetadata) {\n" +
                 "        this.userMetadata = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);\n" +
                 "        if (userMetadata != null) {\n" +
                 "            this.userMetadata.putAll(userMetadata);\n" +
                 "        }\n" +
                 "    }\n\n" +
                 "    public void addUserMetadata(String key, String value) {\n" +
                 "        userMetadata.put(key, value);\n" +
                 "    }\n\n" +
                 "    public String getUserMetaDataOf(String key) {\n" +
                 "        return userMetadata.get(key);\n" +
                 "    }\n\n" +
                 "    @Override\n" +
                 "    public " + SHIM + " clone() {\n" +
                 "        " + SHIM + " copy = new " + SHIM + "();\n" +
                 "        copy.userMetadata.putAll(userMetadata);\n" +
                 "        copy.metadata.putAll(metadata);\n" +
                 "        copy.expirationTime = expirationTime;\n" +
                 "        copy.expirationTimeRuleId = expirationTimeRuleId;\n" +
                 "        return copy;\n" +
                 "    }\n\n" +
                 "    public PutObjectRequest.Builder applyTo(PutObjectRequest.Builder request) {\n" +
                 "        if (metadata.containsKey(\"Content-Length\")) {\n" +
                 "            request.contentLength(getContentLength());\n" +
                 "        }\n" +
                 "        request.contentType(getContentType())\n" +
                 "                .contentEncoding(getContentEncoding())\n" +
                 "                .contentDisposition(getContentDisposition())\n" +
                 "                .contentLanguage(getContentLanguage())\n" +
                 "                .cacheControl(getCacheControl())\n" +
                 "                .contentMD5(getContentMD5())\n" +
                 "                .serverSideEncryption(getSSEAlgorithm())\n" +
                 "                .ssekmsKeyId(getSSEAwsKmsKeyId())\n" +
                 "                .storageClass(stringValue(\"x-amz-storage-class\"))\n" +
                 "                .expires(instant(getHttpExpiresDate()))\n" +
                 "                .metadata(userMetadata.isEmpty() ? null : new TreeMap<>(userMetadata));\n" +
                 "        return request;\n" +
                 "    }\n\n" +
                 "    public CopyObjectRequest.Builder applyTo(CopyObjectRequest.Builder request) {\n" +
                 "        // v1 replaced the copy's metadata whenever new metadata was given\n" +
                 "        return request.metadataDirective(MetadataDirective.REPLACE)\n" +
                 "                .contentType(getContentType())\n" +
                 "                .contentEncoding(getContentEncoding())\n" +
                 "                .contentDisposition(getContentDisposition())\n" +
                 "                .contentLanguage(getContentLanguage())\n" +
                 "                .cacheControl(getCacheControl())\n" +
                 "                .serverSideEncryption(getSSEAlgorithm())\n" +
                 "                .ssekmsKeyId(getSSEAwsKmsKeyId())\n" +
                 "                .storageClass(stringValue(\"x-amz-storage-class\"))\n" +
                 "                .expires(instant(getHttpExpiresDate()))\n" +
                 "                .metadata(new TreeMap<>(userMetadata));\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * The HEAD response S3 would return for an object with this metadata, for stubbing {@code headObject}.\n" +
                 "     */\n" +
                 "    public HeadObjectResponse toHeadObjectResponse() {\n" +
                 "        HeadObjectResponse.Builder b = HeadObjectResponse.builder()\n" +
                 "                .contentType(getContentType())\n" +
                 "                .contentEncoding(getContentEncoding())\n" +
                 "                .contentDisposition(getContentDisposition())\n" +
                 "                .contentLanguage(getContentLanguage())\n" +
                 "                .cacheControl(getCacheControl())\n" +
                 "                .serverSideEncryption(getSSEAlgorithm())\n" +
                 "                .ssekmsKeyId(getSSEAwsKmsKeyId())\n" +
                 "                .storageClass(stringValue(\"x-amz-storage-class\"))\n" +
                 "                .eTag(getETag())\n" +
                 "                .versionId(getVersionId())\n" +
                 "                .lastModified(instant(getLastModified()))\n" +
                 "                .expires(instant(getHttpExpiresDate()))\n" +
                 "                .metadata(new TreeMap<>(userMetadata));\n" +
                 "        if (metadata.containsKey(\"Content-Length\")) {\n" +
                 "            b.contentLength(getContentLength());\n" +
                 "        }\n" +
                 "        return b.build();\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * A GET response with these headers. Only getters are read, so this also works on a Mockito mock.\n" +
                 "     */\n" +
                 "    public static GetObjectResponse getObjectResponse(" + SHIM + " m) {\n" +
                 "        return GetObjectResponse.builder()\n" +
                 "                .contentType(m.getContentType())\n" +
                 "                .contentEncoding(m.getContentEncoding())\n" +
                 "                .contentLength(m.getContentLength())\n" +
                 "                .eTag(m.getETag())\n" +
                 "                .lastModified(instant(m.getLastModified()))\n" +
                 "                .metadata(m.getUserMetadata())\n" +
                 "                .build();\n" +
                 "    }\n\n" +
                 "    public static " + SHIM + " from(HeadObjectResponse response) {\n" +
                 "        " + SHIM + " m = new " + SHIM + "();\n" +
                 "        if (response.contentLength() != null) {\n" +
                 "            m.setContentLength(response.contentLength());\n" +
                 "        }\n" +
                 "        m.fill(response.contentType(), response.contentEncoding(), response.contentDisposition(), response.contentLanguage(),\n" +
                 "                response.cacheControl(), response.serverSideEncryptionAsString(), response.ssekmsKeyId(),\n" +
                 "                response.storageClassAsString(), response.eTag(), response.versionId(), response.lastModified(), response.expires(),\n" +
                 "                response.metadata());\n" +
                 "        return m;\n" +
                 "    }\n\n" +
                 "    public static " + SHIM + " from(GetObjectResponse response) {\n" +
                 "        " + SHIM + " m = new " + SHIM + "();\n" +
                 "        if (response.contentLength() != null) {\n" +
                 "            m.setContentLength(response.contentLength());\n" +
                 "        }\n" +
                 "        m.fill(response.contentType(), response.contentEncoding(), response.contentDisposition(), response.contentLanguage(),\n" +
                 "                response.cacheControl(), response.serverSideEncryptionAsString(), response.ssekmsKeyId(),\n" +
                 "                response.storageClassAsString(), response.eTag(), response.versionId(), response.lastModified(), response.expires(),\n" +
                 "                response.metadata());\n" +
                 "        return m;\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * v1 {@code S3Object.getObjectMetadata()}.\n" +
                 "     */\n" +
                 "    public static " + SHIM + " of(ResponseInputStream<GetObjectResponse> object) {\n" +
                 "        return from(object.response());\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * v1 {@code AmazonS3.getObjectMetadata(bucket, key)}.\n" +
                 "     */\n" +
                 "    public static " + SHIM + " getObjectMetadata(S3Client s3, String bucketName, String key) {\n" +
                 "        return from(s3.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build()));\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * v1 {@code AmazonS3.putObject(bucket, key, input, metadata)}: without a content length v1 buffered the stream too.\n" +
                 "     */\n" +
                 "    public static PutObjectResponse putObject(S3Client s3, String bucketName, String key, InputStream input, " + SHIM + " metadata) {\n" +
                 "        " + SHIM + " m = metadata == null ? new " + SHIM + "() : metadata;\n" +
                 "        PutObjectRequest request = m.applyTo(PutObjectRequest.builder().bucket(bucketName).key(key)).build();\n" +
                 "        RequestBody body = m.metadata.containsKey(\"Content-Length\") ?\n" +
                 "                RequestBody.fromInputStream(input, m.getContentLength()) : RequestBody.fromBytes(readAll(input));\n" +
                 "        return s3.putObject(request, body);\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * v1 {@code PutObjectRequest.withMetadata(metadata)}.\n" +
                 "     */\n" +
                 "    public static PutObjectRequest withMetadata(PutObjectRequest request, " + SHIM + " metadata) {\n" +
                 "        return metadata == null ? request : metadata.applyTo(request.toBuilder()).build();\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * v1 {@code CopyObjectRequest.withNewObjectMetadata(metadata)}.\n" +
                 "     */\n" +
                 "    public static CopyObjectRequest withNewObjectMetadata(CopyObjectRequest request, " + SHIM + " metadata) {\n" +
                 "        return metadata == null ? request : metadata.applyTo(request.toBuilder()).build();\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * v1 {@code new PutObjectRequest(bucket, key, input, metadata)}; the input goes to {@code putObject} or {@code upload}.\n" +
                 "     */\n" +
                 "    public static PutObjectRequest putObjectRequest(String bucketName, String key, " + SHIM + " metadata) {\n" +
                 "        return withMetadata(PutObjectRequest.builder().bucket(bucketName).key(key).build(), metadata);\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * v1 {@code AmazonS3.putObject(request)} for a request built with an input stream.\n" +
                 "     */\n" +
                 "    public static PutObjectResponse putObject(S3Client s3, PutObjectRequest request, InputStream input, " + SHIM + " metadata) {\n" +
                 "        return s3.putObject(request, metadata != null && metadata.metadata.containsKey(\"Content-Length\") ?\n" +
                 "                RequestBody.fromInputStream(input, metadata.getContentLength()) : RequestBody.fromBytes(readAll(input)));\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * v1 {@code PutObjectRequest.setCannedAcl(acl)}.\n" +
                 "     */\n" +
                 "    public static PutObjectRequest withCannedAcl(PutObjectRequest request, ObjectCannedACL acl) {\n" +
                 "        return request.toBuilder().acl(acl).build();\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * v1 {@code PutObjectRequest.setAccessControlList(acl)}: v2 takes the grants as headers, which replace a canned ACL as in v1.\n" +
                 "     */\n" +
                 "    public static PutObjectRequest withAccessControlList(PutObjectRequest request, AccessControlPolicy acl) {\n" +
                 "        if (acl == null) {\n" +
                 "            return request;\n" +
                 "        }\n" +
                 "        Map<Permission, List<String>> grantees = new TreeMap<>();\n" +
                 "        for (Grant grant : acl.grants()) {\n" +
                 "            // S3 has no WRITE permission on objects, so PutObject has no header for it\n" +
                 "            if (grant.permission() != Permission.WRITE) {\n" +
                 "                grantees.computeIfAbsent(grant.permission(), p -> new ArrayList<>()).add(grantee(grant.grantee()));\n" +
                 "            }\n" +
                 "        }\n" +
                 "        PutObjectRequest.Builder builder = request.toBuilder().acl((String) null);\n" +
                 "        builder.grantFullControl(join(grantees.get(Permission.FULL_CONTROL)));\n" +
                 "        builder.grantRead(join(grantees.get(Permission.READ)));\n" +
                 "        builder.grantReadACP(join(grantees.get(Permission.READ_ACP)));\n" +
                 "        builder.grantWriteACP(join(grantees.get(Permission.WRITE_ACP)));\n" +
                 "        return builder.build();\n" +
                 "    }\n\n" +
                 (transferManager ?
                 "    /**\n" +
                 "     * v1 {@code TransferManager.upload(request)} for a request built with an input stream.\n" +
                 "     */\n" +
                 "    public static Upload upload(S3TransferManager transferManager, PutObjectRequest request, InputStream input, " + SHIM + " metadata) {\n" +
                 "        AsyncRequestBody body;\n" +
                 "        if (metadata != null && metadata.metadata.containsKey(\"Content-Length\")) {\n" +
                 "            body = AsyncRequestBody.fromInputStream(input, metadata.getContentLength(), UploadExecutor.INSTANCE);\n" +
                 "        } else {\n" +
                 "            body = AsyncRequestBody.fromBytes(readAll(input));\n" +
                 "        }\n" +
                 "        return transferManager.upload(UploadRequest.builder().putObjectRequest(request).requestBody(body).build());\n" +
                 "    }\n\n" +
                 "    /**\n" +
                 "     * v1 {@code Download.getObjectMetadata()}.\n" +
                 "     */\n" +
                 "    public static " + SHIM + " of(FileDownload download) {\n" +
                 "        return from(download.completionFuture().join().response());\n" +
                 "    }\n\n" +
                 "    private static final class UploadExecutor {\n" +
                 "        // Reads upload streams; daemon threads so it never keeps the JVM alive\n" +
                 "        static final ExecutorService INSTANCE = Executors.newCachedThreadPool(r -> {\n" +
                 "            Thread t = new Thread(r, \"s3-upload-stream\");\n" +
                 "            t.setDaemon(true);\n" +
                 "            return t;\n" +
                 "        });\n" +
                 "    }\n\n" : "") +
                 "    private static String grantee(Grantee grantee) {\n" +
                 "        if (grantee.uri() != null) {\n" +
                 "            return \"uri=\\\"\" + grantee.uri() + \"\\\"\";\n" +
                 "        }\n" +
                 "        if (grantee.emailAddress() != null) {\n" +
                 "            return \"emailAddress=\\\"\" + grantee.emailAddress() + \"\\\"\";\n" +
                 "        }\n" +
                 "        return \"id=\\\"\" + grantee.id() + \"\\\"\";\n" +
                 "    }\n\n" +
                 "    private static String join(List<String> values) {\n" +
                 "        return values == null ? null : String.join(\", \", values);\n" +
                 "    }\n\n" +
                 "    private void fill(String contentType, String contentEncoding, String contentDisposition, String contentLanguage,\n" +
                 "                      String cacheControl, String sse, String kmsKeyId, String storageClass, String eTag, String versionId,\n" +
                 "                      Instant lastModified, Instant expires, Map<String, String> user) {\n" +
                 "        setContentType(contentType);\n" +
                 "        setContentEncoding(contentEncoding);\n" +
                 "        setContentDisposition(contentDisposition);\n" +
                 "        setContentLanguage(contentLanguage);\n" +
                 "        setCacheControl(cacheControl);\n" +
                 "        setSSEAlgorithm(sse);\n" +
                 "        setSSEAwsKmsKeyId(kmsKeyId);\n" +
                 "        setStorageClass(storageClass);\n" +
                 "        // v1 stripped the quotes S3 puts around the ETag\n" +
                 "        setETag(eTag == null ? null : eTag.replaceAll(\"^\\\"|\\\"$\", \"\"));\n" +
                 "        setVersionId(versionId);\n" +
                 "        setLastModified(lastModified == null ? null : Date.from(lastModified));\n" +
                 "        setHttpExpiresDate(expires == null ? null : Date.from(expires));\n" +
                 "        setUserMetadata(user);\n" +
                 "    }\n\n" +
                 "    private String stringValue(String header) {\n" +
                 "        Object value = metadata.get(header);\n" +
                 "        return value == null ? null : value.toString();\n" +
                 "    }\n\n" +
                 "    private static Instant instant(Date date) {\n" +
                 "        return date == null ? null : date.toInstant();\n" +
                 "    }\n\n" +
                 "    private static byte[] readAll(InputStream input) {\n" +
                 "        try {\n" +
                 "            ByteArrayOutputStream out = new ByteArrayOutputStream();\n" +
                 "            byte[] buffer = new byte[8192];\n" +
                 "            for (int n; (n = input.read(buffer)) != -1; ) {\n" +
                 "                out.write(buffer, 0, n);\n" +
                 "            }\n" +
                 "            return out.toByteArray();\n" +
                 "        } catch (IOException e) {\n" +
                 "            throw new UncheckedIOException(e);\n" +
                 "        }\n" +
                 "    }\n" +
                 "}\n");
        return s.toString();
    }
}

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

import java.util.Set;

/**
 * Sources of the shims that {@link MigrateS3Residuals} generates into a project. Each method copies the logic of the v1 method it
 * replaces, so the call site keeps its v1 behaviour on v2.
 */
final class S3Shims {

    static final String S3_COMPAT = "S3Compat";
    static final String PRESIGNER_HOLDER = "S3PresignerHolder";

    static final String DOES_OBJECT_EXIST = "doesObjectExist";
    static final String DOES_BUCKET_EXIST_V2 = "doesBucketExistV2";
    static final String DOES_BUCKET_EXIST = "doesBucketExist";
    static final String ASYNC_CLIENT = "asyncClient";
    static final String PREPARE_DOWNLOAD = "prepareDownload";
    static final String PRESIGNER = "presigner";
    static final String PRESIGN = "presign";
    static final String PRESIGN_REQUEST = "presignRequest";
    static final String PRESIGN_REQUEST_CLASS = "GeneratePresignedUrlRequest";

    private S3Shims() {
    }

    static String s3Compat(String pkg, Set<String> methods) {
        StringBuilder imports = new StringBuilder();
        StringBuilder body = new StringBuilder();
        if (methods.contains(ASYNC_CLIENT)) {
            imports.append("import java.util.Map;\n")
                    .append("import java.util.WeakHashMap;\n");
        }
        if (methods.contains(DOES_BUCKET_EXIST_V2) || methods.contains(DOES_BUCKET_EXIST)) {
            imports.append("import software.amazon.awssdk.awscore.exception.AwsServiceException;\n");
        }
        if (methods.contains(PREPARE_DOWNLOAD)) {
            imports.append("import java.io.File;\n")
                    .append("import java.io.IOException;\n")
                    .append("import java.io.UncheckedIOException;\n")
                    .append("import java.nio.file.Files;\n");
        }
        if (methods.size() > (methods.contains(PREPARE_DOWNLOAD) ? 1 : 0)) {
            imports.append("import software.amazon.awssdk.services.s3.S3Client;\n");
        }
        if (methods.contains(ASYNC_CLIENT)) {
            imports.append("import software.amazon.awssdk.services.s3.S3AsyncClient;\n")
                    .append("import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;\n")
                    .append("import software.amazon.awssdk.services.s3.S3ServiceClientConfiguration;\n");
        }
        if (methods.contains(DOES_OBJECT_EXIST)) {
            imports.append("import software.amazon.awssdk.services.s3.model.S3Exception;\n");
        }

        if (methods.contains(ASYNC_CLIENT)) {
            body.append("\n    private static final Map<S3Client, S3AsyncClient> ASYNC_CLIENTS = new WeakHashMap<>();\n");
        }
        if (methods.contains(DOES_OBJECT_EXIST)) {
            body.append("\n" +
                    "    /**\n" +
                    "     * v1 {@code AmazonS3.doesObjectExist}: a HEAD of the object, where only a 404 means \"absent\".\n" +
                    "     */\n" +
                    "    public static boolean doesObjectExist(S3Client s3, String bucketName, String objectName) {\n" +
                    "        try {\n" +
                    "            s3.headObject(r -> r.bucket(bucketName).key(objectName));\n" +
                    "            return true;\n" +
                    "        } catch (S3Exception e) {\n" +
                    "            if (e.statusCode() == 404) {\n" +
                    "                return false;\n" +
                    "            }\n" +
                    "            throw e;\n" +
                    "        }\n" +
                    "    }\n");
        }
        if (methods.contains(DOES_BUCKET_EXIST_V2)) {
            body.append("\n" +
                    "    /**\n" +
                    "     * v1 {@code AmazonS3.doesBucketExistV2}: a redirect or AccessDenied means the bucket exists in another region or account.\n" +
                    "     */\n" +
                    "    public static boolean doesBucketExistV2(S3Client s3, String bucketName) {\n" +
                    "        if (bucketName == null || bucketName.isEmpty()) {\n" +
                    "            throw new IllegalArgumentException(\"bucketName must not be empty\");\n" +
                    "        }\n" +
                    "        try {\n" +
                    "            s3.getBucketAcl(r -> r.bucket(bucketName));\n" +
                    "            return true;\n" +
                    "        } catch (AwsServiceException e) {\n" +
                    "            if (e.statusCode() == 301 || (e.awsErrorDetails() != null && \"AccessDenied\".equals(e.awsErrorDetails().errorCode()))) {\n" +
                    "                return true;\n" +
                    "            }\n" +
                    "            if (e.statusCode() == 404) {\n" +
                    "                return false;\n" +
                    "            }\n" +
                    "            throw e;\n" +
                    "        }\n" +
                    "    }\n");
        }
        if (methods.contains(DOES_BUCKET_EXIST)) {
            body.append("\n" +
                    "    /**\n" +
                    "     * v1 {@code AmazonS3.doesBucketExist}: a HEAD of the bucket, where a redirect or 403 means it exists.\n" +
                    "     */\n" +
                    "    public static boolean doesBucketExist(S3Client s3, String bucketName) {\n" +
                    "        try {\n" +
                    "            s3.headBucket(r -> r.bucket(bucketName));\n" +
                    "            return true;\n" +
                    "        } catch (AwsServiceException e) {\n" +
                    "            if (e.statusCode() == 301 || e.statusCode() == 403) {\n" +
                    "                return true;\n" +
                    "            }\n" +
                    "            if (e.statusCode() == 404) {\n" +
                    "                return false;\n" +
                    "            }\n" +
                    "            throw e;\n" +
                    "        }\n" +
                    "    }\n");
        }
        if (methods.contains(PREPARE_DOWNLOAD)) {
            body.append("\n" +
                    "    /**\n" +
                    "     * v1 {@code AmazonS3.getObject(request, File)} created missing directories and replaced an existing file; v2's\n" +
                    "     * download to a path fails in both cases.\n" +
                    "     */\n" +
                    "    public static File prepareDownload(File destination) {\n" +
                    "        try {\n" +
                    "            File parent = destination.getAbsoluteFile().getParentFile();\n" +
                    "            if (parent != null) {\n" +
                    "                Files.createDirectories(parent.toPath());\n" +
                    "            }\n" +
                    "            Files.deleteIfExists(destination.toPath());\n" +
                    "        } catch (IOException e) {\n" +
                    "            throw new UncheckedIOException(e);\n" +
                    "        }\n" +
                    "        return destination;\n" +
                    "    }\n");
        }
        if (methods.contains(ASYNC_CLIENT)) {
            body.append("\n" +
                    "    /**\n" +
                    "     * The v2 transfer manager needs an async client. One is built per sync client, with its region, credentials and\n" +
                    "     * endpoint, and reused so that creating a transfer manager per call does not leak clients.\n" +
                    "     */\n" +
                    "    public static synchronized S3AsyncClient asyncClient(S3Client s3) {\n" +
                    "        return ASYNC_CLIENTS.computeIfAbsent(s3, c -> {\n" +
                    "            S3ServiceClientConfiguration config = c.serviceClientConfiguration();\n" +
                    "            S3AsyncClientBuilder builder = S3AsyncClient.builder()\n" +
                    "                    .multipartEnabled(true)\n" +
                    "                    .region(config.region())\n" +
                    "                    .credentialsProvider(config.credentialsProvider());\n" +
                    "            config.endpointOverride().ifPresent(builder::endpointOverride);\n" +
                    "            return builder.build();\n" +
                    "        });\n" +
                    "    }\n");
        }
        return "package " + pkg + ";\n\n" + imports +
                "\n/**\n * v1 {@code AmazonS3} behaviour that the AWS SDK for Java v2 has no single method for.\n */\n" +
                "public final class " + S3_COMPAT + " {\n" + body +
                "\n    private " + S3_COMPAT + "() {\n    }\n}\n";
    }

    static String presignerHolder(String pkg, Set<String> methods) {
        boolean presign = methods.contains(PRESIGN);
        boolean presignRequest = methods.contains(PRESIGN_REQUEST);
        return "package " + pkg + ";\n\n" +
                (presign || presignRequest ? "import java.net.MalformedURLException;\nimport java.net.URL;\nimport java.time.Duration;\n" : "") +
                (presignRequest ? "import java.time.Instant;\n" : "") +
                (presign ? "import java.util.Collections;\n" : "") +
                "import java.util.Map;\n" +
                "import java.util.WeakHashMap;\n" +
                (presignRequest ? "import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;\n" : "") +
                (presign || presignRequest ? "import software.amazon.awssdk.http.SdkHttpMethod;\n" +
                                             "import software.amazon.awssdk.http.SdkHttpRequest;\n" +
                                             "import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;\n" +
                                             "import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;\n" +
                                             "import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;\n" : "") +
                "import software.amazon.awssdk.services.s3.S3Client;\n" +
                (presign || presignRequest ? "import software.amazon.awssdk.services.s3.S3ServiceClientConfiguration;\n" : "") +
                "import software.amazon.awssdk.services.s3.presigner.S3Presigner;\n" +
                "\n" +
                "/**\n" +
                " * v1 {@code AmazonS3.generatePresignedUrl} signed with the client's own region, credentials and endpoint. A presigner is\n" +
                " * expensive to create, so one is kept per client.\n" +
                " */\n" +
                "public final class " + PRESIGNER_HOLDER + " {\n" +
                "\n" +
                "    private static final Map<S3Client, S3Presigner> PRESIGNERS = new WeakHashMap<>();\n" +
                "\n" +
                "    public static synchronized S3Presigner presigner(S3Client s3) {\n" +
                "        return PRESIGNERS.computeIfAbsent(s3, c -> S3Presigner.builder().s3Client(c).build());\n" +
                "    }\n" +
                (presign ? "\n" +
                        "    /**\n" +
                        "     * For an HTTP method only known at run time.\n" +
                        "     */\n" +
                        "    public static URL presign(S3Client s3, SdkHttpMethod method, String bucketName, String key, Duration signatureDuration) {\n" +
                        "        switch (method) {\n" +
                        "            case GET:\n" +
                        "                return presigner(s3).presignGetObject(p -> p.signatureDuration(signatureDuration)\n" +
                        "                        .getObjectRequest(r -> r.bucket(bucketName).key(key))).url();\n" +
                        "            case PUT:\n" +
                        "                return presigner(s3).presignPutObject(p -> p.signatureDuration(signatureDuration)\n" +
                        "                        .putObjectRequest(r -> r.bucket(bucketName).key(key))).url();\n" +
                        "            case DELETE:\n" +
                        "                return presigner(s3).presignDeleteObject(p -> p.signatureDuration(signatureDuration)\n" +
                        "                        .deleteObjectRequest(r -> r.bucket(bucketName).key(key))).url();\n" +
                        "            case HEAD:\n" +
                        "                return presigner(s3).presignHeadObject(p -> p.signatureDuration(signatureDuration)\n" +
                        "                        .headObjectRequest(r -> r.bucket(bucketName).key(key))).url();\n" +
                        "            default:\n" +
                        "                return presignWithMethod(s3, method, bucketName, key, signatureDuration, Collections.emptyMap());\n" +
                        "        }\n" +
                        "    }\n" : "") +
                (presignRequest ? "\n" +
                        "    /**\n" +
                        "     * v1 {@code AmazonS3.generatePresignedUrl(GeneratePresignedUrlRequest)}: the expiry defaults to 15 minutes, and request\n" +
                        "     * parameters are signed into the query string.\n" +
                        "     */\n" +
                        "    public static URL presign(S3Client s3, " + PRESIGN_REQUEST_CLASS + " request) {\n" +
                        "        Duration signatureDuration = request.getExpiration() == null ? Duration.ofMinutes(15) :\n" +
                        "                Duration.between(Instant.now(), request.getExpiration().toInstant());\n" +
                        "        AwsRequestOverrideConfiguration.Builder parameters = AwsRequestOverrideConfiguration.builder();\n" +
                        "        request.getRequestParameters().forEach(parameters::putRawQueryParameter);\n" +
                        "        AwsRequestOverrideConfiguration override = parameters.build();\n" +
                        "        switch (request.getMethod()) {\n" +
                        "            case GET:\n" +
                        "                return presigner(s3).presignGetObject(p -> p.signatureDuration(signatureDuration)\n" +
                        "                        .getObjectRequest(r -> r.bucket(request.getBucketName()).key(request.getKey())\n" +
                        "                                .versionId(request.getVersionId()).overrideConfiguration(override))).url();\n" +
                        "            case PUT:\n" +
                        "                return presigner(s3).presignPutObject(p -> p.signatureDuration(signatureDuration)\n" +
                        "                        .putObjectRequest(r -> r.bucket(request.getBucketName()).key(request.getKey())\n" +
                        "                                .contentType(request.getContentType()).contentMD5(request.getContentMd5())\n" +
                        "                                .overrideConfiguration(override))).url();\n" +
                        "            case DELETE:\n" +
                        "                return presigner(s3).presignDeleteObject(p -> p.signatureDuration(signatureDuration)\n" +
                        "                        .deleteObjectRequest(r -> r.bucket(request.getBucketName()).key(request.getKey())\n" +
                        "                                .versionId(request.getVersionId()).overrideConfiguration(override))).url();\n" +
                        "            case HEAD:\n" +
                        "                return presigner(s3).presignHeadObject(p -> p.signatureDuration(signatureDuration)\n" +
                        "                        .headObjectRequest(r -> r.bucket(request.getBucketName()).key(request.getKey())\n" +
                        "                                .versionId(request.getVersionId()).overrideConfiguration(override))).url();\n" +
                        "            default:\n" +
                        "                return presignWithMethod(s3, request.getMethod(), request.getBucketName(), request.getKey(),\n" +
                        "                        signatureDuration, request.getRequestParameters());\n" +
                        "        }\n" +
                        "    }\n" : "") +
                (presign || presignRequest ? "\n" +
                        "    // S3Presigner has no POST or PATCH, so these are signed with SigV4 query parameters as v1 did\n" +
                        "    private static URL presignWithMethod(S3Client s3, SdkHttpMethod method, String bucketName, String key,\n" +
                        "                                         Duration signatureDuration, Map<String, String> parameters) {\n" +
                        "        URL object = presigner(s3).presignGetObject(p -> p.signatureDuration(signatureDuration)\n" +
                        "                .getObjectRequest(r -> r.bucket(bucketName).key(key))).url();\n" +
                        "        SdkHttpRequest.Builder request = SdkHttpRequest.builder()\n" +
                        "                .method(method)\n" +
                        "                .protocol(object.getProtocol())\n" +
                        "                .host(object.getHost())\n" +
                        "                .port(object.getPort() == -1 ? null : object.getPort())\n" +
                        "                .encodedPath(object.getPath());\n" +
                        "        parameters.forEach(request::putRawQueryParameter);\n" +
                        "        S3ServiceClientConfiguration config = s3.serviceClientConfiguration();\n" +
                        "        AwsCredentialsIdentity credentials = config.credentialsProvider().resolveIdentity().join();\n" +
                        "        SignedRequest signed = AwsV4HttpSigner.create().sign(r -> r\n" +
                        "                .identity(credentials)\n" +
                        "                .request(request.build())\n" +
                        "                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, \"s3\")\n" +
                        "                .putProperty(AwsV4HttpSigner.REGION_NAME, config.region().id())\n" +
                        "                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AwsV4HttpSigner.AuthLocation.QUERY_STRING)\n" +
                        "                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, signatureDuration)\n" +
                        "                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)\n" +
                        "                .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)\n" +
                        "                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false));\n" +
                        "        try {\n" +
                        "            return signed.request().getUri().toURL();\n" +
                        "        } catch (MalformedURLException e) {\n" +
                        "            throw new IllegalStateException(e);\n" +
                        "        }\n" +
                        "    }\n" : "") +
                "\n" +
                "    private " + PRESIGNER_HOLDER + "() {\n" +
                "    }\n" +
                "}\n";
    }

    /**
     * The parts of v1's {@code GeneratePresignedUrlRequest} that {@code S3Presigner} can sign, with the HTTP method as v2's
     * {@code SdkHttpMethod}. Used where the request is built away from the presigning call, so it cannot be inlined.
     */
    static String presignRequest(String pkg) {
        StringBuilder accessors = new StringBuilder();
        String[][] properties = {{"String", "bucketName", "BucketName"}, {"String", "key", "Key"}, {"SdkHttpMethod", "method", "Method"},
                {"Date", "expiration", "Expiration"}, {"String", "contentType", "ContentType"}, {"String", "contentMd5", "ContentMd5"},
                {"String", "versionId", "VersionId"}};
        for (String[] p : properties) {
            accessors.append("\n")
                    .append("    public ").append(p[0]).append(" get").append(p[2]).append("() {\n")
                    .append("        return ").append(p[1]).append(";\n")
                    .append("    }\n\n")
                    .append("    public void set").append(p[2]).append("(").append(p[0]).append(" ").append(p[1]).append(") {\n")
                    .append("        this.").append(p[1]).append(" = ").append(p[1]).append(";\n")
                    .append("    }\n\n")
                    .append("    public ").append(PRESIGN_REQUEST_CLASS).append(" with").append(p[2]).append("(").append(p[0]).append(" ")
                    .append(p[1]).append(") {\n")
                    .append("        this.").append(p[1]).append(" = ").append(p[1]).append(";\n")
                    .append("        return this;\n")
                    .append("    }\n");
        }
        return "package " + pkg + ";\n\n" +
               "import java.util.Date;\n" +
               "import java.util.HashMap;\n" +
               "import java.util.Map;\n" +
               "import software.amazon.awssdk.http.SdkHttpMethod;\n" +
               "\n" +
               "/**\n" +
               " * Replacement for AWS SDK v1's {@code GeneratePresignedUrlRequest}, signed by {@code " + PRESIGNER_HOLDER + ".presign}.\n" +
               " */\n" +
               "public class " + PRESIGN_REQUEST_CLASS + " {\n" +
               "\n" +
               "    private String bucketName;\n" +
               "    private String key;\n" +
               "    private SdkHttpMethod method;\n" +
               "    private Date expiration;\n" +
               "    private String contentType;\n" +
               "    private String contentMd5;\n" +
               "    private String versionId;\n" +
               "    private final Map<String, String> requestParameters = new HashMap<>();\n" +
               "\n" +
               "    public " + PRESIGN_REQUEST_CLASS + "(String bucketName, String key) {\n" +
               "        this(bucketName, key, SdkHttpMethod.GET);\n" +
               "    }\n" +
               "\n" +
               "    public " + PRESIGN_REQUEST_CLASS + "(String bucketName, String key, SdkHttpMethod method) {\n" +
               "        this.bucketName = bucketName;\n" +
               "        this.key = key;\n" +
               "        this.method = method;\n" +
               "    }\n" +
               accessors +
               "\n" +
               "    public Map<String, String> getRequestParameters() {\n" +
               "        return requestParameters;\n" +
               "    }\n" +
               "\n" +
               "    public void addRequestParameter(String key, String value) {\n" +
               "        requestParameters.put(key, value);\n" +
               "    }\n" +
               "}\n";
    }
}

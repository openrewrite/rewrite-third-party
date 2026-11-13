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

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.kotlin.KotlinVisitor;

import java.util.*;

import static java.util.Collections.singletonList;
import static org.openrewrite.aws.sdk.AwsClientChain.*;

/**
 * The Kotlin side of v2-migration's S3, SNS and SQS request, client call and result conversions, for the shapes seen in
 * Kotlin projects: v1 request constructors and {@code with} chains become v2 builders, client convenience overloads take a
 * built request, and Kotlin property reads of v1 results become v2 getters.
 */
final class KotlinAwsModelCalls {

    private static final String[] V2_ARTIFACTS = {"auth", "aws-core", "http-auth-aws", "http-auth-spi", "http-client-spi",
            "identity-spi", "regions", "s3-2", "sdk-core", "sns-2", "sqs-2", "utils"};

    private static final String V1_S3 = "com.amazonaws.services.s3.AmazonS3";
    private static final String V1_SNS = "com.amazonaws.services.sns.AmazonSNS";
    private static final String V1_SQS = "com.amazonaws.services.sqs.AmazonSQS";
    private static final String V1_DYNAMODB = "com.amazonaws.services.dynamodbv2.AmazonDynamoDB";
    private static final String V1_S3_MODEL = "com.amazonaws.services.s3.model.";
    private static final String V2_S3_MODEL = "software.amazon.awssdk.services.s3.model.";
    private static final String V1_ACL = V1_S3_MODEL + "CannedAccessControlList";
    private static final String V1_HTTP_METHOD = "com.amazonaws.HttpMethod";
    private static final String DATE = "java.util.Date";

    // Slots that are not v2 request properties
    private static final String FILE = "@file";
    private static final String ACL = "@acl";
    private static final String METHOD = "@method";
    private static final String EXPIRATION = "@expiration";

    /**
     * Cursor message on the compilation unit: ids of statements folded into a converted call, for the caller to remove.
     */
    static final String FOLDED_STATEMENTS = "AWS_KOTLIN_FOLDED_STATEMENTS";

    /**
     * Cursor message on the compilation unit: v2 types written fully qualified while a same-named v1 request was still in use.
     */
    static final String SHORTEN = "AWS_KOTLIN_SHORTEN";

    private static final Map<String, Spec> SPECS = new HashMap<>();

    private static final class Spec {
        final String v1;
        final String v2;
        final Map<Integer, List<String>> constructors = new HashMap<>();
        final Map<String, String> properties = new HashMap<>();

        Spec(String v1, String v2) {
            this.v1 = v1;
            this.v2 = v2;
        }

        Spec constructor(String... slots) {
            constructors.put(slots.length, Arrays.asList(slots));
            return this;
        }

        /**
         * A v1 property, set by {@code withX(..)} or {@code setX(..)}, and its v2 builder method or slot.
         */
        Spec property(String v1, String v2) {
            properties.put(v1, v2);
            return this;
        }

        Spec same(String... names) {
            for (String name : names) {
                properties.put(name, name);
            }
            return this;
        }
    }

    private static void spec(Spec spec) {
        SPECS.put(spec.v1, spec);
    }

    static {
        String sns = "com.amazonaws.services.sns.model.";
        String sqs = "com.amazonaws.services.sqs.model.";
        spec(new Spec(V1_S3_MODEL + "GetObjectRequest", V2_S3_MODEL + "GetObjectRequest")
                .constructor("bucket", "key").constructor("bucket", "key", "versionId")
                .property("bucketName", "bucket").same("key", "versionId"));
        spec(new Spec(V1_S3_MODEL + "GetObjectMetadataRequest", V2_S3_MODEL + "HeadObjectRequest")
                .constructor("bucket", "key").constructor("bucket", "key", "versionId")
                .property("bucketName", "bucket").same("key", "versionId"));
        spec(new Spec(V1_S3_MODEL + "DeleteObjectRequest", V2_S3_MODEL + "DeleteObjectRequest")
                .constructor("bucket", "key").property("bucketName", "bucket").same("key"));
        spec(new Spec(V1_S3_MODEL + "CopyObjectRequest", V2_S3_MODEL + "CopyObjectRequest")
                .constructor("sourceBucket", "sourceKey", "destinationBucket", "destinationKey")
                .property("sourceBucketName", "sourceBucket").property("destinationBucketName", "destinationBucket")
                .same("sourceKey", "destinationKey"));
        spec(new Spec(V1_S3_MODEL + "ListObjectsRequest", V2_S3_MODEL + "ListObjectsRequest")
                .constructor().constructor("bucket", "prefix", "marker", "delimiter", "maxKeys")
                .property("bucketName", "bucket").same("prefix", "marker", "delimiter", "maxKeys"));
        // The body travels separately in v2, so these requests are only converted where the client call is
        spec(new Spec(V1_S3_MODEL + "PutObjectRequest", V2_S3_MODEL + "PutObjectRequest")
                .constructor("bucket", "key", FILE)
                .property("bucketName", "bucket").same("key").property("file", FILE).property("cannedAcl", ACL));
        spec(new Spec(V1_S3_MODEL + "GeneratePresignedUrlRequest", null)
                .constructor("bucket", "key").constructor("bucket", "key", METHOD)
                .property("bucketName", "bucket").same("key").property("method", METHOD).property("expiration", EXPIRATION));
        spec(new Spec(sns + "PublishRequest", "software.amazon.awssdk.services.sns.model.PublishRequest")
                .constructor().constructor("topicArn", "message").constructor("topicArn", "message", "subject")
                .same("topicArn", "targetArn", "phoneNumber", "message", "subject", "messageStructure", "messageGroupId",
                        "messageDeduplicationId"));
        spec(new Spec(sqs + "SendMessageRequest", "software.amazon.awssdk.services.sqs.model.SendMessageRequest")
                .constructor().constructor("queueUrl", "messageBody")
                .same("queueUrl", "messageBody", "delaySeconds", "messageGroupId", "messageDeduplicationId"));
        spec(new Spec(sqs + "ReceiveMessageRequest", "software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest")
                .constructor().constructor("queueUrl")
                .same("queueUrl", "maxNumberOfMessages", "waitTimeSeconds", "visibilityTimeout"));
        spec(new Spec(sqs + "DeleteMessageRequest", "software.amazon.awssdk.services.sqs.model.DeleteMessageRequest")
                .constructor().constructor("queueUrl", "receiptHandle")
                .same("queueUrl", "receiptHandle"));
    }

    // Kotlin property reads of v1 results: v1 type -> property -> v2 code, where %s is the receiver
    private static final Map<String, Map<String, String>> RESULT_PROPERTIES = new HashMap<>();

    private static void results(String type, String... propertyAndCode) {
        Map<String, String> properties = new HashMap<>();
        for (int i = 0; i < propertyAndCode.length; i += 2) {
            properties.put(propertyAndCode[i], propertyAndCode[i + 1]);
        }
        RESULT_PROPERTIES.put(type, properties);
    }

    static {
        // v1 returns java.util.Date where v2 returns Instant
        String date = "%d.from(%s.lastModified())";
        results(V1_S3_MODEL + "ObjectMetadata", "lastModified", date, "contentLength", "%s.contentLength()",
                "contentType", "%s.contentType()", "contentEncoding", "%s.contentEncoding()", "cacheControl", "%s.cacheControl()",
                "contentDisposition", "%s.contentDisposition()", "contentLanguage", "%s.contentLanguage()",
                "versionId", "%s.versionId()", "userMetadata", "%s.metadata()");
        results(V1_S3_MODEL + "S3Object", "objectContent", "%s", "objectMetadata", "%s.response()");
        results(V1_S3_MODEL + "ObjectListing", "objectSummaries", "%s.contents()", "isTruncated", "%s.isTruncated()",
                "nextMarker", "%s.nextMarker()", "prefix", "%s.prefix()", "bucketName", "%s.name()");
        results(V1_S3_MODEL + "S3ObjectSummary", "key", "%s.key()", "size", "%s.size()", "lastModified", date);
        results("com.amazonaws.services.sns.model.PublishResult", "messageId", "%s.messageId()");
        results("com.amazonaws.services.sqs.model.SendMessageResult", "messageId", "%s.messageId()");
        results("com.amazonaws.services.sqs.model.ReceiveMessageResult", "messages", "%s.messages()");
        results("com.amazonaws.services.sqs.model.Message", "body", "%s.body()", "receiptHandle", "%s.receiptHandle()",
                "messageId", "%s.messageId()");
        results("com.amazonaws.services.dynamodbv2.model.DescribeTableResult", "table", "%s.table()");
        // v1 returns the status as a String
        results("com.amazonaws.services.dynamodbv2.model.TableDescription", "tableName", "%s.tableName()",
                "tableStatus", "%s.tableStatusAsString()", "itemCount", "%s.itemCount()", "tableArn", "%s.tableArn()");
    }

    private KotlinAwsModelCalls() {
    }

    /**
     * Whether the v1 request type is converted by this class, so a bare reference to it is not a remnant.
     */
    static boolean isHandledRequest(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq != null && SPECS.containsKey(fq.getFullyQualifiedName());
    }

    /**
     * Converts a v1 request construction that is not part of a larger expression this class converts.
     *
     * @return the replacement, the unchanged construction when an enclosing expression converts it, or null when unknown.
     */
    static @Nullable J newClass(KotlinVisitor<ExecutionContext> v, J.NewClass newClass, ExecutionContext ctx) {
        Spec spec = spec(newClass.getType());
        if (spec == null) {
            return null;
        }
        if (isWithSelect(v.getCursor()) || spec.v2 == null) {
            return newClass;
        }
        return standalone(v, newClass, spec, ctx);
    }

    /**
     * Converts a v1 request {@code with} chain, a v1 client call or a v1 result getter.
     *
     * @return the replacement, the unchanged call when an enclosing chain converts it, or null when not applicable.
     */
    static @Nullable J methodInvocation(KotlinVisitor<ExecutionContext> v, J.MethodInvocation m, ExecutionContext ctx) {
        JavaType.Method mt = m.getMethodType();
        if (mt == null) {
            return null;
        }
        Spec spec = spec(mt.getDeclaringType());
        if (spec != null && isWith(m)) {
            if (isWithSelect(v.getCursor()) || spec.v2 == null) {
                return m;
            }
            return standalone(v, m, spec, ctx);
        }
        if (m.getSimpleName().startsWith("get") && m.getSimpleName().length() > 3 && arguments(m).isEmpty() && m.getSelect() != null) {
            String property = Character.toLowerCase(m.getSimpleName().charAt(3)) + m.getSimpleName().substring(4);
            J result = result(v, m, m.getSelect(), mt.getDeclaringType(), property, ctx);
            if (result != null) {
                return result;
            }
        }
        if (m.getSelect() == null || mt.hasFlags(Flag.Static)) {
            return null;
        }
        if (TypeUtils.isAssignableTo(V1_S3, mt.getDeclaringType())) {
            return s3(v, m, ctx);
        }
        if (TypeUtils.isAssignableTo(V1_SNS, mt.getDeclaringType())) {
            return sns(v, m, ctx);
        }
        if (TypeUtils.isAssignableTo(V1_SQS, mt.getDeclaringType())) {
            return sqs(v, m, ctx);
        }
        if (TypeUtils.isAssignableTo(V1_DYNAMODB, mt.getDeclaringType())) {
            return dynamoDb(v, m, ctx);
        }
        return null;
    }

    /**
     * A Kotlin property read of a v1 result, such as {@code metadata.lastModified}.
     *
     * @param original the field access before its target was visited, which still carries the v1 type
     */
    static @Nullable J fieldAccess(KotlinVisitor<ExecutionContext> v, J.FieldAccess original, J.FieldAccess visited, ExecutionContext ctx) {
        JavaType targetType = original.getTarget().getType();
        if (!(targetType instanceof JavaType.FullyQualified) && !(targetType instanceof JavaType.Parameterized)) {
            return null;
        }
        return result(v, visited, visited.getTarget(), targetType, original.getSimpleName(), ctx);
    }

    private static @Nullable J result(KotlinVisitor<ExecutionContext> v, Expression site, Expression target, JavaType targetType,
                                      String property, ExecutionContext ctx) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(targetType);
        if (fq == null) {
            return null;
        }
        Map<String, String> properties = RESULT_PROPERTIES.get(fq.getFullyQualifiedName());
        String code = properties == null ? null : properties.get(property);
        if (code == null) {
            return null;
        }
        Set<String> imports = new LinkedHashSet<>();
        if (code.contains("%d")) {
            code = code.replace("%d", reference(v.getCursor(), DATE, imports));
        }
        return template(v, site, code.replace("%s", "#{any()}"), imports, singletonList(target), ctx);
    }

    private static @Nullable J s3(KotlinVisitor<ExecutionContext> v, J.MethodInvocation m, ExecutionContext ctx) {
        List<Expression> args = arguments(m);
        Expression s3 = Objects.requireNonNull(m.getSelect());
        Cursor cursor = v.getCursor();
        Code code = new Code(cursor);
        switch (m.getSimpleName() + "/" + args.size()) {
            case "doesObjectExist/2":
                // v1's HEAD of the object, where only a 404 means "absent"
                code.append("try {\n").param(s3).append(".headObject(")
                        .build(V2_S3_MODEL + "HeadObjectRequest", "bucket", args.get(0), "key", args.get(1))
                        .append(")\ntrue\n} catch (e: ").type(V2_S3_MODEL + "S3Exception")
                        .append(") {\nif (e.statusCode() == 404) false else throw e\n}");
                break;
            case "getObject/2": {
                if (isString(args.get(1).getType())) {
                    // The v2 response stream is the v1 S3Object's content, and its response() the object's metadata
                    code.append("").param(s3).append(".getObject(")
                            .build(V2_S3_MODEL + "GetObjectRequest", "bucket", args.get(0), "key", args.get(1)).append(")");
                    break;
                }
                if (!TypeUtils.isOfClassType(args.get(1).getType(), "java.io.File")) {
                    return null;
                }
                Expression request = args.get(0);
                if (!passThrough(request)) {
                    return null;
                }
                // v1 creates missing parent directories and overwrites an existing file; v2 fails in both cases
                code.append("").param(s3).append(".getObject(").param(request).append(", ").param(args.get(1))
                        .append(".toPath().also { ").type("java.nio.file.Files").append(".createDirectories(it.toAbsolutePath().parent); ")
                        .type("java.nio.file.Files").append(".deleteIfExists(it) })");
                break;
            }
            case "putObject/3": {
                String body;
                if (TypeUtils.isOfClassType(args.get(2).getType(), "java.io.File")) {
                    body = "fromFile";
                } else if (isString(args.get(2).getType())) {
                    body = "fromString";
                } else {
                    return null;
                }
                code.append("").param(s3).append(".putObject(")
                        .build(V2_S3_MODEL + "PutObjectRequest", "bucket", args.get(0), "key", args.get(1))
                        .append(", ").type("software.amazon.awssdk.core.sync.RequestBody").append("." + body + "(").param(args.get(2)).append("))");
                break;
            }
            case "putObject/1": {
                Parsed request = parse(args.get(0), cursor);
                if (request == null || !SPECS.get(V1_S3_MODEL + "PutObjectRequest").equals(request.spec) ||
                    request.slots.get(FILE) == null || request.slots.containsKey(METHOD) || request.slots.containsKey(EXPIRATION)) {
                    return null;
                }
                code.append("").param(s3).append(".putObject(");
                if (!code.build(request)) {
                    return null;
                }
                code.append(", ").type("software.amazon.awssdk.core.sync.RequestBody").append(".fromFile(")
                        .param(request.slots.get(FILE)).append("))");
                code.folded.addAll(request.folded);
                break;
            }
            case "getObjectAsString/2":
                code.append("").param(s3).append(".getObjectAsBytes(")
                        .build(V2_S3_MODEL + "GetObjectRequest", "bucket", args.get(0), "key", args.get(1)).append(").asUtf8String()");
                break;
            case "getUrl/2":
                code.append("").param(s3).append(".utilities().getUrl(")
                        .build(V2_S3_MODEL + "GetUrlRequest", "bucket", args.get(0), "key", args.get(1)).append(")");
                break;
            case "copyObject/4":
                code.append("").param(s3).append(".copyObject(").build(V2_S3_MODEL + "CopyObjectRequest",
                        "sourceBucket", args.get(0), "sourceKey", args.get(1), "destinationBucket", args.get(2),
                        "destinationKey", args.get(3)).append(")");
                break;
            case "getObjectMetadata/2":
                code.append("").param(s3).append(".headObject(")
                        .build(V2_S3_MODEL + "HeadObjectRequest", "bucket", args.get(0), "key", args.get(1)).append(")");
                break;
            case "getObjectMetadata/1":
            case "deleteObject/1":
            case "listObjects/1": {
                if (isString(args.get(0).getType())) {
                    code.append("").param(s3).append(".listObjects(")
                            .build(V2_S3_MODEL + "ListObjectsRequest", "bucket", args.get(0)).append(")");
                    break;
                }
                Expression request = args.get(0);
                if (!passThrough(request)) {
                    return null;
                }
                code.append("").param(s3).append("getObjectMetadata".equals(m.getSimpleName()) ? ".headObject(" : "." + m.getSimpleName() + "(")
                        .param(request).append(")");
                break;
            }
            case "deleteObject/2":
                code.append("").param(s3).append(".deleteObject(")
                        .build(V2_S3_MODEL + "DeleteObjectRequest", "bucket", args.get(0), "key", args.get(1)).append(")");
                break;
            case "listObjects/2":
                code.append("").param(s3).append(".listObjects(")
                        .build(V2_S3_MODEL + "ListObjectsRequest", "bucket", args.get(0), "prefix", args.get(1)).append(")");
                break;
            case "generatePresignedUrl/1": {
                Parsed request = parse(args.get(0), cursor);
                if (request == null || request.spec.v2 != null || !request.properties.keySet().containsAll(Arrays.asList("bucket", "key")) ||
                    request.properties.size() != 2) {
                    return null;
                }
                if (!presign(code, s3, request.slots.get(METHOD), request.properties.get("bucket"), request.properties.get("key"),
                        request.slots.get(EXPIRATION))) {
                    return null;
                }
                code.folded.addAll(request.folded);
                break;
            }
            case "generatePresignedUrl/3":
            case "generatePresignedUrl/4":
                if (!presign(code, s3, args.size() == 4 ? args.get(3) : null, args.get(0), args.get(1), args.get(2))) {
                    return null;
                }
                break;
            default:
                return null;
        }
        return code.apply(v, m, ctx);
    }

    /**
     * {@code S3Presigner} built from the client, as v2-migration does in Java. v1's default expiry is 15 minutes.
     */
    private static boolean presign(Code code, Expression s3, @Nullable Expression method, Expression bucket, Expression key,
                                   @Nullable Expression expiration) {
        String httpMethod = method == null ? "GET" : constant(method, V1_HTTP_METHOD);
        String operation;
        if ("GET".equals(httpMethod)) {
            operation = "GetObject";
        } else if ("PUT".equals(httpMethod)) {
            operation = "PutObject";
        } else if ("DELETE".equals(httpMethod)) {
            operation = "DeleteObject";
        } else {
            return false;
        }
        String presign = "software.amazon.awssdk.services.s3.presigner.model." + operation + "PresignRequest";
        code.type("software.amazon.awssdk.services.s3.presigner.S3Presigner").append(".builder().s3Client(").param(s3)
                .append(").build().presign" + operation + "(").type(presign).append(".builder().signatureDuration(");
        if (expiration == null) {
            code.type("java.time.Duration").append(".ofMinutes(15)");
        } else {
            code.type("java.time.Duration").append(".between(").type("java.time.Instant").append(".now(), ")
                    .param(expiration).append(".toInstant())");
        }
        code.append(")." + Character.toLowerCase(operation.charAt(0)) + operation.substring(1) + "Request(")
                .build(V2_S3_MODEL + operation + "Request", "bucket", bucket, "key", key).append(").build()).url()");
        return true;
    }

    private static @Nullable J sns(KotlinVisitor<ExecutionContext> v, J.MethodInvocation m, ExecutionContext ctx) {
        List<Expression> args = arguments(m);
        Code code = new Code(v.getCursor());
        String request = "software.amazon.awssdk.services.sns.model.PublishRequest";
        if (!"publish".equals(m.getSimpleName())) {
            return null;
        }
        if (args.size() == 1) {
            return passThrough(args.get(0)) ? m : null;
        } else if (args.size() == 2) {
            code.append("").param(m.getSelect()).append(".publish(").build(request, "topicArn", args.get(0), "message", args.get(1)).append(")");
        } else if (args.size() == 3) {
            code.append("").param(m.getSelect()).append(".publish(")
                    .build(request, "topicArn", args.get(0), "message", args.get(1), "subject", args.get(2)).append(")");
        } else {
            return null;
        }
        return code.apply(v, m, ctx);
    }

    private static @Nullable J sqs(KotlinVisitor<ExecutionContext> v, J.MethodInvocation m, ExecutionContext ctx) {
        List<Expression> args = arguments(m);
        Code code = new Code(v.getCursor());
        String model = "software.amazon.awssdk.services.sqs.model.";
        switch (m.getSimpleName() + "/" + args.size()) {
            case "sendMessage/1":
            case "deleteMessage/1":
                return passThrough(args.get(0)) ? m : null;
            case "receiveMessage/1":
                if (isString(args.get(0).getType())) {
                    code.append("").param(m.getSelect()).append(".receiveMessage(")
                            .build(model + "ReceiveMessageRequest", "queueUrl", args.get(0)).append(")");
                    break;
                }
                return passThrough(args.get(0)) ? m : null;
            case "sendMessage/2":
                code.append("").param(m.getSelect()).append(".sendMessage(")
                        .build(model + "SendMessageRequest", "queueUrl", args.get(0), "messageBody", args.get(1)).append(")");
                break;
            case "deleteMessage/2":
                code.append("").param(m.getSelect()).append(".deleteMessage(")
                        .build(model + "DeleteMessageRequest", "queueUrl", args.get(0), "receiptHandle", args.get(1)).append(")");
                break;
            default:
                return null;
        }
        return code.apply(v, m, ctx);
    }

    private static @Nullable J dynamoDb(KotlinVisitor<ExecutionContext> v, J.MethodInvocation m, ExecutionContext ctx) {
        List<Expression> args = arguments(m);
        if (args.size() != 1 || !isString(args.get(0).getType()) ||
            !("describeTable".equals(m.getSimpleName()) || "deleteTable".equals(m.getSimpleName()))) {
            return null;
        }
        String request = "software.amazon.awssdk.services.dynamodb.model." +
                         Character.toUpperCase(m.getSimpleName().charAt(0)) + m.getSimpleName().substring(1) + "Request";
        Code code = new Code(v.getCursor());
        code.append("").param(m.getSelect()).append("." + m.getSimpleName() + "(").build(request, "tableName", args.get(0)).append(")");
        return code.apply(v, m, ctx);
    }

    /**
     * A request argument that is already converted, or a variable whose initializer is converted where it is declared.
     */
    private static boolean passThrough(Expression arg) {
        if (arg instanceof J.Identifier) {
            Spec spec = spec(arg.getType());
            return spec != null && spec.v2 != null;
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(arg.getType());
        return fq != null && fq.getFullyQualifiedName().startsWith("software.amazon.awssdk.");
    }

    private static final class Parsed {
        final Spec spec;
        final Map<String, Expression> properties = new LinkedHashMap<>();
        final Map<String, Expression> slots = new HashMap<>();
        final List<Statement> folded = new ArrayList<>();

        Parsed(Spec spec) {
            this.spec = spec;
        }

        void put(String slot, Expression value) {
            (slot.startsWith("@") ? slots : properties).put(slot, value);
        }

        boolean with(J.MethodInvocation m) {
            String slot = property(spec, m.getSimpleName());
            List<Expression> args = arguments(m);
            if (slot == null || args.size() != 1) {
                return false;
            }
            put(slot, args.get(0));
            return true;
        }
    }

    /**
     * A v1 request built by a constructor and {@code with} calls, inline or in a local variable used only here.
     */
    private static @Nullable Parsed parse(Expression e, Cursor cursor) {
        LinkedList<J.MethodInvocation> withs = new LinkedList<>();
        Expression current = e;
        while (current instanceof J.MethodInvocation && ((J.MethodInvocation) current).getSelect() != null) {
            withs.addFirst((J.MethodInvocation) current);
            current = ((J.MethodInvocation) current).getSelect();
        }
        Parsed parsed;
        if (current instanceof J.NewClass) {
            Spec spec = spec(current.getType());
            List<String> slots = spec == null ? null : spec.constructors.get(arguments(((J.NewClass) current).getArguments()).size());
            if (slots == null) {
                return null;
            }
            parsed = new Parsed(spec);
            List<Expression> args = arguments(((J.NewClass) current).getArguments());
            for (int i = 0; i < slots.size(); i++) {
                parsed.put(slots.get(i), args.get(i));
            }
        } else if (current instanceof J.Identifier && spec(current.getType()) != null) {
            Folded folded = foldLocal(current, cursor, spec(current.getType()).v1);
            if (folded == null) {
                return null;
            }
            parsed = parse(folded.initializer, cursor);
            if (parsed == null) {
                return null;
            }
            for (J.MethodInvocation setter : folded.setters) {
                if (!parsed.with(setter)) {
                    return null;
                }
            }
            parsed.folded.addAll(folded.statements);
        } else {
            return null;
        }
        for (J.MethodInvocation with : withs) {
            if (!parsed.with(with)) {
                return null;
            }
        }
        return parsed;
    }

    private static @Nullable String property(Spec spec, String method) {
        String name;
        if (method.startsWith("with") || method.startsWith("set")) {
            name = method.substring(method.startsWith("with") ? 4 : 3);
        } else {
            return null;
        }
        if (name.isEmpty()) {
            return null;
        }
        return spec.properties.get(Character.toLowerCase(name.charAt(0)) + name.substring(1));
    }

    private static boolean isString(@Nullable JavaType type) {
        return TypeUtils.isString(type) || TypeUtils.isOfClassType(type, "kotlin.String");
    }

    /**
     * A {@code withX(..)} call on a v1 request, whether or not X has a v2 equivalent.
     */
    private static boolean isWith(J.MethodInvocation m) {
        JavaType.Method mt = m.getMethodType();
        return mt != null && m.getSelect() != null && m.getSimpleName().startsWith("with") && spec(mt.getDeclaringType()) != null;
    }

    private static @Nullable Spec spec(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq == null ? null : SPECS.get(fq.getFullyQualifiedName());
    }

    /**
     * Whether the value at the cursor is the receiver of a request {@code with} call, which converts it as part of the chain.
     */
    private static boolean isWithSelect(Cursor cursor) {
        Object value = cursor.getValue();
        Object parent = cursor.getParentTreeCursor().getValue();
        if (!(parent instanceof J.MethodInvocation) || !(value instanceof J)) {
            return false;
        }
        J.MethodInvocation p = (J.MethodInvocation) parent;
        return isWith(p) && p.getSelect().getId().equals(((J) value).getId());
    }

    private static J standalone(KotlinVisitor<ExecutionContext> v, Expression e, Spec spec, ExecutionContext ctx) {
        Parsed parsed = parse(e, v.getCursor());
        if (parsed == null || !parsed.slots.isEmpty()) {
            return org.openrewrite.marker.SearchResult.found(e, "AWS SDK v1 request not migrated in Kotlin: it has a setting with no v2 builder equivalent");
        }
        Code code = new Code(v.getCursor());
        if (!code.build(parsed)) {
            return org.openrewrite.marker.SearchResult.found(e, "AWS SDK v1 request not migrated in Kotlin: it has a setting with no v2 builder equivalent");
        }
        return code.apply(v, e, ctx);
    }

    /**
     * The name of a v1 enum constant, such as {@code PUT} in {@code HttpMethod.PUT} or a member-imported {@code PUT}.
     */
    private static @Nullable String constant(Expression e, String enumType) {
        if (e instanceof J.FieldAccess && TypeUtils.isOfClassType(((J.FieldAccess) e).getTarget().getType(), enumType)) {
            return ((J.FieldAccess) e).getSimpleName();
        }
        if (e instanceof J.Identifier && ((J.Identifier) e).getFieldType() != null &&
            TypeUtils.isOfClassType(((J.Identifier) e).getFieldType().getOwner(), enumType)) {
            return ((J.Identifier) e).getSimpleName();
        }
        return null;
    }

    /**
     * v1 {@code CannedAccessControlList} constants and their v2 {@code ObjectCannedACL} names. {@code LogDeliveryWrite} only
     * applies to buckets, so it has none.
     */
    private static @Nullable String objectAcl(@Nullable String v1) {
        if (v1 == null) {
            return null;
        }
        switch (v1) {
            case "Private":
                return "PRIVATE";
            case "PublicRead":
                return "PUBLIC_READ";
            case "PublicReadWrite":
                return "PUBLIC_READ_WRITE";
            case "AuthenticatedRead":
                return "AUTHENTICATED_READ";
            case "AwsExecRead":
                return "AWS_EXEC_READ";
            case "BucketOwnerRead":
                return "BUCKET_OWNER_READ";
            case "BucketOwnerFullControl":
                return "BUCKET_OWNER_FULL_CONTROL";
            default:
                return null;
        }
    }

    /**
     * The simple name of a type, or its fully qualified name when the file uses another type of that name. A v1 request
     * that the v2 type replaces still resolves the simple name inside the template, so the v2 type is qualified and recorded
     * for {@link #SHORTEN} once the v1 type is gone.
     */
    private static String reference(Cursor cursor, String fqn, Set<String> imports) {
        String simpleName = simpleName(fqn);
        JavaSourceFile sourceFile = cursor.firstEnclosing(JavaSourceFile.class);
        boolean replaced = false;
        if (sourceFile != null) {
            for (JavaType type : sourceFile.getTypesInUse().getTypesInUse()) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                if (fq != null && simpleName.equals(simpleName(fq.getFullyQualifiedName().replace('$', '.'))) &&
                    !fqn.equals(fq.getFullyQualifiedName())) {
                    if (!fqn.equals(v2Request(fq.getFullyQualifiedName()))) {
                        return fqn;
                    }
                    replaced = true;
                }
            }
            for (J.ClassDeclaration classDecl : sourceFile.getClasses()) {
                if (simpleName.equals(classDecl.getSimpleName())) {
                    return fqn;
                }
            }
        }
        if (replaced) {
            Set<String> shorten = cursor.getNearestMessage(SHORTEN);
            if (shorten == null) {
                shorten = new HashSet<>();
                cursor.putMessageOnFirstEnclosing(JavaSourceFile.class, SHORTEN, shorten);
            }
            shorten.add(fqn);
            return fqn;
        }
        imports.add(fqn);
        return simpleName;
    }

    private static @Nullable String v2Request(String v1) {
        Spec spec = SPECS.get(v1);
        return spec == null ? null : spec.v2;
    }

    /**
     * The v2 request a v1 request type is renamed to once no v1 construction or {@code with} call of it is left, so that
     * variables holding a converted request, and the import, follow.
     */
    static @Nullable String convertedRequestType(String v1, J tree) {
        String v2 = v2Request(v1);
        if (v2 == null) {
            return null;
        }
        boolean[] remaining = {false};
        new KotlinVisitor<Integer>() {
            @Override
            public J visitNewClass(J.NewClass newClass, Integer p) {
                remaining[0] |= TypeUtils.isOfClassType(newClass.getType(), v1);
                return super.visitNewClass(newClass, p);
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, Integer p) {
                remaining[0] |= method.getMethodType() != null && TypeUtils.isOfClassType(method.getMethodType().getDeclaringType(), v1);
                return super.visitMethodInvocation(method, p);
            }
        }.visit(tree, 0);
        return remaining[0] ? null : v2;
    }

    private static J template(KotlinVisitor<ExecutionContext> v, Expression original, String code, Set<String> imports,
                              List<Expression> params, ExecutionContext ctx) {
        J result = KotlinTemplate.builder(code)
                .imports(imports.toArray(new String[0]))
                .parser(KotlinParser.builder().classpathFromResources(ctx, V2_ARTIFACTS))
                .build()
                .apply(v.getCursor(), original.getCoordinates().replace(), params.toArray());
        for (String fqn : imports) {
            v.maybeAddImport(fqn, false);
        }
        return result.withPrefix(original.getPrefix());
    }

    /**
     * Accumulates template code, its parameters and imports.
     */
    private static final class Code {
        final Cursor cursor;
        final StringBuilder code = new StringBuilder();
        final List<Expression> params = new ArrayList<>();
        final Set<String> imports = new LinkedHashSet<>();
        final List<Statement> folded = new ArrayList<>();

        Code(Cursor cursor) {
            this.cursor = cursor;
        }

        Code append(String s) {
            code.append(s);
            return this;
        }

        Code param(@Nullable Expression e) {
            code.append("#{any()}");
            params.add(Objects.requireNonNull(e));
            return this;
        }

        Code type(String fqn) {
            code.append(reference(cursor, fqn, imports));
            return this;
        }

        /**
         * {@code Type.builder().a(..).b(..).build()} from alternating property names and values.
         */
        Code build(String v2Type, Object... propertyAndValue) {
            type(v2Type).append(".builder()");
            for (int i = 0; i < propertyAndValue.length; i += 2) {
                append("." + propertyAndValue[i] + "(").param((Expression) propertyAndValue[i + 1]).append(")");
            }
            return append(".build()");
        }

        boolean build(Parsed parsed) {
            type(Objects.requireNonNull(parsed.spec.v2)).append(".builder()");
            for (Map.Entry<String, Expression> property : parsed.properties.entrySet()) {
                append("." + property.getKey() + "(").param(property.getValue()).append(")");
            }
            Expression acl = parsed.slots.get(ACL);
            if (acl != null) {
                String v2 = objectAcl(constant(acl, V1_ACL));
                if (v2 == null) {
                    return false;
                }
                append(".acl(").type(V2_S3_MODEL + "ObjectCannedACL").append("." + v2 + ")");
            }
            append(".build()");
            folded.addAll(parsed.folded);
            return true;
        }

        J apply(KotlinVisitor<ExecutionContext> v, Expression original, ExecutionContext ctx) {
            J result = template(v, original, code.toString(), imports, params, ctx);
            if (!folded.isEmpty()) {
                Set<UUID> ids = v.getCursor().getNearestMessage(FOLDED_STATEMENTS);
                if (ids == null) {
                    ids = new HashSet<>();
                    v.getCursor().putMessageOnFirstEnclosing(JavaSourceFile.class, FOLDED_STATEMENTS, ids);
                }
                for (Statement s : folded) {
                    ids.add(s.getId());
                }
            }
            return result;
        }
    }

    /**
     * The v1 types whose imports may be left unused once their uses are converted.
     */
    static List<String> v1Imports() {
        List<String> types = new ArrayList<>(SPECS.keySet());
        types.add(V1_ACL);
        types.add(V1_HTTP_METHOD);
        return types;
    }
}

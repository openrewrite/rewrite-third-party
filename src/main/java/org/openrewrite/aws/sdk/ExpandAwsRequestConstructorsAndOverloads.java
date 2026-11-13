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
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.SearchResult;
import software.amazon.awssdk.v2migration.ConstructorToFluent;

import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/**
 * v2-migration converts {@code new XRequest().withA(a)} but neither v1 constructors with arguments nor client convenience
 * overloads, and v1 parameter names cannot be recovered from bytecode. This curated table, copied from the v1 1.12 sources
 * ({@code AmazonXClient} overload bodies and model constructors), rewrites them to the no-argument and wither form first.
 */
public class ExpandAwsRequestConstructorsAndOverloads extends Recipe {

    static final String UNKNOWN_CONSTRUCTOR = "AWS SDK v1 constructor with arguments that is not in the expansion table; " +
                                              "rewrite it as new X().withY(..) so v2-migration can convert it";
    static final String UNKNOWN_OVERLOAD = "AWS SDK v1 client convenience overload that is not in the expansion table; " +
                                           "pass a request object instead so v2-migration can convert it";

    private static final String SQS = "com.amazonaws.services.sqs.";
    private static final String SNS = "com.amazonaws.services.sns.";
    private static final String DDB = "com.amazonaws.services.dynamodbv2.";
    private static final String SES = "com.amazonaws.services.simpleemail.";
    private static final String KIN = "com.amazonaws.services.kinesis.";
    private static final String STRING = "java.lang.String";
    private static final String LIST = "java.util.List";
    private static final String MAP = "java.util.Map";

    static final List<Expansion> CONSTRUCTORS = Arrays.asList(
            // SQS
            ctor(SQS + "model.AddPermissionRequest", STRING, "queueUrl", STRING, "label", LIST, "aWSAccountIds", LIST, "actions"),
            ctor(SQS + "model.ChangeMessageVisibilityBatchRequest", STRING, "queueUrl", LIST, "entries"),
            ctor(SQS + "model.ChangeMessageVisibilityBatchRequestEntry", STRING, "id", STRING, "receiptHandle"),
            ctor(SQS + "model.ChangeMessageVisibilityRequest", STRING, "queueUrl", STRING, "receiptHandle", "java.lang.Integer", "visibilityTimeout"),
            ctor(SQS + "model.CreateQueueRequest", STRING, "queueName"),
            ctor(SQS + "model.DeleteMessageBatchRequest", STRING, "queueUrl"),
            ctor(SQS + "model.DeleteMessageBatchRequest", STRING, "queueUrl", LIST, "entries"),
            ctor(SQS + "model.DeleteMessageBatchRequestEntry", STRING, "id", STRING, "receiptHandle"),
            ctor(SQS + "model.DeleteMessageRequest", STRING, "queueUrl", STRING, "receiptHandle"),
            ctor(SQS + "model.DeleteQueueRequest", STRING, "queueUrl"),
            ctor(SQS + "model.GetQueueAttributesRequest", STRING, "queueUrl"),
            ctor(SQS + "model.GetQueueAttributesRequest", STRING, "queueUrl", LIST, "attributeNames"),
            ctor(SQS + "model.GetQueueUrlRequest", STRING, "queueName"),
            ctor(SQS + "model.ListDeadLetterSourceQueuesRequest", STRING, "queueUrl"),
            ctor(SQS + "model.ListQueueTagsRequest", STRING, "queueUrl"),
            ctor(SQS + "model.ListQueuesRequest", STRING, "queueNamePrefix"),
            ctor(SQS + "model.PurgeQueueRequest", STRING, "queueUrl"),
            ctor(SQS + "model.ReceiveMessageRequest", STRING, "queueUrl"),
            ctor(SQS + "model.RemovePermissionRequest", STRING, "queueUrl", STRING, "label"),
            ctor(SQS + "model.SendMessageBatchRequest", STRING, "queueUrl"),
            ctor(SQS + "model.SendMessageBatchRequest", STRING, "queueUrl", LIST, "entries"),
            ctor(SQS + "model.SendMessageBatchRequestEntry", STRING, "id", STRING, "messageBody"),
            ctor(SQS + "model.SendMessageRequest", STRING, "queueUrl", STRING, "messageBody"),
            ctor(SQS + "model.SetQueueAttributesRequest", STRING, "queueUrl", MAP, "attributes"),
            ctor(SQS + "model.TagQueueRequest", STRING, "queueUrl", MAP, "tags"),
            ctor(SQS + "model.UntagQueueRequest", STRING, "queueUrl", LIST, "tagKeys"),
            // SNS
            ctor(SNS + "model.AddPermissionRequest", STRING, "topicArn", STRING, "label", LIST, "aWSAccountIds", LIST, "actionNames"),
            ctor(SNS + "model.ConfirmSubscriptionRequest", STRING, "topicArn", STRING, "token"),
            ctor(SNS + "model.ConfirmSubscriptionRequest", STRING, "topicArn", STRING, "token", STRING, "authenticateOnUnsubscribe"),
            ctor(SNS + "model.CreateTopicRequest", STRING, "name"),
            ctor(SNS + "model.DeleteTopicRequest", STRING, "topicArn"),
            ctor(SNS + "model.GetSubscriptionAttributesRequest", STRING, "subscriptionArn"),
            ctor(SNS + "model.GetTopicAttributesRequest", STRING, "topicArn"),
            ctor(SNS + "model.ListSubscriptionsByTopicRequest", STRING, "topicArn"),
            ctor(SNS + "model.ListSubscriptionsByTopicRequest", STRING, "topicArn", STRING, "nextToken"),
            ctor(SNS + "model.ListSubscriptionsRequest", STRING, "nextToken"),
            ctor(SNS + "model.ListTopicsRequest", STRING, "nextToken"),
            ctor(SNS + "model.PublishRequest", STRING, "topicArn", STRING, "message"),
            ctor(SNS + "model.PublishRequest", STRING, "topicArn", STRING, "message", STRING, "subject"),
            ctor(SNS + "model.RemovePermissionRequest", STRING, "topicArn", STRING, "label"),
            ctor(SNS + "model.SetSubscriptionAttributesRequest", STRING, "subscriptionArn", STRING, "attributeName", STRING, "attributeValue"),
            ctor(SNS + "model.SetTopicAttributesRequest", STRING, "topicArn", STRING, "attributeName", STRING, "attributeValue"),
            ctor(SNS + "model.SubscribeRequest", STRING, "topicArn", STRING, "protocol", STRING, "endpoint"),
            ctor(SNS + "model.UnsubscribeRequest", STRING, "subscriptionArn"),
            // DynamoDB
            ctor(DDB + "model.AttributeDefinition", STRING, "attributeName", STRING, "attributeType"),
            ctor(DDB + "model.AttributeDefinition", STRING, "attributeName", DDB + "model.ScalarAttributeType", "attributeType"),
            ctor(DDB + "model.AttributeValue", STRING, "s"),
            ctor(DDB + "model.AttributeValue", LIST, "sS"),
            ctor(DDB + "model.AttributeValueUpdate", DDB + "model.AttributeValue", "value", STRING, "action"),
            ctor(DDB + "model.AttributeValueUpdate", DDB + "model.AttributeValue", "value", DDB + "model.AttributeAction", "action"),
            ctor(DDB + "model.BatchGetItemRequest", MAP, "requestItems"),
            ctor(DDB + "model.BatchGetItemRequest", MAP, "requestItems", STRING, "returnConsumedCapacity"),
            ctor(DDB + "model.BatchGetItemRequest", MAP, "requestItems", DDB + "model.ReturnConsumedCapacity", "returnConsumedCapacity"),
            ctor(DDB + "model.BatchWriteItemRequest", MAP, "requestItems"),
            ctor(DDB + "model.CreateTableRequest", STRING, "tableName", LIST, "keySchema"),
            ctor(DDB + "model.CreateTableRequest", LIST, "attributeDefinitions", STRING, "tableName", LIST, "keySchema", DDB + "model.ProvisionedThroughput", "provisionedThroughput"),
            ctor(DDB + "model.DeleteItemRequest", STRING, "tableName", MAP, "key"),
            ctor(DDB + "model.DeleteItemRequest", STRING, "tableName", MAP, "key", STRING, "returnValues"),
            ctor(DDB + "model.DeleteItemRequest", STRING, "tableName", MAP, "key", DDB + "model.ReturnValue", "returnValues"),
            ctor(DDB + "model.DeleteRequest", MAP, "key"),
            ctor(DDB + "model.DeleteTableRequest", STRING, "tableName"),
            ctor(DDB + "model.DescribeTableRequest", STRING, "tableName"),
            ctor(DDB + "model.ExpectedAttributeValue", DDB + "model.AttributeValue", "value"),
            ctor(DDB + "model.ExpectedAttributeValue", "java.lang.Boolean", "exists"),
            ctor(DDB + "model.GetItemRequest", STRING, "tableName", MAP, "key"),
            ctor(DDB + "model.GetItemRequest", STRING, "tableName", MAP, "key", "java.lang.Boolean", "consistentRead"),
            ctor(DDB + "model.KeySchemaElement", STRING, "attributeName", STRING, "keyType"),
            ctor(DDB + "model.KeySchemaElement", STRING, "attributeName", DDB + "model.KeyType", "keyType"),
            ctor(DDB + "model.ListTablesRequest", STRING, "exclusiveStartTableName"),
            ctor(DDB + "model.ListTablesRequest", STRING, "exclusiveStartTableName", "java.lang.Integer", "limit"),
            ctor(DDB + "model.ProvisionedThroughput", "java.lang.Long", "readCapacityUnits", "java.lang.Long", "writeCapacityUnits"),
            ctor(DDB + "model.PutItemRequest", STRING, "tableName", MAP, "item"),
            ctor(DDB + "model.PutItemRequest", STRING, "tableName", MAP, "item", STRING, "returnValues"),
            ctor(DDB + "model.PutItemRequest", STRING, "tableName", MAP, "item", DDB + "model.ReturnValue", "returnValues"),
            ctor(DDB + "model.PutRequest", MAP, "item"),
            ctor(DDB + "model.QueryRequest", STRING, "tableName"),
            ctor(DDB + "model.ScanRequest", STRING, "tableName"),
            ctor(DDB + "model.UpdateItemRequest", STRING, "tableName", MAP, "key", MAP, "attributeUpdates"),
            ctor(DDB + "model.UpdateItemRequest", STRING, "tableName", MAP, "key", MAP, "attributeUpdates", STRING, "returnValues"),
            ctor(DDB + "model.UpdateItemRequest", STRING, "tableName", MAP, "key", MAP, "attributeUpdates", DDB + "model.ReturnValue", "returnValues"),
            ctor(DDB + "model.UpdateTableRequest", STRING, "tableName", DDB + "model.ProvisionedThroughput", "provisionedThroughput"),
            ctor(DDB + "model.WriteRequest", DDB + "model.PutRequest", "putRequest"),
            ctor(DDB + "model.WriteRequest", DDB + "model.DeleteRequest", "deleteRequest"),
            // SES
            ctor(SES + "model.Body", SES + "model.Content", "text"),
            ctor(SES + "model.Content", STRING, "data"),
            ctor(SES + "model.Destination", LIST, "toAddresses"),
            ctor(SES + "model.Message", SES + "model.Content", "subject", SES + "model.Body", "body"),
            ctor(SES + "model.RawMessage", "java.nio.ByteBuffer", "data"),
            ctor(SES + "model.SendEmailRequest", STRING, "source", SES + "model.Destination", "destination", SES + "model.Message", "message"),
            ctor(SES + "model.SendRawEmailRequest", SES + "model.RawMessage", "rawMessage"),
            // S3 constructors that v2-migration does not convert
            ctor("com.amazonaws.services.s3.model.Tag", STRING, "key", STRING, "value")
    );

    static final List<Overload> OVERLOADS = Arrays.asList(
            // SQS
            overload(SQS + "AmazonSQS", "addPermission", SQS + "model.AddPermissionRequest", STRING, "queueUrl", STRING, "label", LIST, "aWSAccountIds", LIST, "actions"),
            overload(SQS + "AmazonSQS", "changeMessageVisibility", SQS + "model.ChangeMessageVisibilityRequest", STRING, "queueUrl", STRING, "receiptHandle", "java.lang.Integer", "visibilityTimeout"),
            overload(SQS + "AmazonSQS", "changeMessageVisibilityBatch", SQS + "model.ChangeMessageVisibilityBatchRequest", STRING, "queueUrl", LIST, "entries"),
            overload(SQS + "AmazonSQS", "createQueue", SQS + "model.CreateQueueRequest", STRING, "queueName"),
            overload(SQS + "AmazonSQS", "deleteMessage", SQS + "model.DeleteMessageRequest", STRING, "queueUrl", STRING, "receiptHandle"),
            overload(SQS + "AmazonSQS", "deleteMessageBatch", SQS + "model.DeleteMessageBatchRequest", STRING, "queueUrl", LIST, "entries"),
            overload(SQS + "AmazonSQS", "deleteQueue", SQS + "model.DeleteQueueRequest", STRING, "queueUrl"),
            overload(SQS + "AmazonSQS", "getQueueAttributes", SQS + "model.GetQueueAttributesRequest", STRING, "queueUrl", LIST, "attributeNames"),
            overload(SQS + "AmazonSQS", "getQueueUrl", SQS + "model.GetQueueUrlRequest", STRING, "queueName"),
            overload(SQS + "AmazonSQS", "listQueueTags", SQS + "model.ListQueueTagsRequest", STRING, "queueUrl"),
            overload(SQS + "AmazonSQS", "listQueues", SQS + "model.ListQueuesRequest", STRING, "queueNamePrefix"),
            overload(SQS + "AmazonSQS", "receiveMessage", SQS + "model.ReceiveMessageRequest", STRING, "queueUrl"),
            overload(SQS + "AmazonSQS", "removePermission", SQS + "model.RemovePermissionRequest", STRING, "queueUrl", STRING, "label"),
            overload(SQS + "AmazonSQS", "sendMessage", SQS + "model.SendMessageRequest", STRING, "queueUrl", STRING, "messageBody"),
            overload(SQS + "AmazonSQS", "sendMessageBatch", SQS + "model.SendMessageBatchRequest", STRING, "queueUrl", LIST, "entries"),
            overload(SQS + "AmazonSQS", "setQueueAttributes", SQS + "model.SetQueueAttributesRequest", STRING, "queueUrl", MAP, "attributes"),
            overload(SQS + "AmazonSQS", "tagQueue", SQS + "model.TagQueueRequest", STRING, "queueUrl", MAP, "tags"),
            overload(SQS + "AmazonSQS", "untagQueue", SQS + "model.UntagQueueRequest", STRING, "queueUrl", LIST, "tagKeys"),
            // SNS
            overload(SNS + "AmazonSNS", "addPermission", SNS + "model.AddPermissionRequest", STRING, "topicArn", STRING, "label", LIST, "aWSAccountIds", LIST, "actionNames"),
            overload(SNS + "AmazonSNS", "confirmSubscription", SNS + "model.ConfirmSubscriptionRequest", STRING, "topicArn", STRING, "token", STRING, "authenticateOnUnsubscribe"),
            overload(SNS + "AmazonSNS", "confirmSubscription", SNS + "model.ConfirmSubscriptionRequest", STRING, "topicArn", STRING, "token"),
            overload(SNS + "AmazonSNS", "createTopic", SNS + "model.CreateTopicRequest", STRING, "name"),
            overload(SNS + "AmazonSNS", "deleteTopic", SNS + "model.DeleteTopicRequest", STRING, "topicArn"),
            overload(SNS + "AmazonSNS", "getSubscriptionAttributes", SNS + "model.GetSubscriptionAttributesRequest", STRING, "subscriptionArn"),
            overload(SNS + "AmazonSNS", "getTopicAttributes", SNS + "model.GetTopicAttributesRequest", STRING, "topicArn"),
            overload(SNS + "AmazonSNS", "listSubscriptions", SNS + "model.ListSubscriptionsRequest", STRING, "nextToken"),
            overload(SNS + "AmazonSNS", "listSubscriptionsByTopic", SNS + "model.ListSubscriptionsByTopicRequest", STRING, "topicArn"),
            overload(SNS + "AmazonSNS", "listSubscriptionsByTopic", SNS + "model.ListSubscriptionsByTopicRequest", STRING, "topicArn", STRING, "nextToken"),
            overload(SNS + "AmazonSNS", "listTopics", SNS + "model.ListTopicsRequest", STRING, "nextToken"),
            overload(SNS + "AmazonSNS", "publish", SNS + "model.PublishRequest", STRING, "topicArn", STRING, "message"),
            overload(SNS + "AmazonSNS", "publish", SNS + "model.PublishRequest", STRING, "topicArn", STRING, "message", STRING, "subject"),
            overload(SNS + "AmazonSNS", "removePermission", SNS + "model.RemovePermissionRequest", STRING, "topicArn", STRING, "label"),
            overload(SNS + "AmazonSNS", "setSubscriptionAttributes", SNS + "model.SetSubscriptionAttributesRequest", STRING, "subscriptionArn", STRING, "attributeName", STRING, "attributeValue"),
            overload(SNS + "AmazonSNS", "setTopicAttributes", SNS + "model.SetTopicAttributesRequest", STRING, "topicArn", STRING, "attributeName", STRING, "attributeValue"),
            overload(SNS + "AmazonSNS", "subscribe", SNS + "model.SubscribeRequest", STRING, "topicArn", STRING, "protocol", STRING, "endpoint"),
            overload(SNS + "AmazonSNS", "unsubscribe", SNS + "model.UnsubscribeRequest", STRING, "subscriptionArn"),
            // DynamoDB
            overload(DDB + "AmazonDynamoDB", "batchGetItem", DDB + "model.BatchGetItemRequest", MAP, "requestItems", STRING, "returnConsumedCapacity"),
            overload(DDB + "AmazonDynamoDB", "batchGetItem", DDB + "model.BatchGetItemRequest", MAP, "requestItems"),
            overload(DDB + "AmazonDynamoDB", "batchWriteItem", DDB + "model.BatchWriteItemRequest", MAP, "requestItems"),
            overload(DDB + "AmazonDynamoDB", "createTable", DDB + "model.CreateTableRequest", LIST, "attributeDefinitions", STRING, "tableName", LIST, "keySchema", DDB + "model.ProvisionedThroughput", "provisionedThroughput"),
            overload(DDB + "AmazonDynamoDB", "deleteItem", DDB + "model.DeleteItemRequest", STRING, "tableName", MAP, "key"),
            overload(DDB + "AmazonDynamoDB", "deleteItem", DDB + "model.DeleteItemRequest", STRING, "tableName", MAP, "key", STRING, "returnValues"),
            overload(DDB + "AmazonDynamoDB", "deleteTable", DDB + "model.DeleteTableRequest", STRING, "tableName"),
            overload(DDB + "AmazonDynamoDB", "describeTable", DDB + "model.DescribeTableRequest", STRING, "tableName"),
            overload(DDB + "AmazonDynamoDB", "getItem", DDB + "model.GetItemRequest", STRING, "tableName", MAP, "key"),
            overload(DDB + "AmazonDynamoDB", "getItem", DDB + "model.GetItemRequest", STRING, "tableName", MAP, "key", "java.lang.Boolean", "consistentRead"),
            overload(DDB + "AmazonDynamoDB", "listTables", DDB + "model.ListTablesRequest", STRING, "exclusiveStartTableName"),
            overload(DDB + "AmazonDynamoDB", "listTables", DDB + "model.ListTablesRequest", STRING, "exclusiveStartTableName", "java.lang.Integer", "limit"),
            overload(DDB + "AmazonDynamoDB", "listTables", DDB + "model.ListTablesRequest", "java.lang.Integer", "limit"),
            overload(DDB + "AmazonDynamoDB", "putItem", DDB + "model.PutItemRequest", STRING, "tableName", MAP, "item"),
            overload(DDB + "AmazonDynamoDB", "putItem", DDB + "model.PutItemRequest", STRING, "tableName", MAP, "item", STRING, "returnValues"),
            overload(DDB + "AmazonDynamoDB", "scan", DDB + "model.ScanRequest", STRING, "tableName", LIST, "attributesToGet"),
            overload(DDB + "AmazonDynamoDB", "scan", DDB + "model.ScanRequest", STRING, "tableName", MAP, "scanFilter"),
            overload(DDB + "AmazonDynamoDB", "scan", DDB + "model.ScanRequest", STRING, "tableName", LIST, "attributesToGet", MAP, "scanFilter"),
            overload(DDB + "AmazonDynamoDB", "updateItem", DDB + "model.UpdateItemRequest", STRING, "tableName", MAP, "key", MAP, "attributeUpdates"),
            overload(DDB + "AmazonDynamoDB", "updateItem", DDB + "model.UpdateItemRequest", STRING, "tableName", MAP, "key", MAP, "attributeUpdates", STRING, "returnValues"),
            overload(DDB + "AmazonDynamoDB", "updateTable", DDB + "model.UpdateTableRequest", STRING, "tableName", DDB + "model.ProvisionedThroughput", "provisionedThroughput"),
            // Kinesis
            overload(KIN + "AmazonKinesis", "createStream", KIN + "model.CreateStreamRequest", STRING, "streamName", "java.lang.Integer", "shardCount"),
            overload(KIN + "AmazonKinesis", "deleteStream", KIN + "model.DeleteStreamRequest", STRING, "streamName"),
            overload(KIN + "AmazonKinesis", "describeStream", KIN + "model.DescribeStreamRequest", STRING, "streamName"),
            overload(KIN + "AmazonKinesis", "describeStream", KIN + "model.DescribeStreamRequest", STRING, "streamName", STRING, "exclusiveStartShardId"),
            overload(KIN + "AmazonKinesis", "describeStream", KIN + "model.DescribeStreamRequest", STRING, "streamName", "java.lang.Integer", "limit", STRING, "exclusiveStartShardId"),
            overload(KIN + "AmazonKinesis", "getShardIterator", KIN + "model.GetShardIteratorRequest", STRING, "streamName", STRING, "shardId", STRING, "shardIteratorType"),
            overload(KIN + "AmazonKinesis", "getShardIterator", KIN + "model.GetShardIteratorRequest", STRING, "streamName", STRING, "shardId", STRING, "shardIteratorType", STRING, "startingSequenceNumber"),
            overload(KIN + "AmazonKinesis", "listStreams", KIN + "model.ListStreamsRequest", STRING, "exclusiveStartStreamName"),
            overload(KIN + "AmazonKinesis", "listStreams", KIN + "model.ListStreamsRequest", "java.lang.Integer", "limit", STRING, "exclusiveStartStreamName"),
            overload(KIN + "AmazonKinesis", "mergeShards", KIN + "model.MergeShardsRequest", STRING, "streamName", STRING, "shardToMerge", STRING, "adjacentShardToMerge"),
            overload(KIN + "AmazonKinesis", "putRecord", KIN + "model.PutRecordRequest", STRING, "streamName", "java.nio.ByteBuffer", "data", STRING, "partitionKey"),
            overload(KIN + "AmazonKinesis", "putRecord", KIN + "model.PutRecordRequest", STRING, "streamName", "java.nio.ByteBuffer", "data", STRING, "partitionKey", STRING, "sequenceNumberForOrdering"),
            overload(KIN + "AmazonKinesis", "splitShard", KIN + "model.SplitShardRequest", STRING, "streamName", STRING, "shardToSplit", STRING, "newStartingHashKey")
    );

    @Getter
    final String displayName = "Expand AWS SDK v1 request constructors and client overloads";

    @Getter
    final String description = "Rewrites AWS SDK v1 request and model constructors that take arguments, and client convenience overloads " +
                                "that take individual parameters, into the `new XRequest().withY(..)` form that " +
                                "`software.amazon.awssdk.v2migration.AwsSdkJavaV1ToV2` converts to v2 builders. Covers SQS, SNS, " +
                                "DynamoDB, SES and Kinesis, including the `*Async` variants. Constructors and overloads outside the table are marked.";

    @Override
    public List<Recipe> getRecipeList() {
        List<Recipe> recipes = new ArrayList<>(CONSTRUCTORS.size());
        for (Expansion c : CONSTRUCTORS) {
            recipes.add(new ConstructorToFluent(c.type, c.paramTypes, c.withers()));
        }
        return recipes;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        List<OverloadMatcher> matchers = new ArrayList<>();
        for (Overload o : OVERLOADS) {
            matchers.add(new OverloadMatcher(o, o.method, false));
            matchers.add(new OverloadMatcher(o, o.method + "Async", false));
            matchers.add(new OverloadMatcher(o, o.method + "Async", true));
        }
        return JavaOnly.check(new UsesType<>("com.amazonaws.services..*", false), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                for (OverloadMatcher om : matchers) {
                    if (om.matcher.matches(m)) {
                        // A project's own override of the overload is project API, so calls to it must keep reaching it
                        return Objects.requireNonNull(m.getMethodType()).getDeclaringType().getFullyQualifiedName()
                                       .startsWith("com.amazonaws.") ? expand(m, om) : m;
                    }
                }
                if (isUnknownOverload(m)) {
                    return SearchResult.found(m, UNKNOWN_OVERLOAD);
                }
                return m;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J.NewClass n = super.visitNewClass(newClass, ctx);
                if (isUnknownConstructor(n)) {
                    return SearchResult.found(n, UNKNOWN_CONSTRUCTOR);
                }
                return n;
            }

            private J.MethodInvocation expand(J.MethodInvocation m, OverloadMatcher om) {
                JavaType.Method methodType = Objects.requireNonNull(m.getMethodType());
                int paramCount = om.overload.paramTypes.size();
                List<Expression> args = m.getArguments();
                JavaType.Method target = findMethod(methodType.getDeclaringType(), m.getSimpleName(), om.overload.requestType,
                        om.withHandler ? 2 : 1);
                JavaType.FullyQualified requestType = target != null && target.getParameterTypes().get(0) instanceof JavaType.FullyQualified ?
                        (JavaType.FullyQualified) target.getParameterTypes().get(0) :
                        JavaType.ShallowClass.build(om.overload.requestType);

                Expression request = newRequest(requestType);
                for (int i = 0; i < paramCount; i++) {
                    request = wither(request, requestType, om.overload.withers().get(i),
                            JavaType.buildType(om.overload.paramTypes.get(i)), args.get(i).withPrefix(Space.EMPTY));
                }
                List<Expression> newArgs = new ArrayList<>();
                newArgs.add(request);
                if (om.withHandler) {
                    newArgs.add(args.get(paramCount));
                }
                maybeAddImport(requestType.getFullyQualifiedName());
                J.MethodInvocation expanded = m.withArguments(newArgs);
                if (target != null) {
                    expanded = expanded.withMethodType(target).withName(expanded.getName().withType(target));
                } else {
                    JavaType.Method synthetic = methodType
                            .withParameterNames(om.withHandler ? Arrays.asList("request", "asyncHandler") : singletonList("request"))
                            .withParameterTypes(om.withHandler ?
                                    Arrays.asList(requestType, methodType.getParameterTypes().get(paramCount)) :
                                    singletonList(requestType));
                    expanded = expanded.withMethodType(synthetic).withName(expanded.getName().withType(synthetic));
                }
                return expanded;
            }
        });
    }

    private static boolean isUnknownConstructor(J.NewClass n) {
        if (n.getBody() != null || n.getConstructorType() == null || n.getArguments().isEmpty() ||
            n.getArguments().get(0) instanceof J.Empty) {
            return false;
        }
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(n.getType());
        if (type == null || !isServiceModel(type.getFullyQualifiedName()) ||
            TypeUtils.isAssignableTo("java.lang.Throwable", type)) {
            return false;
        }
        List<JavaType> actual = n.getConstructorType().getParameterTypes();
        for (Expansion c : CONSTRUCTORS) {
            if (c.type.equals(type.getFullyQualifiedName()) && c.matches(actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnknownOverload(J.MethodInvocation m) {
        JavaType.Method mt = m.getMethodType();
        if (mt == null || mt.getParameterTypes().isEmpty()) {
            return false;
        }
        String declaring = mt.getDeclaringType().getFullyQualifiedName();
        if (!declaring.startsWith("com.amazonaws.services.") || declaring.startsWith("com.amazonaws.services.s3.")) {
            return false;
        }
        // Only the client interfaces and classes at the service root, e.g. com.amazonaws.services.sqs.AmazonSQS
        String rest = declaring.substring("com.amazonaws.services.".length());
        if (rest.split("\\.").length != 2 || !(mt.getDeclaringType().getClassName().startsWith("Amazon") ||
                                               mt.getDeclaringType().getClassName().startsWith("AWS"))) {
            return false;
        }
        return !TypeUtils.isAssignableTo("com.amazonaws.AmazonWebServiceRequest", mt.getParameterTypes().get(0));
    }

    private static boolean isServiceModel(String fqn) {
        return fqn.startsWith("com.amazonaws.services.") && !fqn.startsWith("com.amazonaws.services.s3.") &&
               fqn.contains(".model.");
    }

    private static J.NewClass newRequest(JavaType.FullyQualified requestType) {
        JavaType.Method ctor = new JavaType.Method(null, 1L, requestType, "<constructor>", requestType,
                emptyList(), emptyList(), null, null, null, null);
        J.Identifier clazz = new J.Identifier(Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, emptyList(),
                requestType.getClassName(), requestType, null);
        return new J.NewClass(Tree.randomId(), Space.EMPTY, Markers.EMPTY, null, Space.EMPTY, clazz,
                JContainer.build(Space.EMPTY, singletonList(JRightPadded.build(new J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY))), Markers.EMPTY),
                null, ctor);
    }

    private static J.MethodInvocation wither(Expression select, JavaType.FullyQualified requestType, String name,
                                             JavaType paramType, Expression arg) {
        JavaType.Method type = findMethod(requestType, name, null, 1);
        if (type == null) {
            type = new JavaType.Method(null, 1L, requestType, name, requestType, singletonList(name),
                    singletonList(paramType), null, null, null, null);
        }
        return new J.MethodInvocation(Tree.randomId(), Space.EMPTY, Markers.EMPTY, JRightPadded.build(select), null,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), name, type, null),
                JContainer.build(singletonList(JRightPadded.build(arg))), type);
    }

    /**
     * Finds a method by name and arity, optionally requiring its first parameter to be the given type, in the type or its
     * supertypes, so that the rewritten call carries the real v1 signature when the LST has it.
     */
    private static JavaType.@Nullable Method findMethod(JavaType.@Nullable FullyQualified type, String name,
                                                        @Nullable String firstParam, int arity) {
        Set<String> seen = new HashSet<>();
        Deque<JavaType.FullyQualified> queue = new ArrayDeque<>();
        if (type != null) {
            queue.add(type);
        }
        while (!queue.isEmpty()) {
            JavaType.FullyQualified t = queue.poll();
            if (!seen.add(t.getFullyQualifiedName())) {
                continue;
            }
            for (JavaType.Method m : t.getMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().size() == arity &&
                    (firstParam == null || TypeUtils.isOfClassType(m.getParameterTypes().get(0), firstParam))) {
                    return m;
                }
            }
            if (t.getSupertype() != null) {
                queue.add(t.getSupertype());
            }
            queue.addAll(t.getInterfaces());
        }
        return null;
    }

    private static Expansion ctor(String type, String... typesAndNames) {
        return new Expansion(type, typesAndNames);
    }

    private static Overload overload(String client, String method, String requestType, String... typesAndNames) {
        return new Overload(client, method, requestType, typesAndNames);
    }

    static class Expansion {
        final String type;
        final List<String> paramTypes = new ArrayList<>();
        final List<String> paramNames = new ArrayList<>();

        Expansion(String type, String... typesAndNames) {
            this.type = type;
            for (int i = 0; i < typesAndNames.length; i += 2) {
                paramTypes.add(typesAndNames[i]);
                paramNames.add(typesAndNames[i + 1]);
            }
        }

        List<String> withers() {
            List<String> withers = new ArrayList<>(paramNames.size());
            for (String name : paramNames) {
                withers.add("with" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
            }
            return withers;
        }

        boolean matches(List<JavaType> actual) {
            if (actual.size() != paramTypes.size()) {
                return false;
            }
            for (int i = 0; i < actual.size(); i++) {
                if (!TypeUtils.isOfClassType(actual.get(i), paramTypes.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    static class Overload extends Expansion {
        final String client;
        final String method;
        final String requestType;

        Overload(String client, String method, String requestType, String... typesAndNames) {
            super(requestType, typesAndNames);
            this.client = client;
            this.method = method;
            this.requestType = requestType;
        }
    }

    private static class OverloadMatcher {
        final Overload overload;
        final MethodMatcher matcher;
        final boolean withHandler;

        OverloadMatcher(Overload overload, String methodName, boolean withHandler) {
            this.overload = overload;
            this.withHandler = withHandler;
            String client = methodName.endsWith("Async") ? overload.client + "Async" : overload.client;
            StringJoiner params = new StringJoiner(", ");
            for (String t : overload.paramTypes) {
                params.add(t);
            }
            if (withHandler) {
                params.add("com.amazonaws.handlers.AsyncHandler");
            }
            this.matcher = new MethodMatcher(client + " " + methodName + "(" + params + ")", true);
        }
    }
}

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
package org.openrewrite.mongodb;

import org.jspecify.annotations.Nullable;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * The types of the MongoDB Java driver this migration reasons about.
 */
final class MongoTypes {

    private MongoTypes() {
    }

    static final String MONGO = "com.mongodb.Mongo";
    static final String LEGACY_MONGO_CLIENT = "com.mongodb.MongoClient";
    static final String DB = "com.mongodb.DB";
    static final String DB_COLLECTION = "com.mongodb.DBCollection";
    static final String DB_CURSOR = "com.mongodb.DBCursor";
    static final String DB_OBJECT = "com.mongodb.DBObject";
    static final String BASIC_DB_OBJECT = "com.mongodb.BasicDBObject";
    static final String WRITE_RESULT = "com.mongodb.WriteResult";
    static final String COMMAND_RESULT = "com.mongodb.CommandResult";
    static final String MONGO_CLIENT_URI = "com.mongodb.MongoClientURI";
    static final String MONGO_CLIENT_OPTIONS = "com.mongodb.MongoClientOptions";
    static final String MONGO_CLIENT_OPTIONS_BUILDER = "com.mongodb.MongoClientOptions$Builder";
    static final String UTIL_JSON = "com.mongodb.util.JSON";

    static final String MONGO_CLIENTS = "com.mongodb.client.MongoClients";
    static final String MONGO_CLIENT = "com.mongodb.client.MongoClient";
    static final String MONGO_DATABASE = "com.mongodb.client.MongoDatabase";
    static final String MONGO_COLLECTION = "com.mongodb.client.MongoCollection";
    static final String FIND_ITERABLE = "com.mongodb.client.FindIterable";
    static final String MONGO_CURSOR = "com.mongodb.client.MongoCursor";
    static final String MONGO_CLIENT_SETTINGS = "com.mongodb.MongoClientSettings";
    static final String CONNECTION_STRING = "com.mongodb.ConnectionString";

    /**
     * The legacy API types the conversion replaces with their driver 5 counterparts. Every other type of
     * {@code mongodb-driver-legacy} blocks the conversion of the code that uses it.
     */
    static final Set<String> CONVERTED = new HashSet<>(Arrays.asList(
            MONGO, LEGACY_MONGO_CLIENT, DB, DB_COLLECTION, DB_CURSOR, WRITE_RESULT, COMMAND_RESULT,
            MONGO_CLIENT_URI, MONGO_CLIENT_OPTIONS, MONGO_CLIENT_OPTIONS_BUILDER));

    /**
     * Public types only {@code mongodb-driver-legacy} 5.x provides. A module that still references one after the
     * migration keeps that artifact.
     */
    static final Set<String> LEGACY_ARTIFACT_ONLY = new HashSet<>(Arrays.asList(
            "com.mongodb.AcknowledgedBulkWriteResult", "com.mongodb.AggregationOptions",
            "com.mongodb.AggregationOptions$Builder", "com.mongodb.BulkUpdateRequestBuilder", "com.mongodb.BulkWriteError",
            "com.mongodb.BulkWriteException", "com.mongodb.BulkWriteOperation", "com.mongodb.BulkWriteRequestBuilder",
            "com.mongodb.BulkWriteResult", "com.mongodb.BulkWriteUpsert", COMMAND_RESULT, "com.mongodb.Cursor", DB,
            "com.mongodb.DBCallback", "com.mongodb.DBCallbackFactory", DB_COLLECTION, "com.mongodb.DBCollectionObjectFactory",
            DB_CURSOR, "com.mongodb.DBDecoder", "com.mongodb.DBDecoderFactory", "com.mongodb.DBEncoder",
            "com.mongodb.DBEncoderFactory", "com.mongodb.DefaultDBCallback", "com.mongodb.DefaultDBDecoder",
            "com.mongodb.DefaultDBEncoder", "com.mongodb.InsertOptions", "com.mongodb.LazyDBCallback",
            "com.mongodb.LazyDBDecoder", "com.mongodb.LazyDBEncoder", "com.mongodb.LazyDBList", "com.mongodb.LazyDBObject",
            "com.mongodb.MapReduceCommand", "com.mongodb.MapReduceCommand$OutputType", "com.mongodb.MapReduceOutput",
            LEGACY_MONGO_CLIENT, MONGO_CLIENT_OPTIONS, MONGO_CLIENT_OPTIONS_BUILDER, MONGO_CLIENT_URI,
            "com.mongodb.QueryBuilder", "com.mongodb.QueryOperators", "com.mongodb.WriteConcernError", WRITE_RESULT,
            "com.mongodb.client.jndi.MongoClientFactory", "com.mongodb.client.model.DBCollectionCountOptions",
            "com.mongodb.client.model.DBCollectionDistinctOptions", "com.mongodb.client.model.DBCollectionFindAndModifyOptions",
            "com.mongodb.client.model.DBCollectionFindOptions", "com.mongodb.client.model.DBCollectionRemoveOptions",
            "com.mongodb.client.model.DBCollectionUpdateOptions", "com.mongodb.client.model.DBCreateViewOptions",
            "com.mongodb.gridfs.GridFS", "com.mongodb.gridfs.GridFSDBFile", "com.mongodb.gridfs.GridFSFile",
            "com.mongodb.gridfs.GridFSInputFile"));

    /**
     * Types that existed in driver 3.x and are gone in 5.x altogether, with no counterpart in the legacy artifact.
     */
    static final Set<String> REMOVED = new HashSet<>(Arrays.asList(
            MONGO, "com.mongodb.MongoOptions", "com.mongodb.MongoURI", "com.mongodb.DBAddress",
            "com.mongodb.AggregationOutput", "com.mongodb.Bytes", "com.mongodb.GroupCommand",
            "com.mongodb.ParallelScanOptions", "com.mongodb.ReflectionDBObject", "com.mongodb.ReplicaSetStatus",
            "com.mongodb.MongoWaitQueueFullException", "com.mongodb.client.model.FindOptions",
            "com.mongodb.client.model.ParallelCollectionScanOptions", UTIL_JSON, "com.mongodb.util.JSONCallback",
            "com.mongodb.util.JSONParseException", "com.mongodb.util.JSONSerializers", "com.mongodb.util.ObjectSerializer",
            "com.mongodb.client.gridfs.model.GridFSDownloadByNameOptions"));

    static final String REMOVED_PACKAGE_ASYNC = "com.mongodb.async.";

    static boolean isLegacyArtifactOnly(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq != null && LEGACY_ARTIFACT_ONLY.contains(fq.getFullyQualifiedName());
    }

    static boolean isConverted(@Nullable String fqn) {
        return fqn != null && CONVERTED.contains(fqn);
    }

    static @Nullable String fqn(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq == null ? null : fq.getFullyQualifiedName();
    }

    /**
     * Whether a type, or one of its type arguments or bounds, is a legacy MongoDB type that only
     * {@code mongodb-driver-legacy} or driver 3.x provides.
     */
    static boolean mentionsLegacyType(@Nullable JavaType type) {
        return mentionsLegacyType(type, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean mentionsLegacyType(@Nullable JavaType type, Set<JavaType> seen) {
        if (type == null || !seen.add(type)) {
            return false;
        }
        if (type instanceof JavaType.Array) {
            return mentionsLegacyType(((JavaType.Array) type).getElemType(), seen);
        }
        if (type instanceof JavaType.GenericTypeVariable) {
            for (JavaType b : ((JavaType.GenericTypeVariable) type).getBounds()) {
                if (mentionsLegacyType(b, seen)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof JavaType.Parameterized) {
            for (JavaType p : ((JavaType.Parameterized) type).getTypeParameters()) {
                if (mentionsLegacyType(p, seen)) {
                    return true;
                }
            }
        }
        if (type instanceof JavaType.FullyQualified) {
            String fqn = ((JavaType.FullyQualified) type).getFullyQualifiedName();
            return LEGACY_ARTIFACT_ONLY.contains(fqn) || REMOVED.contains(fqn) || fqn.startsWith(REMOVED_PACKAGE_ASYNC);
        }
        return false;
    }
}

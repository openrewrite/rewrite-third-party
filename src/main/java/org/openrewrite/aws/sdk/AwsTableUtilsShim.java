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
 * Source of the {@code AwsTableUtils} that {@link MigrateDynamoDbTableUtils} generates in place of v1's {@code TableUtils}.
 */
final class AwsTableUtilsShim {

    static final String SHIM = "AwsTableUtils";

    private AwsTableUtilsShim() {
    }

    static String source(String pkg) {
        return ProjectShims.packageLine(pkg) +
               "import software.amazon.awssdk.services.dynamodb.DynamoDbClient;\n" +
               "import software.amazon.awssdk.services.dynamodb.model.*;\n" +
               "\n" +
               "/**\n" +
               " * The table helpers of v1's {@code TableUtils}, which has no v2 counterpart, with\n" +
               " * the same polling and the same results.\n" +
               " */\n" +
               "public final class AwsTableUtils {\n" +
               "\n" +
               "    private static final int DEFAULT_WAIT_TIMEOUT = 20 * 60 * 1000;\n" +
               "    private static final int DEFAULT_WAIT_INTERVAL = 10 * 1000;\n" +
               "\n" +
               "    private AwsTableUtils() {\n" +
               "    }\n" +
               "\n" +
               "    /**\n" +
               "     * @return {@code true} if the table was created, {@code false} if it already existed\n" +
               "     */\n" +
               "    public static boolean createTableIfNotExists(DynamoDbClient dynamo, CreateTableRequest createTableRequest) {\n" +
               "        try {\n" +
               "            dynamo.createTable(createTableRequest);\n" +
               "            return true;\n" +
               "        } catch (ResourceInUseException e) {\n" +
               "            return false;\n" +
               "        }\n" +
               "    }\n" +
               "\n" +
               "    /**\n" +
               "     * @return {@code true} if the table was deleted, {@code false} if it did not exist\n" +
               "     */\n" +
               "    public static boolean deleteTableIfExists(DynamoDbClient dynamo, DeleteTableRequest deleteTableRequest) {\n" +
               "        try {\n" +
               "            dynamo.deleteTable(deleteTableRequest);\n" +
               "            return true;\n" +
               "        } catch (ResourceNotFoundException e) {\n" +
               "            return false;\n" +
               "        }\n" +
               "    }\n" +
               "\n" +
               "    public static void waitUntilExists(DynamoDbClient dynamo, String tableName) throws InterruptedException {\n" +
               "        waitUntilExists(dynamo, tableName, DEFAULT_WAIT_TIMEOUT, DEFAULT_WAIT_INTERVAL);\n" +
               "    }\n" +
               "\n" +
               "    public static void waitUntilExists(DynamoDbClient dynamo, String tableName, int timeout, int interval)\n" +
               "            throws InterruptedException {\n" +
               "        if (waitFor(dynamo, tableName, null, timeout, interval) == null) {\n" +
               "            throw new IllegalStateException(\"Table \" + tableName + \" never returned a result\");\n" +
               "        }\n" +
               "    }\n" +
               "\n" +
               "    public static void waitUntilActive(DynamoDbClient dynamo, String tableName)\n" +
               "            throws InterruptedException, TableNeverTransitionedToStateException {\n" +
               "        waitUntilActive(dynamo, tableName, DEFAULT_WAIT_TIMEOUT, DEFAULT_WAIT_INTERVAL);\n" +
               "    }\n" +
               "\n" +
               "    public static void waitUntilActive(DynamoDbClient dynamo, String tableName, int timeout, int interval)\n" +
               "            throws InterruptedException, TableNeverTransitionedToStateException {\n" +
               "        TableDescription table = waitFor(dynamo, tableName, TableStatus.ACTIVE, timeout, interval);\n" +
               "        if (table == null || table.tableStatus() != TableStatus.ACTIVE) {\n" +
               "            throw new TableNeverTransitionedToStateException(tableName, TableStatus.ACTIVE);\n" +
               "        }\n" +
               "    }\n" +
               "\n" +
               "    private static TableDescription waitFor(DynamoDbClient dynamo, String tableName, TableStatus status, int timeout, int interval)\n" +
               "            throws InterruptedException {\n" +
               "        if (timeout < 0) {\n" +
               "            throw new IllegalArgumentException(\"Timeout must be >= 0\");\n" +
               "        }\n" +
               "        if (interval <= 0 || interval >= timeout) {\n" +
               "            throw new IllegalArgumentException(\"Interval must be > 0 and < timeout\");\n" +
               "        }\n" +
               "        long endTime = System.currentTimeMillis() + timeout;\n" +
               "        while (System.currentTimeMillis() < endTime) {\n" +
               "            try {\n" +
               "                TableDescription table = dynamo.describeTable(DescribeTableRequest.builder().tableName(tableName).build()).table();\n" +
               "                if (status == null || table.tableStatus() == status) {\n" +
               "                    return table;\n" +
               "                }\n" +
               "            } catch (ResourceNotFoundException ignored) {\n" +
               "                // the table is still being created\n" +
               "            }\n" +
               "            Thread.sleep(interval);\n" +
               "        }\n" +
               "        return null;\n" +
               "    }\n" +
               "\n" +
               "    public static class TableNeverTransitionedToStateException extends RuntimeException {\n" +
               "        public TableNeverTransitionedToStateException(String tableName, TableStatus desiredStatus) {\n" +
               "            super(\"Table \" + tableName + \" never transitioned to desired state of \" + desiredStatus);\n" +
               "        }\n" +
               "    }\n" +
               "}\n";
    }
}

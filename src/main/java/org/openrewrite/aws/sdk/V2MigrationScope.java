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
import org.openrewrite.Preconditions;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/**
 * Pre recipes must edit exactly the files that {@code AwsSdkJavaV1ToV2Safe} later migrates, otherwise a file would be left
 * half way between v1 and v2.
 */
final class V2MigrationScope {

    private V2MigrationScope() {
    }

    static TreeVisitor<?, ExecutionContext> javaFilesUsing(String typePattern) {
        return Preconditions.and(
                JavaOnly.javaSources(),
                Preconditions.not(new UsesType<>("com.amazonaws.services.kinesis.clientlibrary..*", true)),
                new UsesType<>(typePattern, true));
    }

    /**
     * A call that Mockito stubs or verifies is not executed, so it must not be rewritten as if it were.
     */
    static boolean isMockitoStubbedOrVerified(Cursor cursor) {
        return isMockitoArgument(cursor) || isMockitoReceiver(cursor);
    }

    /**
     * {@code when(client.call(..))}.
     */
    static boolean isMockitoArgument(Cursor cursor) {
        Object parent = cursor.getParentTreeCursor().getValue();
        return parent instanceof J.MethodInvocation && isMockito(((J.MethodInvocation) parent).getMethodType());
    }

    /**
     * {@code verify(client).call(..)}.
     */
    static boolean isMockitoReceiver(Cursor cursor) {
        Object self = cursor.getValue();
        return self instanceof J.MethodInvocation && ((J.MethodInvocation) self).getSelect() instanceof J.MethodInvocation &&
               isMockito(((J.MethodInvocation) ((J.MethodInvocation) self).getSelect()).getMethodType());
    }

    private static boolean isMockito(JavaType.@Nullable Method method) {
        return method != null && method.getDeclaringType().getFullyQualifiedName().startsWith("org.mockito.");
    }
}

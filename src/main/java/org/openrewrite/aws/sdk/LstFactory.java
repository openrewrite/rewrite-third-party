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
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.openrewrite.Tree.randomId;

final class LstFactory {

    private LstFactory() {
    }

    /**
     * Built by hand rather than with a template, because after v2-migration the receiver's type is synthesized from its name and
     * a template could not attribute the call.
     */
    static J.MethodInvocation noArgCall(Expression select, String name, JavaType.@Nullable FullyQualified declaringType,
                                        @Nullable JavaType returnType) {
        JavaType.Method type = declaringType == null ? null : new JavaType.Method(null, Flag.Public.getBitMask(), declaringType, name,
                returnType, emptyList(), emptyList(), emptyList(), emptyList(), null, null);
        return new J.MethodInvocation(
                randomId(),
                select.getPrefix(),
                Markers.EMPTY,
                JRightPadded.build(select.withPrefix(Space.EMPTY)),
                null,
                new J.Identifier(randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), name, type, null),
                JContainer.build(singletonList(JRightPadded.build(new J.Empty(randomId(), Space.EMPTY, Markers.EMPTY)))),
                type);
    }

    static JavaType.Parameterized completableFutureOf(@Nullable JavaType value) {
        return new JavaType.Parameterized(null, JavaType.ShallowClass.build("java.util.concurrent.CompletableFuture"),
                value == null ? emptyList() : singletonList(value));
    }
}

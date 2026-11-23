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
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.staticanalysis.UnnecessaryCatch;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Value
@EqualsAndHashCode(callSuper = false)
public class ChangeLegacyAzureTypes extends Recipe {

    @Getter
    final String displayName = "Retype legacy Azure SDK types to their current counterparts";

    @Getter
    final String description = "Changes each legacy Azure SDK type that has a current counterpart, such as " +
                               "`CloudBlobContainer` to `BlobContainerClient`, in declarations, generics, casts and " +
                               "imports. Runs after the calls on those types have been rewritten, since the members " +
                               "of the two differ.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return sourceFile instanceof J.CompilationUnit && !LegacyAzureMigration.isLeftAsItIs(sourceFile, ctx);
            }

            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                Tree t = tree;
                Map<UUID, J> unmigrated = new HashMap<>();
                new FindUnmigratedCalls().visit(t, unmigrated);
                for (Map.Entry<String, String> mapping : LegacyAzureTypes.mappings().entrySet()) {
                    if (t instanceof J.CompilationUnit &&
                        new UsesType<>(mapping.getKey(), true).visit(t, ctx) != t) {
                        t = new ChangeType(mapping.getKey(), mapping.getValue(), true).getVisitor().visit(t, ctx);
                    }
                }
                if (t != tree) {
                    // The current SDK throws no checked exceptions, so catches of the `URISyntaxException`,
                    // `InvalidKeyException` and `IOException` the legacy calls declared no longer compile. Only now,
                    // once `StorageException` is retyped to an unchecked exception, so that its catches stay.
                    t = new UnnecessaryCatch(false, false).getVisitor().visit(t, ctx);
                    t = new SingleAlternativeMultiCatch().visit(t, ctx);
                }
                if (!unmigrated.isEmpty() && t != tree) {
                    t = new RestoreUnmigratedCalls(unmigrated).visit(t, ctx);
                    // Where the calls it restored were all that used the legacy types, nothing changed
                    if (t instanceof SourceFile && tree instanceof SourceFile &&
                        ((SourceFile) t).printAll().equals(((SourceFile) tree).printAll())) {
                        return tree;
                    }
                }
                return t;
            }
        };
    }

    /**
     * Records each constructor call and static call of a retyped legacy type that no rewrite migrated, as it is with
     * the legacy type fully qualified. Retyped, {@code new CloudStorageAccount(credentials, true)} would read as
     * {@code new String(credentials, true)}, which hides the call FindAzureSdkLegacyRemnants marked for migrating by
     * hand.
     */
    private static class FindUnmigratedCalls extends JavaIsoVisitor<Map<UUID, J>> {
        @Override
        public J.NewClass visitNewClass(J.NewClass newClass, Map<UUID, J> unmigrated) {
            J.NewClass n = super.visitNewClass(newClass, unmigrated);
            if (n.getBody() == null && n.getClazz() != null && LegacyAzureTypes.isMapped(n.getType())) {
                unmigrated.put(n.getId(), new QualifyLegacyTypes().visitNonNull(n, 0, getCursor().getParentOrThrow()));
            }
            return n;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Map<UUID, J> unmigrated) {
            J.MethodInvocation m = super.visitMethodInvocation(method, unmigrated);
            if (isStaticCallOfMappedType(m)) {
                unmigrated.put(m.getId(), new QualifyLegacyTypes().visitNonNull(m, 0, getCursor().getParentOrThrow()));
            }
            return m;
        }
    }

    private static boolean isStaticCallOfMappedType(J.MethodInvocation m) {
        JavaType.Method type = m.getMethodType();
        return type != null && type.hasFlags(Flag.Static) && m.getSelect() instanceof TypeTree &&
               LegacyAzureTypes.isMapped(type.getDeclaringType()) &&
               TypeUtils.isOfType(m.getSelect().getType(), type.getDeclaringType());
    }

    private static class QualifyLegacyTypes extends JavaIsoVisitor<Integer> {
        @Override
        public J.NewClass visitNewClass(J.NewClass newClass, Integer p) {
            J.NewClass n = super.visitNewClass(newClass, p);
            if (n.getBody() == null && n.getClazz() != null && LegacyAzureTypes.isMapped(n.getType())) {
                n = n.withClazz(qualified(n.getClazz()));
            }
            return n;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
            J.MethodInvocation m = super.visitMethodInvocation(method, p);
            if (isStaticCallOfMappedType(m) && m.getSelect() != null) {
                m = m.withSelect(qualified(m.getSelect()));
            }
            return m;
        }

        @SuppressWarnings("unchecked")
        private static <T extends J> T qualified(T typeName) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(((TypedTree) typeName).getType());
            if (type == null) {
                return typeName;
            }
            TypeTree fq = TypeTree.build(type.getFullyQualifiedName().replace('$', '.'));
            return (T) fq.withType(type).withPrefix(typeName.getPrefix());
        }
    }

    private static class RestoreUnmigratedCalls extends JavaVisitor<ExecutionContext> {
        private final Map<UUID, J> unmigrated;

        RestoreUnmigratedCalls(Map<UUID, J> unmigrated) {
            this.unmigrated = unmigrated;
        }

        @Override
        public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
            J original = unmigrated.get(newClass.getId());
            return original != null ? original.withPrefix(newClass.getPrefix()) : super.visitNewClass(newClass, ctx);
        }

        @Override
        public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J original = unmigrated.get(method.getId());
            return original != null ? original.withPrefix(method.getPrefix()) : super.visitMethodInvocation(method, ctx);
        }
    }

    /**
     * A multi-catch that {@code UnnecessaryCatch} left with one alternative is the plain catch the parser would make.
     */
    private static class SingleAlternativeMultiCatch extends JavaIsoVisitor<ExecutionContext> {
        @Override
        public J.Try.Catch visitCatch(J.Try.Catch _catch, ExecutionContext ctx) {
            J.Try.Catch c = super.visitCatch(_catch, ctx);
            J.VariableDeclarations parameter = c.getParameter().getTree();
            if (parameter.getTypeExpression() instanceof J.MultiCatch) {
                J.MultiCatch multiCatch = (J.MultiCatch) parameter.getTypeExpression();
                if (multiCatch.getAlternatives().size() == 1) {
                    NameTree alternative = multiCatch.getAlternatives().get(0);
                    parameter = parameter.withTypeExpression(alternative.withPrefix(multiCatch.getPrefix()));
                    if (alternative.getType() != null) {
                        parameter = parameter.withVariables(ListUtils.map(parameter.getVariables(), v ->
                                v.withType(alternative.getType()).withName(v.getName().withType(alternative.getType()))));
                    }
                    c = c.withParameter(c.getParameter().withTree(parameter));
                }
            }
            return c;
        }
    }
}

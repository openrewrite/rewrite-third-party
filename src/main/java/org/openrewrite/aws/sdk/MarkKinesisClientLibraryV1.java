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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.aws.sdk.table.AwsSdkV1Remnants;
import org.openrewrite.gradle.trait.GradleDependency;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// In a composite the scan sees sources before MigrateKinesisClientLibraryToV2 ports them, so the visitor checks each source again
@Value
@EqualsAndHashCode(callSuper = false)
public class MarkKinesisClientLibraryV1 extends ScanningRecipe<MarkKinesisClientLibraryV1.Accumulator> {

    private static final String KCL_TEXT = AwsSdkV1Remnants.UNMIGRATABLE_MARKER_PREFIX + ": Kinesis Client Library 1.x " +
            "(`com.amazonaws:amazon-kinesis-client`) only works with SDK v1 and has no drop-in v2 release. Port this consumer " +
            "to KCL 2.x/3.x (`software.amazon.kinesis:amazon-kinesis-client`: `Scheduler`, `ShardRecordProcessor`, " +
            "v2 `KinesisAsyncClient`/`DynamoDbAsyncClient`/`CloudWatchAsyncClient`).";

    public static final String CODE_MARKER = KCL_TEXT +
            " Clients passed in here were migrated to v2, so this code will not compile until it is ported.";

    @Getter
    final String displayName = "Mark Kinesis Client Library 1.x";

    @Getter
    final String description = "Marks files that use Kinesis Client Library 1.x and its dependency declaration, which " +
            "keep AWS SDK v1 and must be ported to KCL 2.x/3.x by hand, and records them as unmigratable remnants. A declaration " +
            "that no code in its module uses is removed.";

    @Getter
    transient AwsSdkV1Remnants remnants = new AwsSdkV1Remnants(this);

    public static class Accumulator {
        final Set<Path> sourcesUsingKcl = ConcurrentHashMap.newKeySet();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile && usesKcl((JavaSourceFile) tree)) {
                    acc.sourcesUsingKcl.add(((JavaSourceFile) tree).getSourcePath());
                }
                return tree;
            }
        };
    }

    static boolean usesKcl(JavaSourceFile s) {
        for (JavaType type : s.getTypesInUse().getTypesInUse()) {
            if (type instanceof JavaType.FullyQualified &&
                AwsSdkV2Coordinates.isKclV1Type(((JavaType.FullyQualified) type).getFullyQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean codeAtOrBelow(Path module, Accumulator acc) {
        for (Path p : acc.sourcesUsingKcl) {
            if (module.toString().isEmpty() || p.startsWith(module)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKclV1(@Nullable String groupId, @Nullable String artifactId, @Nullable String version) {
        return "com.amazonaws".equals(groupId) && "amazon-kinesis-client".equals(artifactId) &&
               (version == null || version.startsWith("1.") || version.startsWith("$"));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile s = (SourceFile) tree;
                String path = s.getSourcePath().toString();
                boolean code = (BuildFiles.isMavenPom(s) || BuildFiles.isGradleBuild(s)) && codeAtOrBelow(BuildFiles.moduleDir(s), acc);
                if (BuildFiles.isMavenPom(s)) {
                    SourceFile marked = (SourceFile) new MavenIsoVisitor<ExecutionContext>() {
                        @Override
                        public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                            Xml.Tag t = super.visitTag(tag, ctx);
                            if (isDependencyTag() || isManagedDependencyTag()) {
                                String g = getResolutionResult().getPom().getValue(t.getChildValue("groupId").orElse(null));
                                String a = getResolutionResult().getPom().getValue(t.getChildValue("artifactId").orElse(null));
                                String v = getResolutionResult().getPom().getValue(t.getChildValue("version").orElse(null));
                                if (isKclV1(g, a, v)) {
                                    return code ? mark(t, path, g + ":" + a + (v == null ? "" : ":" + v), ctx) : SearchResult.found(t);
                                }
                            }
                            return t;
                        }
                    }.visitNonNull(s, ctx);
                    // Nothing in the module uses KCL 1.x, so the declaration is dropped rather than ported
                    return marked == s || code ? marked :
                            (Tree) new org.openrewrite.maven.RemoveDependency("com.amazonaws", "amazon-kinesis-client", null)
                                    .getVisitor().visitNonNull(s, ctx);
                } else if (BuildFiles.isGradleBuild(s)) {
                    SourceFile marked = (SourceFile) new GradleDependency.Matcher().groupId("com.amazonaws").artifactId("amazon-kinesis-client")
                            .asVisitor((GradleDependency dep, ExecutionContext c) -> {
                                String v = dep.getDeclaredVersion();
                                if (!isKclV1(dep.getDeclaredGroupId(), dep.getDeclaredArtifactId(), v)) {
                                    return dep.getTree();
                                }
                                return code ? mark(dep.getTree(), path, "com.amazonaws:amazon-kinesis-client" + (v == null ? "" : ":" + v), c) :
                                        SearchResult.found(dep.getTree());
                            }).visitNonNull(s, ctx);
                    return marked == s || code ? marked :
                            (Tree) new org.openrewrite.gradle.RemoveDependency("com.amazonaws", "amazon-kinesis-client", null)
                                    .getVisitor().visitNonNull(s, ctx);
                } else if (acc.sourcesUsingKcl.contains(s.getSourcePath()) && s instanceof JavaSourceFile && usesKcl((JavaSourceFile) s)) {
                    remnants.insertRow(ctx, new AwsSdkV1Remnants.Row(path, "type", "com.amazonaws.services.kinesis.clientlibrary",
                            AwsSdkV1Remnants.UNMIGRATABLE, CODE_MARKER));
                    return SearchResult.found(s, CODE_MARKER);
                }
                return s;
            }
        };
    }

    private <T extends Tree> T mark(T t, String path, String gav, ExecutionContext ctx) {
        remnants.insertRow(ctx, new AwsSdkV1Remnants.Row(path, "dependency", gav, AwsSdkV1Remnants.UNMIGRATABLE, CODE_MARKER));
        return SearchResult.found(t, CODE_MARKER);
    }
}

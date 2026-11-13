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
package org.openrewrite.aws.signing;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.aws.sdk.AwsMigrationScope;
import org.openrewrite.aws.signing.table.AwsRequestSigningBehaviourChanges;
import org.openrewrite.aws.signing.table.AwsRequestSigningRemnants;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.SearchResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.openrewrite.aws.signing.AwsRequestSigning.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class ReplaceAwsRequestSigningApacheInterceptor extends ScanningRecipe<ReplaceAwsRequestSigningApacheInterceptor.Accumulator> {

    @Option(displayName = "Generate helpers",
            description = AwsMigrationScope.GENERATE_HELPERS,
            required = false)
    @Nullable
    Boolean generateHelpers;

    private static final String TEMPLATE = "META-INF/rewrite/aws-request-signing/AwsSigV4RequestInterceptor.java.template";
    private static final JavaType.FullyQualified V2_CREDENTIALS_PROVIDER =
            JavaType.ShallowClass.build("software.amazon.awssdk.auth.credentials.AwsCredentialsProvider");
    private static final List<String> SIGNER_TYPES = Arrays.asList("com.amazonaws.auth.AWS4Signer",
            "software.amazon.awssdk.auth.signer.Aws4Signer", "software.amazon.awssdk.auth.signer.params.Aws4PresignerParams",
            "software.amazon.awssdk.regions.Region", "com.amazonaws.services.elasticsearch.AWSElasticsearch",
            "com.amazonaws.services.opensearch.AmazonOpenSearch", "com.amazonaws.services.cloudwatch.AmazonCloudWatch",
            "com.amazonaws.services.prometheus.AmazonPrometheus", "com.amazonaws.services.apigateway.AmazonApiGateway",
            "com.amazonaws.auth.AWSStaticCredentialsProvider", "com.amazonaws.auth.BasicAWSCredentials",
            "com.amazonaws.auth.BasicSessionCredentials", "com.amazonaws.auth.DefaultAWSCredentialsProviderChain",
            "com.amazonaws.auth.EnvironmentVariableCredentialsProvider", "com.amazonaws.auth.SystemPropertiesCredentialsProvider",
            "com.amazonaws.auth.profile.ProfileCredentialsProvider");

    private static final List<String> INREACH_CLOCK_TYPES = Arrays.asList(INREACH_SIGNER, "com.google.common.base.Supplier",
            "com.google.common.base.Suppliers", "java.util.function.Supplier", "java.time.LocalDateTime", "java.time.ZoneOffset",
            "java.time.ZoneId", "java.time.Clock");

    transient AwsRequestSigningBehaviourChanges behaviourChanges = new AwsRequestSigningBehaviourChanges(this);
    transient AwsRequestSigningRemnants remnants = new AwsRequestSigningRemnants(this);

    @Getter
    final String displayName = "Replace awslabs' `AWSRequestSigningApacheInterceptor` with a generated AWS SDK v2 interceptor";

    @Getter
    final String description = "Replaces each `new AWSRequestSigningApacheInterceptor(service, signer, credentials)` with " +
            "`new AwsSigV4RequestInterceptor(service, region, credentials)`, a class generated into the project that signs " +
            "Apache HttpClient 4 requests with AWS SDK v2's `AwsV4HttpSigner`. The service and region come from the local " +
            "`AWS4Signer`, which is removed. Java credentials are passed through for AWS's v2 migration to retype; Kotlin " +
            "credentials are mapped here. Vendored copies of the interceptor are deleted. Sites that cannot be migrated are " +
            "marked and listed in a data table. Use it through the AWS SDK v1 to v2 migration, which finishes the credentials.";

    public static class Accumulator implements AwsMigrationScope.Limitable {
        final Map<String, Project> projects = new HashMap<>();

        @Nullable
        AwsMigrationScope scope;

        @Override
        public void limitTo(AwsMigrationScope scope) {
            this.scope = scope;
        }

        Project project(SourceFile sf) {
            return projects.computeIfAbsent(projectKey(sf), k -> new Project());
        }
    }

    static class Project {
        final List<SiteFile> siteFiles = new ArrayList<>();
        int unmigratable;
        // top-level classes named like the shim, to avoid a clash
        final Set<String> shimNamedClasses = new HashSet<>();

        @Nullable
        String existingShim;

        // other sources in com.amazonaws.http, which keep its package-info
        int otherInterceptorPackageSources;

        // whether the interceptor in use signs POSTs without a body wrongly; null when unknown
        @Nullable
        Boolean postBug;

        @Nullable
        String shimFqn;

        boolean shimComputed;

        boolean migrates() {
            return unmigratable == 0 && shim() != null;
        }

        @Nullable
        String shim() {
            if (!shimComputed) {
                shimComputed = true;
                if (existingShim != null) {
                    shimFqn = existingShim;
                } else if (!siteFiles.isEmpty()) {
                    Set<String> packages = new TreeSet<>();
                    for (SiteFile f : siteFiles) {
                        if (!f.test) {
                            packages.add(f.pkg);
                        }
                    }
                    if (packages.isEmpty()) {
                        siteFiles.forEach(f -> packages.add(f.pkg));
                    }
                    String pkg = commonPackage(packages);
                    String simple = SHIM_NAME;
                    for (int n = 2; shimNamedClasses.contains(pkg + "." + simple); n++) {
                        simple = SHIM_NAME + n;
                    }
                    shimFqn = pkg + "." + simple;
                }
            }
            return shimFqn;
        }

        @Nullable
        SiteFile reference() {
            SiteFile reference = null;
            for (SiteFile f : siteFiles) {
                if (reference == null || reference.test && !f.test ||
                    reference.test == f.test && f.path.compareTo(reference.path) < 0) {
                    reference = f;
                }
            }
            return reference;
        }
    }

    /**
     * A source file with at least one migratable construction site, and where its project's shim would go.
     */
    static class SiteFile {
        final String path;
        final String pkg;
        final boolean test;
        final Path javaRoot;
        final List<Marker> markers = new ArrayList<>();

        @Nullable
        UUID sourceSetId;

        @Nullable
        String sourceSetName;

        SiteFile(JavaSourceFile cu) {
            path = cu.getSourcePath().toString();
            pkg = packageName(cu);
            test = isTest(cu);
            javaRoot = javaSourceRoot(cu);
            for (Marker marker : cu.getMarkers().getMarkers()) {
                if (marker instanceof JavaProject || marker instanceof JavaVersion) {
                    markers.add(marker);
                } else if (marker instanceof JavaSourceSet) {
                    sourceSetId = marker.getId();
                    sourceSetName = ((JavaSourceSet) marker).getName();
                }
            }
        }
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
                if (!(tree instanceof JavaSourceFile)) {
                    return tree;
                }
                JavaSourceFile cu = (JavaSourceFile) tree;
                Project project = acc.project(cu);
                for (J.ClassDeclaration cd : cu.getClasses()) {
                    if (cd.getSimpleName().startsWith(SHIM_NAME) && cd.getType() != null) {
                        project.shimNamedClasses.add(cd.getType().getFullyQualifiedName());
                        if (cu.printAll().contains("AwsV4HttpSigner")) {
                            project.existingShim = cd.getType().getFullyQualifiedName();
                        }
                    }
                }
                if (declaresInterceptor(cu)) {
                    String printed = cu.printAll();
                    // Copies carrying awslabs' fix for POSTs without a body write an empty content stream.
                    if (!printed.contains("new ByteArrayInputStream(new byte[0])") && !printed.contains("Aws4Signer")) {
                        project.postBug = true;
                    } else if (project.postBug == null) {
                        project.postBug = false;
                    }
                    return tree;
                }
                if (INTERCEPTOR_PACKAGE.equals(packageName(cu)) && !isInterceptorPackageInfo(cu)) {
                    project.otherInterceptorPackageSources++;
                }
                List<InterceptorSite> sites = InterceptorSite.find(cu);
                boolean migratable = false;
                for (InterceptorSite site : sites) {
                    if (site.unmigratable != null) {
                        project.unmigratable++;
                    } else {
                        migratable = true;
                    }
                }
                if (migratable) {
                    project.siteFiles.add(new SiteFile(cu));
                    String provider = interceptorProvider(cu);
                    if (provider != null && isAwslabsGav(provider)) {
                        String version = provider.substring(provider.lastIndexOf(':') + 1);
                        if (POST_BUG_VERSIONS.contains(version)) {
                            project.postBug = true;
                        } else if (project.postBug == null) {
                            project.postBug = false;
                        }
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (Boolean.FALSE.equals(generateHelpers)) {
            return Collections.emptyList();
        }
        List<SourceFile> generated = new ArrayList<>();
        for (Project project : acc.projects.values()) {
            String shim = project.shim();
            SiteFile reference = project.reference();
            if (shim == null || project.existingShim != null || reference == null ||
                acc.scope != null && project.siteFiles.stream().noneMatch(f -> acc.scope.includes(Paths.get(f.path)))) {
                continue;
            }
            String pkg = shim.substring(0, shim.lastIndexOf('.'));
            String simple = shim.substring(shim.lastIndexOf('.') + 1);
            Path path = reference.javaRoot.resolve(pkg.replace('.', '/')).resolve(simple + ".java");
            JavaParser.fromJavaVersion()
                    .classpathFromResources(ctx, SHIM_CLASSPATH)
                    .build()
                    .parse(ctx, render(pkg, simple))
                    .findFirst()
                    .ifPresent(sf -> {
                        Markers markers = sf.getMarkers();
                        for (Marker marker : reference.markers) {
                            markers = markers.setByType(marker);
                        }
                        if (reference.sourceSetId != null && reference.sourceSetName != null) {
                            markers = markers.setByType(new JavaSourceSet(reference.sourceSetId, reference.sourceSetName,
                                    emptyList(), emptyMap()));
                        }
                        generated.add(sf.withSourcePath(path).withMarkers(markers));
                    });
            project.existingShim = shim;
        }
        return generated;
    }

    static String render(String pkg, String simple) {
        try (InputStream in = ReplaceAwsRequestSigningApacheInterceptor.class.getClassLoader().getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + TEMPLATE);
            }
            return StringUtils.readFully(in, StandardCharsets.UTF_8).replace("${package}", pkg).replace("${className}", simple);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof JavaSourceFile)) {
                    return tree;
                }
                JavaSourceFile cu = (JavaSourceFile) tree;
                Project project = acc.projects.get(projectKey(cu));
                if (project == null) {
                    return tree;
                }
                if (declaresInterceptor(cu) || isInterceptorPackageInfo(cu)) {
                    boolean emptyPackage = declaresInterceptor(cu) || project.otherInterceptorPackageSources == 0;
                    return project.migrates() && emptyPackage ? null : tree;
                }
                String shim = project.shim();
                List<InterceptorSite> sites = InterceptorSite.find(cu);
                if (sites.isEmpty()) {
                    if (shim != null && project.migrates() && cu.getTypesInUse().getTypesInUse().stream()
                            .anyMatch(t -> TypeUtils.isOfClassType(t, INTERCEPTOR))) {
                        return new ChangeType(INTERCEPTOR, shim, true).getVisitor().visit(cu, ctx);
                    }
                    if (shim != null && project.migrates() && cu.getTypesInUse().getTypesInUse().stream()
                            .anyMatch(t -> TypeUtils.isOfClassType(t, INREACH_INTERCEPTOR))) {
                        return new ChangeType(INREACH_INTERCEPTOR, shim, true).getVisitor().visit(cu, ctx);
                    }
                    return tree;
                }
                return new Rewriter(cu, sites, project, shim).visit(cu, ctx);
            }
        };
    }

    private class Rewriter extends JavaVisitor<ExecutionContext> {
        private final Map<UUID, InterceptorSite> sites = new HashMap<>();
        private final Set<UUID> remove = new HashSet<>();
        // setter statement -> local it is hoisted into
        private final Map<UUID, String> hoists = new HashMap<>();
        private final Map<UUID, String> hoistedNames = new HashMap<>();
        private final Set<UUID> credentialInitializers = new HashSet<>();
        private final Project project;
        private final @Nullable String shim;
        private final boolean kotlin;
        private final String path;

        Rewriter(JavaSourceFile cu, List<InterceptorSite> found, Project project, @Nullable String shim) {
            this.project = project;
            this.shim = shim;
            this.kotlin = isKotlin(cu);
            this.path = cu.getSourcePath().toString();
            Set<String> names = identifierNames(cu);
            for (InterceptorSite site : found) {
                sites.put(site.id, site);
                if (site.unmigratable != null || shim == null) {
                    continue;
                }
                remove.addAll(site.remove);
                if (site.hoistService() && site.serviceStatement != null) {
                    hoist(site.serviceStatement, site.id + "service", "signingService", names);
                }
                if (site.hoistRegion() && site.regionStatement != null) {
                    hoist(site.regionStatement, site.id + "region", "signingRegion", names);
                }
                if (kotlin && site.credentials instanceof J.Identifier) {
                    J.VariableDeclarations decl = credentialsDeclaration(cu, (J.Identifier) site.credentials);
                    if (decl != null && KotlinV2Credentials.maps(decl.getVariables().get(0).getInitializer())) {
                        credentialInitializers.add(decl.getVariables().get(0).getInitializer().getId());
                    }
                }
            }
        }

        private void hoist(Statement statement, String key, String base, Set<String> names) {
            String name = base;
            for (int n = 2; names.contains(name); n++) {
                name = base + n;
            }
            names.add(name);
            hoists.put(statement.getId(), name);
            hoistedNames.put(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), name);
        }

        private @Nullable String hoisted(InterceptorSite site, String what) {
            return hoistedNames.get(UUID.nameUUIDFromBytes((site.id + what).getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public J visitBlock(J.Block block, ExecutionContext ctx) {
            J.Block b = (J.Block) super.visitBlock(block, ctx);
            // The statement after a removed run takes the run's leading whitespace, so no blank lines are left behind
            List<Statement> statements = new ArrayList<>(b.getStatements().size());
            Space removedPrefix = null;
            for (Statement s : b.getStatements()) {
                if (remove.contains(s.getId()) && !hoists.containsKey(s.getId())) {
                    if (removedPrefix == null) {
                        removedPrefix = s.getPrefix();
                    }
                } else {
                    statements.add(removedPrefix == null ? s : s.withPrefix(s.getPrefix().withWhitespace(removedPrefix.getWhitespace())));
                    removedPrefix = null;
                }
            }
            if (statements.size() != b.getStatements().size()) {
                b = b.withStatements(statements);
            }
            return b.withStatements(ListUtils.map(b.getStatements(), s -> {
                String name = hoists.get(s.getId());
                if (name != null) {
                    Expression value = InterceptorSite.setterValue(s);
                    Cursor at = new Cursor(getCursor(), s);
                    J declaration = kotlin ?
                            KotlinTemplate.builder("val " + name + " = #{any(java.lang.String)}").build()
                                    .apply(at, s.getCoordinates().replace(), value) :
                           JavaTemplate.apply("String " + name + " = #{any(java.lang.String)};", at, s.getCoordinates().replace(), value);
                    return ((Statement) declaration).withPrefix(s.getPrefix());
                }
                return remove.contains(s.getId()) ? null : s;
            }));
        }

        @Override
        public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
            J j = super.visitNewClass(newClass, ctx);
            if (!(j instanceof J.NewClass)) {
                return j;
            }
            J.NewClass nc = (J.NewClass) j;
            if (credentialInitializers.contains(nc.getId())) {
                return KotlinV2Credentials.toV2(nc, this::addImport);
            }
            InterceptorSite site = sites.get(nc.getId());
            if (site == null) {
                return nc;
            }
            if (site.unmigratable != null || shim == null) {
                return markUnmigratable(nc, site, ctx);
            }
            return rewrite(nc, site, ctx);
        }

        @Override
        public J visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = (J.ClassDeclaration) super.visitClassDeclaration(classDecl, ctx);
            InterceptorSite site = sites.get(cd.getName().getId());
            return site == null ? cd : cd.withName(markUnmigratable(cd.getName(), site, ctx));
        }

        private <T extends J> T markUnmigratable(T tree, InterceptorSite site, ExecutionContext ctx) {
            String reason = site.unmigratable != null ? site.unmigratable : "No shim could be placed for this project";
            remnants.insertRow(ctx, new AwsRequestSigningRemnants.Row(path, "unmigratable site", site.text + ": " + reason));
            return SearchResult.found(tree, "Not migrated off " + (site.inreach ? "vc.inreach's AWSSigningRequestInterceptor" :
                    "awslabs' AWSRequestSigningApacheInterceptor") + ": " + reason);
        }

        private J rewrite(J.NewClass nc, InterceptorSite site, ExecutionContext ctx) {
            assert shim != null && site.service != null;
            List<JRightPadded<Expression>> padded = new ArrayList<>();
            for (JRightPadded<Expression> p : nc.getPadding().getArguments().getPadding().getElements()) {
                if (!(p.getElement() instanceof J.Empty)) {
                    padded.add(p);
                }
            }
            String serviceName = hoisted(site, "service");
            String regionName = hoisted(site, "region");
            Expression service = serviceName != null ? identifier(serviceName) : copy(site.service);
            Expression region = regionName != null ? identifier(regionName) :
                    site.region != null ? copy(site.region) :
                            new J.Literal(Tree.randomId(), Space.EMPTY, Markers.EMPTY, null, "null", null, JavaType.Primitive.Null);
            Expression credentials = site.inreach ? copy(Objects.requireNonNull(site.credentials)) : padded.get(2).getElement();
            if (kotlin) {
                credentials = KotlinV2Credentials.toV2(credentials, this::addImport);
            }
            List<JRightPadded<Expression>> args;
            if (site.inreach) {
                Space space = Space.format(" ");
                args = Arrays.asList(JRightPadded.build(service.withPrefix(Space.EMPTY)),
                        JRightPadded.build(region.withPrefix(space)),
                        JRightPadded.build(credentials.withPrefix(space)));
            } else {
                JRightPadded<Expression> last = padded.get(padded.size() - 1);
                args = Arrays.asList(
                        padded.get(0).withElement(service.withPrefix(padded.get(0).getElement().getPrefix())),
                        padded.get(1).withElement(region.withPrefix(padded.get(1).getElement().getPrefix())),
                        last.withElement(credentials.withPrefix(padded.get(2).getElement().getPrefix())));
            }
            JContainer<Expression> container = nc.getPadding().getArguments();

            JavaType.ShallowClass shimType = JavaType.ShallowClass.build(shim);
            JavaType.Method ctor = new JavaType.Method(null, Flag.Public.getBitMask(), shimType, "<constructor>", shimType,
                    Arrays.asList("service", "region", "credentialsProvider"),
                    Arrays.asList(JavaType.Primitive.String, JavaType.Primitive.String,
                            V2_CREDENTIALS_PROVIDER),
                    emptyList(), emptyList(), null, null);
            TypeTree clazz = nc.getClazz();
            J.Identifier name = new J.Identifier(Tree.randomId(), clazz == null ? Space.EMPTY : clazz.getPrefix(), Markers.EMPTY,
                    emptyList(), shimType.getClassName(), shimType, null);
            J.NewClass rewritten = nc.withClazz(name)
                    .withConstructorType(ctor)
                    .getPadding().withArguments(JContainer.build(container.getBefore(), args, container.getMarkers()));

            addImport(shim);
            maybeRemoveImport(INTERCEPTOR);
            for (String type : SIGNER_TYPES) {
                maybeRemoveImport(type);
            }
            if (site.inreach) {
                maybeRemoveImport(INREACH_INTERCEPTOR);
                for (String type : INREACH_CLOCK_TYPES) {
                    maybeRemoveImport(type);
                }
            }
            if (project.migrates()) {
                doAfterVisit(new ChangeType(site.inreach ? INREACH_INTERCEPTOR : INTERCEPTOR, shim, true).getVisitor());
            }
            insertBehaviourChanges(ctx, site);
            return rewritten;
        }

        private void addImport(String fqn) {
            int dot = fqn.lastIndexOf('.');
            maybeAddImport(fqn.substring(0, dot), fqn.substring(dot + 1), null, null, false);
        }

        private void insertBehaviourChanges(ExecutionContext ctx, InterceptorSite site) {
            List<String[]> rows = new ArrayList<>();
            if (site.inreach) {
                rows.add(new String[]{"I1", "deviation", "vc.inreach's signer took the signing time from the supplied clock",
                        "The signing time comes from the system clock", "Only code that injected a fixed or offset clock, usually in tests, sees a difference"});
                rows.add(new String[]{"I2", "deviation", "vc.inreach's signer signed every request header it was given",
                        "Headers are signed as AWS SDK v2 signs them", "None for AWS, which reads the signed headers from the Authorization header"});
                for (String[] row : rows) {
                    behaviourChanges.insertRow(ctx, new AwsRequestSigningBehaviourChanges.Row(path, site.text, row[0], row[1], row[2], row[3], row[4]));
                }
                return;
            }
            if (site.presign) {
                rows.add(new String[]{"F5", "fix", "Discarded the signer's result, and parameters without signing name and region failed validation on every request",
                        "Signs every request", "The interceptor works for the first time; deployments that relied on it failing now send signed requests. " +
                        "Credentials are no longer resolved when the interceptor is built"});
            } else {
                if (Boolean.TRUE.equals(project.postBug)) {
                    rows.add(new String[]{"F1", "fix", "A POST without a body but with query parameters moved the parameters into the signed payload, so the signature was invalid",
                            "The parameters stay in the query and the empty payload is hashed", "Requests that AWS rejected with 403 now succeed"});
                }
                rows.add(new String[]{"F2", "fix", "The request entity was replaced by a non-repeatable one, so synchronous client retries of POST/PUT failed with NonRepeatableRequestException",
                        "The entity is left untouched", "Retries behave as configured"});
                rows.add(new String[]{"F3", "fix", "A non-markable request body threw SdkClientException (\"Unable to read request payload\")",
                        "The body is buffered with BufferedHttpEntity, keeping its content type and encoding, then signed", "Streaming bodies now work"});
                rows.add(new String[]{"F4", "fix", "Query parameter names differing only in case were merged, so the signature was invalid",
                        "Each name is signed separately", "Such requests now succeed"});
            }
            rows.add(new String[]{"V1", "deviation", "x-amz-content-sha256 was only sent when the request asked for it",
                    "x-amz-content-sha256 is always sent and signed", "AWS accepts it; proxies that allow-list headers see an extra header"});
            rows.add(new String[]{"V2", "deviation", "User-Agent, Expect, Transfer-Encoding and X-Forwarded-For were signed",
                    "They are left out of the signature, as AWS SDK v2 does", "None for AWS, which reads the signed headers from the Authorization header"});
            rows.add(new String[]{"V3", "deviation", "A query parameter with an empty name was signed",
                    "It is left out of the canonical query", "Edge case; not observed"});
            rows.add(new String[]{"V4", "deviation", "A null service name produced a scope AWS rejected",
                    "The interceptor throws when signing", "The request fails either way"});
            for (String[] row : rows) {
                behaviourChanges.insertRow(ctx, new AwsRequestSigningBehaviourChanges.Row(path, site.text, row[0], row[1], row[2], row[3], row[4]));
            }
        }
    }

    private static J.@Nullable VariableDeclarations credentialsDeclaration(JavaSourceFile cu, J.Identifier credentials) {
        J.VariableDeclarations[] found = new J.VariableDeclarations[1];
        new JavaVisitor<Integer>() {
            @Override
            public J visitVariableDeclarations(J.VariableDeclarations multiVariable, Integer p) {
                if (multiVariable.getVariables().size() == 1 &&
                    multiVariable.getVariables().get(0).getSimpleName().equals(credentials.getSimpleName()) &&
                    multiVariable.getVariables().get(0).getInitializer() != null) {
                    found[0] = found[0] == null ? multiVariable : found[0];
                }
                return super.visitVariableDeclarations(multiVariable, p);
            }
        }.visit(cu, 0);
        return found[0];
    }

    private static Set<String> identifierNames(JavaSourceFile cu) {
        Set<String> names = new HashSet<>();
        new JavaVisitor<Integer>() {
            @Override
            public J visitIdentifier(J.Identifier ident, Integer p) {
                names.add(ident.getSimpleName());
                return ident;
            }
        }.visit(cu, 0);
        return names;
    }

    private static J.Identifier identifier(String name) {
        return new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), name,
                JavaType.Primitive.String, null);
    }

    @SuppressWarnings("unchecked")
    private static <T extends J> T copy(T tree) {
        return (T) new JavaVisitor<Integer>() {
            @Override
            public J preVisit(J t, Integer p) {
                return t.withId(Tree.randomId());
            }
        }.visitNonNull(tree, 0);
    }
}

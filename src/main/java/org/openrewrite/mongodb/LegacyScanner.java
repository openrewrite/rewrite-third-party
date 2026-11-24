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
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.tree.*;
import org.openrewrite.maven.tree.Dependency;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ManagedDependency;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.quark.Quark;
import org.openrewrite.binary.Binary;
import org.openrewrite.remote.Remote;
import org.openrewrite.text.PlainText;

import java.util.*;
import java.util.regex.Pattern;

import static org.openrewrite.mongodb.MongoTypes.*;

/**
 * Scans a repository for what decides which of its modules move from the legacy MongoDB API to the driver 5 API,
 * and whether its driver moves to 5.
 */
final class LegacyScanner extends TreeVisitor<Tree, ExecutionContext> {

    static final Set<String> DRIVER_ARTIFACTS = new HashSet<>(Arrays.asList(
            "mongo-java-driver", "mongodb-driver", "mongodb-driver-core", "mongodb-driver-sync", "mongodb-driver-legacy"));

    private static final Pattern LEGACY_CLASS_NAME = Pattern.compile(
            "com\\.mongodb\\.(Mongo|MongoClient|MongoClientURI|MongoClientOptions|DB|DBCollection|DBCursor|WriteResult|CommandResult|MongoURI|MongoOptions)(?![\\w$])");

    private static final Set<String> INJECTION_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "org.springframework.beans.factory.annotation.Autowired", "javax.inject.Inject", "jakarta.inject.Inject",
            "javax.annotation.Resource", "jakarta.annotation.Resource", "com.google.inject.Inject",
            "org.springframework.beans.factory.annotation.Value"));

    private static final Set<String> PRODUCER_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "org.springframework.context.annotation.Bean", "javax.enterprise.inject.Produces",
            "jakarta.enterprise.inject.Produces", "com.google.inject.Provides", "dagger.Provides",
            "io.micronaut.context.annotation.Bean", "io.micronaut.context.annotation.Factory"));

    private static final Set<String> MOCK_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "org.mockito.Mock", "org.mockito.Spy", "org.mockito.InjectMocks",
            "org.springframework.boot.test.mock.mockito.MockBean", "org.springframework.boot.test.mock.mockito.SpyBean",
            "org.springframework.test.context.bean.override.mockito.MockitoBean"));

    private static final Set<String> COMPONENT_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "org.springframework.stereotype.Component", "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository", "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController", "org.springframework.context.annotation.Configuration",
            "org.springframework.boot.autoconfigure.SpringBootApplication"));

    /**
     * Exceptions the legacy API throws that the driver 5 API replaces with others, so that a {@code catch} of one would
     * no longer catch anything.
     */
    private static final Set<String> LEGACY_EXCEPTIONS = new HashSet<>(Arrays.asList(
            "com.mongodb.DuplicateKeyException", "com.mongodb.WriteConcernException", "com.mongodb.MongoException$DuplicateKey",
            "com.mongodb.CommandFailureException", "com.mongodb.BulkWriteException"));

    private final LegacyUsage acc;

    LegacyScanner(LegacyUsage acc) {
        this.acc = acc;
    }

    @Override
    public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
        if (!(tree instanceof SourceFile)) {
            return tree;
        }
        SourceFile sf = (SourceFile) tree;
        sf.getMarkers().findFirst(MavenResolutionResult.class).ifPresent(this::scanMaven);
        sf.getMarkers().findFirst(GradleProject.class).ifPresent(this::scanGradle);
        if (sf instanceof J.CompilationUnit) {
            scanJava((J.CompilationUnit) sf, ctx);
        } else if (sf instanceof JavaSourceFile) {
            JavaSourceFile other = (JavaSourceFile) sf;
            if (usesLegacyApi(other)) {
                String module = MongoClasspath.moduleKey(sf);
                acc.modulesUsingLegacyApi.add(module);
                acc.blockers.add(new LegacyUsage.Blocker(module, sf.getSourcePath().toString(),
                        "uses the legacy API in a language other than Java"));
            }
        } else if (sf instanceof PlainText && ModuleRequires.isDescriptor(sf)) {
            if (ModuleRequires.requiresDriver((PlainText) sf)) {
                acc.moduleDescriptors.add(sf.getSourcePath().toString());
            }
        } else if (!(sf instanceof Quark) && !(sf instanceof Binary) && !(sf instanceof Remote) &&
                   !sf.getMarkers().findFirst(MavenResolutionResult.class).isPresent() &&
                   !sf.getMarkers().findFirst(GradleProject.class).isPresent()) {
            String text = sf.printAll();
            java.util.regex.Matcher m = LEGACY_CLASS_NAME.matcher(text);
            if (m.find()) {
                acc.repositoryBlockers.add(sf.getSourcePath() + ": names `" + m.group() +
                                           "`, which configuration outside Java code creates or injects");
            }
        }
        return tree;
    }

    private void scanMaven(MavenResolutionResult mrr) {
        for (List<ResolvedDependency> deps : mrr.getDependencies().values()) {
            for (ResolvedDependency d : deps) {
                resolved(d);
            }
        }
        for (Dependency d : mrr.getPom().getRequestedDependencies()) {
            if ("org.mongodb".equals(d.getGroupId()) && DRIVER_ARTIFACTS.contains(d.getArtifactId())) {
                acc.declaresDriver = true;
            }
        }
        for (ManagedDependency d : mrr.getPom().getRequested().getDependencyManagement()) {
            if ("org.mongodb".equals(d.getGroupId()) && DRIVER_ARTIFACTS.contains(d.getArtifactId())) {
                acc.declaresDriver = true;
            }
        }
    }

    private void scanGradle(GradleProject gp) {
        for (GradleDependencyConfiguration conf : gp.getConfigurations()) {
            for (ResolvedDependency d : conf.getResolved()) {
                resolved(d);
            }
            for (Dependency d : conf.getRequested()) {
                if ("org.mongodb".equals(d.getGroupId()) && DRIVER_ARTIFACTS.contains(d.getArtifactId())) {
                    acc.declaresDriver = true;
                }
            }
        }
    }

    /**
     * Records the version of a driver that a build file resolves, which tells a repository that already left driver 3
     * when no Java source compiles against the driver.
     */
    private void resolved(ResolvedDependency d) {
        if ("org.mongodb".equals(d.getGroupId()) && (DRIVER_ARTIFACTS.contains(d.getArtifactId()) || "bson".equals(d.getArtifactId()))) {
            acc.resolvedDriverVersions.add(d.getVersion());
        }
    }

    static boolean isLegacyType(@Nullable String fqn) {
        return fqn != null && (CONVERTED.contains(fqn) || LEGACY_ARTIFACT_ONLY.contains(fqn) || REMOVED.contains(fqn) ||
                               fqn.startsWith(REMOVED_PACKAGE_ASYNC) || RemovedApi.get().isRemovedType(fqn));
    }

    /**
     * Whether a source uses a type that the conversion replaces or that only {@code mongodb-driver-legacy} has. Types
     * that no driver 5 artifact has are the removed API's concern.
     */
    static boolean usesLegacyApi(JavaSourceFile cu) {
        for (JavaType t : cu.getTypesInUse().getTypesInUse()) {
            String fqn = fqn(t);
            if (fqn != null && (CONVERTED.contains(fqn) || LEGACY_ARTIFACT_ONLY.contains(fqn))) {
                return true;
            }
        }
        return false;
    }

    private void scanJava(J.CompilationUnit cu, ExecutionContext ctx) {
        String module = MongoClasspath.moduleKey(cu);
        String path = cu.getSourcePath().toString();
        String version = MongoClasspath.driverVersion(cu);
        if (version != null) {
            acc.driverVersionByModule.merge(module, version,
                    (a, b) -> MongoClasspath.isAtLeast(a, b) ? a : b);
        }
        acc.boundLibraries.addAll(MongoClasspath.boundLibraries(cu));
        int release = cu.getMarkers().findFirst(JavaVersion.class).map(JavaVersion::getMajorReleaseVersion).orElse(8);
        synchronized (acc) {
            acc.minJavaRelease = Math.min(acc.minJavaRelease, release);
        }
        for (J.ClassDeclaration c : cu.getClasses()) {
            declare(c, module);
        }
        for (JavaType t : cu.getTypesInUse().getTypesInUse()) {
            if ("java.util.UUID".equals(fqn(t))) {
                acc.usesUuid = true;
            }
        }

        // Uses of removed API that no recipe replaces keep the driver where it is
        boolean[] replacesRemovedApi = {false};
        new RemovedApiFinder() {
            @Override
            void found(J tree, String description, boolean fixable) {
                if (fixable) {
                    replacesRemovedApi[0] = true;
                } else {
                    acc.removedApiUses.add(new LegacyUsage.Blocker(module, path, description, true));
                }
            }
        }.visit(cu, ctx);
        new UnimplementableScanner(module, path).visit(cu, ctx);

        if (!usesLegacyApi(cu)) {
            return;
        }
        acc.modulesUsingLegacyApi.add(module);
        if (release < 8) {
            acc.blockers.add(new LegacyUsage.Blocker(module, path, "compiles for Java " + release +
                                                                   ", and the driver 5 API is used with lambdas"));
        }

        // What the conversion cannot translate. When the driver moves to 5, the conversion sees the code with the
        // removed API replaced, which can differ in what it translates, so both are asked, and the decision takes the
        // answer for the code that the conversion will see.
        Set<String> asIs = untranslatable(cu, ctx);
        Set<String> replaced = asIs;
        if (replacesRemovedApi[0]) {
            J.CompilationUnit fixed = RemovedApiFixes.apply(cu, ctx);
            if (fixed != cu) {
                replaced = untranslatable(fixed, ctx);
            }
        }
        for (String reason : asIs) {
            (replaced.contains(reason) ? acc.blockers : acc.blockersUnlessUpgraded).add(new LegacyUsage.Blocker(module, path, reason));
        }
        for (String reason : replaced) {
            if (!asIs.contains(reason)) {
                acc.blockersIfUpgraded.add(new LegacyUsage.Blocker(module, path, reason));
            }
        }
        for (JavaType t : cu.getTypesInUse().getTypesInUse()) {
            String fqn = fqn(t);
            if (fqn != null && LEGACY_ARTIFACT_ONLY.contains(fqn) && !CONVERTED.contains(fqn)) {
                acc.blockers.add(new LegacyUsage.Blocker(module, path, "uses `" + LegacyUsage.simpleName(fqn) +
                                                                       "`, which the driver 5 API has no counterpart for"));
            }
        }

        new StructureScanner(module, path).visit(cu, ctx);
    }

    private static Set<String> untranslatable(J.CompilationUnit cu, ExecutionContext ctx) {
        LegacyApiConverter dryRun = new LegacyApiConverter(true, false);
        dryRun.visit(cu, ctx);
        Set<String> reasons = new LinkedHashSet<>(dryRun.unsupported);
        if (dryRun.facts != null) {
            reasons.addAll(dryRun.facts.blockers);
        }
        return reasons;
    }

    private void declare(J.ClassDeclaration c, String module) {
        if (c.getType() != null) {
            acc.declaredTypeModule.put(c.getType().getFullyQualifiedName(), module);
        }
        for (Statement s : c.getBody().getStatements()) {
            if (s instanceof J.ClassDeclaration) {
                declare((J.ClassDeclaration) s, module);
            }
        }
    }

    private static boolean isConvertedType(@Nullable JavaType type) {
        return isConvertedType(type, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean isConvertedType(@Nullable JavaType type, Set<JavaType> seen) {
        if (type == null || !seen.add(type)) {
            return false;
        }
        if (type instanceof JavaType.Array) {
            return isConvertedType(((JavaType.Array) type).getElemType(), seen);
        }
        if (type instanceof JavaType.Parameterized) {
            for (JavaType p : ((JavaType.Parameterized) type).getTypeParameters()) {
                if (isConvertedType(p, seen)) {
                    return true;
                }
            }
        }
        if (type instanceof JavaType.GenericTypeVariable) {
            for (JavaType b : ((JavaType.GenericTypeVariable) type).getBounds()) {
                if (isConvertedType(b, seen)) {
                    return true;
                }
            }
            return false;
        }
        String fqn = fqn(type);
        return fqn != null && CONVERTED.contains(fqn);
    }

    private static boolean signatureMentionsConverted(JavaType.Method m) {
        if (isConvertedType(m.getReturnType())) {
            return true;
        }
        for (JavaType p : m.getParameterTypes()) {
            if (isConvertedType(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnnotation(List<J.Annotation> annotations, Set<String> names) {
        for (J.Annotation a : annotations) {
            String fqn = fqn(a.getType());
            if (fqn != null && names.contains(fqn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Classes implementing a driver type to which driver 5 adds abstract methods that cannot be written for them, which
     * keep the driver where it is, since they would not compile against driver 5.
     */
    private final class UnimplementableScanner extends JavaIsoVisitor<ExecutionContext> {
        private final String module;
        private final String path;

        UnimplementableScanner(String module, String path) {
            this.module = module;
            this.path = path;
        }

        private void check(String owner, List<AddedMethods.Missing> missing) {
            for (AddedMethods.Missing m : missing) {
                if (m.declaration == null) {
                    acc.removedApiUses.add(new LegacyUsage.Blocker(module, path, owner + " implements `" +
                            LegacyUsage.simpleName(m.type) + "`, of which driver 5 requires `" + m.method.label() + "`, " + m.why));
                    return;
                }
            }
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);
            if (c.getKind() != J.ClassDeclaration.Kind.Type.Interface && c.getKind() != J.ClassDeclaration.Kind.Type.Annotation &&
                !c.hasModifier(J.Modifier.Type.Abstract)) {
                check("declares `" + c.getSimpleName() + "`, which", AddedMethods.get().missing(c.getType(), Collections.emptyList(), true));
            }
            return c;
        }

        @Override
        public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
            J.NewClass n = super.visitNewClass(newClass, ctx);
            if (n.getBody() != null && n.getClazz() != null) {
                List<JavaType.Method> declared = new ArrayList<>();
                for (Statement s : n.getBody().getStatements()) {
                    if (s instanceof J.MethodDeclaration && ((J.MethodDeclaration) s).getMethodType() != null) {
                        declared.add(((J.MethodDeclaration) s).getMethodType());
                    }
                }
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(n.getClazz().getType());
                check("declares an anonymous `" + (type == null ? "" : type.getClassName()) + "`, which",
                        AddedMethods.get().missing(type, declared, false));
            }
            return n;
        }
    }

    /**
     * Uses of the legacy types that tie them to code outside the module or to the framework that wires it.
     */
    private final class StructureScanner extends JavaIsoVisitor<ExecutionContext> {
        private final String module;
        private final String path;

        StructureScanner(String module, String path) {
            this.module = module;
            this.path = path;
        }

        private void block(String reason) {
            acc.blockers.add(new LegacyUsage.Blocker(module, path, reason));
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            if (classDecl.getExtends() != null && isLegacyType(fqn(classDecl.getExtends().getType()))) {
                block("declares `" + classDecl.getSimpleName() + "` extending `" +
                      LegacyUsage.simpleName(String.valueOf(fqn(classDecl.getExtends().getType()))) + "`");
            }
            if (classDecl.getImplements() != null) {
                for (TypeTree i : classDecl.getImplements()) {
                    if (isLegacyType(fqn(i.getType()))) {
                        block("declares `" + classDecl.getSimpleName() + "` implementing `" +
                              LegacyUsage.simpleName(String.valueOf(fqn(i.getType()))) + "`");
                    }
                }
            }
            return super.visitClassDeclaration(classDecl, ctx);
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            JavaType.Method mt = method.getMethodType();
            if (mt != null && signatureMentionsConverted(mt)) {
                Optional<JavaType.Method> overridden = TypeUtils.findOverriddenMethod(mt);
                if (overridden.isPresent()) {
                    JavaType.FullyQualified declaring = overridden.get().getDeclaringType();
                    String fqn = declaring.getFullyQualifiedName();
                    if (!LegacyUsage.isJdkOrCollectionLibrary(fqn)) {
                        acc.foreignUses.add(new LegacyUsage.ForeignUse(module, path, fqn,
                                "overrides `" + LegacyUsage.simpleName(fqn) + "." + mt.getName() + "` with legacy types in its signature"));
                    }
                }
                if (hasAnnotation(method.getLeadingAnnotations(), PRODUCER_ANNOTATIONS) && isConvertedType(mt.getReturnType())) {
                    acc.producedLegacyTypes.add(String.valueOf(fqn(mt.getReturnType())));
                }
            }
            boolean injected = hasAnnotation(method.getLeadingAnnotations(), INJECTION_ANNOTATIONS) ||
                               hasAnnotation(method.getLeadingAnnotations(), PRODUCER_ANNOTATIONS) ||
                               method.isConstructor() && isComponent();
            if (injected) {
                for (Statement p : method.getParameters()) {
                    if (p instanceof J.VariableDeclarations && isConvertedType(((J.VariableDeclarations) p).getType())) {
                        inject(((J.VariableDeclarations) p).getType());
                    }
                }
            }
            return super.visitMethodDeclaration(method, ctx);
        }

        private boolean isComponent() {
            J.ClassDeclaration c = getCursor().firstEnclosing(J.ClassDeclaration.class);
            return c != null && hasAnnotation(c.getLeadingAnnotations(), COMPONENT_ANNOTATIONS);
        }

        private void inject(@Nullable JavaType type) {
            String fqn = fqn(type);
            if (fqn != null) {
                acc.injectedLegacyTypes.computeIfAbsent(module, k -> Collections.synchronizedSet(new TreeSet<>())).add(fqn);
            }
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
            if (isConvertedType(multiVariable.getType())) {
                if (hasAnnotation(multiVariable.getLeadingAnnotations(), MOCK_ANNOTATIONS)) {
                    block("mocks a `" + LegacyUsage.simpleName(String.valueOf(fqn(multiVariable.getType()))) + "`");
                } else if (hasAnnotation(multiVariable.getLeadingAnnotations(), INJECTION_ANNOTATIONS)) {
                    inject(multiVariable.getType());
                }
            }
            return super.visitVariableDeclarations(multiVariable, ctx);
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            foreign(method.getMethodType(), "calls");
            return super.visitMethodInvocation(method, ctx);
        }

        @Override
        public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
            foreign(newClass.getConstructorType(), "creates");
            if (newClass.getBody() != null && isLegacyType(fqn(newClass.getType()))) {
                block("declares an anonymous `" + LegacyUsage.simpleName(String.valueOf(fqn(newClass.getType()))) + "`");
            }
            return super.visitNewClass(newClass, ctx);
        }

        private void foreign(JavaType.@Nullable Method mt, String verb) {
            if (mt == null) {
                return;
            }
            String declaring = fqn(mt.getDeclaringType());
            if (declaring == null || LegacyUsage.isDriverType(declaring) || LegacyUsage.isJdkOrCollectionLibrary(declaring)) {
                return;
            }
            if (signatureMentionsConverted(mt)) {
                acc.foreignUses.add(new LegacyUsage.ForeignUse(module, path, declaring,
                        verb + " `" + LegacyUsage.simpleName(declaring) + "." + mt.getName() + "` with legacy types in its signature"));
            }
        }

        @Override
        public J.Try.Catch visitCatch(J.Try.Catch _catch, ExecutionContext ctx) {
            J.VariableDeclarations param = _catch.getParameter().getTree();
            List<JavaType> caught = new ArrayList<>();
            if (param.getType() instanceof JavaType.MultiCatch) {
                caught.addAll(((JavaType.MultiCatch) param.getType()).getThrowableTypes());
            } else if (param.getType() != null) {
                caught.add(param.getType());
            }
            for (JavaType t : caught) {
                String fqn = fqn(t);
                if (fqn != null && LEGACY_EXCEPTIONS.contains(fqn)) {
                    block("catches `" + LegacyUsage.simpleName(fqn) + "`, which the driver 5 API reports with other exceptions");
                }
            }
            return super.visitCatch(_catch, ctx);
        }

        @Override
        public J.InstanceOf visitInstanceOf(J.InstanceOf instanceOf, ExecutionContext ctx) {
            if (instanceOf.getClazz() instanceof TypedTree && isConvertedType(((TypedTree) instanceOf.getClazz()).getType())) {
                block("tests whether a value is a `" + LegacyUsage.simpleName(String.valueOf(fqn(((TypedTree) instanceOf.getClazz()).getType()))) + "`");
            }
            return super.visitInstanceOf(instanceOf, ctx);
        }

        @Override
        public J.TypeCast visitTypeCast(J.TypeCast typeCast, ExecutionContext ctx) {
            if (isConvertedType(typeCast.getType())) {
                block("casts a value to `" + LegacyUsage.simpleName(String.valueOf(fqn(typeCast.getType()))) + "`");
            }
            return super.visitTypeCast(typeCast, ctx);
        }

        @Override
        public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
            if ("class".equals(fieldAccess.getSimpleName()) && isConvertedType(fieldAccess.getTarget().getType())) {
                block("refers to the class `" + LegacyUsage.simpleName(String.valueOf(fqn(fieldAccess.getTarget().getType()))) + "`");
            }
            return super.visitFieldAccess(fieldAccess, ctx);
        }

        @Override
        public J.MemberReference visitMemberReference(J.MemberReference memberRef, ExecutionContext ctx) {
            JavaType.Method mt = memberRef.getMethodType();
            if (isConvertedType(memberRef.getContaining().getType()) ||
                mt != null && (isConvertedType(mt.getDeclaringType()) || signatureMentionsConverted(mt))) {
                block("refers to a method of the legacy API by reference, `" + LegacyUsage.line(memberRef) + "`");
            }
            return super.visitMemberReference(memberRef, ctx);
        }
    }
}

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

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

import java.util.*;

import static java.util.Collections.emptySet;
import static org.openrewrite.aws.signing.AwsRequestSigning.*;

/**
 * A construction of awslabs' interceptor, with the signing service and region it resolves to, or the reason it cannot
 * be migrated.
 */
final class InterceptorSite {
    private static final String V1_SIGNER = "com.amazonaws.auth.AWS4Signer";
    private static final String V2_SIGNER = "software.amazon.awssdk.auth.signer.Aws4Signer";
    private static final String V2_PRESIGNER_PARAMS = "software.amazon.awssdk.auth.signer.params.Aws4PresignerParams";
    private static final String V2_REGION = "software.amazon.awssdk.regions.Region";

    final UUID id;
    final String text;
    @Nullable
    String unmigratable;

    // vc.inreach's interceptor, which takes an `AWSSigner(credentials, region, service, clock)`
    boolean inreach;

    // the hand-ported SDK v2 variant, which takes a v2 Aws4Signer and Aws4PresignerParams
    boolean presign;

    @Nullable
    Expression service;

    @Nullable
    Expression region;

    @Nullable
    Expression credentials;

    // statements configuring the signer (and presign parameters), removed with it
    final Set<UUID> remove = new HashSet<>();

    // the statement whose value is hoisted into a local when it is not simple enough to move
    @Nullable
    Statement serviceStatement;

    @Nullable
    Statement regionStatement;

    InterceptorSite(J site, Cursor cursor) {
        this.id = site.getId();
        String printed = site.printTrimmed(cursor.getParentOrThrow()).replaceAll("\\s+", " ");
        this.text = printed.length() > 200 ? printed.substring(0, 200) + "..." : printed;
    }

    private InterceptorSite unmigratable(String reason) {
        this.unmigratable = reason;
        return this;
    }

    boolean hoistService() {
        return service != null && !isSimple(service);
    }

    boolean hoistRegion() {
        return region != null && !isSimple(region);
    }

    static List<InterceptorSite> find(JavaSourceFile cu) {
        List<InterceptorSite> sites = new ArrayList<>();
        if (declaresInterceptor(cu)) {
            return sites;
        }
        new JavaIsoVisitor<List<InterceptorSite>>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, List<InterceptorSite> s) {
                JavaType supertype = classDecl.getExtends() == null ? null : classDecl.getExtends().getType();
                boolean inreach = TypeUtils.isOfClassType(supertype, INREACH_INTERCEPTOR);
                if (inreach || TypeUtils.isOfClassType(supertype, INTERCEPTOR)) {
                    InterceptorSite site = new InterceptorSite(classDecl.getName(), new Cursor(getCursor(), classDecl.getName()));
                    site.inreach = inreach;
                    s.add(site.unmigratable("Extends the interceptor, so there is no construction site to replace"));
                }
                return super.visitClassDeclaration(classDecl, s);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, List<InterceptorSite> s) {
                J.NewClass nc = super.visitNewClass(newClass, s);
                // An anonymous subclass is typed as the anonymous class, so also check the instantiated type
                JavaType type = nc.getClazz() != null && nc.getClazz().getType() != null ? nc.getClazz().getType() : nc.getType();
                if (TypeUtils.isOfClassType(type, INTERCEPTOR)) {
                    s.add(analyze(nc, getCursor()));
                } else if (TypeUtils.isOfClassType(type, INREACH_INTERCEPTOR)) {
                    s.add(inreach(nc, getCursor()));
                }
                return nc;
            }
        }.visit(cu, sites);
        return sites;
    }

    private static InterceptorSite analyze(J.NewClass nc, Cursor cursor) {
        InterceptorSite site = new InterceptorSite(nc, cursor);
        if (nc.getBody() != null) {
            return site.unmigratable("Anonymous subclass of the interceptor");
        }
        List<Expression> args = arguments(nc);
        if (args.size() == 3) {
            return v1(site, args, cursor);
        } else if (args.size() == 4) {
            return v2(site, args, cursor);
        }
        return site.unmigratable("Unrecognised constructor with " + args.size() + " arguments");
    }

    static List<Expression> arguments(J.NewClass nc) {
        List<Expression> args = new ArrayList<>();
        for (Expression arg : nc.getArguments()) {
            if (!(arg instanceof J.Empty)) {
                args.add(arg);
            }
        }
        return args;
    }

    private static InterceptorSite inreach(J.NewClass nc, Cursor cursor) {
        InterceptorSite site = new InterceptorSite(nc, cursor);
        site.inreach = true;
        List<Expression> args = arguments(nc);
        if (nc.getBody() != null || args.size() != 1) {
            return site.unmigratable("Unrecognised construction of vc.inreach's interceptor");
        }
        J.NewClass signer;
        J signerTree = nc;
        if (args.get(0) instanceof J.NewClass) {
            signer = (J.NewClass) args.get(0);
        } else if (args.get(0) instanceof J.Identifier) {
            String name = ((J.Identifier) args.get(0)).getSimpleName();
            Local local = Local.find(cursor, name);
            if (local == null || !(local.initializer() instanceof J.NewClass)) {
                return site.unmigratable("The signer `" + name + "` is not created with `new AWSSigner(..)` in the enclosing block");
            }
            if (Uses.of(local, name, emptySet(), args.get(0)).any()) {
                return site.unmigratable("The signer `" + name + "` is used elsewhere");
            }
            signer = (J.NewClass) local.initializer();
            signerTree = local.decl;
            site.remove.add(local.decl.getId());
        } else {
            return site.unmigratable("The signer is neither created inline nor a local variable");
        }
        List<Expression> signerArgs = arguments(signer);
        if (!TypeUtils.isOfClassType(signer.getType(), INREACH_SIGNER) || signerArgs.size() != 4) {
            return site.unmigratable("The signer is not `new AWSSigner(credentials, region, service, clock)`");
        }
        site.credentials = signerArgs.get(0);
        site.region = signerArgs.get(1);
        site.service = signerArgs.get(2);
        // The signer's arguments move to a different position or statement, which is only safe without side effects.
        if (!isSimple(site.service) || !isSimple(site.region)) {
            return site.unmigratable("The signer's service or region is not a simple expression");
        }
        // A clock local that only fed the signer goes with it.
        if (signerArgs.get(3) instanceof J.Identifier) {
            String clock = ((J.Identifier) signerArgs.get(3)).getSimpleName();
            Local local = Local.find(cursor, clock);
            if (local != null && !Uses.of(local, clock, emptySet(), signerTree).any()) {
                site.remove.add(local.decl.getId());
            }
        }
        return site;
    }

    private static InterceptorSite v1(InterceptorSite site, List<Expression> args, Cursor cursor) {
        if (!(args.get(1) instanceof J.Identifier)) {
            return site.unmigratable("The signer is not a local variable, so its service and region cannot be resolved");
        }
        String signer = ((J.Identifier) args.get(1)).getSimpleName();
        Local local = Local.find(cursor, signer);
        if (local == null) {
            return site.unmigratable("The signer `" + signer + "` is not a local variable of the enclosing block, so its service and region cannot be resolved");
        }
        Expression init = local.initializer();
        Map<String, Statement> setters = new HashMap<>();
        J.NewClass ctor;
        if (init instanceof J.MethodInvocation && "apply".equals(((J.MethodInvocation) init).getSimpleName()) &&
            ((J.MethodInvocation) init).getSelect() instanceof J.NewClass) {
            // Kotlin `AWS4Signer().apply { serviceName = ..; regionName = .. }`
            J.MethodInvocation apply = (J.MethodInvocation) init;
            ctor = (J.NewClass) apply.getSelect();
            List<Expression> applyArgs = apply.getArguments();
            if (applyArgs.size() != 1 || !(applyArgs.get(0) instanceof J.Lambda) ||
                !(((J.Lambda) applyArgs.get(0)).getBody() instanceof J.Block)) {
                return site.unmigratable("Unrecognised configuration block on the signer `" + signer + "`");
            }
            for (Statement s : ((J.Block) ((J.Lambda) applyArgs.get(0)).getBody()).getStatements()) {
                String property = applyProperty(s);
                if (property == null) {
                    return site.unmigratable("The signer's configuration block does more than set its service and region");
                }
                setters.put(property, s);
            }
        } else if (init instanceof J.NewClass) {
            ctor = (J.NewClass) init;
        } else {
            return site.unmigratable("The signer `" + signer + "` is not created with `new AWS4Signer()` in this method");
        }
        if (!TypeUtils.isOfClassType(ctor.getType(), V1_SIGNER) || !arguments(ctor).isEmpty()) {
            return site.unmigratable("The signer `" + signer + "` is not a default `AWS4Signer`");
        }

        Set<UUID> accounted = new HashSet<>();
        for (int i = local.declIndex + 1; i < local.siteIndex; i++) {
            Statement s = local.block.getStatements().get(i);
            String property = setterProperty(s, signer);
            if (property != null) {
                setters.put(property, s);
                accounted.add(s.getId());
                site.remove.add(s.getId());
            }
        }
        if (setters.get("service") == null) {
            return site.unmigratable("The signer `" + signer + "` has no service name set before the interceptor is created");
        }
        site.service = inlineConstant(setterValue(setters.get("service")));
        site.serviceStatement = setters.get("service");
        if (setters.get("region") != null) {
            site.region = inlineConstant(setterValue(setters.get("region")));
            site.regionStatement = setters.get("region");
        }
        site.credentials = args.get(2);

        Uses uses = Uses.of(local, signer, accounted, args.get(0), args.get(1));
        if (uses.configures) {
            return site.unmigratable("The signer `" + signer + "` is configured conditionally or after the interceptor is created");
        }
        if (uses.other) {
            // Still used for something else, so it stays as it is.
            site.remove.clear();
            if (site.hoistService() && site.serviceStatement != null && !isApplyStatement(site.serviceStatement, local) ||
                site.hoistRegion() && site.regionStatement != null && !isApplyStatement(site.regionStatement, local)) {
                return site.unmigratable("The signer `" + signer + "` is used elsewhere and its service or region is not a simple expression");
            }
        } else {
            site.remove.add(local.decl.getId());
        }
        if (isApplyStatement(site.serviceStatement, local) && site.hoistService() ||
            isApplyStatement(site.regionStatement, local) && site.hoistRegion()) {
            return site.unmigratable("The signer's service or region is not a simple expression inside its configuration block");
        }
        return site;
    }

    private static boolean isApplyStatement(@Nullable Statement s, Local local) {
        if (s == null) {
            return false;
        }
        for (Statement statement : local.block.getStatements()) {
            if (statement.getId().equals(s.getId())) {
                return false;
            }
        }
        return true;
    }

    private static InterceptorSite v2(InterceptorSite site, List<Expression> args, Cursor cursor) {
        site.presign = true;
        if (!(args.get(1) instanceof J.Identifier) || !(args.get(3) instanceof J.MethodInvocation)) {
            return site.unmigratable("Unrecognised signer or presign parameters");
        }
        J.MethodInvocation build = (J.MethodInvocation) args.get(3);
        if (!"build".equals(build.getSimpleName()) || !(build.getSelect() instanceof J.Identifier)) {
            return site.unmigratable("The presign parameters are not built from a local builder");
        }
        String signer = ((J.Identifier) args.get(1)).getSimpleName();
        String params = ((J.Identifier) build.getSelect()).getSimpleName();
        Local signerLocal = Local.find(cursor, signer);
        Local paramsLocal = Local.find(cursor, params);
        if (signerLocal == null || paramsLocal == null || signerLocal.block != paramsLocal.block) {
            return site.unmigratable("The signer and presign parameters are not local variables of the enclosing block");
        }
        if (!isStaticCall(signerLocal.initializer(), V2_SIGNER, "create") ||
            !isStaticCall(paramsLocal.initializer(), V2_PRESIGNER_PARAMS, "builder")) {
            return site.unmigratable("The signer is not `Aws4Signer.create()` or the parameters are not `Aws4PresignerParams.builder()`");
        }
        Set<UUID> accounted = new HashSet<>();
        Expression regionArg = null;
        for (int i = paramsLocal.declIndex + 1; i < paramsLocal.siteIndex; i++) {
            Statement s = paramsLocal.block.getStatements().get(i);
            if (!(s instanceof J.MethodInvocation) || !(((J.MethodInvocation) s).getSelect() instanceof J.Identifier) ||
                !params.equals(((J.Identifier) ((J.MethodInvocation) s).getSelect()).getSimpleName())) {
                continue;
            }
            J.MethodInvocation m = (J.MethodInvocation) s;
            switch (m.getSimpleName()) {
                case "signingName":
                    site.service = m.getArguments().get(0);
                    site.serviceStatement = s;
                    break;
                case "signingRegion":
                    regionArg = m.getArguments().get(0);
                    site.regionStatement = s;
                    break;
                case "awsCredentials":
                    break;
                default:
                    return site.unmigratable("The presign parameters set `" + m.getSimpleName() + "`, which has no equivalent");
            }
            accounted.add(s.getId());
            site.remove.add(s.getId());
        }
        if (site.service == null || regionArg == null) {
            return site.unmigratable("The presign parameters do not set both the signing name and region");
        }
        if (!isStaticCall(regionArg, V2_REGION, "of")) {
            return site.unmigratable("The signing region is not `Region.of(..)`");
        }
        site.region = ((J.MethodInvocation) regionArg).getArguments().get(0);
        site.credentials = args.get(2);
        if (Uses.of(signerLocal, signer, accounted, args.get(1)).any() ||
            Uses.of(paramsLocal, params, accounted, args.get(3)).any()) {
            return site.unmigratable("The signer or presign parameters are used elsewhere");
        }
        site.remove.add(signerLocal.decl.getId());
        site.remove.add(paramsLocal.decl.getId());
        if (site.hoistService() || site.hoistRegion()) {
            return site.unmigratable("The signing name or region is not a simple expression");
        }
        return site;
    }

    private static boolean isStaticCall(@Nullable Expression e, String type, String name) {
        if (!(e instanceof J.MethodInvocation)) {
            return false;
        }
        J.MethodInvocation m = (J.MethodInvocation) e;
        return name.equals(m.getSimpleName()) && m.getMethodType() != null &&
               TypeUtils.isOfClassType(m.getMethodType().getDeclaringType(), type);
    }

    private static @Nullable String applyProperty(Statement s) {
        if (s instanceof J.Assignment) {
            Expression variable = ((J.Assignment) s).getVariable();
            String name = variable instanceof J.Identifier ? ((J.Identifier) variable).getSimpleName() :
                    variable instanceof J.FieldAccess && ((J.FieldAccess) variable).getTarget() instanceof J.Identifier &&
                    "this".equals(((J.Identifier) ((J.FieldAccess) variable).getTarget()).getSimpleName()) ?
                            ((J.FieldAccess) variable).getSimpleName() : null;
            return property(name);
        }
        return null;
    }

    static @Nullable String setterProperty(Statement s, String var) {
        if (s instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) s;
            if (m.getSelect() instanceof J.Identifier && var.equals(((J.Identifier) m.getSelect()).getSimpleName()) &&
                m.getArguments().size() == 1) {
                return "setServiceName".equals(m.getSimpleName()) ? "service" :
                        "setRegionName".equals(m.getSimpleName()) ? "region" : null;
            }
        } else if (s instanceof J.Assignment && ((J.Assignment) s).getVariable() instanceof J.FieldAccess) {
            J.FieldAccess fa = (J.FieldAccess) ((J.Assignment) s).getVariable();
            if (fa.getTarget() instanceof J.Identifier && var.equals(((J.Identifier) fa.getTarget()).getSimpleName())) {
                return property(fa.getSimpleName());
            }
        }
        return null;
    }

    private static @Nullable String property(@Nullable String name) {
        return "serviceName".equals(name) ? "service" : "regionName".equals(name) ? "region" : null;
    }

    static Expression setterValue(Statement s) {
        return s instanceof J.MethodInvocation ? ((J.MethodInvocation) s).getArguments().get(0) :
                ((J.Assignment) s).getAssignment();
    }

    /**
     * Literals, names, field accesses and argument-less calls on them can move from the signer's setter to the
     * constructor without changing what is evaluated; anything else is hoisted into a local at the setter.
     */
    static boolean isSimple(Expression e) {
        if (e instanceof J.Literal || e instanceof J.Identifier) {
            return true;
        }
        if (e instanceof J.FieldAccess) {
            return isSimple(((J.FieldAccess) e).getTarget());
        }
        if (e instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) e;
            boolean noArgs = m.getArguments().isEmpty() || m.getArguments().size() == 1 && m.getArguments().get(0) instanceof J.Empty;
            return noArgs && (m.getSelect() == null || isSimple(m.getSelect()));
        }
        return false;
    }

    // v1 compile-time constants used as signing names, inlined so no v1 type remains
    private static final Map<String, String> V1_ENDPOINT_PREFIXES = new HashMap<>();

    static {
        V1_ENDPOINT_PREFIXES.put("com.amazonaws.services.elasticsearch.AWSElasticsearch", "es");
        V1_ENDPOINT_PREFIXES.put("com.amazonaws.services.opensearch.AmazonOpenSearch", "es");
        V1_ENDPOINT_PREFIXES.put("com.amazonaws.services.cloudwatch.AmazonCloudWatch", "monitoring");
        V1_ENDPOINT_PREFIXES.put("com.amazonaws.services.prometheus.AmazonPrometheus", "aps");
        V1_ENDPOINT_PREFIXES.put("com.amazonaws.services.apigateway.AmazonApiGateway", "apigateway");
    }

    private static Expression inlineConstant(Expression e) {
        JavaType.Variable field = e instanceof J.FieldAccess ? ((J.FieldAccess) e).getName().getFieldType() :
                e instanceof J.Identifier ? ((J.Identifier) e).getFieldType() : null;
        if (field != null && "ENDPOINT_PREFIX".equals(field.getName())) {
            JavaType.FullyQualified owner = TypeUtils.asFullyQualified(field.getOwner());
            String value = owner == null ? null : V1_ENDPOINT_PREFIXES.get(owner.getFullyQualifiedName());
            if (value != null) {
                return new J.Literal(Tree.randomId(), e.getPrefix(), e.getMarkers(), value, "\"" + value + "\"", null,
                        JavaType.Primitive.String);
            }
        }
        return e;
    }

    /**
     * A local variable and the block declaring it, with the index of the statement holding the construction site.
     */
    static final class Local {
        final J.Block block;
        final int declIndex;
        final int siteIndex;
        final J.VariableDeclarations decl;

        private Local(J.Block block, int declIndex, int siteIndex, J.VariableDeclarations decl) {
            this.block = block;
            this.declIndex = declIndex;
            this.siteIndex = siteIndex;
            this.decl = decl;
        }

        @Nullable
        Expression initializer() {
            return decl.getVariables().get(0).getInitializer();
        }

        static @Nullable Local find(Cursor cursor, String name) {
            J child = cursor.getValue();
            for (Cursor c = cursor.getParent(); c != null; c = c.getParent()) {
                Object value = c.getValue();
                if (value instanceof J.Block) {
                    J.Block block = (J.Block) value;
                    List<Statement> statements = block.getStatements();
                    int siteIndex = -1;
                    for (int i = 0; i < statements.size(); i++) {
                        if (statements.get(i).getId().equals(child.getId())) {
                            siteIndex = i;
                            break;
                        }
                    }
                    for (int i = siteIndex - 1; i >= 0; i--) {
                        Statement s = statements.get(i);
                        if (s instanceof J.VariableDeclarations && ((J.VariableDeclarations) s).getVariables().size() == 1 &&
                            name.equals(((J.VariableDeclarations) s).getVariables().get(0).getSimpleName())) {
                            return new Local(block, i, siteIndex, (J.VariableDeclarations) s);
                        }
                    }
                }
                if (value instanceof J.MethodDeclaration || value instanceof J.ClassDeclaration) {
                    return null;
                }
                if (value instanceof J) {
                    child = (J) value;
                }
            }
            return null;
        }
    }

    /**
     * References to a local beyond the ones the migration accounts for.
     */
    static final class Uses {
        boolean configures;
        boolean other;

        boolean any() {
            return configures || other;
        }

        static Uses of(Local local, String name, Set<UUID> accountedStatements, J... accountedTrees) {
            Set<UUID> skip = new HashSet<>(accountedStatements);
            skip.add(local.decl.getId());
            for (J t : accountedTrees) {
                skip.add(t.getId());
            }
            Uses uses = new Uses();
            J.Block rest = local.block.withStatements(local.block.getStatements().subList(local.declIndex, local.block.getStatements().size()));
            new JavaIsoVisitor<Uses>() {
                @Override
                public @Nullable J preVisit(J tree, Uses u) {
                    if (skip.contains(tree.getId())) {
                        stopAfterPreVisit();
                    }
                    return tree;
                }

                @Override
                public Statement visitStatement(Statement statement, Uses u) {
                    if (!skip.contains(statement.getId()) && setterProperty(statement, name) != null) {
                        u.configures = true;
                    }
                    return super.visitStatement(statement, u);
                }

                @Override
                public J.Identifier visitIdentifier(J.Identifier identifier, Uses u) {
                    if (name.equals(identifier.getSimpleName()) && !isMemberName(identifier)) {
                        u.other = true;
                    }
                    return identifier;
                }

                private boolean isMemberName(J.Identifier identifier) {
                    Object parent = getCursor().getParentTreeCursor().getValue();
                    return parent instanceof J.FieldAccess && ((J.FieldAccess) parent).getName() == identifier ||
                           parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getName() == identifier ||
                           parent instanceof J.VariableDeclarations.NamedVariable;
                }
            }.visit(rest, uses, new Cursor(null, Cursor.ROOT_VALUE));
            return uses;
        }
    }
}

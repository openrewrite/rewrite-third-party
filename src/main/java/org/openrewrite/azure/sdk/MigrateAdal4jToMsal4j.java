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
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.openrewrite.azure.sdk.CallRewrite.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAdal4jToMsal4j extends Recipe {

    static final String A = "com.microsoft.aad.adal4j.";
    private static final String M = "com.microsoft.aad.msal4j.";

    /**
     * {@code authority(String)} on an MSAL application builder throws it, where ADAL's token calls threw nothing checked.
     */
    private static final String[] MALFORMED_URL = {
            "java.net.MalformedURLException", "java.io.IOException", "java.lang.Exception", "java.lang.Throwable"
    };

    @Getter
    final String displayName = "Migrate ADAL4J to MSAL4J";

    @Getter
    final String description = "Rewrites token requests made through ADAL4J's `AuthenticationContext` to MSAL4J. Each " +
                               "request builds a `ConfidentialClientApplication` from what used to be its " +
                               "`ClientCredential` or `AsymmetricKeyCredential`, or a `PublicClientApplication` from its " +
                               "client id, with the context's authority, and asks for the resource's `/.default` scope. " +
                               "The client credential, username and password, on-behalf-of and refresh token flows are " +
                               "rewritten; a context becomes its authority URL and a credential the application builder " +
                               "it configures. Calls given a callback, the authorization code and device code flows, " +
                               "and requests where the `MalformedURLException` MSAL declares would not be caught or " +
                               "declared are left for `FindAzureSdkLegacyRemnants` to report, as are refresh tokens, " +
                               "which MSAL does not expose.";

    static final List<CallRewrite> RULES = Arrays.asList(
            // A context is its authority, which each request gives the application it builds
            call(A + "AuthenticationContext <constructor>(String, boolean, java.util.concurrent.ExecutorService)", "$0"),
            call(A + "AuthenticationContext getAuthority()", "$r"),

            // A credential is the builder of the confidential client application it authenticates
            call(A + "ClientCredential <constructor>(String, String)",
                    M + "ConfidentialClientApplication.builder($0, " + M + "ClientCredentialFactory.createFromSecret($1))"),
            call(A + "AsymmetricKeyCredential create(String, java.security.PrivateKey, java.security.cert.X509Certificate)",
                    M + "ConfidentialClientApplication.builder($0, " + M + "ClientCredentialFactory.createFromCertificate($1, $2))"),
            call(A + "AsymmetricKeyCredential create(String, java.io.InputStream, String)",
                    M + "ConfidentialClientApplication.builder($0, " + M + "ClientCredentialFactory.createFromCertificate($1, $2))"),
            call(A + "UserAssertion <constructor>(String)", "new " + M + "UserAssertion($0)"),
            call(A + "UserAssertion getAssertion()", "$r.getAssertion()"),

            // Client credentials
            token(A + "AuthenticationContext acquireToken(String, " + A + "ClientCredential, " + A + "AuthenticationCallback)",
                    "$1", M + "ClientCredentialParameters.builder(SCOPE0).build()", 2),
            token(A + "AuthenticationContext acquireToken(String, " + A + "AsymmetricKeyCredential, " + A + "AuthenticationCallback)",
                    "$1", M + "ClientCredentialParameters.builder(SCOPE0).build()", 2),
            // Username and password
            token(A + "AuthenticationContext acquireToken(String, String, String, String, " + A + "AuthenticationCallback)",
                    M + "PublicClientApplication.builder($1)",
                    M + "UserNamePasswordParameters.builder(SCOPE0, $2, $3.toCharArray()).build()", 4),
            // On behalf of
            token(A + "AuthenticationContext acquireToken(String, " + A + "UserAssertion, " + A + "ClientCredential, " + A + "AuthenticationCallback)",
                    "$2", M + "OnBehalfOfParameters.builder(SCOPE0, $1).build()", 3),
            token(A + "AuthenticationContext acquireToken(String, " + A + "UserAssertion, " + A + "AsymmetricKeyCredential, " + A + "AuthenticationCallback)",
                    "$2", M + "OnBehalfOfParameters.builder(SCOPE0, $1).build()", 3),
            // Refresh tokens ADAL issued, for a resource
            token(A + "AuthenticationContext acquireTokenByRefreshToken(String, " + A + "ClientCredential, String, " + A + "AuthenticationCallback)",
                    "$1", M + "RefreshTokenParameters.builder(SCOPE2, $0).build()", 3),
            token(A + "AuthenticationContext acquireTokenByRefreshToken(String, " + A + "AsymmetricKeyCredential, String, " + A + "AuthenticationCallback)",
                    "$1", M + "RefreshTokenParameters.builder(SCOPE2, $0).build()", 3),
            token(A + "AuthenticationContext acquireTokenByRefreshToken(String, String, String, " + A + "AuthenticationCallback)",
                    M + "PublicClientApplication.builder($1)", M + "RefreshTokenParameters.builder(SCOPE2, $0).build()", 3),

            // Results
            call(A + "AuthenticationResult getAccessToken()", "$r.accessToken()"),
            call(A + "AuthenticationResult getIdToken()", "$r.idToken()"),
            call(A + "AuthenticationResult getExpiresOnDate()", "$r.expiresOnDate()"),
            call(A + "AuthenticationResult getUserInfo()", "$r.account()"),
            // Every token Microsoft Entra ID issues is a bearer token
            call(A + "AuthenticationResult getAccessTokenType()", "\"Bearer\"")
                    .when(call -> call.receiver instanceof J.Identifier || call.receiver instanceof J.FieldAccess),
            call(A + "UserInfo getDisplayableId()", "$r.username()"),
            // Seconds until the token expires, which ADAL counted from when it was issued
            call(A + "AuthenticationResult getExpiresOn()",
                    "java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds($r.expiresOnDate().getTime() - System.currentTimeMillis())"),
            call(A + "AuthenticationResult getExpiresAfter()",
                    "java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds($r.expiresOnDate().getTime() - System.currentTimeMillis())"),

            // Exceptions
            call(A + "AuthenticationException <constructor>(Throwable)", "new " + M + "MsalException($0)"),
            call(A + "AuthenticationException <constructor>(String)", "new " + M + "MsalException($0, null)")
    );

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>(A + "*", false), new AzureCallRewriter(RULES));
    }

    /**
     * A token request, built from the application the request is made with and the parameters it is made with. In
     * {@code parameters}, {@code SCOPEn} is the {@code /.default} scope of the resource that argument {@code n} names.
     */
    private static CallRewrite token(String pattern, String application, String parameters, int callback) {
        return custom(pattern, (call, rewriter, ctx) -> {
            StringBuffer params = new StringBuffer();
            Matcher scope = Pattern.compile("SCOPE(\\d)").matcher(parameters);
            while (scope.find()) {
                scope.appendReplacement(params, Matcher.quoteReplacement(
                        scope(call.arg(Integer.parseInt(scope.group(1))), scope.group(1))));
            }
            scope.appendTail(params);
            if (handled(call.cursor, MALFORMED_URL)) {
                return rewriter.template(application + ".authority($r).build().acquireToken(" + params + ")", call, ctx);
            }
            // Where nothing catches or declares the MalformedURLException that MSAL's authority(String) declares, the
            // top-level class gets a helper that rethrows it unchecked: ADAL validated the authority when it made the
            // context, which is where its callers handled the exception
            J.ClassDeclaration top = topLevelClass(call.cursor);
            if (top == null || top.getType() == null || top.getKind() == J.ClassDeclaration.Kind.Type.Interface ||
                top.getKind() == J.ClassDeclaration.Kind.Type.Annotation) {
                return null;
            }
            J result = rewriter.template(application + ".authority($r).build().acquireToken(" + params + ")", call, ctx);
            if (!(result instanceof J.MethodInvocation)) {
                return null;
            }
            J.MethodInvocation build = (J.MethodInvocation) ((J.MethodInvocation) result).getSelect();
            J.MethodInvocation authority = build == null ? null : (J.MethodInvocation) build.getSelect();
            // The application is still typed as the ADAL credential it was made from, so the builder is what MSAL's
            // authority(String) returns for it
            JavaType.FullyQualified builder = authority == null || authority.getSelect() == null || authority.getMethodType() == null ? null :
                    TypeUtils.asFullyQualified(authority.getMethodType().getReturnType());
            if (builder == null || !builder.getFullyQualifiedName().startsWith("com.microsoft.aad.msal4j.")) {
                return null;
            }
            rewriter.afterVisit(new AddAuthorityHelper(top.getId(), builder.getFullyQualifiedName().replace('$', '.')));
            return withAuthorityHelper((J.MethodInvocation) result, top.getType(), builder);
        }).when(argsNull(callback));
    }

    static final String HELPER = "withAuthority";

    private static J.@Nullable ClassDeclaration topLevelClass(Cursor cursor) {
        J.ClassDeclaration top = null;
        for (Cursor c = cursor; c != null; c = c.getParent()) {
            if (c.getValue() instanceof J.ClassDeclaration) {
                top = c.getValue();
            }
        }
        return top;
    }

    /**
     * Replaces {@code app.authority(context)} in {@code app.authority(context).build().acquireToken(..)} with
     * {@code withAuthority(app, context)}, the helper {@link AddAuthorityHelper} declares.
     */
    private static J withAuthorityHelper(J.MethodInvocation acquire, JavaType.FullyQualified owner, JavaType.FullyQualified builder) {
        J.MethodInvocation build = (J.MethodInvocation) acquire.getSelect();
        J.MethodInvocation authority = (J.MethodInvocation) Objects.requireNonNull(build).getSelect();
        Expression application = Objects.requireNonNull(Objects.requireNonNull(authority).getSelect());
        Expression authorityUrl = authority.getArguments().get(0);
        JavaType.Method type = authority.getMethodType();
        if (type != null) {
            type = type.withName(HELPER)
                    .withDeclaringType(owner)
                    .withFlags(EnumSet.of(Flag.Private, Flag.Static))
                    .withParameterNames(Arrays.asList("application", "authority"))
                    .withParameterTypes(Arrays.asList(builder, authorityUrl.getType()))
                    .withReturnType(builder)
                    .withThrownExceptions(Collections.emptyList());
        }
        J.MethodInvocation helper = authority
                .withSelect(null)
                .withName(authority.getName().withSimpleName(HELPER).withType(type))
                .withMethodType(type)
                .withArguments(Arrays.asList(application.withPrefix(Space.EMPTY), authorityUrl.withPrefix(Space.SINGLE_SPACE)))
                .withPrefix(application.getPrefix());
        return acquire.withSelect(build.withSelect(helper));
    }

    /**
     * Declares the {@code withAuthority} helper for one kind of application builder in a top-level class that does not
     * have it yet.
     */
    private static class AddAuthorityHelper extends JavaIsoVisitor<ExecutionContext> {
        private final UUID classId;
        private final String builder;

        AddAuthorityHelper(UUID classId, String builder) {
            this.classId = classId;
            this.builder = builder;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            if (!classDecl.getId().equals(classId)) {
                return classDecl;
            }
            for (Statement statement : classDecl.getBody().getStatements()) {
                if (statement instanceof J.MethodDeclaration && HELPER.equals(((J.MethodDeclaration) statement).getSimpleName())) {
                    JavaType.Method helper = ((J.MethodDeclaration) statement).getMethodType();
                    if (helper != null && !helper.getParameterTypes().isEmpty() &&
                        TypeUtils.isOfClassType(helper.getParameterTypes().get(0), builder)) {
                        return classDecl;
                    }
                }
            }
            J.ClassDeclaration c = JavaTemplate.builder(
                            "private static " + builder + " " + HELPER + "(" + builder + " application, String authority) {\n" +
                            "    try {\n" +
                            "        return application.authority(authority);\n" +
                            "    } catch (java.net.MalformedURLException e) {\n" +
                            "        // ADAL's AuthenticationContext validated this authority when it was created\n" +
                            "        throw new IllegalArgumentException(e);\n" +
                            "    }\n" +
                            "}")
                    .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, AzureCallRewriter.CLASSPATH))
                    .build()
                    .apply(updateCursor(classDecl), classDecl.getBody().getCoordinates().lastStatement());
            doAfterVisit(ShortenFullyQualifiedTypeReferences.modifyOnly(c.getBody().getStatements().get(c.getBody().getStatements().size() - 1)));
            return c;
        }
    }

    /**
     * The {@code /.default} scope of a resource, which asks for the permissions the application was granted on it, as
     * ADAL's resource did. A resource ending in a slash keeps it, as Microsoft Entra ID expects.
     */
    private static String scope(Expression resource, String index) {
        if (resource instanceof J.Literal && ((J.Literal) resource).getValue() instanceof String) {
            String value = (String) ((J.Literal) resource).getValue();
            return "java.util.Collections.singleton(\"" + escape(value + "/.default") + "\")";
        }
        return "java.util.Collections.singleton($" + index + " + \"/.default\")";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

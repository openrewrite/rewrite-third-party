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

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;
import org.openrewrite.java.tree.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies {@link CallRewrite} rules to the calls they match, innermost first, so that a rule sees its receiver and
 * arguments already rewritten while the call itself still carries its legacy method type.
 */
class AzureCallRewriter extends JavaVisitor<ExecutionContext> {

    /**
     * The current Azure SDK artifacts in this module's type table, which every template is compiled against.
     */
    static final String[] CLASSPATH = {
            "azure-core-1", "azure-core-amqp-2", "azure-storage-common-12", "azure-storage-blob-12", "azure-storage-queue-12",
            "azure-storage-file-share-12", "azure-data-tables-12", "azure-identity-1", "azure-messaging-eventhubs-5",
            "azure-messaging-servicebus-7", "azure-security-keyvault-secrets-4", "azure-security-keyvault-keys-4",
            "msal4j-1", "reactor-core-3", "reactive-streams-1"
    };

    private static final Pattern TOKEN = Pattern.compile("\\$(r|\\d+)");

    private final List<CallRewrite> rules;
    private final Map<String, String> constants;
    private JavaParser.@Nullable Builder<?, ?> parser;

    AzureCallRewriter(List<CallRewrite> rules) {
        this(rules, Collections.emptyMap());
    }

    /**
     * @param constants legacy constants, as {@code fully.qualified.Type.NAME}, to the expression that replaces them
     */
    AzureCallRewriter(List<CallRewrite> rules, Map<String, String> constants) {
        this.rules = rules;
        this.constants = constants;
    }

    @Override
    public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
        return super.isAcceptable(sourceFile, ctx) && !LegacyAzureMigration.isLeftAsItIs(sourceFile, ctx);
    }

    void afterVisit(TreeVisitor<?, ExecutionContext> visitor) {
        doAfterVisit(visitor);
    }

    void removeImport(String type) {
        maybeRemoveImport(type);
    }

    JavaParser.Builder<?, ?> parser(ExecutionContext ctx) {
        if (parser == null) {
            parser = JavaParser.fromJavaVersion().classpathFromResources(ctx, CLASSPATH);
        }
        return parser;
    }

    @Override
    public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
        J j = super.visitCompilationUnit(cu, ctx);
        // The legacy types without a counterpart that only the rewritten calls used, such as request options and
        // operation contexts. Retyping changes the imports of the others.
        for (J.Import anImport : cu.getImports()) {
            if (!anImport.isStatic() && !"*".equals(anImport.getQualid().getSimpleName()) &&
                LegacyAzureTypes.isLegacy(anImport.getTypeName()) && LegacyAzureTypes.current(anImport.getTypeName()) == null) {
                maybeRemoveImport(anImport.getTypeName());
            }
        }
        return j;
    }

    @Override
    public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        J j = super.visitMethodInvocation(method, ctx);
        if (!(j instanceof J.MethodInvocation)) {
            return j;
        }
        J.MethodInvocation m = (J.MethodInvocation) j;
        JavaType.Method type = m.getMethodType();
        if (type == null || !LegacyAzureTypes.isLegacy(type.getDeclaringType())) {
            return m;
        }
        boolean isStatic = type.hasFlags(Flag.Static);
        CallRewrite.Call call = new CallRewrite.Call(m, type, isStatic ? null : m.getSelect(), m.getArguments().size() == 1 &&
                m.getArguments().get(0) instanceof J.Empty ? Collections.emptyList() : m.getArguments(), getCursor());
        return rewrite(call, ctx);
    }

    @Override
    public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
        J j = super.visitNewClass(newClass, ctx);
        if (!(j instanceof J.NewClass)) {
            return j;
        }
        J.NewClass n = (J.NewClass) j;
        JavaType.Method type = n.getConstructorType();
        if (type == null || n.getBody() != null || !LegacyAzureTypes.isLegacy(type.getDeclaringType())) {
            return n;
        }
        CallRewrite.Call call = new CallRewrite.Call(n, type, null, n.getArguments().size() == 1 &&
                n.getArguments().get(0) instanceof J.Empty ? Collections.emptyList() : n.getArguments(), getCursor());
        return rewrite(call, ctx);
    }

    @Override
    public J visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
        J j = super.visitFieldAccess(fieldAccess, ctx);
        if (!(j instanceof J.FieldAccess) || constants.isEmpty()) {
            return j;
        }
        J.FieldAccess f = (J.FieldAccess) j;
        JavaType.Variable field = f.getName().getFieldType();
        if (field == null) {
            return f;
        }
        JavaType.FullyQualified owner = TypeUtils.asFullyQualified(field.getOwner());
        if (owner == null) {
            return f;
        }
        String replacement = constants.get(owner.getFullyQualifiedName().replace('$', '.') + "." + field.getName());
        if (replacement == null) {
            return f;
        }
        J result = JavaTemplate.builder(replacement)
                .javaParser(parser(ctx))
                .build()
                .apply(getCursor(), f.getCoordinates().replace());
        doAfterVisit(ShortenFullyQualifiedTypeReferences.modifyOnly(result));
        return result;
    }

    private @Nullable J rewrite(CallRewrite.Call call, ExecutionContext ctx) {
        boolean stubbed = stubbed(call);
        for (CallRewrite rule : rules) {
            if (!matches(rule, call) || (rule.condition != null && !rule.condition.test(call)) ||
                (stubbed && rule.builder != null)) {
                continue;
            }
            if (rule.deleteStatement) {
                if (getCursor().getParentTreeCursor().getValue() instanceof J.Block) {
                    return null;
                }
                continue;
            }
            J result = rule.builder != null ? rule.builder.build(call, this, ctx) : template(rule.template, call, ctx);
            if (result != null && stubbed && (!sameCallOnReceiver(result, call) || !passesMatchersThrough(result, call))) {
                // A stubbing or verification only works on a call of the mock itself, with its argument matchers as
                // arguments, so a rewrite that wraps the call, replaces its receiver or wraps a matcher, such as
                // `send(captor.capture(), eq(key))` into a list and send options, is left for
                // FindAzureSdkLegacyRemnants to report
                return call.tree;
            }
            if (result != null) {
                return result;
            }
        }
        return call.tree;
    }

    /**
     * Whether the call is the one a Mockito stubbing or verification is about: the argument of {@code when(..)} or
     * {@code given(..)}, or a call on what {@code verify(..)}, {@code doReturn(..).when(..)} or
     * {@code then(..).should()} returned.
     */
    private static boolean stubbed(CallRewrite.Call call) {
        if (call.receiver instanceof J.MethodInvocation && isMockito(((J.MethodInvocation) call.receiver).getMethodType())) {
            return true;
        }
        Object parent = call.cursor.getParentTreeCursor().getValue();
        return parent instanceof J.MethodInvocation && isMockito(((J.MethodInvocation) parent).getMethodType()) &&
               ((J.MethodInvocation) parent).getArguments().contains(call.tree);
    }

    private static boolean isMockito(JavaType.@Nullable Method type) {
        JavaType.FullyQualified declaring = type == null ? null : type.getDeclaringType();
        return declaring != null && declaring.getFullyQualifiedName().startsWith("org.mockito.");
    }

    private static boolean sameCallOnReceiver(J result, CallRewrite.Call call) {
        if (!(result instanceof J.MethodInvocation) || call.receiver == null) {
            return false;
        }
        Expression select = ((J.MethodInvocation) result).getSelect();
        return select != null && select.getId().equals(call.receiver.getId());
    }

    /**
     * Whether each argument of the call that holds a Mockito matcher or captor is an argument of the rewritten call
     * as it was.
     */
    private static boolean passesMatchersThrough(J result, CallRewrite.Call call) {
        Set<UUID> passed = new HashSet<>();
        for (Expression arg : ((J.MethodInvocation) result).getArguments()) {
            passed.add(arg.getId());
        }
        for (J arg : call.args) {
            if (!passed.contains(arg.getId()) && new JavaIsoVisitor<AtomicBoolean>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicBoolean found) {
                    if (isMockito(method.getMethodType())) {
                        found.set(true);
                    }
                    return super.visitMethodInvocation(method, found);
                }
            }.reduce(arg, new AtomicBoolean()).get()) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(CallRewrite rule, CallRewrite.Call call) {
        if (call.tree instanceof J.MethodInvocation) {
            return rule.matcher.matches((J.MethodInvocation) call.tree);
        }
        return rule.matcher.matches(call.type);
    }

    /**
     * Replaces the call with a template naming its receiver {@code $r} and its arguments {@code $0}, {@code $1}, ...
     *
     * @return the replacement, or {@code null} when the template names a receiver the call does not have
     */
    @Nullable
    J template(@Nullable String template, CallRewrite.Call call, ExecutionContext ctx) {
        if (template == null) {
            return null;
        }
        Matcher whole = Pattern.compile("\\$(r|\\d+)").matcher(template);
        if (whole.matches()) {
            Expression e = "r".equals(whole.group(1)) ? call.receiver : call.args.get(Integer.parseInt(whole.group(1)));
            return e == null ? null : e.withPrefix(((J) call.tree).getPrefix());
        }

        Matcher tok = TOKEN.matcher(template);
        StringBuffer code = new StringBuffer();
        List<Object> params = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (tok.find()) {
            String key = tok.group(1);
            String name = "r".equals(key) ? "r" : "a" + key;
            String replacement;
            if (seen.add(key)) {
                Expression e;
                String type;
                if ("r".equals(key)) {
                    e = call.receiver;
                    if (e == null) {
                        return null;
                    }
                    type = receiverType(call);
                } else {
                    int i = Integer.parseInt(key);
                    e = call.args.get(i);
                    type = argType(call, i);
                }
                replacement = "#{" + name + ":any(" + type + ")}";
                params.add(e);
            } else {
                replacement = "#{" + name + "}";
            }
            tok.appendReplacement(code, Matcher.quoteReplacement(replacement));
        }
        tok.appendTail(code);

        J result = JavaTemplate.builder(code.toString())
                .javaParser(parser(ctx))
                .build()
                .apply(new Cursor(call.cursor.getParent(), call.tree), coordinates(call.tree), params.toArray());
        doAfterVisit(ShortenFullyQualifiedTypeReferences.modifyOnly(result));
        return result;
    }

    private static JavaCoordinates coordinates(J tree) {
        if (tree instanceof J.MethodInvocation) {
            return ((J.MethodInvocation) tree).getCoordinates().replace();
        }
        return ((J.NewClass) tree).getCoordinates().replace();
    }

    private static String receiverType(CallRewrite.Call call) {
        Expression receiver = Objects.requireNonNull(call.receiver);
        JavaType.FullyQualified own = TypeUtils.asFullyQualified(receiver.getType());
        if (own != null && LegacyAzureTypes.current(own.getFullyQualifiedName()) != null) {
            return LegacyAzureTypes.render(receiver.getType());
        }
        return LegacyAzureTypes.render(call.type.getDeclaringType());
    }

    private static String argType(CallRewrite.Call call, int i) {
        List<JavaType> params = call.type.getParameterTypes();
        Expression arg = call.args.get(i);
        if (i < params.size()) {
            JavaType param = params.get(i);
            if (call.type.hasFlags(Flag.Varargs) && i == params.size() - 1 && param instanceof JavaType.Array &&
                !(arg.getType() instanceof JavaType.Array)) {
                return LegacyAzureTypes.render(((JavaType.Array) param).getElemType());
            }
            // A legacy parameter type without a counterpart, such as an abstract credentials type, takes the type of
            // the argument, whose counterpart decides which overload of the current SDK the template calls
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(param);
            if (fq != null && LegacyAzureTypes.current(fq.getFullyQualifiedName()) == null &&
                TypeUtils.asFullyQualified(arg.getType()) != null) {
                return LegacyAzureTypes.render(arg.getType());
            }
            return LegacyAzureTypes.render(param);
        }
        return LegacyAzureTypes.render(arg.getType());
    }
}

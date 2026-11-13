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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.util.*;

public class MigrateAwsSdkV1IoAndStringUtils extends Recipe {

    private static final String IO_UTILS = "com.amazonaws.util.IOUtils";
    private static final String STRING_UTILS = "com.amazonaws.util.StringUtils";
    private static final String BASE64 = "com.amazonaws.util.Base64";
    private static final String STRING_INPUT_STREAM = "com.amazonaws.util.StringInputStream";
    private static final String V2_IO_UTILS = "software.amazon.awssdk.utils.IoUtils";
    private static final String V2_STRING_UTILS = "software.amazon.awssdk.utils.StringUtils";

    private static final MethodMatcher TO_BYTE_ARRAY = new MethodMatcher(IO_UTILS + " toByteArray(java.io.InputStream)");
    private static final MethodMatcher TO_STRING = new MethodMatcher(IO_UTILS + " toString(java.io.InputStream)");
    private static final MethodMatcher COPY = new MethodMatcher(IO_UTILS + " copy(java.io.InputStream, java.io.OutputStream, ..)");
    private static final MethodMatcher DRAIN = new MethodMatcher(IO_UTILS + " drainInputStream(java.io.InputStream)");
    private static final MethodMatcher IO_ANY = new MethodMatcher(IO_UTILS + " *(..)");

    private static final MethodMatcher IS_NULL_OR_EMPTY = new MethodMatcher(STRING_UTILS + " isNullOrEmpty(String)");
    private static final MethodMatcher HAS_VALUE = new MethodMatcher(STRING_UTILS + " hasValue(String)");
    private static final MethodMatcher JOIN = new MethodMatcher(STRING_UTILS + " join(String, ..)");
    private static final MethodMatcher SAME_IN_V2 = new MethodMatcher(STRING_UTILS + " *(String)");
    private static final MethodMatcher STRING_ANY = new MethodMatcher(STRING_UTILS + " *(..)");

    private static final MethodMatcher ENCODE_AS_STRING = new MethodMatcher(BASE64 + " encodeAsString(..)");
    private static final MethodMatcher ENCODE = new MethodMatcher(BASE64 + " encode(byte[])");
    private static final MethodMatcher DECODE = new MethodMatcher(BASE64 + " decode(..)");
    private static final MethodMatcher BASE64_ANY = new MethodMatcher(BASE64 + " *(..)");

    static final String UNMAPPED = "AWS SDK v1 utility method with no v2 or JDK equivalent";

    private static final MethodMatcher CLOSE_QUIETLY = new MethodMatcher(IO_UTILS + " closeQuietly(java.io.Closeable, ..)");
    private static final MethodMatcher RELEASE = new MethodMatcher(IO_UTILS + " release(java.io.Closeable, ..)");
    private static final MethodMatcher FROM_DATE = new MethodMatcher(STRING_UTILS + " fromDate(java.util.Date)");
    private static final MethodMatcher FROM_DATE_FORMAT = new MethodMatcher(STRING_UTILS + " fromDate(java.util.Date, String)");
    private static final String ISO_8601 = "DateTimeFormatter.ofPattern(\"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'\").withZone(ZoneOffset.UTC).format(#{any(java.util.Date)}.toInstant())";
    private static final String RFC_822 = "DateTimeFormatter.ofPattern(\"EEE, dd MMM yyyy HH:mm:ss 'GMT'\", Locale.US).withZone(ZoneOffset.UTC).format(#{any(java.util.Date)}.toInstant())";

    // StringUtils methods whose v1 body is a single JDK or v2 call; the template takes the call's arguments in order
    private static final Map<String, String> STRING_TEMPLATES = new HashMap<>();

    static {
        STRING_TEMPLATES.put("toInteger(java.lang.StringBuilder)", "Integer.valueOf(#{any(java.lang.StringBuilder)}.toString())");
        STRING_TEMPLATES.put("toString(java.lang.StringBuilder)", "#{any(java.lang.StringBuilder)}.toString()");
        STRING_TEMPLATES.put("toBoolean(java.lang.StringBuilder)", "Boolean.valueOf(#{any(java.lang.StringBuilder)}.toString())");
        for (String box : new String[]{"Integer", "Long", "Short", "Float", "Double", "Byte", "Boolean"}) {
            STRING_TEMPLATES.put("from" + box + "(java.lang." + box + ")", box + ".toString(#{any(java.lang." + box + ")})");
        }
        STRING_TEMPLATES.put("fromBigInteger(java.math.BigInteger)", "#{any(java.math.BigInteger)}.toString()");
        STRING_TEMPLATES.put("fromBigDecimal(java.math.BigDecimal)", "#{any(java.math.BigDecimal)}.toString()");
        STRING_TEMPLATES.put("toBigInteger(java.lang.String)", "new BigInteger(#{any(String)})");
        STRING_TEMPLATES.put("toBigDecimal(java.lang.String)", "new BigDecimal(#{any(String)})");
        STRING_TEMPLATES.put("fromByteBuffer(java.nio.ByteBuffer)", "BinaryUtils.toBase64(BinaryUtils.copyBytesFrom(#{any(java.nio.ByteBuffer)}))");
        STRING_TEMPLATES.put("replace(java.lang.String,java.lang.String,java.lang.String)", "#{any(String)}.replace(#{any(String)}, #{any(String)})");
        // v1 threw IllegalArgumentException for a null argument, where Collator throws NullPointerException
        STRING_TEMPLATES.put("compare(java.lang.String,java.lang.String)", "Collator.getInstance(Locale.ENGLISH).compare(#{any(String)}, #{any(String)})");
        // v1 documents this as equivalent to the regular expression form
        STRING_TEMPLATES.put("appendCompactedString(java.lang.StringBuilder,java.lang.String)", "#{any(java.lang.StringBuilder)}.append(#{any(String)}.replaceAll(\"\\\\s+\", \" \"))");
        STRING_TEMPLATES.put("beginsWithIgnoreCase(java.lang.String,java.lang.String)", "{StringUtils}.startsWithIgnoreCase(#{any(String)}, #{any(String)})");
    }

    private static final String[] TEMPLATE_IMPORTS = {"java.math.BigInteger", "java.math.BigDecimal", "java.text.Collator", "java.util.Locale",
            "java.time.ZoneOffset", "java.time.format.DateTimeFormatter", "software.amazon.awssdk.utils.BinaryUtils"};

    @Getter
    final String displayName = "Migrate AWS SDK v1 `IOUtils`, `StringUtils`, `Base64` and `StringInputStream`";

    @Getter
    final String description = "Replaces `com.amazonaws.util` helpers, which have no v2-migration mapping: `IOUtils.toByteArray/toString` become " +
                               "`InputStream.readAllBytes()` on Java 9+ and v2 `IoUtils` otherwise, `IOUtils.copy` and `drainInputStream` become v2 " +
                               "`IoUtils` (identical bodies), `StringUtils.isNullOrEmpty/hasValue/join` become v2 `StringUtils.isEmpty` and " +
                               "`String.join`, `Base64` becomes `java.util.Base64`, and `StringInputStream` becomes v2's identical class. The other " +
                               "`StringUtils` conversions, `fromDate` with a literal format and `IOUtils.closeQuietly/release` become the JDK or " +
                               "v2 `utils` calls their v1 bodies make.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return JavaOnly.check(Preconditions.or(
                        new UsesType<>(IO_UTILS, false),
                        new UsesType<>(STRING_UTILS, false),
                        new UsesType<>(BASE64, false),
                        new UsesType<>(STRING_INPUT_STREAM, false)),
                new JavaVisitor<ExecutionContext>() {
                    @Override
                    public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                        J c = super.visitCompilationUnit(cu, ctx);
                        doAfterVisit(new ChangeType(STRING_INPUT_STREAM, "software.amazon.awssdk.utils.StringInputStream", true).getVisitor());
                        return c;
                    }

                    @Override
                    public J visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                        J j = super.visitFieldAccess(fieldAccess, ctx);
                        if (j instanceof J.FieldAccess && isUtf8Constant(((J.FieldAccess) j).getName().getFieldType())) {
                            return utf8(ctx, j);
                        }
                        return j;
                    }

                    @Override
                    public J visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                        // A statically imported StringUtils.UTF8
                        if (isUtf8Constant(identifier.getFieldType()) && !(getCursor().getParentTreeCursor().getValue() instanceof J.FieldAccess) &&
                            !(getCursor().getParentTreeCursor().getValue() instanceof J.Import)) {
                            maybeRemoveImport(STRING_UTILS + ".UTF8");
                            return utf8(ctx, identifier);
                        }
                        return super.visitIdentifier(identifier, ctx);
                    }

                    private J utf8(ExecutionContext ctx, J j) {
                        maybeRemoveImport(STRING_UTILS);
                        maybeAddImport("java.nio.charset.StandardCharsets");
                        return JavaTemplate.builder("StandardCharsets.UTF_8")
                                .imports("java.nio.charset.StandardCharsets")
                                .build()
                                .apply(getCursor(), ((Expression) j).getCoordinates().replace());
                    }

                    @Override
                    public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        J j = super.visitMethodInvocation(method, ctx);
                        if (!(j instanceof J.MethodInvocation)) {
                            return j;
                        }
                        J.MethodInvocation m = (J.MethodInvocation) j;
                        boolean java9 = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class).getMarkers()
                                .findFirst(JavaVersion.class).map(v -> v.getMajorReleaseVersion() >= 9).orElse(false);
                        if (TO_BYTE_ARRAY.matches(m)) {
                            return java9 ?
                                    replace(m, ctx, "#{any(java.io.InputStream)}.readAllBytes()", null) :
                                    replace(m, ctx, ref(V2_IO_UTILS) + ".toByteArray(#{any(java.io.InputStream)})", V2_IO_UTILS);
                        }
                        if (TO_STRING.matches(m)) {
                            if (java9) {
                                maybeAddImport("java.nio.charset.StandardCharsets");
                                return replace(m, ctx, "new String(#{any(java.io.InputStream)}.readAllBytes(), StandardCharsets.UTF_8)",
                                        "java.nio.charset.StandardCharsets");
                            }
                            return replace(m, ctx, ref(V2_IO_UTILS) + ".toUtf8String(#{any(java.io.InputStream)})", V2_IO_UTILS);
                        }
                        if (COPY.matches(m)) {
                            return replace(m, ctx, ref(V2_IO_UTILS) + ".copy(#{any(java.io.InputStream)}, #{any(java.io.OutputStream)}" +
                                                   (m.getArguments().size() == 3 ? ", #{any(long)})" : ")"), V2_IO_UTILS);
                        }
                        if (DRAIN.matches(m)) {
                            return replace(m, ctx, ref(V2_IO_UTILS) + ".drainInputStream(#{any(java.io.InputStream)})", V2_IO_UTILS);
                        }
                        if (IS_NULL_OR_EMPTY.matches(m)) {
                            return replace(m, ctx, ref(V2_STRING_UTILS) + ".isEmpty(#{any(String)})", V2_STRING_UTILS);
                        }
                        if (HAS_VALUE.matches(m)) {
                            return replace(m, ctx, "!" + ref(V2_STRING_UTILS) + ".isEmpty(#{any(String)})", V2_STRING_UTILS);
                        }
                        if (JOIN.matches(m)) {
                            StringJoiner args = new StringJoiner(", ");
                            for (Expression ignored : m.getArguments()) {
                                args.add("#{any()}");
                            }
                            maybeRemoveImport(STRING_UTILS);
                            return JavaTemplate.apply("String.join(" + args + ")", getCursor(), m.getCoordinates().replace(), m.getArguments().toArray());
                        }
                        if (SAME_IN_V2.matches(m) && ("trim".equals(m.getSimpleName()) ||
                                                      "lowerCase".equals(m.getSimpleName()) || "upperCase".equals(m.getSimpleName()))) {
                            return replace(m, ctx, ref(V2_STRING_UTILS) + "." + m.getSimpleName() + "(#{any(String)})", V2_STRING_UTILS);
                        }
                        if (ENCODE_AS_STRING.matches(m) && m.getArguments().size() == 1 && isByteArray(m.getArguments().get(0))) {
                            return replace(m, ctx, "Base64.getEncoder().encodeToString(#{any(byte[])})", "java.util.Base64");
                        }
                        if (ENCODE.matches(m)) {
                            return replace(m, ctx, "Base64.getEncoder().encode(#{any(byte[])})", "java.util.Base64");
                        }
                        if (DECODE.matches(m)) {
                            String type = TypeUtils.isString(m.getArguments().get(0).getType()) ? "String" : "byte[]";
                            return replace(m, ctx, "Base64.getDecoder().decode(#{any(" + type + ")})", "java.util.Base64");
                        }
                        if (CLOSE_QUIETLY.matches(m) || RELEASE.matches(m)) {
                            // v2's closeQuietly takes an SLF4J logger, so v1's debug message on a failed close is not logged
                            return template(m, ctx, ref(V2_IO_UTILS) + ".closeQuietly(#{any(java.io.Closeable)}, null)", m.getArguments().get(0));
                        }
                        if (FROM_DATE.matches(m)) {
                            return template(m, ctx, ISO_8601, m.getArguments().get(0));
                        }
                        if (FROM_DATE_FORMAT.matches(m) && m.getArguments().get(1) instanceof J.Literal &&
                            ((J.Literal) m.getArguments().get(1)).getValue() instanceof String) {
                            String format = ((String) ((J.Literal) m.getArguments().get(1)).getValue()).toLowerCase(Locale.ROOT);
                            Expression date = m.getArguments().get(0);
                            switch (format) {
                                case "iso8601":
                                    return template(m, ctx, ISO_8601, date);
                                case "rfc822":
                                    return template(m, ctx, RFC_822, date);
                                case "unixtimestamp":
                                    return template(m, ctx, "BigDecimal.valueOf(#{any(java.util.Date)}.getTime()).scaleByPowerOfTen(-3).toPlainString()", date);
                                case "unixtimestampinmillis":
                                    return template(m, ctx, "Long.toString(#{any(java.util.Date)}.getTime())", date);
                                default:
                            }
                        }
                        if (STRING_ANY.matches(m) && m.getMethodType() != null) {
                            if ("fromString".equals(m.getSimpleName()) && m.getArguments().size() == 1) {
                                maybeRemoveImport(STRING_UTILS);
                                return m.getArguments().get(0).withPrefix(m.getPrefix());
                            }
                            if ("findFirstOccurrence".equals(m.getSimpleName())) {
                                StringJoiner args = new StringJoiner(", ");
                                for (Expression ignored : m.getArguments()) {
                                    args.add("#{any()}");
                                }
                                return template(m, ctx, ref(V2_STRING_UTILS) + ".findFirstOccurrence(" + args + ")", m.getArguments().toArray());
                            }
                            String code = STRING_TEMPLATES.get(m.getSimpleName() + "(" + String.join(",", parameterTypes(m.getMethodType())) + ")");
                            if (code != null) {
                                return template(m, ctx, code.replace("{StringUtils}", ref(V2_STRING_UTILS)), m.getArguments().toArray());
                            }
                        }
                        if (BASE64_ANY.matches(m) && "encodeAsString".equals(m.getSimpleName())) {
                            StringJoiner bytes = new StringJoiner(", ");
                            for (Expression ignored : m.getArguments()) {
                                bytes.add("#{any(byte)}");
                            }
                            return template(m, ctx, "Base64.getEncoder().encodeToString(new byte[]{" + bytes + "})", m.getArguments().toArray());
                        }
                        if (IO_ANY.matches(m) || STRING_ANY.matches(m) || BASE64_ANY.matches(m)) {
                            return SearchResult.found(m, UNMAPPED);
                        }
                        return m;
                    }

                    // Use the simple name unless the file already imports another class with that name
                    private String ref(String fqn) {
                        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
                        for (J.Import anImport : getCursor().firstEnclosingOrThrow(J.CompilationUnit.class).getImports()) {
                            if (!anImport.isStatic() && simple.equals(anImport.getClassName()) &&
                                !anImport.getTypeName().startsWith("com.amazonaws.util.") && !fqn.equals(anImport.getTypeName())) {
                                return fqn;
                            }
                        }
                        return simple;
                    }

                    private J replace(J.MethodInvocation m, ExecutionContext ctx, String template, @Nullable String importFqn) {
                        JavaType.Method type = m.getMethodType();
                        if (type != null) {
                            maybeRemoveImport(type.getDeclaringType().getFullyQualifiedName());
                        }
                        JavaTemplate.Builder builder = JavaTemplate.builder(template).javaParser(AwsV2Parser.parser(ctx));
                        if (importFqn != null && !template.startsWith(importFqn)) {
                            builder.imports(importFqn);
                            maybeAddImport(importFqn);
                        }
                        return builder.build().apply(getCursor(), m.getCoordinates().replace(), m.getArguments().toArray());
                    }

                    private J template(J.MethodInvocation m, ExecutionContext ctx, String code, Object... params) {
                        JavaType.Method type = m.getMethodType();
                        if (type != null) {
                            maybeRemoveImport(type.getDeclaringType().getFullyQualifiedName());
                        }
                        List<String> imports = new ArrayList<>();
                        for (String fqn : TEMPLATE_IMPORTS) {
                            if (code.contains(fqn.substring(fqn.lastIndexOf('.') + 1) + ".") ||
                                code.contains("new " + fqn.substring(fqn.lastIndexOf('.') + 1) + "(")) {
                                imports.add(fqn);
                                maybeAddImport(fqn);
                            }
                        }
                        if (code.startsWith("Base64.")) {
                            imports.add("java.util.Base64");
                            maybeAddImport("java.util.Base64");
                        }
                        for (String v2 : new String[]{V2_IO_UTILS, V2_STRING_UTILS}) {
                            if (code.startsWith(v2.substring(v2.lastIndexOf('.') + 1) + ".")) {
                                imports.add(v2);
                                maybeAddImport(v2);
                            }
                        }
                        return JavaTemplate.builder(code)
                                .imports(imports.toArray(new String[0]))
                                .javaParser(AwsV2Parser.parser(ctx))
                                .build()
                                .apply(getCursor(), m.getCoordinates().replace(), params);
                    }

                    private List<String> parameterTypes(JavaType.Method type) {
                        List<String> names = new ArrayList<>();
                        for (JavaType p : type.getParameterTypes()) {
                            names.add(p instanceof JavaType.FullyQualified ? ((JavaType.FullyQualified) p).getFullyQualifiedName() : p.toString());
                        }
                        return names;
                    }

                    private boolean isByteArray(Expression e) {
                        return e.getType() instanceof JavaType.Array &&
                               ((JavaType.Array) e.getType()).getElemType() == JavaType.Primitive.Byte;
                    }
                });
    }

    private static boolean isUtf8Constant(JavaType.@Nullable Variable fieldType) {
        return fieldType != null && "UTF8".equals(fieldType.getName()) && TypeUtils.isOfClassType(fieldType.getOwner(), STRING_UTILS);
    }
}

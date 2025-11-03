/*
 * Copyright 2025 the original author or authors.
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
package org.openrewrite.java.internal.parser;

import org.jspecify.annotations.Nullable;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.internal.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class InlineMethodCallsRecipeGenerator {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: InlineMethodCallsRecipeGenerator <input-tsv-path> <artifactId>");
            System.exit(1);
        }
        generate(args[0]);
    }

    static void generate(String artifactId) {
        List<InlineMeMethod> inlineMethods = new ArrayList<>();

        TypeTable.Reader reader = new TypeTable.Reader(new InMemoryExecutionContext());
        try (InputStream is = ClassLoader.getSystemResourceAsStream(TypeTable.DEFAULT_RESOURCE_PATH); InputStream inflate = new GZIPInputStream(is)) {
            TypeTable.Reader.Options options = TypeTable.Reader.Options.builder()
              .artifactMatcher(artifactIdVersion -> artifactIdVersion.startsWith(artifactId + '-'))
              .build();
            reader.parseTsvAndProcess(inflate, options, (gav, classes, nestedTypes) -> {
                // Process each class in this GAV
                for (TypeTable.ClassDefinition classDef : classes.values()) {
                    // Process each member (method/constructor) in the class
                    for (TypeTable.Member member : classDef.getMembers()) {
                        // Check if member has @InlineMe annotation
                        String annotations = member.getAnnotations();
                        if (annotations != null && annotations.contains("InlineMe")) {
                            InlineMeMethod inlineMethod = extractInlineMeMethod(gav, classDef, member);
                            if (inlineMethod != null) {
                                inlineMethods.add(inlineMethod);
                            }
                        }
                    }
                }
            });

            generateYamlRecipes(inlineMethods);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static @Nullable InlineMeMethod extractInlineMeMethod(
      TypeTable.GroupArtifactVersion gav,
      TypeTable.ClassDefinition classDef,
      TypeTable.Member member) {
        try {
            // Parse the annotations to find @InlineMe
            List<AnnotationDeserializer.AnnotationInfo> annotations =
              AnnotationDeserializer.parseAnnotations(requireNonNull(member.getAnnotations()));
            for (AnnotationDeserializer.AnnotationInfo annotation : annotations) {
                if (!annotation.getDescriptor().endsWith("/InlineMe;")) {
                    continue;
                }

                List<AnnotationDeserializer.AttributeInfo> attributes = annotation.getAttributes();
                if (attributes == null) {
                    continue;
                }

                // Extract annotation values
                String replacement = null;
                List<String> imports = new ArrayList<>();
                List<String> staticImports = new ArrayList<>();

                for (AnnotationDeserializer.AttributeInfo attr : attributes) {
                    switch (attr.getName()) {
                        case "replacement":
                            replacement = (String) attr.getValue();
                            break;
                        case "imports":
                            if (attr.getValue() instanceof Object[]) {
                                for (Object imp : (Object[]) attr.getValue()) {
                                    imports.add((String) imp);
                                }
                            }
                            break;
                        case "staticImports":
                            if (attr.getValue() instanceof Object[]) {
                                for (Object imp : (Object[]) attr.getValue()) {
                                    staticImports.add((String) imp);
                                }
                            }
                            break;
                    }
                }

                if (replacement != null) {
                    // Build the method pattern
                    String methodPattern = buildMethodPattern(classDef, member);
                    String classpathResource = gav.getArtifactId() + "-" + gav.getVersion().substring(0, gav.getVersion().indexOf('.'));
                    return new InlineMeMethod(
                      gav,
                      methodPattern,
                      replacement,
                      imports,
                      staticImports,
                      classpathResource);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse annotations for " + classDef.getName() + "." + member.getName() + ": " + e.getMessage());
        }

        return null;
    }

    private static String buildMethodPattern(TypeTable.ClassDefinition classDef, TypeTable.Member member) {
        String className = classDef.getName().replace('/', '.');
        String methodName = member.getName();

        // For constructors, use the class name
        if ("<init>".equals(methodName)) {
            methodName = className.substring(className.lastIndexOf('.') + 1);
        }

        // Parse method descriptor to extract parameter types
        String descriptor = member.getDescriptor();
        String paramPattern = parseMethodParameters(descriptor);

        return className + " " + methodName + paramPattern;
    }

    private static String parseMethodParameters(String descriptor) {
        if (!descriptor.startsWith("(")) {
            return "()";
        }

        List<String> paramTypes = new ArrayList<>();
        int i = 1; // Skip opening '('
        while (i < descriptor.length() && descriptor.charAt(i) != ')') {
            String type = parseType(descriptor, i);
            paramTypes.add(type);
            i += getTypeLength(descriptor, i);
        }

        if (paramTypes.isEmpty()) {
            return "()";
        }
        return "(" + String.join(", ", paramTypes) + ")";
    }

    private static String parseType(String descriptor, int start) {
        char c = descriptor.charAt(start);
        return switch (c) {
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'S' -> "short";
            case 'Z' -> "boolean";
            case 'V' -> "void";
            case 'L' -> {
                // Object type - extract class name
                int semicolon = descriptor.indexOf(';', start);
                String className = descriptor.substring(start + 1, semicolon);
                yield className.replace('/', '.');
            }
            case '[' -> {
                // Array type
                String elementType = parseType(descriptor, start + 1);
                yield elementType + "[]";
            }
            default -> "Object"; // Fallback
        };
    }

    private static int getTypeLength(String descriptor, int start) {
        char c = descriptor.charAt(start);
        return switch (c) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'V' -> 1;
            // Object type - find the semicolon
            case 'L' -> descriptor.indexOf(';', start) - start + 1;
            // Array type - recurse for element type
            case '[' -> 1 + getTypeLength(descriptor, start + 1);
            default -> 1;
        };
    }

    private static void generateYamlRecipes(List<InlineMeMethod> methods) throws IOException {
        InlineMeMethod firstMethod = methods.getFirst();
        TypeTable.GroupArtifactVersion gav = firstMethod.gav();
        String moduleName = Arrays.stream(gav.getArtifactId().split("-"))
          .map(StringUtils::capitalize)
          .collect(joining());
        Path outputPath = Path.of("src/main/resources/META-INF/rewrite/inline-%s-methods.yml".formatted(firstMethod.classpathResource));

        StringBuilder yaml = new StringBuilder();
        yaml.append("#\n");
        yaml.append("# Recipes generated for `@InlineMe` annotated methods in `")
          .append(gav.getGroupId()).append(":")
          .append(gav.getArtifactId()).append(":")
          .append(gav.getVersion()).append("`\n");
        yaml.append("#\n\n");

        yaml.append("type: specs.openrewrite.org/v1beta/recipe\n");
        yaml.append("name: ").append(gav.getGroupId()).append(".Inline").append(moduleName).append("Methods").append("\n");
        yaml.append("displayName: Inline `").append(gav.getArtifactId()).append("` methods annotated with `@InlineMe`\n");
        yaml.append("description: >-\n");
        yaml.append("  Automatically generated recipes to inline method calls based on `@InlineMe` annotations\n");
        yaml.append("  discovered in the type table.\n");
        yaml.append("recipeList:\n");

        for (InlineMeMethod method : methods) {
            yaml.append("  - org.openrewrite.java.InlineMethodCalls:\n");
            yaml.append("      methodPattern: '").append(escapeYaml(method.methodPattern)).append("'\n");
            yaml.append("      replacement: '").append(escapeYaml(method.replacement)).append("'\n");

            if (!method.imports.isEmpty()) {
                yaml.append("      imports:\n");
                for (String imp : method.imports) {
                    yaml.append("        - '").append(escapeYaml(imp)).append("'\n");
                }
            }

            if (!method.staticImports.isEmpty()) {
                yaml.append("      staticImports:\n");
                for (String imp : method.staticImports) {
                    yaml.append("        - '").append(escapeYaml(imp)).append("'\n");
                }
            }

            yaml.append("      classpathFromResources:\n");
            yaml.append("        - '").append(escapeYaml(method.classpathResource)).append("'\n");
        }

        Files.write(outputPath, yaml.toString().getBytes());
        System.out.println("Generated " + methods.size() + " inline recipes to " + outputPath);
    }

    private static String escapeYaml(String value) {
        // Escape single quotes by doubling them
        return value.replace("'", "''");
    }

    private record InlineMeMethod(
      TypeTable.GroupArtifactVersion gav,
      String methodPattern,
      String replacement,
      List<String> imports,
      List<String> staticImports,
      String classpathResource) {
    }
}

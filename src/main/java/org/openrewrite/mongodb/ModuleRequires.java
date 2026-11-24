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

import org.openrewrite.SourceFile;
import org.openrewrite.text.PlainText;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Has a {@code module-info.java}, which the Java parser leaves as plain text, require the modules of driver 5 in place of
 * those of driver 3: the automatic module {@code mongo.java.driver} of the {@code mongo-java-driver} jar or
 * {@code mongodb.driver} of the {@code mongodb-driver} jar, and the names drawn from the file names of the parts of the
 * driver before 3.8, which declared none. Only the modules of the driver 5 API are required, since no module can read
 * the legacy API of driver 5, whose package {@code com.mongodb} is split between {@code mongodb-driver-legacy} and
 * {@code mongodb-driver-core}; a repository with a module that keeps the legacy API stays on driver 3.
 */
final class ModuleRequires {

    private static final Pattern REQUIRES = Pattern.compile(
            "(?<![\\w.$])requires\\s+((?:(?:transitive|static)\\s+)*)([\\w.]+)\\s*;");

    private static final Pattern DRIVER_MODULE = Pattern.compile(
            "mongo\\.java\\.driver|mongodb\\.driver(\\.[\\w.]+)?|org\\.mongodb\\.[\\w.]+|bson");

    private static final List<String> DRIVER_5 = Arrays.asList("org.mongodb.driver.sync.client", "org.mongodb.driver.core", "org.mongodb.bson");

    private static final Map<String, List<String>> RENAMED = new HashMap<>();

    static {
        RENAMED.put("bson", Collections.singletonList("org.mongodb.bson"));
        RENAMED.put("mongodb.driver.core", Collections.singletonList("org.mongodb.driver.core"));
        RENAMED.put("mongodb.driver.sync.client", Collections.singletonList("org.mongodb.driver.sync.client"));
    }

    private ModuleRequires() {
    }

    static boolean isDescriptor(SourceFile sf) {
        return "module-info.java".equals(String.valueOf(sf.getSourcePath().getFileName()));
    }

    static boolean requiresDriver(PlainText descriptor) {
        Matcher m = REQUIRES.matcher(code(descriptor.getText()));
        while (m.find()) {
            if (DRIVER_MODULE.matcher(m.group(2)).matches()) {
                return true;
            }
        }
        return false;
    }

    static PlainText requireDriver5(PlainText descriptor) {
        String text = descriptor.getText();
        String code = code(text);
        Set<String> required = new HashSet<>();
        Matcher existing = REQUIRES.matcher(code);
        while (existing.find()) {
            required.add(existing.group(2));
        }
        Matcher m = REQUIRES.matcher(code);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String name = m.group(2);
            List<String> replacements = "mongo.java.driver".equals(name) || "mongodb.driver".equals(name) ? DRIVER_5 : RENAMED.get(name);
            if (replacements == null) {
                continue;
            }
            String modifiers = m.group(1).trim().isEmpty() ? "" : String.join(" ", m.group(1).trim().split("\\s+")) + " ";
            List<String> clauses = new ArrayList<>();
            for (String r : replacements) {
                if (required.add(r)) {
                    clauses.add("requires " + modifiers + r + ";");
                }
            }
            int lineStart = text.lastIndexOf('\n', m.start() - 1) + 1;
            int lineEnd = text.indexOf('\n', m.end());
            String eol = lineEnd < 0 ? "" : lineEnd > 0 && text.charAt(lineEnd - 1) == '\r' ? "\r\n" : "\n";
            int contentEnd = lineEnd < 0 ? text.length() : lineEnd - eol.length() + 1;
            boolean startsLine = text.substring(lineStart, m.start()).trim().isEmpty();
            changed = true;
            if (startsLine && clauses.isEmpty() && text.substring(m.end(), contentEnd).trim().isEmpty()) {
                // Driver 5's modules are all required already, so the line goes
                out.append(text, last, lineStart);
                last = lineEnd < 0 ? text.length() : lineEnd + 1;
                continue;
            }
            int end = m.end();
            if (clauses.isEmpty()) {
                while (end < contentEnd && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
                    end++;
                }
            }
            // A clause that starts its line is replaced by a line for each module, others in place
            String lineBreak = !eol.isEmpty() ? eol : text.contains("\r\n") ? "\r\n" : "\n";
            String separator = startsLine ? lineBreak + text.substring(lineStart, m.start()) : " ";
            out.append(text, last, m.start()).append(String.join(separator, clauses));
            last = end;
        }
        if (!changed) {
            return descriptor;
        }
        out.append(text, last, text.length());
        return descriptor.withText(out.toString());
    }

    /**
     * The text with each comment and literal blanked out, line breaks aside, so that a clause found in it is one at the
     * same place in the text.
     */
    static String code(String text) {
        char[] c = text.toCharArray();
        int i = 0;
        while (i < c.length) {
            if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '/') {
                while (i < c.length && c[i] != '\n' && c[i] != '\r') {
                    c[i++] = ' ';
                }
            } else if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '*') {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? c.length : end + 2;
                for (; i < end; i++) {
                    if (c[i] != '\n' && c[i] != '\r') {
                        c[i] = ' ';
                    }
                }
            } else if (text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                end = end < 0 ? c.length : end + 3;
                for (; i < end; i++) {
                    if (c[i] != '\n' && c[i] != '\r') {
                        c[i] = ' ';
                    }
                }
            } else if (c[i] == '"' || c[i] == '\'') {
                char quote = c[i];
                c[i++] = ' ';
                while (i < c.length && c[i] != quote && c[i] != '\n') {
                    if (c[i] == '\\' && i + 1 < c.length) {
                        c[i++] = ' ';
                    }
                    c[i++] = ' ';
                }
                if (i < c.length && c[i] == quote) {
                    c[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(c);
    }
}

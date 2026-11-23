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
            "(?m)^([ \\t]*)requires\\s+((?:(?:transitive|static)\\s+)*)([\\w.]+)\\s*;[ \\t]*(\\r?\\n)?");

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
        Matcher m = REQUIRES.matcher(descriptor.getText());
        while (m.find()) {
            if (DRIVER_MODULE.matcher(m.group(3)).matches()) {
                return true;
            }
        }
        return false;
    }

    static PlainText requireDriver5(PlainText descriptor) {
        String text = descriptor.getText();
        Set<String> required = new HashSet<>();
        Matcher existing = REQUIRES.matcher(text);
        while (existing.find()) {
            required.add(existing.group(3));
        }
        Matcher m = REQUIRES.matcher(text);
        StringBuffer out = new StringBuffer();
        boolean changed = false;
        while (m.find()) {
            String name = m.group(3);
            List<String> replacements = "mongo.java.driver".equals(name) || "mongodb.driver".equals(name) ? DRIVER_5 : RENAMED.get(name);
            if (replacements == null) {
                continue;
            }
            String eol = m.group(4) == null ? "" : m.group(4);
            StringBuilder lines = new StringBuilder();
            for (String r : replacements) {
                if (required.add(r)) {
                    lines.append(m.group(1)).append("requires ").append(m.group(2)).append(r).append(';').append(eol.isEmpty() ? "\n" : eol);
                }
            }
            String replacement = lines.length() == 0 ? "" : eol.isEmpty() ? lines.substring(0, lines.length() - 1) : lines.toString();
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            changed = true;
        }
        if (!changed) {
            return descriptor;
        }
        m.appendTail(out);
        return descriptor.withText(out.toString());
    }
}

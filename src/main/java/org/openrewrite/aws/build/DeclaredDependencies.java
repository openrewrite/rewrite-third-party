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
package org.openrewrite.aws.build;

import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.SourceFile;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.tree.Dependency;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.xml.tree.Xml;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;

/**
 * The dependencies a build file declares as it reads now, after earlier recipes in the run edited it.
 * <p>
 * Build markers are resolved before the run and are not refreshed in other modules when a root pom, a root
 * {@code subprojects}/{@code allprojects} block or a version catalog changes, so remnant searches that read them
 * report dependencies the run already replaced. Searching the declarations instead reports each remnant once, in the
 * file that declares it, which is where it has to be fixed.
 */
public final class DeclaredDependencies {

    private static final Pattern COORDINATE = Pattern.compile("^([\\w.\\-]+):([\\w.\\-]+)(?::([^:@\\s]*))?(?::[\\w.\\-]+)?(?:@\\w+)?$");
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern TEMPLATE_COORDINATE = Pattern.compile("^([\\w.\\-]+):([\\w.\\-]+):");
    private static final Pattern TOML_SECTION = Pattern.compile("^\\s*\\[([\\w.\\-]+)]\\s*$");
    private static final Pattern TOML_ENTRY = Pattern.compile("^\\s*([\\w.\\-]+)\\s*=\\s*(.+?)\\s*$");
    private static final Pattern TOML_STRING = Pattern.compile("^\"([^\"]*)\"$");
    private static final Pattern TOML_FIELD = Pattern.compile("([\\w.]+)\\s*=\\s*\"([^\"]*)\"");

    private DeclaredDependencies() {
    }

    /**
     * A declaration of a dependency, BOM or catalog entry.
     */
    @Value
    public static class Declaration {
        /**
         * The tree to mark, or the source file itself for files that are only read as text.
         */
        UUID treeId;
        String groupId;
        String artifactId;

        /**
         * The version as written, with properties and catalog version references resolved; {@code null} when the
         * declaration has none or it cannot be resolved from the file.
         */
        @Nullable
        String version;

        /**
         * The version exactly as written, for comparison with what the build marker resolved.
         */
        @Nullable
        String rawVersion;

        /**
         * Managed versions, imported BOMs, platforms and catalog entries, which are not dependencies by themselves.
         */
        boolean managed;

        public String coordinates() {
            return groupId + ":" + artifactId + (version == null ? "" : ":" + version);
        }
    }

    public static boolean isBuildFile(SourceFile sf) {
        String path = sf.getSourcePath().toString().replace('\\', '/');
        String name = path.substring(path.lastIndexOf('/') + 1);
        return "pom.xml".equals(name) || name.endsWith(".gradle") || name.endsWith(".gradle.kts") ||
               name.endsWith(".versions.toml");
    }

    public static List<Declaration> find(SourceFile sf) {
        if (!isBuildFile(sf)) {
            return emptyList();
        }
        if (sf instanceof Xml.Document) {
            return maven((Xml.Document) sf);
        }
        if (sf instanceof JavaSourceFile) {
            return gradle((JavaSourceFile) sf);
        }
        return toml(sf);
    }

    /**
     * What the declaration brought in when the build was resolved, if it reads the same as it did then; empty when it
     * changed, since the marker no longer describes it.
     */
    public static List<ResolvedDependency> broughtIn(SourceFile sf, Declaration d) {
        if (d.isManaged()) {
            return emptyList();
        }
        List<ResolvedDependency> direct = new ArrayList<>();
        MavenResolutionResult mrr = sf.getMarkers().findFirst(MavenResolutionResult.class).orElse(null);
        GradleProject gp = sf.getMarkers().findFirst(GradleProject.class).orElse(null);
        if (mrr != null) {
            boolean unchanged = false;
            for (Dependency requested : mrr.getPom().getRequested().getDependencies()) {
                if (d.getArtifactId().equals(requested.getArtifactId()) &&
                    Objects.equals(d.getRawVersion(), requested.getVersion())) {
                    unchanged = true;
                }
            }
            if (!unchanged) {
                return emptyList();
            }
            for (List<ResolvedDependency> scope : mrr.getDependencies().values()) {
                for (ResolvedDependency r : scope) {
                    if (r.isDirect() && d.getGroupId().equals(r.getGroupId()) && d.getArtifactId().equals(r.getArtifactId())) {
                        direct.add(r);
                    }
                }
            }
        } else if (gp != null) {
            for (GradleDependencyConfiguration conf : gp.getConfigurations()) {
                Dependency requested = conf.findRequestedDependency(d.getGroupId(), d.getArtifactId());
                if (requested == null || !Objects.equals(d.getRawVersion(), requested.getVersion())) {
                    continue;
                }
                ResolvedDependency r = conf.findResolvedDependency(d.getGroupId(), d.getArtifactId());
                if (r != null) {
                    direct.add(r);
                }
            }
        }
        Map<String, ResolvedDependency> all = new LinkedHashMap<>();
        Deque<ResolvedDependency> queue = new ArrayDeque<>();
        for (ResolvedDependency r : direct) {
            queue.addAll(r.getDependencies());
        }
        while (!queue.isEmpty()) {
            ResolvedDependency r = queue.poll();
            if (all.putIfAbsent(r.getGroupId() + ":" + r.getArtifactId() + ":" + r.getVersion(), r) == null) {
                queue.addAll(r.getDependencies());
            }
        }
        return new ArrayList<>(all.values());
    }

    /**
     * Marks the trees of the given declarations; a declaration of the file itself marks the file.
     */
    @SuppressWarnings("unchecked")
    public static <T extends SourceFile> T mark(T sf, Map<UUID, String> descriptions) {
        if (descriptions.isEmpty()) {
            return sf;
        }
        String own = descriptions.get(sf.getId());
        if (own != null) {
            return SearchResult.found(sf, own);
        }
        if (sf instanceof Xml.Document) {
            return (T) new MavenIsoVisitor<Integer>() {
                @Override
                public Xml.Tag visitTag(Xml.Tag tag, Integer p) {
                    Xml.Tag t = super.visitTag(tag, p);
                    String description = descriptions.get(t.getId());
                    return description == null ? t : SearchResult.found(t, description);
                }
            }.visitNonNull(sf, 0);
        }
        return (T) new JavaVisitor<Integer>() {
            @Override
            public @Nullable J postVisit(J tree, Integer p) {
                String description = descriptions.get(tree.getId());
                return description == null ? tree : SearchResult.found(tree, description);
            }
        }.visitNonNull(sf, 0);
    }

    private static List<Declaration> maven(Xml.Document pom) {
        Map<String, String> properties = new HashMap<>();
        MavenResolutionResult mrr = pom.getMarkers().findFirst(MavenResolutionResult.class).orElse(null);
        if (mrr != null) {
            properties.putAll(mrr.getPom().getProperties());
            properties.put("project.version", mrr.getPom().getVersion());
            properties.put("project.groupId", mrr.getPom().getGroupId());
        }
        Xml.Tag root = pom.getRoot();
        root.getChild("properties").ifPresent(p -> {
            for (Xml.Tag property : p.getChildren()) {
                property.getValue().ifPresent(v -> properties.put(property.getName(), v.trim()));
            }
        });
        List<Declaration> found = new ArrayList<>();
        new MavenIsoVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer p) {
                if ("dependency".equals(tag.getName()) && getCursor().getParentTreeCursor().getValue() instanceof Xml.Tag &&
                    "dependencies".equals(((Xml.Tag) getCursor().getParentTreeCursor().getValue()).getName())) {
                    String groupId = resolve(tag.getChildValue("groupId").orElse(null), properties);
                    String artifactId = resolve(tag.getChildValue("artifactId").orElse(null), properties);
                    String rawVersion = tag.getChildValue("version").map(String::trim).orElse(null);
                    if (groupId != null && artifactId != null) {
                        boolean managed = getCursor().getPathAsStream().anyMatch(t -> t instanceof Xml.Tag &&
                                "dependencyManagement".equals(((Xml.Tag) t).getName()));
                        found.add(new Declaration(tag.getId(), groupId, artifactId, resolve(rawVersion, properties),
                                rawVersion, managed));
                    }
                    return tag;
                }
                return super.visitTag(tag, p);
            }
        }.visit(pom, 0);
        return found;
    }

    private static @Nullable String resolve(@Nullable String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        for (int i = 0; i < 5 && v.contains("${"); i++) {
            Matcher m = PROPERTY.matcher(v);
            StringBuffer sb = new StringBuffer();
            boolean unresolved = false;
            while (m.find()) {
                String replacement = properties.get(m.group(1));
                if (replacement == null) {
                    unresolved = true;
                    replacement = m.group();
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            v = sb.toString();
            if (unresolved) {
                return v.contains("${") ? null : v;
            }
        }
        return v.contains("${") ? null : v;
    }

    private static List<Declaration> gradle(JavaSourceFile script) {
        boolean settings = script.getSourcePath().toString().replace('\\', '/').matches("(.*/)?settings\\.gradle(\\.kts)?");
        Map<String, String> catalogVersions = new HashMap<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                List<Expression> args = method.getArguments();
                if ("version".equals(method.getSimpleName()) && method.getSelect() == null && args.size() == 2 &&
                    stringValue(args.get(0)) != null && stringValue(args.get(1)) != null) {
                    catalogVersions.put(stringValue(args.get(0)), stringValue(args.get(1)));
                }
                return super.visitMethodInvocation(method, p);
            }
        }.visit(script, 0);

        List<Declaration> found = new ArrayList<>();
        Set<UUID> claimed = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                List<Expression> args = method.getArguments();
                if ("library".equals(method.getSimpleName()) && args.size() == 3) {
                    String groupId = stringValue(args.get(1));
                    String artifactId = stringValue(args.get(2));
                    if (groupId != null && artifactId != null) {
                        String version = null;
                        Object parent = getCursor().getParentTreeCursor().getValue();
                        if (parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() == method &&
                            !((J.MethodInvocation) parent).getArguments().isEmpty()) {
                            J.MethodInvocation chained = (J.MethodInvocation) parent;
                            String arg = stringValue(chained.getArguments().get(0));
                            version = "versionRef".equals(chained.getSimpleName()) ? catalogVersions.get(arg) :
                                    "version".equals(chained.getSimpleName()) ? arg : null;
                        }
                        found.add(new Declaration(method.getId(), groupId, artifactId, version, version, true));
                        args.forEach(a -> claimed.add(a.getId()));
                    }
                } else {
                    String groupId = null;
                    String artifactId = null;
                    String version = null;
                    for (Expression arg : args) {
                        String key = null;
                        Expression value = null;
                        if (arg instanceof G.MapEntry) {
                            key = stringValue(((G.MapEntry) arg).getKey());
                            value = ((G.MapEntry) arg).getValue();
                        } else if (arg instanceof J.Assignment && ((J.Assignment) arg).getVariable() instanceof J.Identifier) {
                            key = ((J.Identifier) ((J.Assignment) arg).getVariable()).getSimpleName();
                            value = ((J.Assignment) arg).getAssignment();
                        }
                        if (key != null && value != null) {
                            switch (key) {
                                case "group":
                                    groupId = stringValue(value);
                                    break;
                                case "name":
                                    artifactId = stringValue(value);
                                    break;
                                case "version":
                                    version = stringValue(value);
                                    break;
                            }
                        }
                    }
                    if (groupId != null && artifactId != null) {
                        found.add(new Declaration(method.getId(), groupId, artifactId, version, version, settings));
                        args.forEach(a -> claimed.add(a.getId()));
                    }
                }
                return super.visitMethodInvocation(method, p);
            }

            @Override
            public J.Literal visitLiteral(J.Literal literal, Integer p) {
                if (literal.getValue() instanceof String && !claimed.contains(literal.getId())) {
                    Matcher m = COORDINATE.matcher((String) literal.getValue());
                    if (m.matches() && m.group(1).contains(".")) {
                        String version = m.group(3) == null || m.group(3).isEmpty() ? null : m.group(3);
                        found.add(new Declaration(literal.getId(), m.group(1), m.group(2), version, version,
                                settings || isPlatform(getCursor())));
                    }
                }
                return literal;
            }

            @Override
            public @Nullable J preVisit(J tree, Integer p) {
                if (tree instanceof K.StringTemplate || tree instanceof G.GString) {
                    String text = tree.printTrimmed(getCursor().getParentOrThrow()).replaceAll("^\"+|\"+$", "");
                    Matcher m = TEMPLATE_COORDINATE.matcher(text);
                    if (m.find() && m.group(1).contains(".")) {
                        found.add(new Declaration(tree.getId(), m.group(1), m.group(2), null,
                                text.substring(m.end()), settings || isPlatform(getCursor())));
                    }
                    stopAfterPreVisit();
                }
                return tree;
            }
        }.visit(script, 0);
        return found;
    }

    private static boolean isPlatform(Cursor cursor) {
        J.MethodInvocation call = cursor.firstEnclosing(J.MethodInvocation.class);
        return call != null && ("platform".equals(call.getSimpleName()) || "enforcedPlatform".equals(call.getSimpleName()) ||
                                "mavenBom".equals(call.getSimpleName()));
    }

    private static @Nullable String stringValue(Expression e) {
        return e instanceof J.Literal && ((J.Literal) e).getValue() instanceof String ? (String) ((J.Literal) e).getValue() : null;
    }

    /**
     * Version catalogs are only read as text, so their declarations mark the whole file.
     */
    private static List<Declaration> toml(SourceFile sf) {
        Map<String, String> versions = new HashMap<>();
        List<String> libraries = new ArrayList<>();
        String section = "";
        for (String line : sf.printAll().split("\\R")) {
            String content = line.replaceFirst("\\s+#.*$", "");
            Matcher s = TOML_SECTION.matcher(content);
            if (s.matches()) {
                section = s.group(1);
                continue;
            }
            Matcher e = TOML_ENTRY.matcher(content);
            if (!e.matches()) {
                continue;
            }
            if ("versions".equals(section)) {
                Matcher v = TOML_STRING.matcher(e.group(2));
                if (v.matches()) {
                    versions.put(e.group(1), v.group(1));
                }
            } else if ("libraries".equals(section)) {
                libraries.add(e.group(2));
            }
        }
        List<Declaration> found = new ArrayList<>();
        for (String value : libraries) {
            String groupId = null;
            String artifactId = null;
            String version = null;
            Matcher plain = TOML_STRING.matcher(value);
            if (plain.matches()) {
                Matcher c = COORDINATE.matcher(plain.group(1));
                if (c.matches()) {
                    groupId = c.group(1);
                    artifactId = c.group(2);
                    version = c.group(3);
                }
            } else {
                Matcher f = TOML_FIELD.matcher(value);
                while (f.find()) {
                    switch (f.group(1)) {
                        case "module":
                            String[] ga = f.group(2).split(":");
                            if (ga.length == 2) {
                                groupId = ga[0];
                                artifactId = ga[1];
                            }
                            break;
                        case "group":
                            groupId = f.group(2);
                            break;
                        case "name":
                            artifactId = f.group(2);
                            break;
                        case "version":
                            version = f.group(2);
                            break;
                        case "version.ref":
                            version = versions.get(f.group(2));
                            break;
                    }
                }
            }
            if (groupId != null && artifactId != null) {
                found.add(new Declaration(sf.getId(), groupId, artifactId, version, version, true));
            }
        }
        return found;
    }
}

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

/**
 * Source of the {@code BucketLifecycleConfiguration} that {@link MigrateS3BucketLifecycleConfiguration} generates. v1's
 * lifecycle model is mutable and code edits a fetched configuration's rule list in place before saving it; v2's model is
 * immutable, so a mutable copy with v1's accessors is converted to and from v2's model when it is fetched and saved.
 */
final class S3BucketLifecycleShim {

    static final String SHIM = "BucketLifecycleConfiguration";

    private S3BucketLifecycleShim() {
    }

    // name, type, v1 default
    private static final String[][] RULE_FIELDS = {
            {"Id", "String", "null"},
            {"Prefix", "String", "null"},
            {"Filter", "LifecycleRuleFilter", "null"},
            {"Status", "String", "null"},
            {"ExpirationInDays", "int", "-1"},
            {"ExpirationDate", "Date", "null"},
            {"NoncurrentVersionExpirationInDays", "int", "-1"},
    };

    static String source(String pkg) {
        StringBuilder rule = new StringBuilder();
        for (String[] f : RULE_FIELDS) {
            String field = Character.toLowerCase(f[0].charAt(0)) + f[0].substring(1);
            rule.append("        private ").append(f[1]).append(' ').append(field).append(" = ").append(f[2]).append(";\n");
        }
        rule.append("        private boolean expiredObjectDeleteMarker;\n\n");
        for (String[] f : RULE_FIELDS) {
            String field = Character.toLowerCase(f[0].charAt(0)) + f[0].substring(1);
            rule.append("        public ").append(f[1]).append(" get").append(f[0]).append("() {\n")
                    .append("            return ").append(field).append(";\n")
                    .append("        }\n\n")
                    .append("        public void set").append(f[0]).append('(').append(f[1]).append(' ').append(field).append(") {\n")
                    .append("            this.").append(field).append(" = ").append(field).append(";\n")
                    .append("        }\n\n")
                    .append("        public Rule with").append(f[0]).append('(').append(f[1]).append(' ').append(field).append(") {\n")
                    .append("            this.").append(field).append(" = ").append(field).append(";\n")
                    .append("            return this;\n")
                    .append("        }\n\n");
        }
        return ProjectShims.packageLine(pkg) +
               "import java.util.ArrayList;\n" +
               "import java.util.Date;\n" +
               "import java.util.List;\n" +
               "import software.amazon.awssdk.services.s3.S3Client;\n" +
               "import software.amazon.awssdk.services.s3.model.LifecycleExpiration;\n" +
               "import software.amazon.awssdk.services.s3.model.LifecycleRule;\n" +
               "import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;\n" +
               "import software.amazon.awssdk.services.s3.model.NoncurrentVersionExpiration;\n" +
               "import software.amazon.awssdk.services.s3.model.S3Exception;\n" +
               "\n" +
               "/**\n" +
               " * AWS SDK v1's mutable {@code BucketLifecycleConfiguration}. SDK v2's lifecycle model is immutable, so this keeps v1's\n" +
               " * accessors and converts to and from the v2 model when the configuration is fetched and saved.\n" +
               " */\n" +
               "public class " + SHIM + " {\n\n" +
               "    public static final String ENABLED = \"Enabled\";\n" +
               "    public static final String DISABLED = \"Disabled\";\n\n" +
               "    private List<Rule> rules = new ArrayList<>();\n\n" +
               "    public " + SHIM + "() {\n" +
               "    }\n\n" +
               "    public " + SHIM + "(List<Rule> rules) {\n" +
               "        setRules(rules);\n" +
               "    }\n\n" +
               "    public List<Rule> getRules() {\n" +
               "        return rules;\n" +
               "    }\n\n" +
               "    public void setRules(List<Rule> rules) {\n" +
               "        this.rules = rules == null ? new ArrayList<>() : rules;\n" +
               "    }\n\n" +
               "    public " + SHIM + " withRules(List<Rule> rules) {\n" +
               "        setRules(rules);\n" +
               "        return this;\n" +
               "    }\n\n" +
               "    /**\n" +
               "     * v1 {@code AmazonS3.getBucketLifecycleConfiguration(bucket)}: null when the bucket has none, where v2 throws.\n" +
               "     */\n" +
               "    public static " + SHIM + " get(S3Client s3, String bucketName) {\n" +
               "        List<LifecycleRule> v2Rules;\n" +
               "        try {\n" +
               "            v2Rules = s3.getBucketLifecycleConfiguration(r -> r.bucket(bucketName)).rules();\n" +
               "        } catch (S3Exception e) {\n" +
               "            if (e.statusCode() == 404) {\n" +
               "                return null;\n" +
               "            }\n" +
               "            throw e;\n" +
               "        }\n" +
               "        " + SHIM + " configuration = new " + SHIM + "();\n" +
               "        for (LifecycleRule v2 : v2Rules) {\n" +
               "            configuration.rules.add(Rule.from(v2));\n" +
               "        }\n" +
               "        return configuration;\n" +
               "    }\n\n" +
               "    /**\n" +
               "     * v1 {@code AmazonS3.setBucketLifecycleConfiguration(bucket, configuration)}.\n" +
               "     */\n" +
               "    public static void set(S3Client s3, String bucketName, " + SHIM + " configuration) {\n" +
               "        List<LifecycleRule> v2Rules = new ArrayList<>();\n" +
               "        for (Rule rule : configuration.rules) {\n" +
               "            v2Rules.add(rule.toV2());\n" +
               "        }\n" +
               "        s3.putBucketLifecycleConfiguration(r -> r.bucket(bucketName).lifecycleConfiguration(c -> c.rules(v2Rules)));\n" +
               "    }\n\n" +
               "    public static class Rule {\n" +
               rule +
               "        public boolean isExpiredObjectDeleteMarker() {\n" +
               "            return expiredObjectDeleteMarker;\n" +
               "        }\n\n" +
               "        public void setExpiredObjectDeleteMarker(boolean expiredObjectDeleteMarker) {\n" +
               "            this.expiredObjectDeleteMarker = expiredObjectDeleteMarker;\n" +
               "        }\n\n" +
               "        public Rule withExpiredObjectDeleteMarker(boolean expiredObjectDeleteMarker) {\n" +
               "            this.expiredObjectDeleteMarker = expiredObjectDeleteMarker;\n" +
               "            return this;\n" +
               "        }\n\n" +
               "        LifecycleRule toV2() {\n" +
               "            LifecycleRule.Builder b = LifecycleRule.builder().id(id).status(status);\n" +
               "            // v1 sent a bare prefix only when no filter was set\n" +
               "            if (filter != null) {\n" +
               "                b.filter(filter);\n" +
               "            } else if (prefix != null) {\n" +
               "                b.filter(LifecycleRuleFilter.builder().prefix(prefix).build());\n" +
               "            }\n" +
               "            if (expirationInDays != -1 || expirationDate != null || expiredObjectDeleteMarker) {\n" +
               "                LifecycleExpiration.Builder e = LifecycleExpiration.builder();\n" +
               "                if (expirationInDays != -1) {\n" +
               "                    e.days(expirationInDays);\n" +
               "                }\n" +
               "                if (expirationDate != null) {\n" +
               "                    e.date(expirationDate.toInstant());\n" +
               "                }\n" +
               "                if (expiredObjectDeleteMarker) {\n" +
               "                    e.expiredObjectDeleteMarker(true);\n" +
               "                }\n" +
               "                b.expiration(e.build());\n" +
               "            }\n" +
               "            if (noncurrentVersionExpirationInDays != -1) {\n" +
               "                b.noncurrentVersionExpiration(NoncurrentVersionExpiration.builder().noncurrentDays(noncurrentVersionExpirationInDays).build());\n" +
               "            }\n" +
               "            return b.build();\n" +
               "        }\n\n" +
               "        static Rule from(LifecycleRule v2) {\n" +
               "            Rule rule = new Rule().withId(v2.id()).withStatus(v2.statusAsString()).withFilter(v2.filter());\n" +
               "            if (v2.filter() == null) {\n" +
               "                rule.setPrefix(v2.prefix());\n" +
               "            }\n" +
               "            LifecycleExpiration e = v2.expiration();\n" +
               "            if (e != null) {\n" +
               "                if (e.days() != null) {\n" +
               "                    rule.setExpirationInDays(e.days());\n" +
               "                }\n" +
               "                if (e.date() != null) {\n" +
               "                    rule.setExpirationDate(Date.from(e.date()));\n" +
               "                }\n" +
               "                rule.setExpiredObjectDeleteMarker(Boolean.TRUE.equals(e.expiredObjectDeleteMarker()));\n" +
               "            }\n" +
               "            if (v2.noncurrentVersionExpiration() != null && v2.noncurrentVersionExpiration().noncurrentDays() != null) {\n" +
               "                rule.setNoncurrentVersionExpirationInDays(v2.noncurrentVersionExpiration().noncurrentDays());\n" +
               "            }\n" +
               "            return rule;\n" +
               "        }\n" +
               "    }\n" +
               "}\n";
    }
}

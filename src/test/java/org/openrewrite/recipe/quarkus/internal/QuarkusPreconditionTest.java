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
package org.openrewrite.recipe.quarkus.internal;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.maven.Assertions.pomXml;

/// Verifies that the generated Quarkus aggregation recipes from
/// `quarkus-consolidated.yml` do not run on projects whose
/// `io.quarkus:quarkus-core` version is at or above the recipe's target version.
class QuarkusPreconditionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.quarkus.MigrateToQuarkus_v3_1_0");
    }

    private static String pomWithQuarkusBom(String bomVersion) {
        //language=xml
        return """
          <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>test</artifactId>
              <version>1.0</version>
              <dependencyManagement>
                  <dependencies>
                      <dependency>
                          <groupId>io.quarkus.platform</groupId>
                          <artifactId>quarkus-bom</artifactId>
                          <version>%s</version>
                          <type>pom</type>
                          <scope>import</scope>
                      </dependency>
                  </dependencies>
              </dependencyManagement>
              <dependencies>
                  <dependency>
                      <groupId>io.quarkus</groupId>
                      <artifactId>quarkus-core</artifactId>
                  </dependency>
                  <dependency>
                      <groupId>javax.validation</groupId>
                      <artifactId>validation-api</artifactId>
                      <version>2.0.1.Final</version>
                  </dependency>
              </dependencies>
          </project>
          """.formatted(bomVersion);
    }

    @Test
    void doesNotRunWhenQuarkusVersionIsAtTarget() {
        // A project on quarkus-bom 3.1.0.Final should NOT be modified,
        // because the precondition version: (,3.1.0) excludes 3.1.0 itself.
        // quarkus-core resolves to 3.1.0.Final via the BOM.
        rewriteRun(
          mavenProject(
            "project",
            pomXml(pomWithQuarkusBom("3.1.0.Final"))
          )
        );
    }

    @Test
    void runsWhenQuarkusVersionIsBelowTarget() {
        rewriteRun(
          mavenProject(
            "project",
            pomXml(
              pomWithQuarkusBom("2.16.12.Final"),
              after -> after.after(pom -> assertThat(pom)
                .doesNotContain("javax")
                .contains("jakarta")
                .actual())
            )
          )
        );
    }
}

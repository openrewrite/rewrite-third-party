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
import org.openrewrite.test.RewriteTest;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.recipe.quarkus.internal.QuarkusPreconditionTest.loadConsolidatedRecipe;
import static org.openrewrite.yaml.Assertions.yaml;

/// Compares the generated aggregation recipes with what `quarkus update` does for the same project.
class QuarkusCliParityTest implements RewriteTest {

    //language=xml
    private static final String POM_ON_QUARKUS_3_6 = """
      <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>app</artifactId>
          <version>1.0</version>
          <properties>
              <maven.compiler.source>11</maven.compiler.source>
              <maven.compiler.target>11</maven.compiler.target>
              <quarkus.platform.version>3.6.6</quarkus.platform.version>
          </properties>
          <dependencyManagement>
              <dependencies>
                  <dependency>
                      <groupId>io.quarkus.platform</groupId>
                      <artifactId>quarkus-bom</artifactId>
                      <version>${quarkus.platform.version}</version>
                      <type>pom</type>
                      <scope>import</scope>
                  </dependency>
              </dependencies>
          </dependencyManagement>
          <dependencies>
              <dependency>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-resteasy-reactive-jackson</artifactId>
              </dependency>
              <dependency>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-junit5</artifactId>
                  <scope>test</scope>
              </dependency>
          </dependencies>
          <build>
              <plugins>
                  <plugin>
                      <groupId>io.quarkus.platform</groupId>
                      <artifactId>quarkus-maven-plugin</artifactId>
                      <version>${quarkus.platform.version}</version>
                  </plugin>
              </plugins>
          </build>
      </project>
      """;

    @Test
    void upgradesQuarkusPlatformLikeTheCli() {
        rewriteRun(
          spec -> spec.recipe(loadConsolidatedRecipe("org.openrewrite.quarkus.MigrateToQuarkus_v3_39_0")),
          mavenProject(
            "app",
            pomXml(
              POM_ON_QUARKUS_3_6,
              spec -> spec.after(printed -> {
                  // Upgrading the platform first, as `quarkus update` does, leaves the not yet renamed artifacts unmanaged
                  // for a moment, which the test prints as markers, but which do not end up in the changed file
                  String pom = printed.replaceAll("<!--~~\\(.*?\\)~~>-->", "");
                  assertThat(pom)
                    .containsPattern(Pattern.compile("<quarkus.platform.version>3\\.39\\.\\d+(\\.\\d+)?</quarkus.platform.version>"))
                    .contains("<artifactId>quarkus-rest-jackson</artifactId>")
                    .contains("<artifactId>quarkus-junit</artifactId>")
                    // only quarkus-cxf stopped pulling in quarkus-jackson, so only its users need it added
                    .doesNotContain("<artifactId>quarkus-jackson</artifactId>");
                  // renamed artifacts are managed by the upgraded platform, so get no version of their own
                  assertThat(pom.substring(pom.lastIndexOf("<dependencies>"), pom.lastIndexOf("</dependencies>"))).doesNotContain("<version>");
                  return printed;
              })
            )
          )
        );
    }

    @Test
    void upgradesJavaInGitHubActionsWorkflowLikeTheCli() {
        rewriteRun(
          spec -> spec.recipe(loadConsolidatedRecipe("org.openrewrite.quarkus.MigrateToQuarkus_v3_7_0")),
          mavenProject(
            "app",
            pomXml(POM_ON_QUARKUS_3_6, spec -> spec.after(pom -> {
                assertThat(pom).contains("<maven.compiler.source>17</maven.compiler.source>");
                return pom;
            }))
          ),
          //language=yaml
          yaml(
            """
              jobs:
                build:
                  runs-on: ubuntu-latest
                  steps:
                    - uses: actions/setup-java@v4
                      with:
                        distribution: temurin
                        java-version: 11
              """,
            """
              jobs:
                build:
                  runs-on: ubuntu-latest
                  steps:
                    - uses: actions/setup-java@v4
                      with:
                        distribution: temurin
                        java-version: 17
              """,
            spec -> spec.path(".github/workflows/ci.yml")
          )
        );
    }
}

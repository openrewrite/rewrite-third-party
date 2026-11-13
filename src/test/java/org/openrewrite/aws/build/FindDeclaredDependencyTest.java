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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.maven.ChangeDependencyGroupIdAndArtifactId;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.settingsGradleKts;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.test.SourceSpecs.text;

class FindDeclaredDependencyTest implements RewriteTest {

    private static final FindDeclaredDependency SPRING_CLOUD_AWS =
      new FindDeclaredDependency("org.springframework.cloud", "spring-cloud-aws*,spring-cloud-starter-aws*", null);
    private static final FindDeclaredDependency AWSPRING_2 = new FindDeclaredDependency("io.awspring.cloud", "*", "2.x");

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipes(SPRING_CLOUD_AWS, AWSPRING_2);
    }

    @DocumentExample
    @Test
    void childOfMigratedParentIsNotReported() {
        rewriteRun(
          // the child's marker still resolves Spring Cloud AWS 2.x through the parent the first recipe migrated
          spec -> spec.recipes(
            new ChangeDependencyGroupIdAndArtifactId("org.springframework.cloud", "spring-cloud-starter-aws-messaging",
              "io.awspring.cloud", "spring-cloud-aws-starter-sqs", "3.3.1", null),
            SPRING_CLOUD_AWS, AWSPRING_2),
          mavenProject("parent",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>child</module>
                    </modules>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.cloud</groupId>
                            <artifactId>spring-cloud-starter-aws-messaging</artifactId>
                            <version>2.2.6.RELEASE</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>child</module>
                    </modules>
                    <dependencies>
                        <dependency>
                            <groupId>io.awspring.cloud</groupId>
                            <artifactId>spring-cloud-aws-starter-sqs</artifactId>
                            <version>3.3.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            mavenProject("child",
              pomXml(
                """
                  <project>
                      <parent>
                          <groupId>com.example</groupId>
                          <artifactId>parent</artifactId>
                          <version>1</version>
                      </parent>
                      <artifactId>child</artifactId>
                  </project>
                  """
              )
            )
          )
        );
    }

    @Test
    void declaredDependenciesAndManagedVersionsAreReported() {
        rewriteRun(
          pomXml(
            """
              <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                      <awspring.version>2.4.4</awspring.version>
                  </properties>
                  <dependencyManagement>
                      <dependencies>
                          <dependency>
                              <groupId>io.awspring.cloud</groupId>
                              <artifactId>spring-cloud-aws-dependencies</artifactId>
                              <version>${awspring.version}</version>
                              <type>pom</type>
                              <scope>import</scope>
                          </dependency>
                      </dependencies>
                  </dependencyManagement>
                  <dependencies>
                      <dependency>
                          <groupId>org.springframework.cloud</groupId>
                          <artifactId>spring-cloud-starter-aws</artifactId>
                          <version>2.2.6.RELEASE</version>
                      </dependency>
                      <dependency>
                          <groupId>org.springframework.cloud</groupId>
                          <artifactId>spring-cloud-function-adapter-aws</artifactId>
                          <version>3.2.12</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            """
              <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                      <awspring.version>2.4.4</awspring.version>
                  </properties>
                  <dependencyManagement>
                      <dependencies>
                          <!--~~(io.awspring.cloud:spring-cloud-aws-dependencies:2.4.4)~~>--><dependency>
                              <groupId>io.awspring.cloud</groupId>
                              <artifactId>spring-cloud-aws-dependencies</artifactId>
                              <version>${awspring.version}</version>
                              <type>pom</type>
                              <scope>import</scope>
                          </dependency>
                      </dependencies>
                  </dependencyManagement>
                  <dependencies>
                      <!--~~(org.springframework.cloud:spring-cloud-starter-aws:2.2.6.RELEASE)~~>--><dependency>
                          <groupId>org.springframework.cloud</groupId>
                          <artifactId>spring-cloud-starter-aws</artifactId>
                          <version>2.2.6.RELEASE</version>
                      </dependency>
                      <dependency>
                          <groupId>org.springframework.cloud</groupId>
                          <artifactId>spring-cloud-function-adapter-aws</artifactId>
                          <version>3.2.12</version>
                      </dependency>
                  </dependencies>
              </project>
              """
          )
        );
    }

    @Test
    void thirdPartyDependencyBringingItTransitively() {
        rewriteRun(
          pomXml(
            """
              <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <dependencies>
                      <dependency>
                          <groupId>org.springframework.integration</groupId>
                          <artifactId>spring-integration-aws</artifactId>
                          <version>2.5.4</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            spec -> spec.after(actual -> assertThat(actual).containsPattern(
                       "<!--~~\\(io\\.awspring\\.cloud:spring-cloud-aws-core:2\\.[^ ]+ via org\\.springframework\\.integration:spring-integration-aws").actual())
          )
        );
    }

    @Test
    void rootSubprojectsBlockAndCatalogsAreReported() {
        rewriteRun(
          buildGradle(
            """
              subprojects {
                  dependencies {
                      implementation 'org.springframework.cloud:spring-cloud-starter-aws-secrets-manager-config:2.2.6.RELEASE'
                      implementation 'io.awspring.cloud:spring-cloud-aws-starter-sqs:3.3.1'
                  }
              }
              """,
            """
              subprojects {
                  dependencies {
                      implementation /*~~(org.springframework.cloud:spring-cloud-starter-aws-secrets-manager-config:2.2.6.RELEASE)~~>*/'org.springframework.cloud:spring-cloud-starter-aws-secrets-manager-config:2.2.6.RELEASE'
                      implementation 'io.awspring.cloud:spring-cloud-aws-starter-sqs:3.3.1'
                  }
              }
              """
          ),
          settingsGradleKts(
            """
              dependencyResolutionManagement {
                  versionCatalogs {
                      create("libs") {
                          library("aws-messaging", "org.springframework.cloud", "spring-cloud-aws-messaging").version("2.2.6.RELEASE")
                          library("sqs", "io.awspring.cloud:spring-cloud-aws-starter-sqs:3.3.1")
                      }
                  }
              }
              """,
            """
              dependencyResolutionManagement {
                  versionCatalogs {
                      create("libs") {
                          /*~~(org.springframework.cloud:spring-cloud-aws-messaging:2.2.6.RELEASE)~~>*/library("aws-messaging", "org.springframework.cloud", "spring-cloud-aws-messaging").version("2.2.6.RELEASE")
                          library("sqs", "io.awspring.cloud:spring-cloud-aws-starter-sqs:3.3.1")
                      }
                  }
              }
              """
          ),
          text(
            """
              [versions]
              awspring = "2.4.4"

              [libraries]
              sqs = { module = "io.awspring.cloud:spring-cloud-aws-messaging", version.ref = "awspring" }
              """,
            """
              ~~(io.awspring.cloud:spring-cloud-aws-messaging:2.4.4)~~>[versions]
              awspring = "2.4.4"

              [libraries]
              sqs = { module = "io.awspring.cloud:spring-cloud-aws-messaging", version.ref = "awspring" }
              """,
            spec -> spec.path("gradle/libs.versions.toml")
          )
        );
    }
}

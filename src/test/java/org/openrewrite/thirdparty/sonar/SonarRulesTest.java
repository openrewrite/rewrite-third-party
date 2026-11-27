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
package org.openrewrite.thirdparty.sonar;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.Recipe;
import org.openrewrite.Validated;
import org.openrewrite.config.Environment;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.maven.tree.ResolvedGroupArtifactVersion;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import tech.picnic.errorprone.refasterrules.StringRulesRecipes;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.java.Assertions.version;
import static org.openrewrite.maven.Assertions.pomXml;

class SonarRulesTest implements RewriteTest {

    private static final Environment ENVIRONMENT = Environment.builder().scanRuntimeClasspath().build();

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.thirdparty.sonar.SonarRules");
    }

    @Test
    void compositesResolve() {
        assertThat(ENVIRONMENT
          .activateRecipes("org.openrewrite.thirdparty.sonar.SonarRules")
          .validateAll())
          .filteredOn(Validated::isInvalid)
          .isEmpty();
    }

    @Test
    void runsSonarRules() {
        rewriteRun(
          //language=java
          java(
            """
              class A {
                  int find(String s, int offset) {
                      return s.substring(offset).indexOf('x');
                  }
              }
              """,
            """
              class A {
                  int find(String s, int offset) {
                      return Math.max(-1, s.indexOf('x', offset) - offset);
                  }
              }
              """
          )
        );
    }

    @Test
    void listsEveryPicnicStringRule() {
        Recipe critical = ENVIRONMENT
          .activateRecipes("org.openrewrite.thirdparty.sonar.Critical");
        assertThat(recipeNames(critical).toList())
          .containsAll(new StringRulesRecipes().getRecipeList().stream().map(Recipe::getName).toList());
    }

    private static Stream<String> recipeNames(Recipe recipe) {
        return Stream.concat(Stream.of(recipe.getName()), recipe.getRecipeList().stream().flatMap(SonarRulesTest::recipeNames));
    }

    @DocumentExample
    @Test
    void usesJava11ApisFromJava11Only() {
        rewriteRun(
          //language=java
          version(
            java(
              """
                import java.util.function.Predicate;

                class A {
                    boolean blank(String s) {
                        return s.trim().isEmpty();
                    }

                    Predicate<String> notEmpty() {
                        return s -> !s.isEmpty();
                    }

                    boolean startsAt(String s, int offset) {
                        return s.substring(offset).startsWith("x");
                    }
                }
                """,
              """
                import java.util.function.Predicate;

                class A {
                    boolean blank(String s) {
                        return s.trim().isEmpty();
                    }

                    Predicate<String> notEmpty() {
                        return s -> !s.isEmpty();
                    }

                    boolean startsAt(String s, int offset) {
                        return s.startsWith("x", offset);
                    }
                }
                """
            ),
            8
          ),
          //language=java
          version(
            java(
              """
                import java.util.function.Predicate;

                class B {
                    boolean blank(String s) {
                        return s.trim().isEmpty();
                    }

                    Predicate<String> notEmpty() {
                        return s -> !s.isEmpty();
                    }
                }
                """,
              """
                import java.util.function.Predicate;

                import static java.util.function.Predicate.not;

                class B {
                    boolean blank(String s) {
                        return s.isBlank();
                    }

                    Predicate<String> notEmpty() {
                        return not(String::isEmpty);
                    }
                }
                """
            ),
            11
          )
        );
    }

    @Test
    void usesFormattedFromJava15Only() {
        rewriteRun(
          //language=java
          version(
            java(
              """
                class A {
                    String greet(String name) {
                        return String.format("Hello %s", name);
                    }
                }
                """
            ),
            14
          ),
          //language=java
          version(
            java(
              """
                class B {
                    String greet(String name) {
                        return String.format("Hello %s", name);
                    }
                }
                """,
              """
                class B {
                    String greet(String name) {
                        return ("Hello %s").formatted(name);
                    }
                }
                """
            ),
            15
          )
        );
    }

    @Test
    void usesBoundedIndexOfFromJava21Only() {
        rewriteRun(
          //language=java
          version(
            java(
              """
                class A {
                    int find(String s, int begin, int end) {
                        return s.substring(begin, end).indexOf('x');
                    }
                }
                """
            ),
            17
          ),
          //language=java
          version(
            java(
              """
                class B {
                    int find(String s, int begin, int end) {
                        return s.substring(begin, end).indexOf('x');
                    }
                }
                """,
              """
                class B {
                    int find(String s, int begin, int end) {
                        return Math.max(-1, s.indexOf('x', begin, end) - begin);
                    }
                }
                """
            ),
            21
          )
        );
    }

    @Test
    void usesGuavaWhereMavenCompilesMainSourcesAgainstIt() {
        rewriteRun(
          mavenProject("without-guava",
            //language=xml
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>without-guava</artifactId>
                    <version>1.0</version>
                </project>
                """
            ),
            srcMainJava(
              //language=java
              java(
                """
                  class A {
                      boolean empty(String s) {
                          return s == null || s.isEmpty();
                      }
                  }
                  """
              )
            )
          ),
          mavenProject("with-guava",
            //language=xml
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>with-guava</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.4.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            srcMainJava(
              //language=java
              java(
                """
                  class B {
                      boolean empty(String s) {
                          return s == null || s.isEmpty();
                      }
                  }
                  """,
                """
                  import com.google.common.base.Strings;

                  class B {
                      boolean empty(String s) {
                          return Strings.isNullOrEmpty(s);
                      }
                  }
                  """
              )
            )
          ),
          mavenProject("provided-guava",
            //language=xml
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>provided-guava</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.4.0-jre</version>
                            <scope>provided</scope>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            srcMainJava(
              //language=java
              java(
                """
                  class C {
                      boolean empty(String s) {
                          return s == null || s.isEmpty();
                      }
                  }
                  """,
                """
                  import com.google.common.base.Strings;

                  class C {
                      boolean empty(String s) {
                          return Strings.isNullOrEmpty(s);
                      }
                  }
                  """
              )
            )
          ),
          mavenProject("test-guava",
            //language=xml
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>test-guava</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.4.0-jre</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            srcMainJava(
              //language=java
              java(
                """
                  class D {
                      boolean empty(String s) {
                          return s == null || s.isEmpty();
                      }
                  }
                  """
              )
            )
          )
        );
    }

    @Test
    void usesGuavaOnlyWhereGradleCompilesMainSourcesAgainstIt() {
        JavaProject testOnly = new JavaProject(randomId(), "test-only", null);
        JavaProject main = new JavaProject(randomId(), "main", null);
        rewriteRun(
          //language=groovy
          buildGradle(
            """
              plugins { id 'java' }
              dependencies { testImplementation 'com.google.guava:guava:33.4.0-jre' }
              """,
            spec -> spec.path("test-only/build.gradle").markers(testOnly, gradleProject("testCompileClasspath"))
          ),
          //language=java
          java(
            """
              class A {
                  boolean empty(String s) {
                      return s == null || s.isEmpty();
                  }
              }
              """,
            spec -> spec.path("test-only/src/main/java/A.java").markers(testOnly)
          ),
          //language=groovy
          buildGradle(
            """
              plugins { id 'java' }
              dependencies { implementation 'com.google.guava:guava:33.4.0-jre' }
              """,
            spec -> spec.path("main/build.gradle").markers(main, gradleProject("compileClasspath"))
          ),
          //language=java
          java(
            """
              class B {
                  boolean empty(String s) {
                      return s == null || s.isEmpty();
                  }
              }
              """,
            """
              import com.google.common.base.Strings;

              class B {
                  boolean empty(String s) {
                      return Strings.isNullOrEmpty(s);
                  }
              }
              """,
            spec -> spec.path("main/src/main/java/B.java").markers(main)
          )
        );
    }

    private static GradleProject gradleProject(String configurationWithGuava) {
        ResolvedDependency guava = ResolvedDependency.builder()
          .gav(new ResolvedGroupArtifactVersion(null, "com.google.guava", "guava", "33.4.0-jre", null))
          .build();
        GradleDependencyConfiguration configuration = GradleDependencyConfiguration.builder()
          .name(configurationWithGuava)
          .isCanBeResolved(true)
          .extendsFrom(List.of())
          .requested(List.of())
          .directResolved(List.of(guava))
          .build();
        return GradleProject.builder()
          .group("com.example")
          .name("example")
          .version("1.0")
          .path(":")
          .mavenRepositories(List.of())
          .nameToConfiguration(Map.of(configurationWithGuava, configuration))
          .build();
    }
}

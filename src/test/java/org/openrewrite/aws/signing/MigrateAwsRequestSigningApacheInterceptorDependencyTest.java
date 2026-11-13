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
package org.openrewrite.aws.signing;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.Tree;
import org.openrewrite.aws.sdk.AwsSdkV2Coordinates;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;
import java.util.Map;

import static org.openrewrite.java.Assertions.*;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.test.SourceSpecs.text;

class MigrateAwsRequestSigningApacheInterceptorDependencyTest implements RewriteTest {

    private static final String V = AwsSdkV2Coordinates.version();

    private static final String JITPACK = """
          <repositories>
              <repository>
                  <id>jitpack.io</id>
                  <url>https://jitpack.io</url>
              </repository>
          </repositories>
      """;

    //language=java
    private static final String SITE = """
      package com.example;

      import com.amazonaws.auth.AWS4Signer;
      import com.amazonaws.auth.AWSCredentialsProvider;
      import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

      class Clients {
          Object interceptor(AWSCredentialsProvider credentials) {
              AWS4Signer signer = new AWS4Signer();
              signer.setServiceName("es");
              return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
          }
      }
      """;

    //language=java
    private static final String UNMIGRATABLE_SITE = """
      package com.example;

      import com.amazonaws.auth.AWS4Signer;
      import com.amazonaws.auth.AWSCredentialsProvider;
      import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

      class Clients {
          Object interceptor(AWS4Signer signer, AWSCredentialsProvider credentials) {
              return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
          }
      }
      """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAwsRequestSigningApacheInterceptorDependency(null))
          .parser(InterceptorStubs.java());
    }

    private static JavaSourceSet sourceSet(Map<String, List<JavaType.FullyQualified>> gavToTypes) {
        return new JavaSourceSet(Tree.randomId(), "main", List.of(), gavToTypes);
    }

    private static List<JavaType.FullyQualified> types(String... fqns) {
        return java.util.Arrays.stream(fqns).map(JavaType.ShallowClass::build).map(JavaType.FullyQualified.class::cast).toList();
    }

    @DocumentExample
    @Test
    void mavenDeclaredBecomesAuth() {
        rewriteRun(
          mavenProject("app",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                %s
                    <dependencies>
                        <dependency>
                            <groupId>com.github.awslabs</groupId>
                            <artifactId>aws-request-signing-apache-interceptor</artifactId>
                            <version>b3772780da</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(JITPACK),
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                %%s
                    <dependencies>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>auth</artifactId>
                            <version>%s</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V).formatted(JITPACK)
            ),
            srcMainJava(java(SITE))
          )
        );
    }

    @Test
    void mavenPropertyVersion() {
        rewriteRun(
          mavenProject("app",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                    <properties>
                        <interceptor.version>deb7941e85</interceptor.version>
                    </properties>
                %s
                    <dependencies>
                        <dependency>
                            <groupId>com.github.awslabs</groupId>
                            <artifactId>aws-request-signing-apache-interceptor</artifactId>
                            <version>${interceptor.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(JITPACK),
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                %%s
                    <dependencies>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>auth</artifactId>
                            <version>%s</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V).formatted(JITPACK)
            ),
            srcMainJava(java(SITE))
          )
        );
    }

    @Test
    void preservesTransitiveTheModuleUses() {
        rewriteRun(
          mavenProject("app",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                %s
                    <dependencies>
                        <dependency>
                            <groupId>com.github.awslabs</groupId>
                            <artifactId>aws-request-signing-apache-interceptor</artifactId>
                            <version>b3772780da</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(JITPACK),
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                %%s
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.httpcomponents</groupId>
                            <artifactId>httpclient</artifactId>
                            <version>4.5.2</version>
                        </dependency>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>auth</artifactId>
                            <version>%s</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V).formatted(JITPACK)
            ),
            srcMainJava(
              java(SITE),
              java(
                """
                  package com.example;

                  import org.apache.http.impl.client.HttpClients;

                  class Http {
                      Object client() {
                          return HttpClients.createDefault();
                      }
                  }
                  """,
                spec -> spec.markers(sourceSet(Map.of(
                  "org.apache.httpcomponents:httpclient:4.5.2", types("org.apache.http.impl.client.HttpClients"),
                  "joda-time:joda-time:2.8.1", types("org.joda.time.DateTime"))))
              )
            )
          )
        );
    }

    @Test
    void existingAuthOnlyRemovesAndOldBomIsRaised() {
        rewriteRun(
          mavenProject("app",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                %s
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>software.amazon.awssdk</groupId>
                                <artifactId>bom</artifactId>
                                <version>2.15.6</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>auth</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>com.github.awslabs</groupId>
                            <artifactId>aws-request-signing-apache-interceptor</artifactId>
                            <version>b3772780da</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(JITPACK),
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                %%s
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>software.amazon.awssdk</groupId>
                                <artifactId>bom</artifactId>
                                <version>%s</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>auth</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V).formatted(JITPACK)
            ),
            srcMainJava(java(SITE))
          )
        );
    }

    @Test
    void vendoredCopyGetsAuth() {
        rewriteRun(
          spec -> spec.parser(InterceptorStubs.javaWithoutStub()),
          mavenProject("app",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.amazonaws</groupId>
                            <artifactId>aws-java-sdk-core</artifactId>
                            <version>1.12.780</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.amazonaws</groupId>
                            <artifactId>aws-java-sdk-core</artifactId>
                            <version>1.12.780</version>
                        </dependency>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>auth</artifactId>
                            <version>%s</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V)
            ),
            srcMainJava(
              java(InterceptorStubs.INTERCEPTOR_JAVA, spec -> spec.path("com/amazonaws/http/AWSRequestSigningApacheInterceptor.java")),
              java(SITE)
            )
          )
        );
    }

    @Test
    void transitiveSignerNeedsNoAuth() {
        rewriteRun(
          spec -> spec.parser(InterceptorStubs.javaWithoutStub()),
          mavenProject("app",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>sts</artifactId>
                            <version>2.34.3</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            srcMainJava(
              java(InterceptorStubs.INTERCEPTOR_JAVA, spec -> spec.path("com/amazonaws/http/AWSRequestSigningApacheInterceptor.java")),
              java(SITE)
            )
          )
        );
    }

    @Test
    void unmigratableSiteKeepsDependency() {
        rewriteRun(
          mavenProject("app",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                %s
                    <dependencies>
                        <dependency>
                            <groupId>com.github.awslabs</groupId>
                            <artifactId>aws-request-signing-apache-interceptor</artifactId>
                            <version>b3772780da</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(JITPACK)
            ),
            srcMainJava(java(UNMIGRATABLE_SITE))
          )
        );
    }

    @Test
    void redundantSystemJarIsRemoved() {
        var gavToTypes = Map.of(
          "aws-java-sdk-custom:core:1.11.365", types("com.amazonaws.http.AWSRequestSigningApacheInterceptor", "com.amazonaws.AmazonClientException"),
          "com.amazonaws:aws-java-sdk-core:1.11.365", types("com.amazonaws.AmazonClientException"));
        rewriteRun(
          mavenProject("api",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>api</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>aws-java-sdk-custom</groupId>
                            <artifactId>core</artifactId>
                            <version>1.11.365</version>
                            <systemPath>${project.basedir}/lib/aws-java-sdk-custom-core-1.11.365.jar</systemPath>
                            <scope>system</scope>
                        </dependency>
                    </dependencies>
                </project>
                """,
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>api</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>auth</artifactId>
                            <version>%s</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V)
            ),
            text("jar", null, spec -> spec.path("lib/aws-java-sdk-custom-core-1.11.365.jar")),
            srcMainJava(java(SITE, spec -> spec.markers(sourceSet(gavToTypes))))
          )
        );
    }

    @Test
    void systemJarWithOwnTypesIsMarked() {
        var gavToTypes = Map.of(
          "aws-java-sdk-custom:core:1.11.365", types("com.amazonaws.http.AWSRequestSigningApacheInterceptor", "com.example.Custom"),
          "com.amazonaws:aws-java-sdk-core:1.11.365", types("com.amazonaws.AmazonClientException"));
        rewriteRun(
          mavenProject("api",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>api</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>aws-java-sdk-custom</groupId>
                            <artifactId>core</artifactId>
                            <version>1.11.365</version>
                            <systemPath>${project.basedir}/lib/aws-java-sdk-custom-core-1.11.365.jar</systemPath>
                            <scope>system</scope>
                        </dependency>
                    </dependencies>
                </project>
                """,
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>api</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>auth</artifactId>
                            <version>%s</version>
                        </dependency>
                        <!--~~(Provides awslabs' AWSRequestSigningApacheInterceptor along with types no other dependency provides; remove it by hand once nothing needs them)~~>--><dependency>
                            <groupId>aws-java-sdk-custom</groupId>
                            <artifactId>core</artifactId>
                            <version>1.11.365</version>
                            <systemPath>${project.basedir}/lib/aws-java-sdk-custom-core-1.11.365.jar</systemPath>
                            <scope>system</scope>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V)
            ),
            text("jar", spec -> spec.path("lib/aws-java-sdk-custom-core-1.11.365.jar")),
            srcMainJava(java(SITE, spec -> spec.markers(sourceSet(gavToTypes))))
          )
        );
    }

    @Test
    void removesJitPackWhenAsked() {
        rewriteRun(
          spec -> spec.recipe(new MigrateAwsRequestSigningApacheInterceptorDependency(true)),
          mavenProject("app",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                %s
                    <dependencies>
                        <dependency>
                            <groupId>com.github.awslabs</groupId>
                            <artifactId>aws-request-signing-apache-interceptor</artifactId>
                            <version>b3772780da</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(JITPACK),
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>

                    <dependencies>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>auth</artifactId>
                            <version>%s</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V)
            ),
            srcMainJava(java(SITE))
          )
        );
    }

    @Test
    void unusedInreachInterceptorRemoved() {
        rewriteRun(
          spec -> spec.recipeFromResources("org.openrewrite.aws.signing.RemoveUnusedInreachSigningInterceptor"),
          mavenProject("app",
            srcMainJava(
              //language=java
              java(
                """
                  package com.example;

                  class Clients {
                  }
                  """
              )
            ),
            //language=xml
            pomXml(
              """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>vc.inreach.aws</groupId>
                            <artifactId>aws-signing-request-interceptor</artifactId>
                            <version>0.0.22</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
              """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            )
          )
        );
    }

    @Test
    void migratedInreachInterceptorBecomesAuth() {
        rewriteRun(
          spec -> spec.parser(InterceptorStubs.javaWithInreach()),
          mavenProject("app",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>vc.inreach.aws</groupId>
                            <artifactId>aws-signing-request-interceptor</artifactId>
                            <version>0.0.22</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>auth</artifactId>
                            <version>%s</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V)
            ),
            srcMainJava(
              //language=java
              java(
                """
                  package com.example;

                  import com.amazonaws.auth.AWSCredentialsProvider;
                  import java.time.LocalDateTime;
                  import vc.inreach.aws.request.AWSSigner;
                  import vc.inreach.aws.request.AWSSigningRequestInterceptor;

                  class Clients {
                      Object interceptor(AWSCredentialsProvider credentials) {
                          return new AWSSigningRequestInterceptor(new AWSSigner(credentials, "eu-west-1", "es", LocalDateTime::now));
                      }
                  }
                  """
              )
            )
          )
        );
    }
}

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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.toolingapi.Assertions.withToolingApi;
import static org.openrewrite.java.Assertions.*;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.yaml.Assertions.yaml;

class MigrateAwsSdkV2DependenciesTest implements RewriteTest {

    private static final String V = AwsSdkV2Coordinates.version();

    static final String TOOLING_API_UNAVAILABLE = "The Gradle tooling API init script needs org.openrewrite.tools:jgit:1.10.0, " +
      "which is not published to Maven Central";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAwsSdkV2Dependencies())
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(),
              "aws-java-sdk-core",
              "aws-java-sdk-s3",
              "aws-java-sdk-sqs"));
    }

    @DocumentExample
    @Test
    void bundleBecomesPerServiceArtifactsUnderBom() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.amazonaws.services.s3.AmazonS3;
                  import com.amazonaws.services.sqs.AmazonSQS;

                  class Clients {
                      AmazonS3 s3;
                      AmazonSQS sqs;
                  }
                  """
              )
            ),
            //language=xml
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.amazonaws</groupId>
                            <artifactId>aws-java-sdk</artifactId>
                            <version>1.12.700</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
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
                            <artifactId>s3</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>sqs</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V)
            )
          )
        );
    }

    @Test
    void afterV2MigrationBomIsAlignedVersionsStrippedAndUnusedConversionRemoved() {
        rewriteRun(
          spec -> spec.recipes(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.AwsSdkJavaV1ToV2Safe"), new MigrateAwsSdkV2Dependencies())
            .parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-s3")),
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.amazonaws.services.s3.AmazonS3;

                  class Clients {
                      AmazonS3 s3;
                  }
                  """,
                """
                  import software.amazon.awssdk.services.s3.S3Client;

                  class Clients {
                      S3Client s3;
                  }
                  """
              )
            ),
            //language=xml
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.amazonaws</groupId>
                                <artifactId>aws-java-sdk-bom</artifactId>
                                <version>1.12.700</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.amazonaws</groupId>
                            <artifactId>aws-java-sdk-s3</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>com.amazonaws</groupId>
                            <artifactId>aws-java-sdk-secretsmanager</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>com.amazonaws</groupId>
                            <artifactId>aws-java-sdk-sts</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """,
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
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
                            <artifactId>s3</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>sts</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V)
            )
          )
        );
    }

    @Test
    void transitiveOnlyModuleGetsServiceArtifactAndKeepsHttpClient() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(),
              "aws-java-sdk-core",
              "aws-java-sdk-sqs",
              "httpclient",
              "httpcore")),
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.amazonaws.services.sqs.AmazonSQSAsync;
                  import org.apache.http.client.HttpClient;

                  class Clients {
                      AmazonSQSAsync sqs;
                      HttpClient http;
                  }
                  """
              )
            ),
            //language=xml
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.cloud</groupId>
                            <artifactId>spring-cloud-aws-messaging</artifactId>
                            <version>2.2.6.RELEASE</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
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
                            <groupId>org.apache.httpcomponents</groupId>
                            <artifactId>httpclient</artifactId>
                            <version>4.5.9</version>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.cloud</groupId>
                            <artifactId>spring-cloud-aws-messaging</artifactId>
                            <version>2.2.6.RELEASE</version>
                        </dependency>
                        <dependency>
                            <groupId>software.amazon.awssdk</groupId>
                            <artifactId>sqs</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V)
            )
          )
        );
    }

    @Test
    void onlyUsageGetsTestScope() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcTestJava(
              java(
                """
                  import com.amazonaws.services.s3.AmazonS3;

                  class S3Test {
                      AmazonS3 s3;
                  }
                  """
              )
            ),
            //language=xml
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>software.amazon.awssdk</groupId>
                                <artifactId>bom</artifactId>
                                <version>2.20.0</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """,
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
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
                            <artifactId>s3</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(V)
            )
          )
        );
    }

    @Test
    void deletesV1LoggingKeys() {
        rewriteRun(
          //language=properties
          properties(
            """
              logging.level.com.amazonaws=WARN
              logging.level.com.amazonaws.internal.InstanceMetadataServiceResourceFetcher=ERROR
              logging.level.com.amazonaws.xray=DEBUG
              logging.level.org.springframework=INFO
              """,
            """
              logging.level.com.amazonaws.xray=DEBUG
              logging.level.org.springframework=INFO
              """,
            spec -> spec.path("src/main/resources/application.properties")
          ),
          //language=yaml
          yaml(
            """
              logging:
                level:
                  com.amazonaws.util.EC2MetadataUtils: ERROR
                  root: INFO
              """,
            """
              logging:
                level:
                  root: INFO
              """,
            spec -> spec.path("src/main/resources/application.yml")
          )
        );
    }

    @Disabled(TOOLING_API_UNAVAILABLE)
    @Test
    void gradleV1ArtifactReplacedUnderPlatformBom() {
        rewriteRun(
          spec -> spec.beforeRecipe(withToolingApi()),
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.amazonaws.services.s3.AmazonS3;

                  class Clients {
                      AmazonS3 s3;
                  }
                  """
              )
            ),
            //language=groovy
            buildGradle(
              """
                plugins {
                    id 'java'
                }
                repositories {
                    mavenCentral()
                }
                dependencies {
                    implementation 'com.amazonaws:aws-java-sdk-s3:1.12.700'
                }
                """,
              """
                plugins {
                    id 'java'
                }
                repositories {
                    mavenCentral()
                }
                dependencies {
                    implementation platform("software.amazon.awssdk:bom:%s")
                    implementation "software.amazon.awssdk:s3"
                }
                """.formatted(V)
            )
          )
        );
    }

    @Disabled(TOOLING_API_UNAVAILABLE)
    @Test
    void gradleBomStaysOutOfBuildscript() {
        rewriteRun(
          spec -> spec.beforeRecipe(withToolingApi()),
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.amazonaws.services.s3.AmazonS3;

                  class Clients {
                      AmazonS3 s3;
                  }
                  """
              )
            ),
            //language=groovy
            buildGradle(
              """
                buildscript {
                    repositories {
                        mavenCentral()
                    }
                    dependencies {
                        classpath 'org.springframework.boot:spring-boot-gradle-plugin:2.7.0'
                    }
                }
                plugins {
                    id 'java'
                }
                repositories {
                    mavenCentral()
                }
                dependencies {
                    implementation 'com.amazonaws:aws-java-sdk-s3:1.12.700'
                }
                """,
              """
                buildscript {
                    repositories {
                        mavenCentral()
                    }
                    dependencies {
                        classpath 'org.springframework.boot:spring-boot-gradle-plugin:2.7.0'
                    }
                }
                plugins {
                    id 'java'
                }
                repositories {
                    mavenCentral()
                }
                dependencies {
                    implementation platform("software.amazon.awssdk:bom:%s")
                    implementation "software.amazon.awssdk:s3"
                }
                """.formatted(V)
            )
          )
        );
    }

    @Disabled(TOOLING_API_UNAVAILABLE)
    @Test
    void gradleExistingBomAlignedAndLooseVersionsStripped() {
        rewriteRun(
          spec -> spec.beforeRecipe(withToolingApi()),
          mavenProject("app",
            //language=groovy
            buildGradle(
              """
                plugins {
                    id 'java'
                }
                repositories {
                    mavenCentral()
                }
                dependencies {
                    implementation platform('software.amazon.awssdk:bom:2.15.6')
                    implementation 'software.amazon.awssdk:dynamodb:2.17.267'
                }
                """,
              """
                plugins {
                    id 'java'
                }
                repositories {
                    mavenCentral()
                }
                dependencies {
                    implementation platform('software.amazon.awssdk:bom:%s')
                    implementation 'software.amazon.awssdk:dynamodb'
                }
                """.formatted(V)
            )
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void moduleAlreadyOnV2LeftAlone() {
            rewriteRun(
              spec -> spec.typeValidationOptions(TypeValidation.none()),
              mavenProject("already-v2",
                //language=java
                srcMainJava(
                  java(
                    """
                      import software.amazon.awssdk.services.s3.S3Client;

                      class Clients {
                          S3Client s3;
                      }
                      """
                  )
                ),
                //language=xml
                pomXml(
                  """
                    <project>
                        <groupId>com.example</groupId>
                        <artifactId>already-v2</artifactId>
                        <version>1.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>software.amazon.awssdk</groupId>
                                <artifactId>s3</artifactId>
                                <version>2.29.9</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """
                )
              )
            );
        }

        @Test
        void typesVendoredIntoAmazonawsPackagesAreNotSdkUsage() {
            rewriteRun(
              mavenProject("vendored",
                srcMainJava(
                  //language=java
                  java(
                    """
                      package com.amazonaws.http;

                      public class AWSRequestSigningApacheInterceptor {
                      }
                      """
                  ),
                  //language=java
                  java(
                    """
                      import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                      class Signing {
                          AWSRequestSigningApacheInterceptor interceptor;
                      }
                      """
                  )
                ),
                //language=xml
                pomXml(
                  """
                    <project>
                        <groupId>com.example</groupId>
                        <artifactId>vendored</artifactId>
                        <version>1.0</version>
                    </project>
                    """
                )
              )
            );
        }
    }
}

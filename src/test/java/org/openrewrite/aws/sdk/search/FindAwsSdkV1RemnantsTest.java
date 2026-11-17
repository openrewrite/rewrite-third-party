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
package org.openrewrite.aws.sdk.search;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.aws.sdk.table.AwsSdkV1Remnants;
import org.openrewrite.java.JavaParser;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.RemoveDependency;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.xml.RemoveContentVisitor;
import org.openrewrite.xml.tree.Xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.test.RewriteTest.toRecipe;
import static org.openrewrite.test.SourceSpecs.text;
import static org.openrewrite.xml.Assertions.xml;

class FindAwsSdkV1RemnantsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindAwsSdkV1Remnants())
          .parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(),
            "aws-java-sdk-core",
            "aws-java-sdk-s3",
            "aws-java-sdk-kinesis",
            "amazon-kinesis-client-1",
            "aws-xray-recorder-sdk-core",
            "aws-lambda-java-core"));
    }

    @DocumentExample
    @Test
    void reportsV1TypesButNotLibrariesSharingThePackageRoot() {
        rewriteRun(
          spec -> spec.dataTable(AwsSdkV1Remnants.Row.class, rows -> assertThat(rows)
            .extracting(r -> r.getKind() + " " + r.getItem() + " " + r.getReason())
            .containsExactly("type com.amazonaws.services.s3.AmazonS3 missed")),
          //language=java
          java(
            """
              import com.amazonaws.services.lambda.runtime.Context;
              import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.xray.AWSXRay;

              class Handler {
                  AmazonS3 s3;
                  void handle(Context context) {
                      AWSXRay.beginSegment("x");
                  }
              }
              """,
            """
              import com.amazonaws.services.lambda.runtime.Context;
              /*~~(AWS SDK v1 type com.amazonaws.services.s3.AmazonS3)~~>*/import com.amazonaws.services.s3.AmazonS3;
              import com.amazonaws.xray.AWSXRay;

              class Handler {
                  AmazonS3 s3;
                  void handle(Context context) {
                      AWSXRay.beginSegment("x");
                  }
              }
              """
          )
        );
    }

    @Test
    void kinesisClientLibraryIsUnmigratable() {
        rewriteRun(
          spec -> spec.dataTable(AwsSdkV1Remnants.Row.class, rows -> assertThat(rows)
            .extracting(AwsSdkV1Remnants.Row::getReason).containsOnly(AwsSdkV1Remnants.UNMIGRATABLE)),
          //language=java
          java(
            """
              import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessor;

              abstract class Processor implements IRecordProcessor {
              }
              """,
            """
              /*~~(AWS SDK v1 type com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessor)~~>*/import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessor;

              abstract class Processor implements IRecordProcessor {
              }
              """
          )
        );
    }

    @Test
    void reportsV2MigrationTodoComments() {
        rewriteRun(
          spec -> spec.dataTable(AwsSdkV1Remnants.Row.class, rows -> assertThat(rows)
            .extracting(AwsSdkV1Remnants.Row::getKind).containsExactly("migration-comment")),
          //language=java
          java(
            """
              class Uploader {
                  void upload() {
                      /*AWS SDK for Java v2 migration: Transform for listNextBatchOfObjects method not supported.*/
                      System.out.println();
                  }
              }
              """,
            """
              class Uploader {
                  void upload() {
                      /*AWS SDK for Java v2 migration: Transform for listNextBatchOfObjects method not supported.*/
                      /*~~(AWS SDK v2 migration TODO)~~>*/System.out.println();
                  }
              }
              """
          )
        );
    }

    @Test
    void reportsTransitiveV1Dependency() {
        rewriteRun(
          spec -> spec.dataTable(AwsSdkV1Remnants.Row.class, rows -> assertThat(rows)
            .extracting(AwsSdkV1Remnants.Row::getItem)
            .anyMatch(i -> i.startsWith("com.amazonaws:aws-java-sdk-core:"))),
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
            spec -> spec.after(actual -> assertThat(actual).contains("<!--~~(AWS SDK v1 dependency: ").contains("com.amazonaws:aws-java-sdk-core:").actual())
          )
        );
    }

    @Test
    void moduleDoesNotReportDependencyRemovedFromItsParentInTheSameRun() {
        rewriteRun(
          spec -> spec.recipes(new RemoveDependency("com.amazonaws", "aws-java-sdk-s3", null), new FindAwsSdkV1Remnants())
            .afterRecipe(run -> assertThat(run.getDataTableRows(AwsSdkV1Remnants.class)).isEmpty()),
          //language=xml
          pomXml(
            """
              <project>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules>
                      <module>app</module>
                  </modules>
                  <dependencies>
                      <dependency>
                          <groupId>com.amazonaws</groupId>
                          <artifactId>aws-java-sdk-s3</artifactId>
                          <version>1.12.261</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            """
              <project>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules>
                      <module>app</module>
                  </modules>
              </project>
              """
          ),
          //language=xml
          pomXml(
            """
              <project>
                  <parent>
                      <groupId>com.example</groupId>
                      <artifactId>parent</artifactId>
                      <version>1.0</version>
                  </parent>
                  <artifactId>app</artifactId>
              </project>
              """,
            spec -> spec.path("app/pom.xml")
          )
        );
    }

    @Test
    void moduleDoesNotReportWhatASiblingModuleNoLongerBringsIn() {
        rewriteRun(
          spec -> spec.recipes(new RemoveDependency("com.amazonaws", "aws-java-sdk-s3", null), new FindAwsSdkV1Remnants())
            .afterRecipe(run -> assertThat(run.getDataTableRows(AwsSdkV1Remnants.class)).isEmpty()),
          //language=xml
          pomXml(
            """
              <project>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules>
                      <module>app</module>
                      <module>common</module>
                  </modules>
              </project>
              """
          ),
          //language=xml
          pomXml(
            """
              <project>
                  <parent>
                      <groupId>com.example</groupId>
                      <artifactId>parent</artifactId>
                      <version>1.0</version>
                  </parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                      <dependency>
                          <groupId>com.example</groupId>
                          <artifactId>common</artifactId>
                          <version>1.0</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            spec -> spec.path("app/pom.xml")
          ),
          //language=xml
          pomXml(
            """
              <project>
                  <parent>
                      <groupId>com.example</groupId>
                      <artifactId>parent</artifactId>
                      <version>1.0</version>
                  </parent>
                  <artifactId>common</artifactId>
                  <dependencies>
                      <dependency>
                          <groupId>com.amazonaws</groupId>
                          <artifactId>aws-java-sdk-s3</artifactId>
                          <version>1.12.261</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            """
              <project>
                  <parent>
                      <groupId>com.example</groupId>
                      <artifactId>parent</artifactId>
                      <version>1.0</version>
                  </parent>
                  <artifactId>common</artifactId>
              </project>
              """,
            spec -> spec.path("common/pom.xml")
          )
        );
    }

    @Test
    void pomDoesNotReportDependencyRemovedWithoutUpdatingItsModel() {
        rewriteRun(
          spec -> spec.recipes(toRecipe(() -> new MavenIsoVisitor<>() {
                @Override
                public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                    if (isDependencyTag("com.amazonaws", "aws-java-sdk-s3")) {
                        doAfterVisit(new RemoveContentVisitor<>(tag, true, true));
                    }
                    return super.visitTag(tag, ctx);
                }
            }), new FindAwsSdkV1Remnants())
            .afterRecipe(run -> assertThat(run.getDataTableRows(AwsSdkV1Remnants.class)).isEmpty()),
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
                          <artifactId>aws-java-sdk-s3</artifactId>
                          <version>1.12.261</version>
                      </dependency>
                  </dependencies>
              </project>
              """,
            """
              <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
              </project>
              """
          )
        );
    }

    @Test
    void reportsTextualReferences() {
        rewriteRun(
          //language=xml
          xml(
            """
              <beans>
                  <bean class="com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper"/>
              </beans>
              """,
            """
              <!--~~(AWS SDK v1 references: com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper)~~>--><beans>
                  <bean class="com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper"/>
              </beans>
              """,
            spec -> spec.path("src/main/resources/applicationContext.xml")
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void typesVendoredIntoAmazonawsPackagesAreNotReported() {
            rewriteRun(
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
            );
        }

        @Test
        void namesSharingTheRootWithoutNamingAV1PackageAreNotReported() {
            rewriteRun(
              text(
                """
                  {
                    "metadata": {
                      "targetPrefix": "com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101",
                      "documentation": "The service name, in the form com.amazonaws.us-east-1.s3"
                    }
                  }
                  """,
                spec -> spec.path("vendor/botocore/data/cloudtrail/2013-11-01/service-2.json")
              )
            );
        }
    }
}

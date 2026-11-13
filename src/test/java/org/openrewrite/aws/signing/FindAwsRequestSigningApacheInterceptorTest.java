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
import org.openrewrite.aws.signing.table.AwsRequestSigningRemnants;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.java.Assertions.*;
import static org.openrewrite.maven.Assertions.pomXml;

class FindAwsRequestSigningApacheInterceptorTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindAwsRequestSigningApacheInterceptor())
          .parser(InterceptorStubs.javaWithoutStub());
    }

    @DocumentExample
    @Test
    void reportsEveryRemnant() {
        rewriteRun(
          spec -> spec.dataTable(AwsRequestSigningRemnants.Row.class, rows -> assertThat(rows)
            .extracting(AwsRequestSigningRemnants.Row::getKind)
            .containsExactlyInAnyOrder("dependency", "vendored copy", "vendored copy", "type reference")),
          mavenProject("app",
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                    <repositories>
                        <repository>
                            <id>jitpack.io</id>
                            <url>https://jitpack.io</url>
                        </repository>
                    </repositories>
                    <dependencies>
                        <dependency>
                            <groupId>com.github.awslabs</groupId>
                            <artifactId>aws-request-signing-apache-interceptor</artifactId>
                            <version>b3772780da</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1</version>
                    <repositories>
                        <repository>
                            <id>jitpack.io</id>
                            <url>https://jitpack.io</url>
                        </repository>
                    </repositories>
                    <dependencies>
                        <!--~~>--><dependency>
                            <groupId>com.github.awslabs</groupId>
                            <artifactId>aws-request-signing-apache-interceptor</artifactId>
                            <version>b3772780da</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            srcMainJava(
              java(
                InterceptorStubs.INTERCEPTOR_JAVA,
                "/*~~(Vendored copy of awslabs' AWSRequestSigningApacheInterceptor)~~>*/" + InterceptorStubs.INTERCEPTOR_JAVA,
                spec -> spec.path("com/amazonaws/http/AWSRequestSigningApacheInterceptor.java")
              ),
              java(
                """
                  package com.amazonaws.http;
                  """,
                """
                  /*~~(package-info.java of the vendored com.amazonaws.http package)~~>*/package com.amazonaws.http;
                  """,
                spec -> spec.path("com/amazonaws/http/package-info.java")
              ),
              java(
                """
                  package com.example;

                  import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                  class Check {
                      boolean signs(Object o) {
                          return o instanceof AWSRequestSigningApacheInterceptor;
                      }
                  }
                  """,
                """
                  package com.example;

                  import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

                  class Check {
                      boolean signs(Object o) {
                          return o instanceof /*~~>*/AWSRequestSigningApacheInterceptor;
                      }
                  }
                  """
              )
            )
          )
        );
    }

    @Test
    void migratedModuleIsUnmarked() {
        rewriteRun(
          srcMainJava(
            java(InterceptorStubs.shim("com.example"), spec -> spec.path("com/example/AwsSigV4RequestInterceptor.java"))
          )
        );
    }

    @Test
    void reportsInreachLeftovers() {
        rewriteRun(
          spec -> spec.parser(InterceptorStubs.javaWithInreach())
            .dataTable(AwsRequestSigningRemnants.Row.class, rows -> assertThat(rows)
              .extracting(AwsRequestSigningRemnants.Row::getKind, AwsRequestSigningRemnants.Row::getDetail)
              .containsExactlyInAnyOrder(
                tuple("dependency", "vc.inreach.aws:aws-signing-request-interceptor"),
                tuple("type reference", "vc.inreach.aws"))),
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
                        <!--~~>--><dependency>
                            <groupId>vc.inreach.aws</groupId>
                            <artifactId>aws-signing-request-interceptor</artifactId>
                            <version>0.0.22</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            srcMainJava(
              //language=java
              java(
                """
                  import vc.inreach.aws.request.AWSSigningRequestInterceptor;

                  class Search {
                      AWSSigningRequestInterceptor interceptor;
                  }
                  """,
                """
                  import vc.inreach.aws.request.AWSSigningRequestInterceptor;

                  class Search {
                      /*~~>*/AWSSigningRequestInterceptor interceptor;
                  }
                  """
              )
            )
          )
        );
    }
}

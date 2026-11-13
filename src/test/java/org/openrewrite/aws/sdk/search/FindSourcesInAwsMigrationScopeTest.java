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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

import static java.util.Collections.singletonList;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.maven.Assertions.pomXml;

class FindSourcesInAwsMigrationScopeTest implements RewriteTest {

    private static final String DYNAMODB_POM = """
      <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>%s</artifactId>
          <version>1.0.0</version>
          <dependencies>
              <dependency>
                  <groupId>com.amazonaws</groupId>
                  <artifactId>aws-java-sdk-dynamodb</artifactId>
                  <version>1.12.782</version>
              </dependency>
          </dependencies>
      </project>
      """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.parser(JavaParser.fromJavaVersion()
          .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-dynamodb"));
    }

    @DocumentExample
    @Test
    void onlySourcesUsingTheTypesOfMatchingModules() {
        rewriteRun(
          spec -> spec.recipe(new FindSourcesInAwsMigrationScope(
            singletonList("com.amazonaws:aws-java-sdk-dynamodb using com.amazonaws.services.dynamodbv2..*"), null)),
          mavenProject("ddb",
            // with `using`, a build file is never one of the sources that use the types
            pomXml(DYNAMODB_POM.formatted("ddb")),
            srcMainJava(
              //language=java
              java(
                """
                  import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
                  class Orders {
                      AmazonDynamoDB client;
                  }
                  """,
                """
                  /*~~>*/import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
                  class Orders {
                      AmazonDynamoDB client;
                  }
                  """
              ),
              //language=java
              java(
                """
                  class Unrelated {
                  }
                  """
              )
            )
          ),
          mavenProject("other",
            //language=xml
            pomXml(
              """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>other</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            ),
            srcMainJava(
              //language=java
              java(
                """
                  import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
                  class Elsewhere {
                      AmazonDynamoDB client;
                  }
                  """
              )
            )
          )
        );
    }

    @Test
    void exceptModulesWinOverOnly() {
        List<String> dynamoDb = singletonList("com.amazonaws:aws-java-sdk-dynamodb");
        rewriteRun(
          spec -> spec.recipe(new FindSourcesInAwsMigrationScope(dynamoDb, singletonList("com.amazonaws:aws-java-sdk-core:1.x"))),
          mavenProject("ddb",
            pomXml(DYNAMODB_POM.formatted("ddb")),
            srcMainJava(
              //language=java
              java(
                """
                  class Orders {
                  }
                  """
              )
            )
          )
        );
    }
}

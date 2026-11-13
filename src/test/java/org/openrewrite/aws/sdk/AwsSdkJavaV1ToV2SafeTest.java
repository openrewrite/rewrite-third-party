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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.config.DeclarativeRecipe;
import org.openrewrite.java.JavaParser;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.kotlin.Assertions.kotlin;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.test.SourceSpecs.text;

class AwsSdkJavaV1ToV2SafeTest implements RewriteTest {

    private static final String SAFE = "org.openrewrite.aws.sdk.AwsSdkJavaV1ToV2Safe";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(AwsTestRecipes.fromResources(SAFE))
          .parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(),
            "aws-java-sdk-core",
            "aws-java-sdk-sqs",
            "aws-java-sdk-kinesis",
            "amazon-kinesis-client-1"))
          .parser(KotlinParser.builder().classpathFromResources(new InMemoryExecutionContext(),
            "aws-java-sdk-core", "aws-java-sdk-sqs"));
    }

    // The preconditions only gate AWS's nested recipes if DeclarativeRecipe wrapped every one of them
    @Test
    void preconditionsReachEveryNestedRecipe() {
        var safe = (DeclarativeRecipe) AwsTestRecipes.fromResources(SAFE);
        assertThat(safe.getPreconditions()).hasSize(2);
        List<Recipe> children = safe.getRecipeList();
        assertThat(children.getFirst().getClass().getSimpleName()).isEqualTo("PreconditionBellwether");
        var unwrapped = new ArrayList<String>();
        int[] wrapped = {0};
        walk(children.subList(1, children.size()), unwrapped, wrapped);
        assertThat(unwrapped).isEmpty();
        // AwsSdkJavaV1ToV2 and its tree of nested recipes
        assertThat(wrapped[0]).isGreaterThan(30);
    }

    private static void walk(List<Recipe> recipes, List<String> unwrapped, int[] wrapped) {
        for (Recipe r : recipes) {
            if (r.getClass().getSimpleName().startsWith("BellwetherDecorated")) {
                wrapped[0]++;
            } else {
                unwrapped.add(r.getName());
            }
            walk(r.getRecipeList(), unwrapped, wrapped);
        }
    }

    @DocumentExample
    @Test
    void javaMigratedButKotlinLeftAlone() {
        rewriteRun(
          // AWS's v2-migration leaves the `Region` it adds untyped
          spec -> spec.afterTypeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

              class JavaClients {
                  AmazonSQS sqs() {
                      return AmazonSQSClientBuilder.standard().withRegion("us-east-1").build();
                  }
              }
              """,
            """
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sqs.SqsClient;

              class JavaClients {
                  SqsClient sqs() {
                      return SqsClient.builder().region(Region.of("us-east-1")).build();
                  }
              }
              """
          ),
          //language=kotlin
          kotlin(
            """
              import com.amazonaws.services.sqs.AmazonSQS
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder

              class KotlinClients {
                  fun sqs(): AmazonSQS = AmazonSQSClientBuilder.standard().withRegion("us-east-1").build()
              }
              """
          )
        );
    }

    @Test
    void fileUsingKinesisClientLibraryV1LeftAlone() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
              import com.amazonaws.services.sqs.AmazonSQS;

              class Consumer {
                  KinesisClientLibConfiguration config;
                  AmazonSQS sqs;
              }
              """
          ),
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQS;

              class Producer {
                  AmazonSQS sqs;
              }
              """,
            """
              import software.amazon.awssdk.services.sqs.SqsClient;

              class Producer {
                  SqsClient sqs;
              }
              """
          )
        );
    }

    @Test
    void pomStillProcessed() {
        rewriteRun(
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
                          <artifactId>aws-java-sdk-sqs</artifactId>
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
                  <dependencies>
                      <dependency>
                          <groupId>software.amazon.awssdk</groupId>
                          <artifactId>sqs</artifactId>
                          <version>%s</version>
                      </dependency>
                  </dependencies>
              </project>
              """.formatted(AwsSdkV2Coordinates.version())
          )
        );
    }

    //language=yaml
    private static final String PROBE = """
      type: specs.openrewrite.org/v1beta/recipe
      name: test.SafePreconditionsProbe
      displayName: Probe
      description: Marks every file that the wrapper's preconditions let through.
      preconditions:
        - org.openrewrite.aws.sdk.search.FindAwsSdkV1MigrationTargets
        - org.openrewrite.java.search.DoesNotUseType:
            fullyQualifiedTypeName: com.amazonaws.services.kinesis.clientlibrary..*
      recipeList:
        - org.openrewrite.FindSourceFiles:
            filePattern: "**"
      """;

    @Test
    void probeMirrorsTheWrappersPreconditions() {
        var safe = (DeclarativeRecipe) AwsTestRecipes.fromResources(SAFE);
        assertThat(safe.getPreconditions()).extracting(r -> r.getName() + r.getInstanceNameSuffix())
          .containsExactly("org.openrewrite.aws.sdk.search.FindAwsSdkV1MigrationTargets",
            "org.openrewrite.java.search.DoesNotUseType" + new org.openrewrite.java.search.DoesNotUseType(
              "com.amazonaws.services.kinesis.clientlibrary..*", null).getInstanceNameSuffix());
    }

    /// Gradle dependency edits need the tooling API (see [MigrateAwsSdkV2DependenciesTest#TOOLING_API_UNAVAILABLE]),
    /// so this checks with a probe carrying the wrapper's preconditions that Gradle build scripts get through.
    @Test
    void gradleBuildScriptsPassThePreconditions() {
        rewriteRun(
          spec -> spec.recipeFromYaml(PROBE, "test.SafePreconditionsProbe"),
          //language=groovy
          buildGradle(
            """
              dependencies {
                  implementation 'com.amazonaws:aws-java-sdk-sqs:1.12.700'
              }
              """,
            """
              /*~~>*/dependencies {
                  implementation 'com.amazonaws:aws-java-sdk-sqs:1.12.700'
              }
              """
          ),
          //language=kotlin
          buildGradleKts(
            """
              dependencies {
                  implementation("com.amazonaws:aws-java-sdk-sqs:1.12.700")
              }
              """,
            """
              /*~~>*/dependencies {
                  implementation("com.amazonaws:aws-java-sdk-sqs:1.12.700")
              }
              """
          ),
          //language=kotlin
          kotlin(
            """
              class NotABuildScript
              """
          )
        );
    }

    @Test
    void nonJavaSourcesDoNotPassButVersionCatalogsDo() {
        rewriteRun(
          spec -> spec.recipeFromYaml(PROBE, "test.SafePreconditionsProbe"),
          text("import { S3 } from 'aws-sdk';", spec -> spec.path("web/src/upload.ts")),
          text("import boto3", spec -> spec.path("scripts/upload.py")),
          text("resource \"aws_s3_bucket\" \"b\" {}", spec -> spec.path("infra/main.tf")),
          text(
            """
              [libraries]
              aws-sqs = "com.amazonaws:aws-java-sdk-sqs:1.12.700"
              """,
            """
              ~~>[libraries]
              aws-sqs = "com.amazonaws:aws-java-sdk-sqs:1.12.700"
              """,
            spec -> spec.path("gradle/libs.versions.toml"))
        );
    }

    @Test
    void javaSourcesWithoutSdkV1TypesDoNotPass() {
        rewriteRun(
          spec -> spec.recipeFromYaml(PROBE, "test.SafePreconditionsProbe"),
          //language=java
          java(
            """
              class AlreadyOnV2 {
                  String bucket = "software.amazon.awssdk.services.s3.S3Client";
              }
              """
          ),
          //language=java
          java(
            """
              import com.amazonaws.services.sqs.AmazonSQS;

              class OnV1 {
                  AmazonSQS sqs;
              }
              """,
            """
              /*~~>*/import com.amazonaws.services.sqs.AmazonSQS;

              class OnV1 {
                  AmazonSQS sqs;
              }
              """
          )
        );
    }
}

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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.kotlin.Assertions.kotlin;
import static org.openrewrite.test.SourceSpecs.text;

class MigrateAwsCredentialsAndRegionResidualsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipes(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.MigrateAwsCredentialsAndRegionResiduals"), new GenerateAwsSdkV2Helpers(null, null))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sqs"));
    }

    @DocumentExample
    @Test
    void anonymousCredentials() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.auth.AWSStaticCredentialsProvider;
              import com.amazonaws.auth.AnonymousAWSCredentials;
              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

              class TestAWSConfiguration {
                  AmazonSQS sqs() {
                      return AmazonSQSClientBuilder.standard()
                              .withCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials()))
                              .build();
                  }
              }
              """,
            """
              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
              import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;

              class TestAWSConfiguration {
                  AmazonSQS sqs() {
                      return AmazonSQSClientBuilder.standard()
                              .withCredentials(AnonymousCredentialsProvider.create())
                              .build();
                  }
              }
              """
          )
        );
    }

    @Test
    void userCredentialsProvider() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.auth.AWSCredentials;
              import com.amazonaws.auth.AWSCredentialsProvider;
              import com.amazonaws.auth.BasicAWSCredentials;

              class VaultCredentialsProvider implements AWSCredentialsProvider {
                  @Override
                  public AWSCredentials getCredentials() {
                      return new BasicAWSCredentials("a", "b");
                  }

                  @Override
                  public void refresh() {
                  }
              }

              class Caller {
                  String key(AWSCredentialsProvider provider) {
                      provider.refresh();
                      return provider.getCredentials().getAWSAccessKeyId();
                  }

                  void reload(VaultCredentialsProvider vault) {
                      vault.refresh();
                  }
              }
              """,
            """
              import com.amazonaws.auth.AWSCredentials;
              import com.amazonaws.auth.AWSCredentialsProvider;
              import com.amazonaws.auth.BasicAWSCredentials;

              class VaultCredentialsProvider implements AWSCredentialsProvider {
                  @Override
                  public AWSCredentials resolveCredentials() {
                      return new BasicAWSCredentials("a", "b");
                  }

                  public void refresh() {
                  }
              }

              class Caller {
                  String key(AWSCredentialsProvider provider) {
                      return provider.resolveCredentials().getAWSAccessKeyId();
                  }

                  void reload(VaultCredentialsProvider vault) {
                      vault.refresh();
                  }
              }
              """
          )
        );
    }

    @Test
    void webIdentityTokenCredentialsProvider() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.auth.AWSCredentialsProvider;
              import com.amazonaws.auth.WebIdentityTokenCredentialsProvider;

              class WebIdentity {
                  AWSCredentialsProvider constructed = new WebIdentityTokenCredentialsProvider();
                  AWSCredentialsProvider created = WebIdentityTokenCredentialsProvider.create();
                  AWSCredentialsProvider built = WebIdentityTokenCredentialsProvider.builder().build();

                  AWSCredentialsProvider configured(String role, String tokenFile) {
                      return WebIdentityTokenCredentialsProvider.builder()
                              .roleArn(role)
                              // the projected service account token
                              .webIdentityTokenFile(tokenFile)
                              .build();
                  }
              }
              """,
            """
              import com.amazonaws.auth.AWSCredentialsProvider;
              import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;

              import java.nio.file.Paths;

              class WebIdentity {
                  AWSCredentialsProvider constructed = WebIdentityTokenFileCredentialsProvider.create();
                  AWSCredentialsProvider created = WebIdentityTokenFileCredentialsProvider.create();
                  AWSCredentialsProvider built = WebIdentityTokenFileCredentialsProvider.create();

                  AWSCredentialsProvider configured(String role, String tokenFile) {
                      return WebIdentityTokenFileCredentialsProvider.builder()
                              .roleArn(role)
                              // the projected service account token
                              .webIdentityTokenFile(Paths.get(tokenFile))
                              .build();
                  }
              }
              """
          )
        );
    }

    @Test
    void assumeRoleCredentialsProvider() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sts")),
          //language=java
          java(
            """
              import com.amazonaws.auth.AWSCredentialsProvider;
              import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;

              class AssumeRole {
                  AWSCredentialsProvider simple(String role) {
                      return new STSAssumeRoleSessionCredentialsProvider(role, "app");
                  }

                  AWSCredentialsProvider built(String role, AWSCredentialsProvider base) {
                      return new STSAssumeRoleSessionCredentialsProvider.Builder(role, "app")
                              .withLongLivedCredentialsProvider(base)
                              .withRoleSessionDurationSeconds(900)
                              .build();
                  }
              }
              """,
            """
              import com.amazonaws.auth.AWSCredentialsProvider;
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sts.StsClient;
              import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
              import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

              class AssumeRole {
                  AWSCredentialsProvider simple(String role) {
                      return StsAssumeRoleCredentialsProvider.builder().stsClient(StsClient.builder().region(Region.AWS_GLOBAL).build()).refreshRequest(AssumeRoleRequest.builder().roleArn(role).roleSessionName("app").build()).build();
                  }

                  AWSCredentialsProvider built(String role, AWSCredentialsProvider base) {
                      return StsAssumeRoleCredentialsProvider.builder().stsClient(StsClient.builder().region(Region.AWS_GLOBAL).credentialsProvider(base).build()).refreshRequest(AssumeRoleRequest.builder().roleArn(role).roleSessionName("app").durationSeconds(900).build()).build();
                  }
              }
              """
          )
        );
    }

    @Test
    void propertiesFileCredentialsProvider() {
        rewriteRun(
          spec -> spec.expectedCyclesThatMakeChanges(1),
          //language=java
          java(
            """
              package com.adaptris.aws;

              import com.amazonaws.auth.AWSCredentialsProvider;
              import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider;
              import com.amazonaws.auth.PropertiesFileCredentialsProvider;

              import java.io.File;

              class PropertiesFileCredentialsBuilder {
                  AWSCredentialsProvider build(String propertyFile) throws Exception {
                      File file = new File(propertyFile);
                      if (file.exists()) {
                          return new PropertiesFileCredentialsProvider(file.getCanonicalPath());
                      }
                      return new ClasspathPropertiesFileCredentialsProvider(propertyFile);
                  }
              }
              """,
            """
              package com.adaptris.aws;

              import com.amazonaws.auth.AWSCredentialsProvider;

              import java.io.File;

              class PropertiesFileCredentialsBuilder {
                  AWSCredentialsProvider build(String propertyFile) throws Exception {
                      File file = new File(propertyFile);
                      if (file.exists()) {
                          return new PropertiesFileCredentialsProvider(file.getCanonicalPath());
                      }
                      return new ClasspathPropertiesFileCredentialsProvider(propertyFile);
                  }
              }
              """,
            spec -> spec.path("interlok-aws-common/src/main/java/com/adaptris/aws/PropertiesFileCredentialsBuilder.java")
          ),
          java(
            doesNotExist(),
            MigratePropertiesFileAwsCredentialsProviders.fileSource("com.adaptris.aws"),
            spec -> spec.path("interlok-aws-common/src/main/java/com/adaptris/aws/PropertiesFileCredentialsProvider.java")
          ),
          java(
            doesNotExist(),
            MigratePropertiesFileAwsCredentialsProviders.classpathSource("com.adaptris.aws"),
            spec -> spec.path("interlok-aws-common/src/main/java/com/adaptris/aws/ClasspathPropertiesFileCredentialsProvider.java")
          )
        );
    }

    @Test
    void legacyChainWhenAliasesAreReferenced() {
        // docker-compose sets the v1-only AWS_ACCESS_KEY alias while the code uses the default chain
        rewriteRun(
          spec -> spec.dataTable(AwsCredentialChainSwaps.Row.class, rows ->
              assertThat(rows).singleElement()
                .extracting(AwsCredentialChainSwaps.Row::getReplacement)
                .isEqualTo("br.com.bluesburguer.production.framework.LegacyDefaultCredentialsChain")),
          text(
            """
              services:
                production:
                  environment:
                    - AWS_ACCESS_KEY=${AWS_ACCESS_KEY}
                    - AWS_SECRET_KEY=${AWS_SECRET_KEY}
              """,
            spec -> spec.path("docker-compose.yml")
          ),
          //language=java
          java(
            """
              package br.com.bluesburguer.production.framework;

              import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder;

              class SQSConfig {
                  AmazonSQSAsync amazonSQSAsync() {
                      return AmazonSQSAsyncClientBuilder.standard()
                              .withCredentials(new DefaultAWSCredentialsProviderChain())
                              .build();
                  }
              }
              """,
            """
              package br.com.bluesburguer.production.framework;

              import com.amazonaws.services.sqs.AmazonSQSAsync;
              import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder;

              class SQSConfig {
                  AmazonSQSAsync amazonSQSAsync() {
                      return AmazonSQSAsyncClientBuilder.standard()
                              .withCredentials(new LegacyDefaultCredentialsChain())
                              .build();
                  }
              }
              """,
            spec -> spec.path("src/main/java/br/com/bluesburguer/production/framework/SQSConfig.java")
          ),
          java(
            doesNotExist(),
            MigrateAwsDefaultCredentialsChainEnvAliases.source("br.com.bluesburguer.production.framework"),
            spec -> spec.path("src/main/java/br/com/bluesburguer/production/framework/LegacyDefaultCredentialsChain.java")
          )
        );
    }

    @Test
    void regionProviderChain() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.regions.DefaultAwsRegionProviderChain;

              class Config {
                  String region() {
                      DefaultAwsRegionProviderChain regionProviderChain = new DefaultAwsRegionProviderChain();
                      return regionProviderChain.getRegion();
                  }
              }
              """,
            """
              import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

              class Config {
                  String region() {
                      DefaultAwsRegionProviderChain regionProviderChain = new DefaultAwsRegionProviderChain();
                      return regionProviderChain.getRegion().id();
                  }
              }
              """
          )
        );
    }

    @Test
    void regionAndRegionUtils() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.regions.Region;
              import com.amazonaws.regions.RegionUtils;
              import com.amazonaws.regions.Regions;

              class Regional {
                  String name(String configured) {
                      Region region = RegionUtils.getRegion(configured);
                      Region fixed = Region.getRegion(Regions.EU_WEST_1);
                      return region.getName() + fixed.getName();
                  }
              }
              """,
            """
              import com.amazonaws.regions.Regions;
              import software.amazon.awssdk.regions.Region;

              class Regional {
                  String name(String configured) {
                      Region region = Region.of(configured);
                      Region fixed = Regions.EU_WEST_1;
                      return region.id() + fixed.id();
                  }
              }
              """
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void kotlinIsLeftToTheKotlinStep() {
            rewriteRun(
              spec -> spec.parser(KotlinParser.builder()
                .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core")),
              text(
                "AWS_ACCESS_KEY=x",
                spec -> spec.path(".env")
              ),
              //language=kotlin
              kotlin(
                """
                  import com.amazonaws.auth.DefaultAWSCredentialsProviderChain

                  class SnsClientBean {
                      val credentials = DefaultAWSCredentialsProviderChain()
                  }
                  """
              )
            );
        }

        @Test
        void v2ChainWithoutAliases() {
            rewriteRun(
              //language=java
              java(
                """
                  import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;

                  class Config {
                      // AWS_ACCESS_KEY_ID is not an alias
                      String name = "AWS_ACCESS_KEY_ID";
                      DefaultAWSCredentialsProviderChain chain = DefaultAWSCredentialsProviderChain.getInstance();
                  }
                  """
              )
            );
        }

        @Test
        void assumeRoleOptionWithoutV2Equivalent() {
            rewriteRun(
              spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sts")),
              //language=java
              java(
                """
                  import com.amazonaws.auth.AWSCredentialsProvider;
                  import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;

                  class AssumeRole {
                      AWSCredentialsProvider regional(String role) {
                          return new STSAssumeRoleSessionCredentialsProvider.Builder(role, "app")
                                  .withServiceEndpoint("sts.eu-west-1.amazonaws.com")
                                  .build();
                      }
                  }
                  """
              )
            );
        }
    }
    @Test
    void clientRegionFromName() {
        rewriteRun(
          spec -> spec.recipe(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.MigrateAwsSdkV1ToV2Steps"))
            .afterTypeValidationOptions(TypeValidation.none()),
          //language=java
          java(
            """
              import com.amazonaws.regions.Regions;
              import com.amazonaws.services.sqs.AmazonSQS;
              import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

              class Queues {
                  AmazonSQS sqs(String region) {
                      return AmazonSQSClientBuilder.standard().withRegion(Regions.fromName(region)).build();
                  }
              }
              """,
            """
              import software.amazon.awssdk.regions.Region;
              import software.amazon.awssdk.services.sqs.SqsClient;

              class Queues {
                  SqsClient sqs(String region) {
                      return SqsClient.builder().region(Region.of(region)).build();
                  }
              }
              """
          )
        );
    }
}

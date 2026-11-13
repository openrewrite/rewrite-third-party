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
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateAwsProfilesConfigFileTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAwsProfilesConfigFile())
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core"))
          // The v2 profiles module is attributed from stubs in the recipe
          .typeValidationOptions(org.openrewrite.test.TypeValidation.none());
    }

    @DocumentExample
    @Test
    void profileCredentialsFromConfigFile() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.auth.AWSCredentialsProvider;
              import com.amazonaws.auth.profile.ProfileCredentialsProvider;
              import com.amazonaws.auth.profile.ProfilesConfigFile;

              class ProfileCredentialsBuilder {
                  String configFile;
                  String profileName;
                  Long refreshIntervalNanos;

                  AWSCredentialsProvider build() {
                      ProfileCredentialsProvider credentials = new ProfileCredentialsProvider(configFile(), profileName);
                      if (refreshIntervalNanos != null) {
                          credentials.setRefreshIntervalNanos(refreshIntervalNanos.longValue());
                          credentials.setRefreshForceIntervalNanos(refreshIntervalNanos.longValue() * 2);
                      }
                      return credentials;
                  }

                  private ProfilesConfigFile configFile() {
                      return configFile != null ? new ProfilesConfigFile(configFile) : null;
                  }
              }
              """,
            """
              import com.amazonaws.auth.AWSCredentialsProvider;
              import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
              import software.amazon.awssdk.profiles.ProfileFile;
              import software.amazon.awssdk.profiles.ProfileFileSupplier;

              import java.nio.file.Paths;

              class ProfileCredentialsBuilder {
                  String configFile;
                  String profileName;
                  Long refreshIntervalNanos;

                  AWSCredentialsProvider build() {
                      ProfileCredentialsProvider credentials = ProfileCredentialsProvider.builder().profileFile(configFile()).profileName(profileName).build();
                      return credentials;
                  }

                  private ProfileFileSupplier configFile() {
                      return configFile != null ? ProfileFileSupplier.reloadWhenModified(Paths.get(configFile), ProfileFile.Type.CREDENTIALS) : null;
                  }
              }
              """
          )
        );
    }
}

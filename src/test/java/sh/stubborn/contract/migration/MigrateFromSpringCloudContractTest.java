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
package sh.stubborn.contract.migration;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.yaml.Assertions.yaml;

class MigrateFromSpringCloudContractTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("sh.stubborn.contract.migration.MigrateFromSpringCloudContract");
    }

    @DocumentExample
    @Test
    void migrateMavenPlugin() {
        rewriteRun(
          pomXml(
            """
              <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <build>
                      <plugins>
                          <plugin>
                              <groupId>org.springframework.cloud</groupId>
                              <artifactId>spring-cloud-contract-maven-plugin</artifactId>
                              <version>4.1.4</version>
                          </plugin>
                      </plugins>
                  </build>
              </project>
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .containsPattern("<groupId>sh\\.stubborn</groupId>\\s*<artifactId>stubborn-contract-maven-plugin</artifactId>\\s*<version>\\d+\\.\\d+\\.\\d+</version>")
              .actual())
          )
        );
    }

    @Test
    void migrateStubRunnerProperties() {
        rewriteRun(
          properties(
            "spring.cloud.contract.stubrunner.ids=com.example:service:+:stubs:8080",
            "stubborn.contract.stubrunner.ids=com.example:service:+:stubs:8080",
            spec -> spec.path("src/test/resources/application.properties")
          ),
          yaml(
            """
              spring:
                cloud:
                  contract:
                    stubrunner:
                      stubs-mode: LOCAL
              """,
            """
              stubborn.contract.stubrunner:
                stubs-mode: LOCAL
              """,
            spec -> spec.path("src/test/resources/application.yml")
          )
        );
    }
}

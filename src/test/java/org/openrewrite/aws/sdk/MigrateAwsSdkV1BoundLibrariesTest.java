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

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.*;
import static org.openrewrite.maven.Assertions.pomXml;

class MigrateAwsSdkV1BoundLibrariesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.MigrateAwsSdkV1BoundLibraries"))
          .parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(),
            "aws-java-sdk-core",
            "aws-java-sdk-s3",
            "aws-java-sdk-kinesis",
            "amazon-kinesis-client-1",
            "amazon-kinesis-producer",
            "aws-lambda-java-events",
            "aws-lambda-java-core"));
    }

    private static String pom(String dependencies) {
        return """
          <project>
              <groupId>com.example</groupId>
              <artifactId>app</artifactId>
              <version>1.0</version>
              <dependencies>
          %s
              </dependencies>
          </project>
          """.formatted(dependencies);
    }

    private static String dep(String g, String a, String v) {
        return """
                  <dependency>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                  </dependency>""".formatted(g, a, v);
    }

    private static String marked(String dependency, String marker) {
        return dependency.replaceFirst("<dependency>", java.util.regex.Matcher.quoteReplacement("<!--~~(" + marker + ")~~>--><dependency>"));
    }

    @DocumentExample
    @Test
    void kinesisProducerLibraryMovesTo1x() {
        rewriteRun(
          mavenProject("app",
            //language=java
            srcMainJava(
              java(
                """
                  import com.amazonaws.services.kinesis.producer.KinesisProducer;
                  import com.amazonaws.services.kinesis.producer.UserRecordResult;

                  class Producer {
                      KinesisProducer producer;
                      UserRecordResult last;
                  }
                  """,
                """
                  import software.amazon.kinesis.producer.KinesisProducer;
                  import software.amazon.kinesis.producer.UserRecordResult;

                  class Producer {
                      KinesisProducer producer;
                      UserRecordResult last;
                  }
                  """
              )
            ),
            pomXml(
              pom(dep("com.amazonaws", "amazon-kinesis-producer", "0.15.12")),
              spec -> spec.after(actual -> assertThat(actual).contains("<groupId>software.amazon.kinesis</groupId>")
                         .containsPattern("<version>1\\.\\d+\\.\\d+</version>").actual())
            )
          )
        );
    }

    @Test
    void kinesisClientLibraryV1OutsideThePortIsMarkedNotMigrated() {
        // KCL 1.x's lease manager has no KCL 2.x counterpart with the same contract
        rewriteRun(
          mavenProject("consumer",
            //language=java
            srcMainJava(
              java(
                """
                  import com.amazonaws.services.kinesis.leases.interfaces.ILeaseManager;

                  class Consumer {
                      ILeaseManager<?> leases;
                  }
                  """,
                """
                  /*~~(%s)~~>*/import com.amazonaws.services.kinesis.leases.interfaces.ILeaseManager;

                  class Consumer {
                      ILeaseManager<?> leases;
                  }
                  """.formatted(MarkKinesisClientLibraryV1.CODE_MARKER)
              )
            ),
            pomXml(
              pom(dep("com.amazonaws", "amazon-kinesis-client", "1.15.2")),
              pom(marked(dep("com.amazonaws", "amazon-kinesis-client", "1.15.2"), MarkKinesisClientLibraryV1.CODE_MARKER))
            )
          )
        );
    }

    @Test
    void kinesisClientLibraryDeclaredButUnusedIsRemoved() {
        rewriteRun(
          pomXml(
            pom(dep("com.amazonaws", "amazon-kinesis-client", "1.10.0") + "\n" + dep("org.slf4j", "slf4j-api", "2.0.17")),
            pom(dep("org.slf4j", "slf4j-api", "2.0.17"))
          )
        );
    }

    @Test
    void sqsJavaMessagingLibStaysOnJavaxJms() {
        rewriteRun(
          pomXml(
            pom(dep("com.amazonaws", "amazon-sqs-java-messaging-lib", "1.0.8")),
            pom(dep("com.amazonaws", "amazon-sqs-java-messaging-lib", "2.0.3"))
          )
        );
    }

    @Test
    void sqsJavaMessagingLibOnJakartaJms() {
        rewriteRun(
          pomXml(
            pom(dep("com.amazonaws", "amazon-sqs-java-messaging-lib", "1.0.8") + "\n" +
                dep("jakarta.jms", "jakarta.jms-api", "3.1.0")),
            spec -> spec.after(actual -> assertThat(actual).containsPattern(Pattern.compile(
                       "amazon-sqs-java-messaging-lib</artifactId>\\s*<version>2\\.[1-9]\\d*\\.\\d+</version>")).actual())
          )
        );
    }

    @Test
    void xrayMovesToSdkV2Instrumentation() {
        rewriteRun(
          pomXml(
            pom(dep("com.amazonaws", "aws-xray-recorder-sdk-core", "2.15.0") + "\n" +
                dep("com.amazonaws", "aws-xray-recorder-sdk-aws-sdk", "2.15.0")),
            pom(dep("com.amazonaws", "aws-xray-recorder-sdk-core", "2.21.2") + "\n" +
                dep("com.amazonaws", "aws-xray-recorder-sdk-aws-sdk-v2", "2.21.2"))
          )
        );
    }

    @Test
    void secretsManagerCachingMovesTo2x() {
        rewriteRun(
          pomXml(
            pom(dep("com.amazonaws.secretsmanager", "aws-secretsmanager-caching-java", "1.0.1")),
            spec -> spec.after(actual -> assertThat(actual).containsPattern("<version>2\\.\\d+\\.\\d+</version>").actual())
          )
        );
    }

    @Test
    void lambdaEventsMoveTo3xWithTheirOwnModelTypes() {
        rewriteRun(
          mavenProject("fn",
            //language=java
            srcMainJava(
              java(
                """
                  import com.amazonaws.services.lambda.runtime.events.S3Event;
                  import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord;

                  class Handler {
                      String bucket(S3Event event) {
                          S3EventNotificationRecord record = event.getRecords().get(0);
                          return record.getS3().getBucket().getName();
                      }
                  }
                  """,
                """
                  import com.amazonaws.services.lambda.runtime.events.S3Event;
                  import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;

                  class Handler {
                      String bucket(S3Event event) {
                          S3EventNotificationRecord record = event.getRecords().get(0);
                          return record.getS3().getBucket().getName();
                      }
                  }
                  """
              )
            ),
            pomXml(
              pom(dep("com.amazonaws", "aws-lambda-java-events", "2.2.9")),
              spec -> spec.after(actual -> assertThat(actual).containsPattern("<version>3\\.\\d+\\.\\d+</version>").actual())
            )
          )
        );
    }
}

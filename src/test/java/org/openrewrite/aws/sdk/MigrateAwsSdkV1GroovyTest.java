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
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.groovy.Assertions.groovy;

class MigrateAwsSdkV1GroovyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAwsSdkV1Groovy())
          .parser(GroovyParser.builder().classpathFromResource(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sqs"));
    }

    @DocumentExample
    @Test
    void spockTestOfMigratedAsyncClientCode() {
        rewriteRun(
          //language=groovy
          groovy(
            """
              import com.amazonaws.services.sqs.AmazonSQSAsync
              import com.amazonaws.services.sqs.model.GetQueueUrlResult

              class QueueTemplateTest {
                  def "send test"() {
                      given:
                      def amazonSqs = Mock(AmazonSQSAsync) { getQueueUrl(_) >> getDummyQueueUrlResult() }
                      def counted = Mock(AmazonSQSAsync) { 1 * getQueueUrl(_) >> getDummyQueueUrlResult() }
                  }

                  def getDummyQueueUrlResult() {
                      def getQueueUrlResult = new GetQueueUrlResult()
                      getQueueUrlResult.setQueueUrl("dummyQueue")
                      return getQueueUrlResult
                  }

                  def emptyResult() {
                      return new GetQueueUrlResult()
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.sqs.SqsAsyncClient
              import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse

              import java.util.concurrent.CompletableFuture

              class QueueTemplateTest {
                  def "send test"() {
                      given:
                      def amazonSqs = Mock(SqsAsyncClient) { getQueueUrl(_) >> CompletableFuture.completedFuture(getDummyQueueUrlResult()) }
                      def counted = Mock(SqsAsyncClient) { 1 * getQueueUrl(_) >> CompletableFuture.completedFuture(getDummyQueueUrlResult()) }
                  }

                  def getDummyQueueUrlResult() {
                      def getQueueUrlResult = GetQueueUrlResponse.builder().queueUrl("dummyQueue").build()
                      return getQueueUrlResult
                  }

                  def emptyResult() {
                      return GetQueueUrlResponse.builder().build()
                  }
              }
              """
          )
        );
    }

    @Test
    void syncClientStubsKeepTheirValue() {
        rewriteRun(
          //language=groovy
          groovy(
            """
              import com.amazonaws.services.sqs.AmazonSQS

              class SyncTest {
                  def "receive"() {
                      given:
                      def sqs = Mock(AmazonSQS) { getQueueUrl(_) >> null }
                  }
              }
              """,
            """
              import software.amazon.awssdk.services.sqs.SqsClient

              class SyncTest {
                  def "receive"() {
                      given:
                      def sqs = Mock(SqsClient) { getQueueUrl(_) >> null }
                  }
              }
              """
          )
        );
    }
}

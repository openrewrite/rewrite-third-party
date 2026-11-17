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

class MigrateAwsRetryPoliciesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAwsRetryPolicies())
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core"));
    }

    @DocumentExample
    @Test
    void customRetryCondition() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.AmazonClientException;
              import com.amazonaws.AmazonServiceException;
              import com.amazonaws.AmazonWebServiceRequest;
              import com.amazonaws.retry.PredefinedRetryPolicies;
              import com.amazonaws.retry.RetryPolicy;

              class RetryNotFound {
                  RetryPolicy build() {
                      return new RetryPolicy(new Allow404(), PredefinedRetryPolicies.DEFAULT_BACKOFF_STRATEGY,
                          PredefinedRetryPolicies.DEFAULT_MAX_ERROR_RETRY, true);
                  }

                  private class Allow404 extends PredefinedRetryPolicies.SDKDefaultRetryCondition {
                      @Override
                      public boolean shouldRetry(AmazonWebServiceRequest orig, AmazonClientException exc, int attempts) {
                          if (exc instanceof AmazonServiceException) {
                              AmazonServiceException ase = (AmazonServiceException) exc;
                              if (ase.getStatusCode() == 404) return true;
                          }
                          return super.shouldRetry(orig, exc, attempts);
                      }
                  }
              }
              """,
            """
              import com.amazonaws.AmazonServiceException;
              import software.amazon.awssdk.core.retry.RetryMode;
              import software.amazon.awssdk.core.retry.RetryPolicy;
              import software.amazon.awssdk.core.retry.RetryPolicyContext;
              import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
              import software.amazon.awssdk.core.retry.conditions.RetryCondition;

              class RetryNotFound {
                  RetryPolicy build() {
                      return RetryPolicy.builder(RetryMode.LEGACY).retryCondition(new Allow404()).backoffStrategy(BackoffStrategy.defaultStrategy()).numRetries(3).build();
                  }

                  private class Allow404 implements RetryCondition {
                      @Override
                      public boolean shouldRetry(RetryPolicyContext context) {
                          if (context.exception() instanceof AmazonServiceException) {
                              AmazonServiceException ase = (AmazonServiceException) context.exception();
                              if (ase.getStatusCode() == 404) return true;
                          }
                          return RetryCondition.defaultRetryCondition().shouldRetry(context);
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void predefinedPoliciesAndConditionCalls() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.AmazonServiceException;
              import com.amazonaws.retry.PredefinedRetryPolicies;
              import com.amazonaws.retry.RetryPolicy;
              import com.amazonaws.retry.RetryPolicy.RetryCondition;

              /**
               * Uses {@link PredefinedRetryPolicies}.
               */
              class Policies {
                  RetryPolicy standard() {
                      return PredefinedRetryPolicies.getDefaultRetryPolicy();
                  }

                  RetryPolicy none() {
                      return PredefinedRetryPolicies.NO_RETRY_POLICY;
                  }

                  boolean retries(RetryPolicy policy, AmazonServiceException e) {
                      RetryCondition condition = policy.getRetryCondition();
                      return condition.shouldRetry(null, e, 0) && policy.getMaxErrorRetry() > 0;
                  }
              }
              """,
            """
              import com.amazonaws.AmazonServiceException;
              import software.amazon.awssdk.core.retry.RetryMode;
              import software.amazon.awssdk.core.retry.RetryPolicy;
              import software.amazon.awssdk.core.retry.RetryPolicyContext;
              import software.amazon.awssdk.core.retry.conditions.RetryCondition;

              /**
               * Uses {@link RetryPolicy}.
               */
              class Policies {
                  RetryPolicy standard() {
                      return RetryPolicy.forRetryMode(RetryMode.LEGACY);
                  }

                  RetryPolicy none() {
                      return RetryPolicy.none();
                  }

                  boolean retries(RetryPolicy policy, AmazonServiceException e) {
                      RetryCondition condition = policy.retryCondition();
                      return condition.shouldRetry(RetryPolicyContext.builder().originalRequest(null).exception(e).retriesAttempted(0).build()) && policy.numRetries() > 0;
                  }
              }
              """
          )
        );
    }

    @Test
    void customBackoffStrategy() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.AmazonClientException;
              import com.amazonaws.AmazonWebServiceRequest;
              import com.amazonaws.retry.RetryPolicy.BackoffStrategy;

              class MyBackoffStrategy implements BackoffStrategy {
                  @Override
                  public long delayBeforeNextRetry(AmazonWebServiceRequest originalRequest, AmazonClientException exception, int retriesAttempted) {
                      return 100L * retriesAttempted;
                  }
              }
              """,
            """
              import software.amazon.awssdk.core.retry.RetryPolicyContext;
              import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;

              import java.time.Duration;

              class MyBackoffStrategy implements BackoffStrategy {
                  @Override
                  public Duration computeDelayBeforeNextRetry(RetryPolicyContext context) {
                      return Duration.ofMillis(100L * context.retriesAttempted());
                  }
              }
              """
          )
        );
    }
}

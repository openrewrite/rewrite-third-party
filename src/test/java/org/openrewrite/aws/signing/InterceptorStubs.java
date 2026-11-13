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

import org.intellij.lang.annotations.Language;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.kotlin.KotlinParser;

/**
 * awslabs' interceptor is only published on JitPack, so tests compile against a stub of it.
 */
final class InterceptorStubs {

    @Language("java")
    static final String INTERCEPTOR_JAVA = """
      package com.amazonaws.http;
      import com.amazonaws.auth.AWSCredentialsProvider;
      import com.amazonaws.auth.Signer;
      import org.apache.http.HttpRequest;
      import org.apache.http.HttpRequestInterceptor;
      import org.apache.http.protocol.HttpContext;
      public class AWSRequestSigningApacheInterceptor implements HttpRequestInterceptor {
          public AWSRequestSigningApacheInterceptor(String service, Signer signer, AWSCredentialsProvider awsCredentialsProvider) {
          }
          @Override
          public void process(HttpRequest request, HttpContext context) {
          }
      }
      """;

    @Language("kotlin")
    static final String INTERCEPTOR_KOTLIN = """
      package com.amazonaws.http
      import com.amazonaws.auth.AWSCredentialsProvider
      import com.amazonaws.auth.Signer
      import org.apache.http.HttpRequest
      import org.apache.http.HttpRequestInterceptor
      import org.apache.http.protocol.HttpContext
      open class AWSRequestSigningApacheInterceptor(service: String, signer: Signer, awsCredentialsProvider: AWSCredentialsProvider) : HttpRequestInterceptor {
          override fun process(request: HttpRequest, context: HttpContext) {
          }
      }
      """;

    // vc.inreach's interceptor, with the Guava supplier its signer takes
    static final String[] INREACH_JAVA = {
      """
      package com.google.common.base;
      public interface Supplier<T> {
          T get();
      }
      """,
      """
      package vc.inreach.aws.request;
      import com.amazonaws.auth.AWSCredentialsProvider;
      import com.google.common.base.Supplier;
      import java.time.LocalDateTime;
      public class AWSSigner {
          public AWSSigner(AWSCredentialsProvider credentialsProvider, String region, String service, Supplier<LocalDateTime> clock) {
          }
      }
      """,
      """
      package vc.inreach.aws.request;
      import org.apache.http.HttpRequest;
      import org.apache.http.HttpRequestInterceptor;
      import org.apache.http.protocol.HttpContext;
      public class AWSSigningRequestInterceptor implements HttpRequestInterceptor {
          public AWSSigningRequestInterceptor(AWSSigner signer) {
          }
          @Override
          public void process(HttpRequest request, HttpContext context) {
          }
      }
      """
    };

    static final String[] CLASSPATH = {"aws-java-sdk-core", "aws-java-sdk-elasticsearch", "httpclient-4", "httpcore-4",
      "auth-2", "regions-2", "http-auth-aws-2", "http-auth-spi-2", "identity-spi-2", "utils-2", "http-client-spi-2", "sdk-core-2"};

    private InterceptorStubs() {
    }

    static JavaParser.Builder<?, ?> java() {
        return JavaParser.fromJavaVersion()
          .classpathFromResources(new InMemoryExecutionContext(), CLASSPATH)
          .dependsOn(INTERCEPTOR_JAVA);
    }

    static JavaParser.Builder<?, ?> javaWithInreach() {
        return javaWithoutStub().dependsOn(INTERCEPTOR_JAVA, INREACH_JAVA[0], INREACH_JAVA[1], INREACH_JAVA[2]);
    }

    static JavaParser.Builder<?, ?> javaWithoutStub() {
        return JavaParser.fromJavaVersion()
          .classpathFromResources(new InMemoryExecutionContext(), CLASSPATH);
    }

    static KotlinParser.Builder kotlin() {
        return KotlinParser.builder()
          .classpathFromResources(new InMemoryExecutionContext(), CLASSPATH)
          .dependsOn(INTERCEPTOR_KOTLIN);
    }

    static String shim(String pkg) {
        return ReplaceAwsRequestSigningApacheInterceptor.render(pkg, "AwsSigV4RequestInterceptor");
    }
}

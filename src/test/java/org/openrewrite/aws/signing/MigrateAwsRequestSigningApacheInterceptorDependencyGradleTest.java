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
import org.openrewrite.aws.sdk.AwsSdkV2Coordinates;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.maven.tree.Dependency;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.maven.tree.MavenRepository;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.LinkedHashMap;
import java.util.List;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.srcMainJava;

class MigrateAwsRequestSigningApacheInterceptorDependencyGradleTest implements RewriteTest {

    private static final String V = AwsSdkV2Coordinates.version();

    //language=java
    private static final String SITE = """
      package com.example;

      import com.amazonaws.auth.AWS4Signer;
      import com.amazonaws.auth.AWSCredentialsProvider;
      import com.amazonaws.http.AWSRequestSigningApacheInterceptor;

      class Clients {
          Object interceptor(AWSCredentialsProvider credentials) {
              AWS4Signer signer = new AWS4Signer();
              signer.setServiceName("es");
              return new AWSRequestSigningApacheInterceptor("es", signer, credentials);
          }
      }
      """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAwsRequestSigningApacheInterceptorDependency(null))
          .parser(InterceptorStubs.java());
    }

    // Built by hand, as the tooling API init script cannot be resolved in this build (see `MigrateAwsSdkV2DependenciesTest`)
    private static GradleProject gradleProject(String configuration) {
        Dependency requested = Dependency.builder()
          .gav(new GroupArtifactVersion("com.github.awslabs", "aws-request-signing-apache-interceptor", "b3772780da"))
          .build();
        GradleDependencyConfiguration declared = GradleDependencyConfiguration.builder()
          .name(configuration)
          .isCanBeResolved(false)
          .extendsFrom(List.of())
          .requested(List.of(requested))
          .directResolved(List.of())
          .build();
        GradleDependencyConfiguration compileClasspath = GradleDependencyConfiguration.builder()
          .name("compileClasspath")
          .isCanBeResolved(true)
          .extendsFrom(List.of(declared))
          .requested(List.of())
          .directResolved(List.of())
          .build();
        var configurations = new LinkedHashMap<String, GradleDependencyConfiguration>();
        configurations.put(configuration, declared);
        configurations.put("compileClasspath", compileClasspath);
        return GradleProject.builder()
          .group("com.example")
          .name("example")
          .version("1.0")
          .path(":")
          .mavenRepositories(List.of(MavenRepository.MAVEN_CENTRAL,
            new MavenRepository("jitpack", "https://jitpack.io", "true", "false", true, null, null, null, true)))
          .nameToConfiguration(configurations)
          .build();
    }

    @Test
    void groovyImplementation() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }
              repositories {
                  mavenCentral()
                  maven { url 'https://jitpack.io' }
              }
              dependencies {
                  implementation 'com.github.awslabs:aws-request-signing-apache-interceptor:b3772780da'
              }
              """,
            """
              plugins {
                  id 'java'
              }
              repositories {
                  mavenCentral()
                  maven { url 'https://jitpack.io' }
              }
              dependencies {
                  implementation 'software.amazon.awssdk:auth:%s'
              }
              """.formatted(V),
            spec -> spec.markers(gradleProject("implementation"))
          ),
          srcMainJava(java(SITE))
        );
    }

    @Test
    void kotlinDslAndJitPackRemoval() {
        rewriteRun(
          spec -> spec.recipe(new MigrateAwsRequestSigningApacheInterceptorDependency(true)),
          buildGradleKts(
            """
              plugins {
                  java
              }
              repositories {
                  mavenCentral()
                  maven("https://jitpack.io")
              }
              dependencies {
                  implementation("com.github.awslabs:aws-request-signing-apache-interceptor:b3772780da")
              }
              """,
            """
              plugins {
                  java
              }
              repositories {
                  mavenCentral()
              }
              dependencies {
                  implementation("software.amazon.awssdk:auth:%s")
              }
              """.formatted(V),
            spec -> spec.markers(gradleProject("implementation"))
          ),
          srcMainJava(java(SITE))
        );
    }

    @Test
    void groovyCompileConfiguration() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }
              repositories {
                  mavenCentral()
                  maven { url 'https://jitpack.io' }
              }
              dependencies {
                  compile 'com.github.awslabs:aws-request-signing-apache-interceptor:b3772780da'
              }
              """,
            """
              plugins {
                  id 'java'
              }
              repositories {
                  mavenCentral()
                  maven { url 'https://jitpack.io' }
              }
              dependencies {
                  compile 'software.amazon.awssdk:auth:%s'
              }
              """.formatted(V),
            spec -> spec.markers(gradleProject("compile"))
          ),
          srcMainJava(java(SITE))
        );
    }
}

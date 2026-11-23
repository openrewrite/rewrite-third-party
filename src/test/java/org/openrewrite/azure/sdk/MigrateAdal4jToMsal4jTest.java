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
package org.openrewrite.azure.sdk;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.maven.Assertions.pomXml;

class MigrateAdal4jToMsal4jTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.azure.sdk.MigrateAzureSdkLegacyToCurrent")
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "adal4j-1"));
    }

    @DocumentExample
    @Test
    void clientCredentials() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.aad.adal4j.AuthenticationContext;
              import com.microsoft.aad.adal4j.AuthenticationResult;
              import com.microsoft.aad.adal4j.ClientCredential;

              import java.util.concurrent.ExecutorService;
              import java.util.concurrent.Executors;
              import java.util.concurrent.Future;

              class KeyVaultCredentials {
                  AuthenticationResult token(String authority, String resource, String clientId, String clientKey) {
                      ExecutorService service = Executors.newFixedThreadPool(1);
                      try {
                          AuthenticationContext context = new AuthenticationContext(authority, false, service);
                          ClientCredential credentials = new ClientCredential(clientId, clientKey);
                          Future<AuthenticationResult> future = context.acquireToken(resource, credentials, null);
                          return future.get();
                      } catch (Exception e) {
                          throw new RuntimeException(e);
                      } finally {
                          service.shutdown();
                      }
                  }
              }
              """,
            """
              import com.microsoft.aad.msal4j.ClientCredentialFactory;
              import com.microsoft.aad.msal4j.ClientCredentialParameters;
              import com.microsoft.aad.msal4j.ConfidentialClientApplication;
              import com.microsoft.aad.msal4j.IAuthenticationResult;

              import java.util.Collections;
              import java.util.concurrent.ExecutorService;
              import java.util.concurrent.Executors;
              import java.util.concurrent.Future;

              class KeyVaultCredentials {
                  IAuthenticationResult token(String authority, String resource, String clientId, String clientKey) {
                      ExecutorService service = Executors.newFixedThreadPool(1);
                      try {
                          String context = authority;
                          ConfidentialClientApplication.Builder credentials = ConfidentialClientApplication.builder(clientId, ClientCredentialFactory.createFromSecret(clientKey));
                          Future<IAuthenticationResult> future = credentials.authority(context).build().acquireToken(ClientCredentialParameters.builder(Collections.singleton(resource + "/.default")).build());
                          return future.get();
                      } catch (Exception e) {
                          throw new RuntimeException(e);
                      } finally {
                          service.shutdown();
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void accessTokenOfAFieldContext() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.aad.adal4j.AuthenticationContext;
              import com.microsoft.aad.adal4j.ClientCredential;

              import java.net.MalformedURLException;
              import java.util.Objects;
              import java.util.concurrent.ExecutionException;
              import java.util.concurrent.ExecutorService;

              class VaultCredentials {
                  private AuthenticationContext authenticationContext;
                  private ExecutorService executorService;

                  String authenticate(String authorization, String clientId, String clientSecret) {
                      try {
                          if (Objects.isNull(authenticationContext)) {
                              this.authenticationContext = new AuthenticationContext(authorization, false, executorService);
                          }
                          ClientCredential credential = new ClientCredential(clientId, clientSecret);
                          return authenticationContext.acquireToken("https://vault.azure.net", credential, null).get().getAccessToken();
                      } catch (ExecutionException | MalformedURLException ex) {
                          throw new RuntimeException(ex);
                      } catch (InterruptedException ex) {
                          Thread.currentThread().interrupt();
                          throw new RuntimeException(ex);
                      }
                  }
              }
              """,
            """
              import com.microsoft.aad.msal4j.ClientCredentialFactory;
              import com.microsoft.aad.msal4j.ClientCredentialParameters;
              import com.microsoft.aad.msal4j.ConfidentialClientApplication;

              import java.net.MalformedURLException;
              import java.util.Collections;
              import java.util.Objects;
              import java.util.concurrent.ExecutionException;
              import java.util.concurrent.ExecutorService;

              class VaultCredentials {
                  private String authenticationContext;
                  private ExecutorService executorService;

                  String authenticate(String authorization, String clientId, String clientSecret) {
                      try {
                          if (Objects.isNull(authenticationContext)) {
                              this.authenticationContext = authorization;
                          }
                          ConfidentialClientApplication.Builder credential = ConfidentialClientApplication.builder(clientId, ClientCredentialFactory.createFromSecret(clientSecret));
                          return credential.authority(authenticationContext).build().acquireToken(ClientCredentialParameters.builder(Collections.singleton("https://vault.azure.net/.default")).build()).get().accessToken();
                      } catch (ExecutionException | MalformedURLException ex) {
                          throw new RuntimeException(ex);
                      } catch (InterruptedException ex) {
                          Thread.currentThread().interrupt();
                          throw new RuntimeException(ex);
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void usernameAndPassword() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.aad.adal4j.AuthenticationContext;
              import com.microsoft.aad.adal4j.AuthenticationResult;

              import java.util.concurrent.ExecutorService;
              import java.util.concurrent.Executors;
              import java.util.concurrent.Future;

              class Login {
                  static final String AUTHORITY = "https://login.microsoftonline.com/common/";

                  String login(String resource, String appId, String username, String password) throws Exception {
                      ExecutorService service = Executors.newFixedThreadPool(1);
                      AuthenticationContext context = new AuthenticationContext(AUTHORITY, false, service);
                      Future<AuthenticationResult> future = context.acquireToken(
                              resource,
                              appId,
                              username,
                              password,
                              null);
                      AuthenticationResult result = future.get();
                      return result.getUserInfo().getDisplayableId() + " " + result.getAccessTokenType() + " " + result.getAccessToken();
                  }
              }
              """,
            """
              import com.microsoft.aad.msal4j.IAuthenticationResult;
              import com.microsoft.aad.msal4j.PublicClientApplication;
              import com.microsoft.aad.msal4j.UserNamePasswordParameters;

              import java.util.Collections;
              import java.util.concurrent.ExecutorService;
              import java.util.concurrent.Executors;
              import java.util.concurrent.Future;

              class Login {
                  static final String AUTHORITY = "https://login.microsoftonline.com/common/";

                  String login(String resource, String appId, String username, String password) throws Exception {
                      ExecutorService service = Executors.newFixedThreadPool(1);
                      String context = AUTHORITY;
                      Future<IAuthenticationResult> future = PublicClientApplication.builder(appId).authority(context).build().acquireToken(UserNamePasswordParameters.builder(Collections.singleton(resource + "/.default"), username, password.toCharArray()).build());
                      IAuthenticationResult result = future.get();
                      return result.account().username() + " " + "Bearer" + " " + result.accessToken();
                  }
              }
              """
          )
        );
    }

    @Test
    void refreshToken() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.aad.adal4j.AuthenticationContext;
              import com.microsoft.aad.adal4j.AuthenticationResult;
              import com.microsoft.aad.adal4j.ClientCredential;

              import java.io.IOException;
              import java.util.concurrent.ExecutionException;

              class Refresher {
                  private final AuthenticationContext context;
                  private final ClientCredential credential;

                  Refresher(AuthenticationContext context, ClientCredential credential) {
                      this.context = context;
                      this.credential = credential;
                  }

                  AuthenticationResult refresh(String refreshToken, String resource) throws IOException, ExecutionException, InterruptedException {
                      return context.acquireTokenByRefreshToken(refreshToken, credential, resource, null).get();
                  }
              }
              """,
            """
              import com.microsoft.aad.msal4j.ConfidentialClientApplication;
              import com.microsoft.aad.msal4j.IAuthenticationResult;
              import com.microsoft.aad.msal4j.RefreshTokenParameters;

              import java.io.IOException;
              import java.util.Collections;
              import java.util.concurrent.ExecutionException;

              class Refresher {
                  private final String context;
                  private final ConfidentialClientApplication.Builder credential;

                  Refresher(String context, ConfidentialClientApplication.Builder credential) {
                      this.context = context;
                      this.credential = credential;
                  }

                  IAuthenticationResult refresh(String refreshToken, String resource) throws IOException, ExecutionException, InterruptedException {
                      return credential.authority(context).build().acquireToken(RefreshTokenParameters.builder(Collections.singleton(resource + "/.default"), refreshToken).build()).get();
                  }
              }
              """
          )
        );
    }

    @Test
    void authorityOfAContextMadeElsewhere() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.aad.adal4j.AuthenticationContext;
              import com.microsoft.aad.adal4j.AuthenticationResult;
              import com.microsoft.aad.adal4j.ClientCredential;

              import java.util.concurrent.ExecutionException;

              class Tokens {
                  AuthenticationContext context;

                  String accessTokenOf(String resource, ClientCredential credential) throws ExecutionException, InterruptedException {
                      AuthenticationResult result = context.acquireToken(resource, credential, null).get();
                      long expiresIn = result.getExpiresOn();
                      return result.getAccessToken();
                  }
              }
              """,
            """
              import com.microsoft.aad.msal4j.ClientCredentialParameters;
              import com.microsoft.aad.msal4j.ConfidentialClientApplication;
              import com.microsoft.aad.msal4j.IAuthenticationResult;

              import java.net.MalformedURLException;
              import java.util.Collections;
              import java.util.concurrent.ExecutionException;
              import java.util.concurrent.TimeUnit;

              class Tokens {
                  String context;

                  String accessTokenOf(String resource, ConfidentialClientApplication.Builder credential) throws ExecutionException, InterruptedException {
                      IAuthenticationResult result = withAuthority(credential, context).build().acquireToken(ClientCredentialParameters.builder(Collections.singleton(resource + "/.default")).build()).get();
                      long expiresIn = TimeUnit.MILLISECONDS.toSeconds(result.expiresOnDate().getTime() - System.currentTimeMillis());
                      return result.accessToken();
                  }

                  private static ConfidentialClientApplication.Builder withAuthority(ConfidentialClientApplication.Builder application, String authority) {
                      try {
                          return application.authority(authority);
                      } catch (MalformedURLException e) {
                          // ADAL's AuthenticationContext validated this authority when it was created
                          throw new IllegalArgumentException(e);
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void swapsMavenDependency() {
        rewriteRun(
          mavenProject("app",
            srcMainJava(
              //language=java
              java(
                """
                  import com.microsoft.aad.adal4j.AuthenticationContext;
                  import com.microsoft.aad.adal4j.ClientCredential;

                  class Token {
                      String get(String authority, String id, String secret) throws Exception {
                          AuthenticationContext context = new AuthenticationContext(authority, true, null);
                          return context.acquireToken("https://graph.microsoft.com", new ClientCredential(id, secret), null).get().getAccessToken();
                      }
                  }
                  """,
                """
                  import com.microsoft.aad.msal4j.ClientCredentialFactory;
                  import com.microsoft.aad.msal4j.ClientCredentialParameters;
                  import com.microsoft.aad.msal4j.ConfidentialClientApplication;

                  import java.util.Collections;

                  class Token {
                      String get(String authority, String id, String secret) throws Exception {
                          String context = authority;
                          return ConfidentialClientApplication.builder(id, ClientCredentialFactory.createFromSecret(secret)).authority(context).build().acquireToken(ClientCredentialParameters.builder(Collections.singleton("https://graph.microsoft.com/.default")).build()).get().accessToken();
                      }
                  }
                  """
              )
            ),
            //language=xml
            pomXml(
              """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                    <!-- MSAL4J, unlike the Azure SDK, doesn't run on Jackson -->
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.fasterxml.jackson.core</groupId>
                                <artifactId>jackson-databind</artifactId>
                                <version>2.9.9</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.microsoft.azure</groupId>
                            <artifactId>adal4j</artifactId>
                            <version>1.6.7</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
              spec -> spec.after(pom -> {
                  assertThat(pom)
                    .contains("<artifactId>msal4j</artifactId>")
                    .doesNotContain("<artifactId>adal4j</artifactId>")
                    .containsPattern("<version>1\\.\\d+\\.\\d+</version>");
                  return pom;
              })
            )
          )
        );
    }
}

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
import static org.openrewrite.java.Assertions.version;

class MigrateAwsSdkV1UtilitiesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipes(AwsTestRecipes.fromResources("org.openrewrite.aws.sdk.MigrateAwsSdkV1Utilities"), new GenerateAwsSdkV2Helpers(null, null))
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-s3"));
    }

    @DocumentExample
    @Test
    void ioUtilsOnJava8() {
        rewriteRun(
          version(
            //language=java
            java(
              """
                import com.amazonaws.util.IOUtils;

                import java.io.IOException;
                import java.io.InputStream;
                import java.io.OutputStream;

                class RootInterceptor {
                    byte[] read(InputStream inputStream) throws IOException {
                        return IOUtils.toByteArray(inputStream);
                    }

                    void write(InputStream ios, OutputStream out) throws IOException {
                        IOUtils.copy(ios, out);
                    }
                }
                """,
              """
                import software.amazon.awssdk.utils.IoUtils;

                import java.io.IOException;
                import java.io.InputStream;
                import java.io.OutputStream;

                class RootInterceptor {
                    byte[] read(InputStream inputStream) throws IOException {
                        return IoUtils.toByteArray(inputStream);
                    }

                    void write(InputStream ios, OutputStream out) throws IOException {
                        IoUtils.copy(ios, out);
                    }
                }
                """
            ),
            8
          )
        );
    }

    @Test
    void ioUtilsOnJava17() {
        rewriteRun(
          version(
            //language=java
            java(
              """
                import com.amazonaws.util.IOUtils;

                import java.io.IOException;
                import java.io.InputStream;

                class CortexClient {
                    String body(InputStream content) throws IOException {
                        return IOUtils.toString(content);
                    }

                    byte[] bytes(InputStream content) throws IOException {
                        return IOUtils.toByteArray(content);
                    }
                }
                """,
              """
                import java.io.IOException;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;

                class CortexClient {
                    String body(InputStream content) throws IOException {
                        return new String(content.readAllBytes(), StandardCharsets.UTF_8);
                    }

                    byte[] bytes(InputStream content) throws IOException {
                        return content.readAllBytes();
                    }
                }
                """
            ),
            17
          )
        );
    }

    @Test
    void remainingUtilityMethods() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.util.Base64;
              import com.amazonaws.util.IOUtils;
              import com.amazonaws.util.StringUtils;

              import java.io.InputStream;
              import java.math.BigDecimal;
              import java.nio.ByteBuffer;
              import java.util.Date;

              class Marshaller {
                  void close(InputStream in) {
                      IOUtils.closeQuietly(in, null);
                  }

                  String values(Integer i, BigDecimal d, ByteBuffer buffer, String s) {
                      String numbers = StringUtils.fromInteger(i) + StringUtils.fromBigDecimal(d);
                      return numbers + StringUtils.fromByteBuffer(buffer) + StringUtils.replace(s, "a", "b") + StringUtils.fromString(s);
                  }

                  boolean starts(String s) {
                      return StringUtils.beginsWithIgnoreCase(s, "x") && StringUtils.compare(s, "y") < 0;
                  }

                  String dates(Date date) {
                      return StringUtils.fromDate(date) + StringUtils.fromDate(date, "unixTimestamp");
                  }

                  BigDecimal number(String s) {
                      return StringUtils.toBigDecimal(s);
                  }

                  void compact(StringBuilder out, String s) {
                      StringUtils.appendCompactedString(out, s);
                  }

                  String twoBytes(byte a, byte b) {
                      return Base64.encodeAsString(a, b);
                  }
              }
              """,
            """
              import software.amazon.awssdk.utils.BinaryUtils;
              import software.amazon.awssdk.utils.IoUtils;
              import software.amazon.awssdk.utils.StringUtils;

              import java.io.InputStream;
              import java.math.BigDecimal;
              import java.nio.ByteBuffer;
              import java.text.Collator;
              import java.time.ZoneOffset;
              import java.time.format.DateTimeFormatter;
              import java.util.Base64;
              import java.util.Date;
              import java.util.Locale;

              class Marshaller {
                  void close(InputStream in) {
                      IoUtils.closeQuietly(in, null);
                  }

                  String values(Integer i, BigDecimal d, ByteBuffer buffer, String s) {
                      String numbers = Integer.toString(i) + d.toString();
                      return numbers + BinaryUtils.toBase64(BinaryUtils.copyBytesFrom(buffer)) + s.replace("a", "b") + s;
                  }

                  boolean starts(String s) {
                      return StringUtils.startsWithIgnoreCase(s, "x") && Collator.getInstance(Locale.ENGLISH).compare(s, "y") < 0;
                  }

                  String dates(Date date) {
                      return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(date.toInstant()) + BigDecimal.valueOf(date.getTime()).scaleByPowerOfTen(-3).toPlainString();
                  }

                  BigDecimal number(String s) {
                      return new BigDecimal(s);
                  }

                  void compact(StringBuilder out, String s) {
                      out.append(s.replaceAll("\\\\s+", " "));
                  }

                  String twoBytes(byte a, byte b) {
                      return Base64.getEncoder().encodeToString(new byte[]{a, b});
                  }
              }
              """
          )
        );
    }

    @Test
    void stringUtilsBase64AndStringInputStream() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.util.Base64;
              import com.amazonaws.util.StringInputStream;
              import com.amazonaws.util.StringUtils;

              import java.io.InputStream;

              class Utilities {
                  boolean blank(String agentName) {
                      return StringUtils.isNullOrEmpty(agentName) || !StringUtils.hasValue(agentName);
                  }

                  String md5(byte[] hash) {
                      return Base64.encodeAsString(hash);
                  }

                  String dims(String[] dimensions) {
                      return "[" + StringUtils.join(", ", dimensions) + "]";
                  }

                  InputStream secret(String clientSecret) throws Exception {
                      return new StringInputStream(clientSecret);
                  }
              }
              """,
            """
              import software.amazon.awssdk.utils.StringInputStream;
              import software.amazon.awssdk.utils.StringUtils;

              import java.io.InputStream;
              import java.util.Base64;

              class Utilities {
                  boolean blank(String agentName) {
                      return StringUtils.isEmpty(agentName) || !!StringUtils.isEmpty(agentName);
                  }

                  String md5(byte[] hash) {
                      return Base64.getEncoder().encodeToString(hash);
                  }

                  String dims(String[] dimensions) {
                      return "[" + String.join(", ", dimensions) + "]";
                  }

                  InputStream secret(String clientSecret) throws Exception {
                      return new StringInputStream(clientSecret);
                  }
              }
              """
          )
        );
    }

    @Test
    void cborDisabledBecomesCborEnabledFalse() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.SDKGlobalConfiguration;

              import static com.amazonaws.SDKGlobalConfiguration.AWS_CBOR_DISABLE_SYSTEM_PROPERTY;

              class LocalStackConfig {
                  void setUp() {
                      System.setProperty(AWS_CBOR_DISABLE_SYSTEM_PROPERTY, "true"); // Disable CBOR
                      System.setProperty(SDKGlobalConfiguration.AWS_REGION_SYSTEM_PROPERTY, "us-west-1");
                  }

                  void tearDown() {
                      System.clearProperty(SDKGlobalConfiguration.AWS_CBOR_DISABLE_SYSTEM_PROPERTY);
                  }
              }
              """,
            """
              import software.amazon.awssdk.core.SdkSystemSetting;

              class LocalStackConfig {
                  void setUp() {
                      System.setProperty(SdkSystemSetting.CBOR_ENABLED.property(), "false"); // Disable CBOR
                      System.setProperty(SdkSystemSetting.AWS_REGION.property(), "us-west-1");
                  }

                  void tearDown() {
                      System.clearProperty(SdkSystemSetting.CBOR_ENABLED.property());
                  }
              }
              """
          )
        );
    }

    @Test
    void globalSettingsWithoutV2SettingAreInlined() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.SDKGlobalConfiguration;

              class Settings {
                  void configure(String endpoint) {
                      System.setProperty(SDKGlobalConfiguration.EC2_METADATA_SERVICE_OVERRIDE_SYSTEM_PROPERTY, endpoint);
                      System.setProperty(SDKGlobalConfiguration.DISABLE_CERT_CHECKING_SYSTEM_PROPERTY, "true");
                      SDKGlobalConfiguration.setGlobalTimeOffset(10);
                  }

                  boolean insecure() {
                      return SDKGlobalConfiguration.isCertCheckingDisabled();
                  }

                  boolean binary() {
                      return !SDKGlobalConfiguration.isCborDisabled();
                  }

                  int offset() {
                      return SDKGlobalConfiguration.getGlobalTimeOffset() + SDKGlobalConfiguration.DEFAULT_AWS_CSM_PORT;
                  }
              }
              """,
            """
              class Settings {
                  void configure(String endpoint) {
                      System.setProperty("com.amazonaws.sdk.ec2MetadataServiceEndpointOverride", endpoint);
                      System.setProperty("com.amazonaws.sdk.disableCertChecking", "true");
                  }

                  boolean insecure() {
                      return (System.getProperty("com.amazonaws.sdk.disableCertChecking") != null && !"false".equalsIgnoreCase(System.getProperty("com.amazonaws.sdk.disableCertChecking")));
                  }

                  boolean binary() {
                      return !((System.getProperty("com.amazonaws.sdk.disableCbor") != null && !"false".equalsIgnoreCase(System.getProperty("com.amazonaws.sdk.disableCbor"))) || (System.getenv("AWS_CBOR_DISABLE") != null && !"false".equalsIgnoreCase(System.getenv("AWS_CBOR_DISABLE"))));
                  }

                  int offset() {
                      return 0 + 31000;
                  }
              }
              """
          )
        );
    }

    @Test
    void iamPolicy() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.auth.policy.Policy;
              import com.amazonaws.auth.policy.Resource;
              import com.amazonaws.auth.policy.Statement;
              import com.amazonaws.auth.policy.actions.S3Actions;

              class S3Helper {
                  String getPolicy(String bucketName, String key) {
                      String resource = "arn:aws:s3:::" + bucketName + "/" + key;
                      Statement statement = new Statement(Statement.Effect.Allow).withActions(
                              S3Actions.GetObject).withResources(new Resource(resource));
                      Policy policy = new Policy().withStatements(statement);
                      return policy.toJson();
                  }
              }
              """,
            """
              import software.amazon.awssdk.policybuilder.iam.IamEffect;
              import software.amazon.awssdk.policybuilder.iam.IamPolicy;
              import software.amazon.awssdk.policybuilder.iam.IamStatement;

              class S3Helper {
                  String getPolicy(String bucketName, String key) {
                      String resource = "arn:aws:s3:::" + bucketName + "/" + key;
                      IamStatement statement = IamStatement.builder().effect(IamEffect.ALLOW).addAction("s3:GetObject").addResource(resource).build();
                      IamPolicy policy = IamPolicy.builder().addStatement(statement).build();
                      return policy.toJson();
                  }
              }
              """
          )
        );
    }

    @Test
    void iamPolicySerializedInline() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.auth.policy.Policy;
              import com.amazonaws.auth.policy.Resource;
              import com.amazonaws.auth.policy.Statement;
              import com.amazonaws.auth.policy.actions.S3Actions;

              class S3Helper {
                  String getPolicy(String resource) {
                      return new Policy().withStatements(new Statement(Statement.Effect.Deny)
                              .withActions(S3Actions.AllS3Actions).withResources(new Resource(resource))).toJson();
                  }
              }
              """,
            """
              import software.amazon.awssdk.policybuilder.iam.IamEffect;
              import software.amazon.awssdk.policybuilder.iam.IamPolicy;
              import software.amazon.awssdk.policybuilder.iam.IamStatement;

              class S3Helper {
                  String getPolicy(String resource) {
                      return IamPolicy.builder().addStatement(IamStatement.builder().effect(IamEffect.DENY).addAction("s3:*").addResource(resource).build()).build().toJson();
                  }
              }
              """
          )
        );
    }

    @Test
    void iamPolicyFromStatementArray() {
        rewriteRun(
          //language=java
          java(
            """
              import com.amazonaws.auth.policy.Policy;
              import com.amazonaws.auth.policy.Statement;

              class Policies {
                  String json(Statement[] statements) {
                      Policy policy = new Policy().withStatements(statements);
                      return policy.toJson();
                  }
              }
              """,
            """
              import software.amazon.awssdk.policybuilder.iam.IamPolicy;
              import software.amazon.awssdk.policybuilder.iam.IamStatement;

              import java.util.Arrays;

              class Policies {
                  String json(IamStatement[] statements) {
                      IamPolicy policy = IamPolicy.builder().statements(Arrays.asList(statements)).build();
                      return policy.toJson();
                  }
              }
              """
          )
        );
    }

    @Test
    void iamPolicyWithPrincipalsAndConditions() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "aws-java-sdk-core", "aws-java-sdk-sqs")),
          //language=java
          java(
            """
              import com.amazonaws.auth.policy.Policy;
              import com.amazonaws.auth.policy.Principal;
              import com.amazonaws.auth.policy.Resource;
              import com.amazonaws.auth.policy.Statement;
              import com.amazonaws.auth.policy.actions.SQSActions;
              import com.amazonaws.auth.policy.conditions.ConditionFactory;
              import com.amazonaws.auth.policy.conditions.IpAddressCondition;
              import com.amazonaws.auth.policy.conditions.StringCondition;

              class QueuePolicy {
                  String allowTopic(String queueArn, String topicArn, String account) {
                      return new Policy().withStatements(new Statement(Statement.Effect.Allow)
                              .withPrincipals(Principal.AllUsers, new Principal("1234-5678-9012"), new Principal(account),
                                      new Principal(Principal.Services.AmazonEC2))
                              .withActions(SQSActions.SendMessage, SQSActions.AllSQSActions)
                              .withResources(new Resource(queueArn))
                              .withConditions(ConditionFactory.newSourceArnCondition(topicArn),
                                      new IpAddressCondition("10.0.0.0/8"),
                                      new StringCondition(StringCondition.StringComparisonType.StringEquals,
                                              ConditionFactory.USER_AGENT_CONDITION_KEY, "app"))).toJson();
                  }

                  String action() {
                      return SQSActions.ReceiveMessage.getActionName();
                  }
              }
              """,
            """
              import software.amazon.awssdk.policybuilder.iam.IamEffect;
              import software.amazon.awssdk.policybuilder.iam.IamPolicy;
              import software.amazon.awssdk.policybuilder.iam.IamStatement;

              class QueuePolicy {
                  String allowTopic(String queueArn, String topicArn, String account) {
                      return IamPolicy.builder().addStatement(IamStatement.builder().effect(IamEffect.ALLOW).addPrincipal("AWS", "*").addPrincipal("AWS", "123456789012").addPrincipal("AWS", account.replace("-", "")).addPrincipal("Service", "ec2.amazonaws.com").addAction("sqs:SendMessage").addAction("sqs:*").addResource(queueArn).addCondition("ArnLike", "aws:SourceArn", topicArn).addCondition("IpAddress", "aws:SourceIp", "10.0.0.0/8").addCondition("StringEquals", "aws:UserAgent", "app").build()).build().toJson();
                  }

                  String action() {
                      return "sqs:ReceiveMessage";
                  }
              }
              """
          )
        );
    }

    @Test
    void ec2MetadataUtils() {
        rewriteRun(
          //language=java
          java(
            """
              package com.example;

              import com.amazonaws.util.EC2MetadataUtils;

              import java.util.ArrayList;
              import java.util.List;
              import java.util.Map;

              class CacheConfiguration {
                  String address() {
                      String ipAddress = EC2MetadataUtils.getPrivateIpAddress();
                      return ipAddress;
                  }

                  String region() {
                      return EC2MetadataUtils.getEC2InstanceRegion();
                  }

                  String account() {
                      EC2MetadataUtils.InstanceInfo info = EC2MetadataUtils.getInstanceInfo();
                      return info == null ? null : info.getAccountId();
                  }

                  String roleKey() {
                      Map<String, EC2MetadataUtils.IAMSecurityCredential> credentials = EC2MetadataUtils.getIAMSecurityCredentials();
                      return credentials.values().iterator().next().accessKeyId;
                  }

                  List<String> subnets() {
                      List<String> subnets = new ArrayList<>();
                      for (EC2MetadataUtils.NetworkInterface ni : EC2MetadataUtils.getNetworkInterfaces()) {
                          subnets.add(ni.getSubnetId());
                      }
                      return subnets;
                  }
              }
              """,
            """
              package com.example;

              import java.util.ArrayList;
              import java.util.List;
              import java.util.Map;

              class CacheConfiguration {
                  String address() {
                      String ipAddress = Ec2MetadataUtils.getPrivateIpAddress();
                      return ipAddress;
                  }

                  String region() {
                      return Ec2MetadataUtils.getEC2InstanceRegion();
                  }

                  String account() {
                      Ec2MetadataUtils.InstanceInfo info = Ec2MetadataUtils.getInstanceInfo();
                      return info == null ? null : info.getAccountId();
                  }

                  String roleKey() {
                      Map<String, Ec2MetadataUtils.IAMSecurityCredential> credentials = Ec2MetadataUtils.getIAMSecurityCredentials();
                      return credentials.values().iterator().next().accessKeyId;
                  }

                  List<String> subnets() {
                      List<String> subnets = new ArrayList<>();
                      for (Ec2MetadataUtils.NetworkInterface ni : Ec2MetadataUtils.getNetworkInterfaces()) {
                          subnets.add(ni.getSubnetId());
                      }
                      return subnets;
                  }
              }
              """,
            spec -> spec.path("src/main/java/com/example/CacheConfiguration.java")
          ),
          java(
            doesNotExist(),
            MigrateEc2MetadataUtils.source("com.example"),
            spec -> spec.path("src/main/java/com/example/Ec2MetadataUtils.java")
          )
        );
    }
}

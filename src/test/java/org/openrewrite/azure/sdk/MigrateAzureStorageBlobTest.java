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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.maven.Assertions.pomXml;

class MigrateAzureStorageBlobTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.azure.sdk.MigrateAzureSdkLegacyToCurrent")
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "azure-storage-8"));
    }

    @DocumentExample
    @Test
    void uploadAndDownload() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.CloudStorageAccount;
              import com.microsoft.azure.storage.StorageException;
              import com.microsoft.azure.storage.blob.CloudBlobClient;
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import com.microsoft.azure.storage.blob.CloudBlockBlob;

              import java.io.IOException;
              import java.io.InputStream;
              import java.net.URISyntaxException;
              import java.security.InvalidKeyException;

              class Files {
                  private final CloudBlobContainer container;

                  Files(String connectionString) throws URISyntaxException, InvalidKeyException, StorageException {
                      CloudStorageAccount account = CloudStorageAccount.parse(connectionString);
                      CloudBlobClient client = account.createCloudBlobClient();
                      container = client.getContainerReference("files");
                      container.createIfNotExists();
                  }

                  void save(String name, String text) {
                      try {
                          CloudBlockBlob blob = container.getBlockBlobReference(name);
                          blob.uploadText(text);
                      } catch (URISyntaxException | StorageException | IOException e) {
                          throw new IllegalStateException(e);
                      }
                  }

                  void save(String name, InputStream in, long length) throws Exception {
                      container.getBlockBlobReference(name).upload(in, length);
                  }

                  String load(String name) throws Exception {
                      CloudBlockBlob blob = container.getBlockBlobReference(name);
                      if (!blob.exists()) {
                          return null;
                      }
                      blob.downloadAttributes();
                      long size = blob.getProperties().getLength();
                      return blob.downloadText();
                  }

                  int status(StorageException e) {
                      return e.getHttpStatusCode();
                  }
              }
              """,
            """
              import com.azure.core.util.BinaryData;
              import com.azure.storage.blob.BlobClient;
              import com.azure.storage.blob.BlobContainerClient;
              import com.azure.storage.blob.BlobServiceClient;
              import com.azure.storage.blob.BlobServiceClientBuilder;
              import com.azure.storage.blob.models.BlobStorageException;

              import java.io.InputStream;
              import java.net.URISyntaxException;
              import java.security.InvalidKeyException;

              class Files {
                  private final BlobContainerClient container;

                  Files(String connectionString) throws URISyntaxException, InvalidKeyException, BlobStorageException {
                      String account = connectionString;
                      BlobServiceClient client = new BlobServiceClientBuilder().connectionString(account).buildClient();
                      container = client.getBlobContainerClient("files");
                      container.createIfNotExists();
                  }

                  void save(String name, String text) {
                      try {
                          BlobClient blob = container.getBlobClient(name);
                          blob.upload(BinaryData.fromString(text), true);
                      } catch (BlobStorageException e) {
                          throw new IllegalStateException(e);
                      }
                  }

                  void save(String name, InputStream in, long length) throws Exception {
                      container.getBlobClient(name).upload(in, length, true);
                  }

                  String load(String name) throws Exception {
                      BlobClient blob = container.getBlobClient(name);
                      if (!blob.exists()) {
                          return null;
                      }
                      blob.getProperties();
                      long size = blob.getProperties().getBlobSize();
                      return blob.downloadContent().toString();
                  }

                  int status(BlobStorageException e) {
                      return e.getStatusCode();
                  }
              }
              """
          )
        );
    }

    @Test
    void listingLoop() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.blob.CloudBlob;
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import com.microsoft.azure.storage.blob.CloudBlockBlob;
              import com.microsoft.azure.storage.blob.ListBlobItem;

              import java.util.ArrayList;
              import java.util.List;

              class Listing {
                  List<String> list(CloudBlobContainer container, String prefix) throws Exception {
                      List<String> uris = new ArrayList<>();
                      for (ListBlobItem item : container.listBlobs(prefix, true)) {
                          if (item instanceof CloudBlob) {
                              uris.add(item.getUri().toString());
                              ((CloudBlob) item).delete();
                          }
                      }
                      for (ListBlobItem item : container.listBlobs()) {
                          if (item instanceof CloudBlockBlob) {
                              CloudBlockBlob blob = (CloudBlockBlob) item;
                              uris.add(blob.getName());
                          }
                      }
                      return uris;
                  }
              }
              """,
            """
              import com.azure.storage.blob.BlobClient;
              import com.azure.storage.blob.BlobContainerClient;
              import com.azure.storage.blob.models.BlobItem;
              import com.azure.storage.blob.models.BlobType;
              import com.azure.storage.blob.models.ListBlobsOptions;

              import java.net.URI;
              import java.util.ArrayList;
              import java.util.List;

              class Listing {
                  List<String> list(BlobContainerClient container, String prefix) throws Exception {
                      List<String> uris = new ArrayList<>();
                      for (BlobItem item : container.listBlobs(new ListBlobsOptions().setPrefix(prefix), null)) {
                          if (!Boolean.TRUE.equals(item.isPrefix())) {
                              uris.add(URI.create(container.getBlobClient(item.getName()).getBlobUrl()).toString());
                              container.getBlobClient(item.getName()).delete();
                          }
                      }
                      for (BlobItem item : container.listBlobsByHierarchy(null)) {
                          if (!Boolean.TRUE.equals(item.isPrefix()) && item.getProperties().getBlobType() == BlobType.BLOCK_BLOB) {
                              BlobClient blob = container.getBlobClient(item.getName());
                              uris.add(blob.getBlobName());
                          }
                      }
                      return uris;
                  }
              }
              """
          )
        );
    }

    @Test
    void listingLoopOverAVariable() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import com.microsoft.azure.storage.blob.ListBlobItem;

              class Listing {
                  void print(CloudBlobContainer container) throws Exception {
                      Iterable<ListBlobItem> items = container.listBlobs();
                      int prefix = container.getUri().toString().length() + 1;
                      for (ListBlobItem item : items) {
                          System.out.println(item.getUri().toString().substring(prefix));
                      }
                  }
              }
              """,
            """
              import com.azure.storage.blob.BlobContainerClient;
              import com.azure.storage.blob.models.BlobItem;

              import java.net.URI;

              class Listing {
                  void print(BlobContainerClient container) throws Exception {
                      Iterable<BlobItem> items = container.listBlobsByHierarchy(null);
                      int prefix = URI.create(container.getBlobContainerUrl()).toString().length() + 1;
                      for (BlobItem item : items) {
                          System.out.println(URI.create(container.getBlobClient(item.getName()).getBlobUrl()).toString().substring(prefix));
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void sharedAccessSignature() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.blob.CloudBlockBlob;
              import com.microsoft.azure.storage.blob.SharedAccessBlobPermissions;
              import com.microsoft.azure.storage.blob.SharedAccessBlobPolicy;

              import java.util.Date;
              import java.util.EnumSet;

              class Sas {
                  String sas(CloudBlockBlob blob, Date expiry) throws Exception {
                      SharedAccessBlobPolicy policy = new SharedAccessBlobPolicy();
                      policy.setPermissions(EnumSet.of(SharedAccessBlobPermissions.READ, SharedAccessBlobPermissions.WRITE));
                      policy.setSharedAccessExpiryTime(expiry);
                      return blob.getUri() + "?" + blob.generateSharedAccessSignature(policy, null);
                  }
              }
              """,
            """
              import com.azure.storage.blob.BlobClient;
              import com.azure.storage.blob.sas.BlobSasPermission;
              import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

              import java.net.URI;
              import java.time.ZoneOffset;
              import java.util.Date;

              class Sas {
                  String sas(BlobClient blob, Date expiry) throws Exception {
                      BlobServiceSasSignatureValues policy = new BlobServiceSasSignatureValues();
                      policy.setPermissions(new BlobSasPermission().setReadPermission(true).setWritePermission(true));
                      policy.setExpiryTime(expiry.toInstant().atOffset(ZoneOffset.UTC));
                      return URI.create(blob.getBlobUrl()) + "?" + blob.generateSas(policy);
                  }
              }
              """
          )
        );
    }

    @Test
    void containerAccessAndMetadata() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.blob.BlobContainerPermissions;
              import com.microsoft.azure.storage.blob.BlobContainerPublicAccessType;
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import com.microsoft.azure.storage.blob.CloudBlockBlob;

              import java.util.HashMap;

              class Access {
                  void open(CloudBlobContainer container, CloudBlockBlob blob, HashMap<String, String> tags) throws Exception {
                      BlobContainerPermissions permissions = new BlobContainerPermissions();
                      permissions.setPublicAccess(BlobContainerPublicAccessType.CONTAINER);
                      container.uploadPermissions(permissions);
                      container.createIfNotExists(BlobContainerPublicAccessType.BLOB, null, null);
                      blob.setMetadata(tags);
                      blob.uploadMetadata();
                      HashMap<String, String> metadata = blob.getMetadata();
                  }
              }
              """,
            """
              import com.azure.core.util.Context;
              import com.azure.storage.blob.BlobClient;
              import com.azure.storage.blob.BlobContainerClient;
              import com.azure.storage.blob.models.PublicAccessType;
              import com.azure.storage.blob.options.BlobContainerCreateOptions;

              import java.util.HashMap;

              class Access {
                  void open(BlobContainerClient container, BlobClient blob, HashMap<String, String> tags) throws Exception {
                      PublicAccessType permissions = null;
                      permissions = PublicAccessType.CONTAINER;
                      container.setAccessPolicy(permissions, null);
                      container.createIfNotExistsWithResponse(new BlobContainerCreateOptions().setPublicAccessType(PublicAccessType.BLOB), null, Context.NONE).getValue();
                      blob.setMetadata(tags);
                      HashMap<String, String> metadata = new HashMap<>(blob.getProperties().getMetadata());
                  }
              }
              """
          )
        );
    }

    @Test
    void accountFromNameAndKey() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.CloudStorageAccount;
              import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
              import com.microsoft.azure.storage.blob.CloudBlobClient;

              class Account {
                  CloudBlobClient client(String name, String key) throws Exception {
                      CloudStorageAccount account = new CloudStorageAccount(new StorageCredentialsAccountAndKey(name, key), true);
                      return account.createCloudBlobClient();
                  }
              }
              """,
            """
              import com.azure.storage.blob.BlobServiceClient;
              import com.azure.storage.blob.BlobServiceClientBuilder;

              class Account {
                  BlobServiceClient client(String name, String key) throws Exception {
                      String account = "DefaultEndpointsProtocol=https;AccountName=" + name + ";AccountKey=" + key;
                      return new BlobServiceClientBuilder().connectionString(account).buildClient();
                  }
              }
              """
          )
        );
    }

    @Test
    void marksWhatItCannotMigrate() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.OperationContext;
              import com.microsoft.azure.storage.blob.CloudBlockBlob;

              class Remnants {
                  int read(CloudBlockBlob blob, byte[] buffer) throws Exception {
                      OperationContext context = new OperationContext();
                      blob.downloadAttributes();
                      return blob.downloadToByteArray(buffer, 0);
                  }
              }
              """,
            """
              import com.microsoft.azure.storage.OperationContext;
              import com.microsoft.azure.storage.blob.CloudBlockBlob;

              class Remnants {
                  int read(CloudBlockBlob blob, byte[] buffer) throws Exception {
                      /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.OperationContext` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/OperationContext context = /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.OperationContext` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/new OperationContext();
                      blob.downloadAttributes();
                      return /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.blob.CloudBlob.downloadToByteArray` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/blob.downloadToByteArray(buffer, 0);
                  }
              }
              """
          )
        );
    }

    @Test
    void headersSetBeforeAnUpload() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import com.microsoft.azure.storage.blob.CloudBlockBlob;

              import java.io.InputStream;

              class Uploads {
                  void save(CloudBlobContainer container, String name, InputStream in, long length, String type) throws Exception {
                      CloudBlockBlob blob = container.getBlockBlobReference(name);
                      blob.getProperties().setContentType(type);
                      blob.getProperties().setCacheControl("max-age=60");
                      blob.upload(in, length);
                  }

                  void save(CloudBlobContainer container, String path) throws Exception {
                      CloudBlockBlob blob = container.getBlockBlobReference(path);
                      blob.getProperties().setContentType("text/html");
                      blob.uploadFromFile(path);
                  }
              }
              """,
            """
              import com.azure.core.util.BinaryData;
              import com.azure.core.util.Context;
              import com.azure.storage.blob.BlobClient;
              import com.azure.storage.blob.BlobContainerClient;
              import com.azure.storage.blob.models.BlobHttpHeaders;
              import com.azure.storage.blob.options.BlobParallelUploadOptions;
              import com.azure.storage.blob.options.BlobUploadFromFileOptions;

              import java.io.InputStream;

              class Uploads {
                  void save(BlobContainerClient container, String name, InputStream in, long length, String type) throws Exception {
                      BlobClient blob = container.getBlobClient(name);
                      blob.uploadWithResponse(new BlobParallelUploadOptions(BinaryData.fromStream(in, length)).setHeaders(new BlobHttpHeaders().setContentType(type).setCacheControl("max-age=60")), null, Context.NONE);
                  }

                  void save(BlobContainerClient container, String path) throws Exception {
                      BlobClient blob = container.getBlobClient(path);
                      blob.uploadFromFileWithResponse(new BlobUploadFromFileOptions(path).setHeaders(new BlobHttpHeaders().setContentType("text/html")), null, Context.NONE);
                  }
              }
              """
          ),
          // Setting a header with no upload to carry it has no counterpart, so this source is left as it is
          //language=java
          java(
            """
              import com.microsoft.azure.storage.blob.CloudBlockBlob;

              class Labels {
                  void label(CloudBlockBlob blob) {
                      blob.getProperties().setContentType("text/plain");
                      System.out.println(blob.getName());
                  }
              }
              """,
            """
              import com.microsoft.azure.storage.blob.CloudBlockBlob;

              class Labels {
                  void label(CloudBlockBlob blob) {
                      /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.blob.BlobProperties.setContentType` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/blob.getProperties().setContentType("text/plain");
                      System.out.println(blob.getName());
                  }
              }
              """
          )
        );
    }

    @Test
    void contentMd5OfABlobWithoutOne() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.blob.BlobProperties;

              class Checksums {
                  String md5(BlobProperties properties) {
                      if (properties.getContentMD5() != null) {
                          return properties.getContentMD5();
                      }
                      return "";
                  }
              }
              """,
            """
              import com.azure.storage.blob.models.BlobProperties;

              import java.util.Base64;
              import java.util.Optional;

              class Checksums {
                  String md5(BlobProperties properties) {
                      if (properties.getContentMd5() != null) {
                          return Optional.ofNullable(properties.getContentMd5()).map(Base64.getEncoder()::encodeToString).orElse(null);
                      }
                      return "";
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
                  import com.microsoft.azure.storage.CloudStorageAccount;
                  import com.microsoft.azure.storage.blob.CloudBlobClient;

                  class Clients {
                      CloudBlobClient blobs(String connectionString) throws Exception {
                          return CloudStorageAccount.parse(connectionString).createCloudBlobClient();
                      }
                  }
                  """,
                """
                  import com.azure.storage.blob.BlobServiceClient;
                  import com.azure.storage.blob.BlobServiceClientBuilder;

                  class Clients {
                      BlobServiceClient blobs(String connectionString) throws Exception {
                          return new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
                      }
                  }
                  """
              )
            ),
            //language=xml
            pomXml(
              """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.microsoft.azure</groupId>
                      <artifactId>azure-storage</artifactId>
                      <version>8.6.6</version>
                    </dependency>
                  </dependencies>
                </project>
                """,
              spec -> spec.after(pom -> {
                  assertThat(pom).doesNotContain("<artifactId>azure-storage</artifactId>");
                  assertThat(pom).containsPattern("<artifactId>azure-storage-blob</artifactId>\\s*<version>12\\.\\d+\\.\\d+</version>");
                  return pom;
              })
            )
          )
        );
    }

    @Test
    void keepsTheLegacyArtifactForASourceItLeaves() {
        rewriteRun(
          mavenProject("app",
            srcMainJava(
              //language=java
              java(
                """
                  import com.microsoft.azure.storage.CloudStorageAccount;
                  import com.microsoft.azure.storage.blob.CloudBlobClient;

                  class Clients {
                      CloudBlobClient blobs(String connectionString) throws Exception {
                          return CloudStorageAccount.parse(connectionString).createCloudBlobClient();
                      }
                  }
                  """,
                """
                  import com.azure.storage.blob.BlobServiceClient;
                  import com.azure.storage.blob.BlobServiceClientBuilder;

                  class Clients {
                      BlobServiceClient blobs(String connectionString) throws Exception {
                          return new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
                      }
                  }
                  """
              ),
              // Migrated around the read, which has no counterpart, it would not compile, so none of it is
              //language=java
              java(
                """
                  import com.microsoft.azure.storage.blob.CloudBlockBlob;

                  class Reads {
                      int read(CloudBlockBlob blob, byte[] buffer) throws Exception {
                          blob.downloadAttributes();
                          return blob.downloadToByteArray(buffer, 0);
                      }
                  }
                  """,
                """
                  import com.microsoft.azure.storage.blob.CloudBlockBlob;

                  class Reads {
                      int read(CloudBlockBlob blob, byte[] buffer) throws Exception {
                          blob.downloadAttributes();
                          return /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.blob.CloudBlob.downloadToByteArray` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/blob.downloadToByteArray(buffer, 0);
                      }
                  }
                  """
              )
            ),
            //language=xml
            pomXml(
              """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.microsoft.azure</groupId>
                      <artifactId>azure-storage</artifactId>
                      <version>8.6.6</version>
                    </dependency>
                  </dependencies>
                </project>
                """,
              spec -> spec.after(pom -> {
                  assertThat(pom).contains("<artifactId>azure-storage</artifactId>");
                  assertThat(pom).containsPattern("<artifactId>azure-storage-blob</artifactId>\\s*<version>12\\.\\d+\\.\\d+</version>");
                  return pom;
              })
            )
          )
        );
    }

    @Test
    void leavesASourceWithTheOneItSharesLegacyTypesWith() {
        rewriteRun(
          // Migrated, it would hand Reads a BlobContainerClient where Reads, which can't be migrated, takes a CloudBlobContainer
          //language=java
          java(
            """
              import com.microsoft.azure.storage.CloudStorageAccount;
              import com.microsoft.azure.storage.blob.CloudBlobContainer;

              class Containers {
                  static CloudBlobContainer container(String connectionString, String name) throws Exception {
                      return CloudStorageAccount.parse(connectionString).createCloudBlobClient().getContainerReference(name);
                  }
              }
              """,
            """
              import com.microsoft.azure.storage.CloudStorageAccount;
              import com.microsoft.azure.storage.blob.CloudBlobContainer;

              /*~~(Legacy Azure SDK: left as it is with `Reads.java`, which shares its legacy types and has uses to migrate by hand)~~>*/class Containers {
                  static CloudBlobContainer container(String connectionString, String name) throws Exception {
                      return CloudStorageAccount.parse(connectionString).createCloudBlobClient().getContainerReference(name);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import com.microsoft.azure.storage.blob.CloudBlockBlob;

              class Reads {
                  int read(String connectionString, byte[] buffer) throws Exception {
                      CloudBlockBlob blob = Containers.container(connectionString, "logs").getBlockBlobReference("today");
                      return blob.downloadToByteArray(buffer, 0);
                  }
              }
              """,
            """
              import com.microsoft.azure.storage.blob.CloudBlockBlob;

              class Reads {
                  int read(String connectionString, byte[] buffer) throws Exception {
                      CloudBlockBlob blob = Containers.container(connectionString, "logs").getBlockBlobReference("today");
                      return /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.blob.CloudBlob.downloadToByteArray` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/blob.downloadToByteArray(buffer, 0);
                  }
              }
              """
          ),
          // Shares nothing with them
          //language=java
          java(
            """
              import com.microsoft.azure.storage.CloudStorageAccount;
              import com.microsoft.azure.storage.blob.CloudBlobClient;

              class Clients {
                  CloudBlobClient blobs(String connectionString) throws Exception {
                      return CloudStorageAccount.parse(connectionString).createCloudBlobClient();
                  }
              }
              """,
            """
              import com.azure.storage.blob.BlobServiceClient;
              import com.azure.storage.blob.BlobServiceClientBuilder;

              class Clients {
                  BlobServiceClient blobs(String connectionString) throws Exception {
                      return new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
                  }
              }
              """
          )
        );
    }

    @Test
    void leavesABeanWithWhatItIsInjectedInto() {
        rewriteRun(
          //language=java
          java(
            """
              package org.springframework.context.annotation;

              public @interface Bean {
              }
              """
          ),
          //language=java
          java(
            """
              package org.springframework.stereotype;

              public @interface Service {
              }
              """
          ),
          // Migrated, it would provide a BlobContainerClient bean and nothing would inject the CloudBlobContainer Reads takes
          //language=java
          java(
            """
              import com.microsoft.azure.storage.CloudStorageAccount;
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import org.springframework.context.annotation.Bean;

              class StorageConfig {
                  @Bean
                  CloudBlobContainer container() throws Exception {
                      return CloudStorageAccount.parse("UseDevelopmentStorage=true").createCloudBlobClient().getContainerReference("logs");
                  }
              }
              """,
            """
              import com.microsoft.azure.storage.CloudStorageAccount;
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import org.springframework.context.annotation.Bean;

              /*~~(Legacy Azure SDK: left as it is with `Reads.java`, which shares its legacy types and has uses to migrate by hand)~~>*/class StorageConfig {
                  @Bean
                  CloudBlobContainer container() throws Exception {
                      return CloudStorageAccount.parse("UseDevelopmentStorage=true").createCloudBlobClient().getContainerReference("logs");
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import org.springframework.stereotype.Service;

              @Service
              class Reads {
                  private final CloudBlobContainer container;

                  Reads(CloudBlobContainer container) {
                      this.container = container;
                  }

                  int read(byte[] buffer) throws Exception {
                      return container.getBlockBlobReference("today").downloadToByteArray(buffer, 0);
                  }
              }
              """,
            """
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import org.springframework.stereotype.Service;

              @Service
              class Reads {
                  private final CloudBlobContainer container;

                  Reads(CloudBlobContainer container) {
                      this.container = container;
                  }

                  int read(byte[] buffer) throws Exception {
                      return /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.blob.CloudBlob.downloadToByteArray` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/container.getBlockBlobReference("today").downloadToByteArray(buffer, 0);
                  }
              }
              """
          )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"StringUtils.isBlank(name)", "name == null"})
    void declaresWhatOnlyTheLegacyArtifactBrought(String blank) {
        // commons-lang3 arrives only through azure-storage, and the module's other code uses it, or only imports it,
        // which javac needs resolved all the same
        JavaSourceSet main = new JavaSourceSet(Tree.randomId(), "main", Collections.emptyList(),
          Collections.singletonMap("org.apache.commons:commons-lang3:3.4",
            Collections.singletonList(JavaType.ShallowClass.build("org.apache.commons.lang3.StringUtils"))));
        rewriteRun(
          //language=java
          java(
            """
              package org.apache.commons.lang3;

              public class StringUtils {
                  public static boolean isBlank(CharSequence s) {
                      return s == null;
                  }
              }
              """
          ),
          mavenProject("app",
            srcMainJava(
              //language=java
              java(
                """
                  import com.microsoft.azure.storage.CloudStorageAccount;
                  import com.microsoft.azure.storage.blob.CloudBlobClient;

                  class Clients {
                      CloudBlobClient blobs(String connectionString) throws Exception {
                          return CloudStorageAccount.parse(connectionString).createCloudBlobClient();
                      }
                  }
                  """,
                """
                  import com.azure.storage.blob.BlobServiceClient;
                  import com.azure.storage.blob.BlobServiceClientBuilder;

                  class Clients {
                      BlobServiceClient blobs(String connectionString) throws Exception {
                          return new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
                      }
                  }
                  """
              ),
              //language=java
              java(
                """
                  import org.apache.commons.lang3.StringUtils;

                  class Names {
                      boolean blank(String name) {
                          return %s;
                      }
                  }
                  """.formatted(blank),
                spec -> spec.markers(main)
              )
            ),
            //language=xml
            pomXml(
              """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.microsoft.azure</groupId>
                      <artifactId>azure-storage</artifactId>
                      <version>8.6.6</version>
                    </dependency>
                  </dependencies>
                </project>
                """,
              spec -> spec.after(pom -> {
                  assertThat(pom).doesNotContain("<artifactId>azure-storage</artifactId>");
                  assertThat(pom).containsPattern("<artifactId>commons-lang3</artifactId>\\s*<version>3\\.4</version>");
                  // jackson-core and slf4j-api, which the module does not use, come with the current artifact anyway
                  assertThat(pom).doesNotContain("jackson-core", "slf4j-api");
                  return pom;
              })
            )
          )
        );
    }

    @Test
    void alignsWithTheAzureSdkArtifactsTheModuleAlreadyHas() {
        rewriteRun(
          mavenProject("app",
            srcMainJava(
              //language=java
              java(
                """
                  import com.microsoft.azure.storage.CloudStorageAccount;
                  import com.microsoft.azure.storage.blob.CloudBlobClient;

                  class Clients {
                      CloudBlobClient blobs(String connectionString) throws Exception {
                          return CloudStorageAccount.parse(connectionString).createCloudBlobClient();
                      }
                  }
                  """,
                """
                  import com.azure.storage.blob.BlobServiceClient;
                  import com.azure.storage.blob.BlobServiceClientBuilder;

                  class Clients {
                      BlobServiceClient blobs(String connectionString) throws Exception {
                          return new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
                      }
                  }
                  """
              )
            ),
            //language=xml
            pomXml(
              """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.microsoft.azure</groupId>
                      <artifactId>azure-storage</artifactId>
                      <version>8.6.6</version>
                    </dependency>
                    <dependency>
                      <groupId>com.azure</groupId>
                      <artifactId>azure-messaging-eventhubs</artifactId>
                      <version>5.10.3</version>
                    </dependency>
                  </dependencies>
                </project>
                """,
              spec -> spec.after(pom -> {
                  assertThat(pom).doesNotContain("<artifactId>azure-storage</artifactId>");
                  assertThat(pom).containsPattern("<artifactId>azure-sdk-bom</artifactId>\\s*<version>1\\.\\d+\\.\\d+</version>\\s*<type>pom</type>\\s*<scope>import</scope>");
                  assertThat(pom).containsPattern("<artifactId>azure-storage-blob</artifactId>\\s*</dependency>");
                  assertThat(pom).containsPattern("<artifactId>azure-messaging-eventhubs</artifactId>\\s*</dependency>");
                  return pom;
              })
            )
          )
        );
    }

    @Test
    void defaultOptionsListingDetailsAndAccountName() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.Constants;
              import com.microsoft.azure.storage.OperationContext;
              import com.microsoft.azure.storage.StorageException;
              import com.microsoft.azure.storage.blob.BlobContainerPublicAccessType;
              import com.microsoft.azure.storage.blob.BlobListingDetails;
              import com.microsoft.azure.storage.blob.BlobRequestOptions;
              import com.microsoft.azure.storage.blob.CloudBlob;
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import com.microsoft.azure.storage.blob.ListBlobItem;

              import java.util.ArrayList;
              import java.util.EnumSet;
              import java.util.List;

              class Snapshots {
                  List<String> snapshots(CloudBlobContainer container, String key) throws StorageException {
                      container.createIfNotExists(BlobContainerPublicAccessType.OFF, new BlobRequestOptions(), new OperationContext());
                      List<String> ids = new ArrayList<>();
                      for (ListBlobItem item : container.listBlobs(key, true, EnumSet.of(BlobListingDetails.SNAPSHOTS), null, null)) {
                          CloudBlob blob = (CloudBlob) item;
                          ids.add(blob.getName());
                      }
                      ids.add(container.getServiceClient().getCredentials().getAccountName());
                      ids.add(Constants.UTF8_CHARSET);
                      return ids;
                  }
              }
              """,
            """
              import com.azure.storage.blob.BlobClient;
              import com.azure.storage.blob.BlobContainerClient;
              import com.azure.storage.blob.models.BlobItem;
              import com.azure.storage.blob.models.BlobListDetails;
              import com.azure.storage.blob.models.BlobStorageException;
              import com.azure.storage.blob.models.ListBlobsOptions;

              import java.util.ArrayList;
              import java.util.List;

              class Snapshots {
                  List<String> snapshots(BlobContainerClient container, String key) throws BlobStorageException {
                      container.createIfNotExists();
                      List<String> ids = new ArrayList<>();
                      for (BlobItem item : container.listBlobs(new ListBlobsOptions().setPrefix(key).setDetails(new BlobListDetails().setRetrieveSnapshots(true)), null)) {
                          BlobClient blob = container.getBlobClient(item.getName(), item.getSnapshot());
                          ids.add(blob.getBlobName());
                      }
                      ids.add(container.getServiceClient().getAccountName());
                      ids.add("UTF-8");
                      return ids;
                  }
              }
              """
          )
        );
    }

    @Test
    void marksListingItemsCastOutsideALoop() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.blob.CloudBlob;
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import com.microsoft.azure.storage.blob.ListBlobItem;

              import java.util.Iterator;

              class Names {
                  void print(CloudBlobContainer container) {
                      Iterator<ListBlobItem> items = container.listBlobs("logs/", true).iterator();
                      while (items.hasNext()) {
                          ListBlobItem item = items.next();
                          if (item instanceof CloudBlob) {
                              System.out.println(((CloudBlob) item).getName());
                          }
                      }
                  }
              }
              """,
            """
              import com.microsoft.azure.storage.blob.CloudBlob;
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import com.microsoft.azure.storage.blob.ListBlobItem;

              import java.util.Iterator;

              class Names {
                  void print(CloudBlobContainer container) {
                      Iterator<ListBlobItem> items = container.listBlobs("logs/", true).iterator();
                      while (items.hasNext()) {
                          ListBlobItem item = items.next();
                          if (/*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.blob.ListBlobItem as com.microsoft.azure.storage.blob.CloudBlob` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/item instanceof CloudBlob) {
                              System.out.println((/*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.blob.ListBlobItem as com.microsoft.azure.storage.blob.CloudBlob` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/(CloudBlob) item).getName());
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void keepsAnAccountMadeFromCredentialsItCannotMigrate() {
        rewriteRun(
          //language=java
          java(
            """
              import com.microsoft.azure.storage.CloudStorageAccount;
              import com.microsoft.azure.storage.StorageCredentials;

              class Accounts {
                  CloudStorageAccount account(StorageCredentials credentials) throws Exception {
                      return new CloudStorageAccount(credentials, true);
                  }
              }
              """,
            """
              import com.microsoft.azure.storage.CloudStorageAccount;
              import com.microsoft.azure.storage.StorageCredentials;

              class Accounts {
                  CloudStorageAccount account(/*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.StorageCredentials` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/StorageCredentials credentials) throws Exception {
                      return /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.CloudStorageAccount` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/new CloudStorageAccount(credentials, true);
                  }
              }
              """
          )
        );
    }

    @Test
    void stubbingsStayOnTheMock() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "azure-storage-8", "mockito-core-5")),
          //language=java
          java(
            """
              import com.microsoft.azure.storage.blob.CloudBlobClient;
              import com.microsoft.azure.storage.blob.CloudBlobContainer;
              import com.microsoft.azure.storage.blob.CloudBlockBlob;

              import static org.mockito.ArgumentMatchers.anyString;
              import static org.mockito.Mockito.*;

              class StorageTest {
                  CloudBlobClient client = mock(CloudBlobClient.class);
                  CloudBlobContainer container = mock(CloudBlobContainer.class);
                  CloudBlockBlob blob = mock(CloudBlockBlob.class);

                  void setUp() throws Exception {
                      when(client.getContainerReference(anyString())).thenReturn(container);
                      when(container.getBlockBlobReference(anyString())).thenReturn(blob);
                      verify(blob).exists();
                  }
              }
              """,
            """
              import com.azure.storage.blob.BlobClient;
              import com.azure.storage.blob.BlobContainerClient;
              import com.azure.storage.blob.BlobServiceClient;

              import static org.mockito.ArgumentMatchers.anyString;
              import static org.mockito.Mockito.*;

              class StorageTest {
                  BlobServiceClient client = mock(BlobServiceClient.class);
                  BlobContainerClient container = mock(BlobContainerClient.class);
                  BlobClient blob = mock(BlobClient.class);

                  void setUp() throws Exception {
                      when(client.getBlobContainerClient(anyString())).thenReturn(container);
                      when(container.getBlobClient(anyString())).thenReturn(blob);
                      verify(blob).exists();
                  }
              }
              """
          ),
          // A stubbing of a call that has no counterpart leaves its source as it is
          //language=java
          java(
            """
              import com.microsoft.azure.storage.CloudStorageAccount;
              import com.microsoft.azure.storage.blob.BlobProperties;
              import com.microsoft.azure.storage.blob.CloudBlobClient;

              import java.util.Date;

              import static org.mockito.Mockito.*;

              class AccountTest {
                  CloudBlobClient client = mock(CloudBlobClient.class);
                  CloudStorageAccount account = mock(CloudStorageAccount.class);
                  BlobProperties properties = mock(BlobProperties.class);

                  void setUp() throws Exception {
                      when(account.createCloudBlobClient()).thenReturn(client);
                      when(properties.getLastModified()).thenReturn(new Date());
                  }
              }
              """,
            """
              import com.microsoft.azure.storage.CloudStorageAccount;
              import com.microsoft.azure.storage.blob.BlobProperties;
              import com.microsoft.azure.storage.blob.CloudBlobClient;

              import java.util.Date;

              import static org.mockito.Mockito.*;

              class AccountTest {
                  CloudBlobClient client = mock(CloudBlobClient.class);
                  CloudStorageAccount account = mock(CloudStorageAccount.class);
                  BlobProperties properties = mock(BlobProperties.class);

                  void setUp() throws Exception {
                      when(/*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.CloudStorageAccount.createCloudBlobClient` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/account.createCloudBlobClient()).thenReturn(client);
                      when(/*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.blob.BlobProperties.getLastModified` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/properties.getLastModified()).thenReturn(new Date());
                  }
              }
              """
          )
        );
    }
}

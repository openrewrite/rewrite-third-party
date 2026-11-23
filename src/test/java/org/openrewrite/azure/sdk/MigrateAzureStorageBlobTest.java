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
              import com.azure.storage.blob.BlobClient;
              import com.microsoft.azure.storage.OperationContext;

              class Remnants {
                  int read(BlobClient blob, byte[] buffer) throws Exception {
                      /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.OperationContext` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/OperationContext context = /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.OperationContext` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/new OperationContext();
                      blob.getProperties();
                      return /*~~(Legacy Azure SDK: migrate `com.microsoft.azure.storage.blob.CloudBlob.downloadToByteArray` by hand, see https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/storage/azure-storage-blob/migrationGuides/V8_V12.md)~~>*/blob.downloadToByteArray(buffer, 0);
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
}

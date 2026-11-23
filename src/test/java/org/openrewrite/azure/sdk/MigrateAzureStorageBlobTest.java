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

import static org.openrewrite.java.Assertions.java;

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
}

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

import org.jspecify.annotations.Nullable;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * The types of the legacy Azure SDK for Java and ADAL4J, and the current Azure SDK type each one is retyped to once the
 * calls on it have been rewritten. A legacy type without an entry has no counterpart, so its uses are remnants.
 */
public final class LegacyAzureTypes {

    private LegacyAzureTypes() {
    }

    /**
     * Packages of the legacy Azure SDK, ADAL4J and the AutoRest runtime they share. Libraries that only share the
     * {@code com.microsoft.azure} root and are still current (Azure Functions, Kusto, the IoT SDK, MSAL4J, the Maven
     * and Gradle plugins, Relay) are not legacy.
     */
    private static final Pattern LEGACY = Pattern.compile(
            "com\\.microsoft\\.azure\\.(storage|eventhubs|eventprocessorhost|servicebus|keyvault|documentdb|cosmosdb|" +
            "management|credentials|batch|datalake|eventgrid)(\\..+)?|" +
            "com\\.microsoft\\.azure\\.[A-Z][A-Za-z0-9_$]*|" +
            "com\\.microsoft\\.aad\\.adal4j\\..+|" +
            "com\\.microsoft\\.rest\\..+");

    private static final Map<String, String> TYPES;

    static {
        Map<String, String> t = new LinkedHashMap<>();
        String storage = "com.microsoft.azure.storage.";
        String blob = storage + "blob.";
        String v12Blob = "com.azure.storage.blob.";

        // Storage, shared
        t.put(storage + "CloudStorageAccount", "java.lang.String");
        t.put(storage + "StorageException", v12Blob + "models.BlobStorageException");
        t.put(storage + "StorageCredentialsAccountAndKey", "com.azure.storage.common.StorageSharedKeyCredential");
        t.put(storage + "StorageCredentialsSharedAccessSignature", "com.azure.core.credential.AzureSasCredential");

        // Blob storage v8 to v12
        t.put(blob + "CloudBlobClient", v12Blob + "BlobServiceClient");
        t.put(blob + "CloudBlobContainer", v12Blob + "BlobContainerClient");
        t.put(blob + "CloudBlob", v12Blob + "BlobClient");
        t.put(blob + "CloudBlockBlob", v12Blob + "BlobClient");
        t.put(blob + "CloudAppendBlob", v12Blob + "specialized.AppendBlobClient");
        t.put(blob + "CloudPageBlob", v12Blob + "specialized.PageBlobClient");
        t.put(blob + "ListBlobItem", v12Blob + "models.BlobItem");
        t.put(blob + "BlobProperties", v12Blob + "models.BlobProperties");
        t.put(blob + "BlobContainerProperties", v12Blob + "models.BlobContainerProperties");
        t.put(blob + "BlobContainerPublicAccessType", v12Blob + "models.PublicAccessType");
        t.put(blob + "BlobContainerPermissions", v12Blob + "models.PublicAccessType");
        t.put(blob + "BlobInputStream", v12Blob + "specialized.BlobInputStream");
        t.put(blob + "BlobOutputStream", v12Blob + "specialized.BlobOutputStream");
        t.put(blob + "BlobType", v12Blob + "models.BlobType");
        t.put(blob + "CopyStatus", v12Blob + "models.CopyStatusType");
        t.put(blob + "DeleteSnapshotsOption", v12Blob + "models.DeleteSnapshotsOptionType");
        t.put(blob + "SharedAccessBlobPolicy", v12Blob + "sas.BlobServiceSasSignatureValues");
        t.put(blob + "SharedAccessBlobPermissions", v12Blob + "sas.BlobSasPermission");

        // ADAL4J to MSAL4J: a context is its authority, and a credential the builder of the application it authenticates
        String adal = "com.microsoft.aad.adal4j.";
        String msal = "com.microsoft.aad.msal4j.";
        t.put(adal + "AuthenticationContext", "java.lang.String");
        t.put(adal + "AuthenticationResult", msal + "IAuthenticationResult");
        t.put(adal + "ClientCredential", msal + "ConfidentialClientApplication$Builder");
        t.put(adal + "AsymmetricKeyCredential", msal + "ConfidentialClientApplication$Builder");
        t.put(adal + "UserInfo", msal + "IAccount");
        t.put(adal + "UserAssertion", msal + "UserAssertion");
        t.put(adal + "AuthenticationException", msal + "MsalException");

        TYPES = Collections.unmodifiableMap(t);
    }

    public static Map<String, String> mappings() {
        return TYPES;
    }

    public static boolean isLegacy(@Nullable String fqn) {
        return fqn != null && LEGACY.matcher(fqn.replace('$', '.')).matches();
    }

    public static boolean isLegacy(@Nullable JavaType type) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        return fq != null && isLegacy(fq.getFullyQualifiedName());
    }

    /**
     * The current type a legacy type is retyped to, or the type itself when it is not legacy.
     */
    public static @Nullable String current(String fqn) {
        String normalized = fqn.replace('$', '.');
        if (TYPES.containsKey(normalized)) {
            return TYPES.get(normalized);
        }
        return isLegacy(normalized) ? null : normalized;
    }

    /**
     * The source form of a type as it will be once legacy types are retyped, for declaring template parameters.
     * A legacy type without a counterpart becomes {@code java.lang.Object}.
     */
    static String render(@Nullable JavaType type) {
        if (type == null || type instanceof JavaType.Unknown) {
            return "java.lang.Object";
        }
        if (type instanceof JavaType.Primitive) {
            JavaType.Primitive p = (JavaType.Primitive) type;
            return p == JavaType.Primitive.Null || p == JavaType.Primitive.None ? "java.lang.Object" : p.getKeyword();
        }
        if (type instanceof JavaType.Array) {
            return render(((JavaType.Array) type).getElemType()) + "[]";
        }
        if (type instanceof JavaType.GenericTypeVariable) {
            JavaType.GenericTypeVariable g = (JavaType.GenericTypeVariable) type;
            return g.getBounds().isEmpty() ? "java.lang.Object" : render(g.getBounds().get(0));
        }
        if (type instanceof JavaType.Parameterized) {
            JavaType.Parameterized p = (JavaType.Parameterized) type;
            StringJoiner params = new StringJoiner(", ", "<", ">");
            for (JavaType param : p.getTypeParameters()) {
                String rendered = render(param);
                params.add(rendered);
            }
            return render(p.getType()) + params;
        }
        if (type instanceof JavaType.FullyQualified) {
            String current = current(((JavaType.FullyQualified) type).getFullyQualifiedName());
            return current == null ? "java.lang.Object" : current.replace('$', '.');
        }
        return "java.lang.Object";
    }
}

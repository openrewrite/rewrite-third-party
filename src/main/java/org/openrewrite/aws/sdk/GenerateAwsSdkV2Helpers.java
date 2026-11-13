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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Validated;
import org.openrewrite.aws.signing.ReplaceAwsRequestSigningApacheInterceptor;

import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;

/**
 * Preconditions do not apply to generated files, so the steps of the AWS SDK v1 to v2 migration don't generate their helpers
 * themselves; this does, in the modules a composition migrates.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class GenerateAwsSdkV2Helpers extends Recipe {

    @Option(displayName = "Only modules depending on",
            description = "Generates helpers only for the sources of modules that depend on one of these libraries. " +
                          AwsMigrationScope.ENTRY_DESCRIPTION + " Every module is in scope when not set.",
            example = "*:spring-data-dynamodb using com.amazonaws.services.dynamodbv2..*",
            required = false)
    @Nullable
    List<String> onlyModulesDependingOn;

    @Option(displayName = "Except modules depending on",
            description = "Generates no helpers for the sources of modules that depend on one of these libraries. " +
                          AwsMigrationScope.ENTRY_DESCRIPTION,
            example = "org.springframework.cloud:spring-cloud-*aws*",
            required = false)
    @Nullable
    List<String> exceptModulesDependingOn;

    String displayName = "Generate the helpers of the AWS SDK v1 to v2 migration";

    String description = "Generates the project-local helper classes, built on AWS SDK v2, that the steps of " +
                         "`org.openrewrite.aws.sdk.MigrateAwsSdkV1ToV2` move v1 calls without a v2 counterpart to.";

    @Override
    public Validated<Object> validate() {
        return super.validate()
                .and(AwsMigrationScope.validate("onlyModulesDependingOn", onlyModulesDependingOn))
                .and(AwsMigrationScope.validate("exceptModulesDependingOn", exceptModulesDependingOn));
    }

    @Override
    public List<Recipe> getRecipeList() {
        List<Recipe> helpers = new ArrayList<>();
        for (ScanningRecipe<?> generator : generators()) {
            helpers.add(new GenerateHelpersOf<>(generator, onlyModulesDependingOn, exceptModulesDependingOn));
        }
        return helpers;
    }

    private static List<ScanningRecipe<?>> generators() {
        return asList(
                new ReplaceAwsRequestSigningApacheInterceptor(null),
                new MigrateKinesisClientLibraryToV2(null),
                new MigrateSharedAwsClientConfiguration(null),
                new MigrateAwsClientBuilderSubclasses(null),
                new MigrateAwsEndpointConfigurationValues(null),
                new MigratePropertiesFileAwsCredentialsProviders(null),
                new MigrateAwsDefaultCredentialsChainEnvAliases(null),
                new MigrateCloudFrontSigners(null),
                new MigrateSqsBufferedClient(null),
                new PrepareAwsAsyncClientCalls(null),
                new MigrateDynamoDbTableUtils(null),
                new MigrateDynamoDbDocumentApi(null),
                new MigrateS3ObjectMetadata(null),
                new MigrateS3BucketLifecycleConfiguration(null),
                new MigrateS3Residuals(null),
                new MigrateEc2MetadataUtils(null));
    }
}

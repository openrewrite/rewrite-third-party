<p align="center">
  <a href="https://docs.openrewrite.org">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-dark.svg">
      <source media="(prefers-color-scheme: light)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg">
      <img alt="OpenRewrite Logo" src="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg" width='600px'>
    </picture>
  </a>
</p>

<div align="center">
  <h1>rewrite-third-party</h1>
</div>

<div align="center">

<!-- Keep the gap above this line, otherwise they won't render correctly! -->
[![ci](https://github.com/openrewrite/rewrite-third-party/actions/workflows/ci.yml/badge.svg)](https://github.com/openrewrite/rewrite-third-party/actions/workflows/ci.yml)
[![Apache 2.0](https://img.shields.io/github/license/openrewrite/rewrite-third-party.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Revved up by Develocity](https://img.shields.io/badge/Revved%20up%20by-Develocity-06A0CE?logo=Gradle&labelColor=02303A)](https://community.develocity.cloud/scans)
[![Contributing Guide](https://img.shields.io/badge/Contributing-Guide-informational)](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md)
</div>

### What is this?

This project implements a [Rewrite module](https://github.com/openrewrite/rewrite) that bundles OpenRewrite recipes maintained by third parties.
These recipes are not maintained by the OpenRewrite team, but are still useful for migrating codebases.

## Updating the `@InlineMe` recipes in rewrite-migrate-java

`InlineMethodCallsRecipeGenerator` turns the `@InlineMe` annotations found in the type tables into an `InlineMethodCalls` recipe list.
The generated YAML is not shipped from here, but copied into the module that owns those recipes, such as [rewrite-migrate-java](https://github.com/openrewrite/rewrite-migrate-java) for Guava.

1. Refresh the type tables, such that the `+` versions in `recipeDependencies` resolve to the latest releases.
   ```bash
   ./gradlew createTypeTable createTestTypeTable --refresh-dependencies
   ```
   Commit both `src/main/resources/META-INF/rewrite/classpath.tsv.gz` and `src/test/resources/META-INF/rewrite/classpath.tsv.gz`; `parserClasspath` artifacts land in the first, `testParserClasspath` artifacts in the second.
2. Generate the recipes, which prints how many were found for each artifact.
   ```bash
   ./gradlew generateInlineGuavaMethods generateInlineLog4jMethods
   ```
   Each task writes `build/generated/META-INF/rewrite/inline-<artifact>-<major>-methods.yml`, with a header naming the exact version the method patterns were generated from, and pointing back here.
3. Copy the generated file into the target repository.
   ```bash
   cp build/generated/META-INF/rewrite/inline-guava-33-methods.yml \
     ../rewrite-migrate-java/src/main/resources/META-INF/rewrite/
   ```
4. There, pin `parserClasspath` to the version named in the header of the generated file, and run `./gradlew createTypeTable --refresh-dependencies`, such that the `classpathFromResources` entries resolve against the version the method patterns were generated from.
5. Finish with `./gradlew licenseFormat` to add the license header the generated file lacks, and `./gradlew recipeCsvGenerate` to update the recipe count in `recipes.csv`.

## Contributing

We appreciate all types of contributions. See the [contributing guide](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md) for detailed instructions on how to get started.

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("org.openrewrite.build.recipe-library") version "latest.release"
    id("com.gradleup.shadow") version "latest.release"
    id("org.owasp.dependencycheck") version "latest.release"
}

dependencyCheck {
    analyzers.assemblyEnabled = false
    analyzers.nodeAuditEnabled = false
    analyzers.nodeEnabled = false
    failBuildOnCVSS = System.getenv("FAIL_BUILD_ON_CVSS")?.toFloatOrNull() ?: 9.0F
    format = System.getenv("DEPENDENCY_CHECK_FORMAT") ?: "HTML"
    nvd.apiKey = System.getenv("NVD_API_KEY")
    suppressionFile = "suppressions.xml"
}

group = "org.openrewrite.recipe"
description = "Third-party maintained OpenRewrite recipes"

recipeDependencies {
    parserClasspath("org.junit.jupiter:junit-jupiter-api:5.+")
    parserClasspath("org.assertj:assertj-core:latest.release")
    parserClasspath("org.springframework:spring-context:5.3.+")
    parserClasspath("org.springframework:spring-test:5.3.+")
    parserClasspath("org.springframework:spring-web:5.3.+")
    parserClasspath("org.springframework:spring-webflux:5.3.+")
    parserClasspath("org.testng:testng:7.5")
    parserClasspath("io.projectreactor:reactor-core:latest.release")
    parserClasspath("io.projectreactor:reactor-test:latest.release")
    parserClasspath("io.projectreactor.addons:reactor-adapter:latest.release")
    parserClasspath("io.projectreactor.addons:reactor-extra:latest.release")
}

val rewriteVersion = rewriteRecipe.rewriteVersion.get()
dependencies {
    implementation(platform("org.openrewrite:rewrite-bom:$rewriteVersion"))

    runtimeOnly("org.openrewrite:rewrite-java")
    runtimeOnly("org.openrewrite:rewrite-templating:${rewriteVersion}")

    runtimeOnly("ai.timefold.solver:timefold-solver-migration:latest.release") {
        exclude(module = "jakarta.xml.bind-api")
    }
    runtimeOnly("com.oracle.weblogic.rewrite:rewrite-weblogic:latest.release") {isTransitive = false}
    runtimeOnly("io.quarkus:quarkus-update-recipes:latest.release") { isTransitive = false }
    runtimeOnly("org.apache.camel.upgrade:camel-upgrade-recipes:latest.release") { isTransitive = false }
    runtimeOnly("org.apache.wicket:wicket-migration:latest.release") { isTransitive = false }
    runtimeOnly("org.axonframework:axon-migration:latest.release") { isTransitive = false }
    runtimeOnly("software.amazon.awssdk:v2-migration:latest.release")
    runtimeOnly("tech.picnic.error-prone-support:error-prone-contrib:${rewriteVersion}:recipes")

    testImplementation("org.openrewrite:rewrite-java")
    testImplementation("org.openrewrite:rewrite-test")

    testImplementation("tech.picnic.error-prone-support:error-prone-contrib:${rewriteVersion}:recipes")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.13.3")

    testRuntimeOnly("org.openrewrite:rewrite-java-17")
    testRuntimeOnly("org.gradle:gradle-tooling-api:latest.release")
}

// ./gradlew shadowJar
tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    dependencies {
        include(dependency("ai.timefold.solver:timefold-solver-migration"))
        include(dependency("com.oracle.weblogic.rewrite:rewrite-weblogic"))
        include(dependency("io.quarkus:quarkus-update-recipes:.*"))
        include(dependency("org.apache.camel.upgrade:camel-upgrade-recipes"))
        include(dependency("org.apache.wicket:wicket-migration"))
        include(dependency("org.axonframework:axon-migration"))
        include(dependency("software.amazon.awssdk:v2-migration"))
        include(dependency("tech.picnic.error-prone-support:error-prone-contrib"))
    }
    // Redeclares existing Quarkus and OpenRewrite recipes
    exclude("**/ToLatest9.yml")
    relocate("quarkus-updates", "META-INF.rewrite")
    // Amazon SDK v2 migration recipe contains some scripts
    exclude("generate-recipes")
    exclude("scripts/")
    exclude("v1-v2-service-mapping-diffs.csv")
}

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

val rewriteVersion = rewriteRecipe.rewriteVersion.get()
dependencies {
    implementation(platform("org.openrewrite:rewrite-bom:$rewriteVersion"))

    runtimeOnly("org.openrewrite:rewrite-java")
    runtimeOnly("org.openrewrite:rewrite-templating:${rewriteVersion}")

    runtimeOnly("ai.timefold.solver:timefold-solver-migration:latest.release") {
        exclude(module = "jakarta.xml.bind-api")
    }
    runtimeOnly("com.oracle.weblogic.rewrite:rewrite-weblogic:latest.release") { isTransitive = false }
    runtimeOnly("io.quarkus:quarkus-update-recipes:latest.release") { isTransitive = false }
    runtimeOnly("org.apache.camel.upgrade:camel-upgrade-recipes:latest.release") { isTransitive = false }
    runtimeOnly("org.apache.wicket:wicket-migration:latest.release") { isTransitive = false }
    runtimeOnly("org.axonframework:axon-migration:latest.release") { isTransitive = false }
    runtimeOnly("software.amazon.awssdk:v2-migration:latest.release")
    runtimeOnly("tech.picnic.error-prone-support:error-prone-contrib:${rewriteVersion}:recipes") {
        exclude(module = "refaster-support")
    }

    testImplementation("org.openrewrite:rewrite-java")
    testImplementation("org.openrewrite:rewrite-test")

    testImplementation("tech.picnic.error-prone-support:error-prone-contrib:${rewriteVersion}:recipes")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.13.3")
    testImplementation("org.junit-pioneer:junit-pioneer:latest.release")

    testRuntimeOnly("org.openrewrite:rewrite-java-21")
    testRuntimeOnly("org.gradle:gradle-tooling-api:latest.release")
}

recipeDependencies {
    // error-prone-contrib only has provided dependencies, whereas the platform needs these on the classpath at runtime
    parserClasspath("com.fasterxml.jackson.core:jackson-core:2.+")
    parserClasspath("com.fasterxml.jackson.core:jackson-databind:2.+")
    parserClasspath("com.github.ben-manes.caffeine:caffeine:3.+")
    parserClasspath("com.google.guava:guava:33.+")
    parserClasspath("org.jspecify:jspecify:1.+")
    parserClasspath("org.junit.jupiter:junit-jupiter-api:5.+")
    parserClasspath("org.assertj:assertj-core:3.+")
    parserClasspath("org.mockito:mockito-core:5.+")
    parserClasspath("org.reactivestreams:reactive-streams:1.+")
    parserClasspath("org.springframework:spring-context:6.+")
    parserClasspath("org.springframework:spring-test:6.+")
    parserClasspath("org.springframework:spring-web:6.+")
    parserClasspath("org.springframework:spring-webflux:6.+")
    parserClasspath("org.testng:testng:7.+")
    parserClasspath("io.micrometer:micrometer-core:1.+")
    parserClasspath("io.projectreactor:reactor-core:3.+")
    parserClasspath("io.projectreactor:reactor-test:3.+")
    parserClasspath("io.projectreactor.addons:reactor-adapter:3.+")
    parserClasspath("io.projectreactor.addons:reactor-extra:3.+")
    parserClasspath("io.reactivex.rxjava2:rxjava:2.+")

    // `@InlineMe` methods defined in log4j-api, only generated here, not used directly
    testParserClasspath("org.apache.logging.log4j:log4j-api:2.+")
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

tasks {
    val generateQuarkusAggregation by registering(JavaExec::class) {
        group = "generate"
        description = "Generate Quarkus migration aggregation Recipes."
        mainClass = "org.openrewrite.recipe.quarkus.internal.AggregateQuarkusUpdates"
        classpath = sourceSets.getByName("test").runtimeClasspath
    }
    val generateInlineGuavaMethods by registering(JavaExec::class) {
        group = "generate"
        description = "Generate Quarkus migration aggregation Recipes."
        mainClass = "org.openrewrite.java.internal.parser.InlineMethodCallsRecipeGenerator"
        classpath = sourceSets.getByName("test").runtimeClasspath
        args("guava")
        finalizedBy("licenseFormat")
    }
    val generateInlineLog4jMethods by registering(JavaExec::class) {
        group = "generate"
        description = "Generate Quarkus migration aggregation Recipes."
        mainClass = "org.openrewrite.java.internal.parser.InlineMethodCallsRecipeGenerator"
        classpath = sourceSets.getByName("test").runtimeClasspath
        args("log4j-api")
        finalizedBy("licenseFormat")
    }
}

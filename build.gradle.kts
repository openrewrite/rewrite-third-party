import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("org.openrewrite.build.recipe-library") version "latest.release"
    id("com.github.johnrengelman.shadow") version "latest.release"
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
    runtimeOnly("io.quarkus:quarkus-update-recipes:latest.release")
    runtimeOnly("org.apache.wicket:wicket-migration:latest.release")
    runtimeOnly("org.axonframework:axon-migration:latest.release")
    runtimeOnly("tech.picnic.error-prone-support:error-prone-contrib:latest.release:recipes")

    // error-prone-contrib only has provided dependencies, whereas the platform needs these on the classpath at runtime
    runtimeOnly("org.junit.jupiter:junit-jupiter-api:latest.release")
    runtimeOnly("org.assertj:assertj-core:latest.release")
    runtimeOnly("org.springframework:spring-context:5.3.32")
    runtimeOnly("org.springframework:spring-test:5.3.32")
    runtimeOnly("org.springframework:spring-web:5.3.32")
    runtimeOnly("org.springframework:spring-webflux:5.3.32")
    runtimeOnly("org.testng:testng:7.5")
    runtimeOnly("io.projectreactor:reactor-core:latest.release")
    runtimeOnly("io.projectreactor:reactor-test:latest.release")
    runtimeOnly("io.projectreactor.addons:reactor-adapter:latest.release")
    runtimeOnly("io.projectreactor.addons:reactor-extra:latest.release")

    testImplementation("org.openrewrite:rewrite-java")
    testImplementation("org.openrewrite:rewrite-test")

    testImplementation("tech.picnic.error-prone-support:error-prone-contrib:latest.release:recipes")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:latest.release")

    testRuntimeOnly("org.openrewrite:rewrite-java-17")
    testRuntimeOnly("org.gradle:gradle-tooling-api:latest.release")
}

// ./gradlew shadowJar
tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    dependencies {
        include(dependency("ai.timefold.solver:timefold-solver-migration"))
        include(dependency("io.quarkus:quarkus-update-recipes:.*"))
        include(dependency("org.apache.wicket:wicket-migration"))
        include(dependency("org.axonframework:axon-migration"))
        include(dependency("tech.picnic.error-prone-support:error-prone-contrib:recipes"))
    }
    // Redeclares existing Quarkus and OpenRewrite recipes
    exclude("**/ToLatest9.yml")
    relocate("quarkus-updates", "META-INF.rewrite")
}

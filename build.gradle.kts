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

    runtimeOnly("org.axonframework:axon-migration:latest.release")
    runtimeOnly("tech.picnic.error-prone-support:error-prone-contrib:latest.release")

    testImplementation("org.openrewrite:rewrite-java")
    testImplementation("org.openrewrite:rewrite-test")

    testImplementation("tech.picnic.error-prone-support:error-prone-contrib:latest.release")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:latest.release")

    testRuntimeOnly("org.openrewrite:rewrite-java-17")
    testRuntimeOnly("org.gradle:gradle-tooling-api:latest.release")
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    dependencies {
        include(dependency("org.axonframework:axon-migration"))
        include(dependency("tech.picnic.error-prone-support:error-prone-contrib"))
    }
}

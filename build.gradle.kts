plugins {
    id("org.openrewrite.build.recipe-library") version "latest.release"
}

group = "org.openrewrite.recipe"
description = "Third-party maintained OpenRewrite recipes"

val rewriteVersion = rewriteRecipe.rewriteVersion.get()
dependencies {
    implementation(platform("org.openrewrite:rewrite-bom:$rewriteVersion"))

    implementation("org.openrewrite:rewrite-java")
    implementation("org.openrewrite:rewrite-templating:${rewriteVersion}")
    implementation("tech.picnic.error-prone-support:error-prone-contrib:latest.release")

    testImplementation("org.openrewrite:rewrite-java-17")
    testImplementation("org.openrewrite:rewrite-test")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:latest.release")
    testRuntimeOnly("org.gradle:gradle-tooling-api:latest.release")
}

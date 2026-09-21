rootProject.name = "rewrite-third-party"

pluginManagement {
    repositories {
        mavenLocal()
        val codegenomeUsername = providers.gradleProperty("codegenomeUsername").orNull ?: System.getenv("CODEGENOME_USERNAME")
        val codegenomePassword = providers.gradleProperty("codegenomePassword").orNull ?: System.getenv("CODEGENOME_TOKEN")
        if (!codegenomeUsername.isNullOrBlank() && !codegenomePassword.isNullOrBlank()) {
            maven {
                name = "codegenome"
                url = uri("https://artifacts.codegenomeproject.org/maven")
                credentials {
                    username = codegenomeUsername
                    password = codegenomePassword
                }
                content {
                    includeGroupAndSubgroups("org.openrewrite")
                    includeGroupAndSubgroups("io.moderne")
                }
            }
        }
        gradlePluginPortal()
    }
}

plugins {
    id("com.gradle.develocity") version "latest.release"
    id("com.gradle.common-custom-user-data-gradle-plugin") version "latest.release"
}

develocity {
    server = "https://community.develocity.cloud/"
    projectId = "openrewrite"

    val isCiServer = System.getenv("CI")?.equals("true") ?: false
    val accessKey = System.getenv("GRADLE_ENTERPRISE_ACCESS_KEY")
    val authenticated = !accessKey.isNullOrBlank()
    buildCache {
        remote(develocity.buildCache) {
            isEnabled = true
            isPush = isCiServer && authenticated
        }
    }

    buildScan {
        capture {
            fileFingerprints = true
        }
        publishing {
            onlyIf {
                authenticated
            }
        }
        uploadInBackground = !isCiServer
    }
}

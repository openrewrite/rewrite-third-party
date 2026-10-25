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

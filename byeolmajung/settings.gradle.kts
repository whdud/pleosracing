pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Kotlin/JS adds Node.js and Yarn download repositories to the project; they are declared here instead.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
        ivy {
            name = "Node.js"
            url = uri("https://nodejs.org/dist")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy {
            name = "Yarn"
            url = uri("https://github.com/yarnpkg/yarn/releases/download")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

rootProject.name = "Byeolmajung"
include(":sky", ":app")

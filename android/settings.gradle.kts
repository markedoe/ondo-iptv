// Ondo for Android: the Compose app and the pure Kotlin core it shares logic with.
pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first: Central answers GitHub's runners 429 (Too Many Requests) at times, and
        // Gradle stops on that answer instead of trying the next repository. Same artifacts, checked by Gradle.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first: Central answers GitHub's runners 429 (Too Many Requests) at times, and
        // Gradle stops on that answer instead of trying the next repository. Same artifacts, checked by Gradle.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
    }
}

rootProject.name = "Ondo"
include(":app")
includeBuild("core")

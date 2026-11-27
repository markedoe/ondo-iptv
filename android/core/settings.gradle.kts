// Pure Kotlin core (no Android): parsers, Xtream client, guide, catch-up, search and library.
// Built on its own (`./gradlew -p core test`) and included in the app build.
pluginManagement {
    repositories {
        gradlePluginPortal()
        // Google's mirror of Maven Central first: Central answers GitHub's runners 429 (Too Many Requests) at times, and
        // Gradle stops on that answer instead of trying the next repository. Same artifacts, checked by Gradle.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // Google's mirror of Maven Central first: Central answers GitHub's runners 429 (Too Many Requests) at times, and
        // Gradle stops on that answer instead of trying the next repository. Same artifacts, checked by Gradle.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "core"

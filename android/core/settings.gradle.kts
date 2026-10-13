// Pure Kotlin core (no Android): parsers, Xtream client, guide, catch-up, search and library.
// Built on its own (`./gradlew -p core test`) and included in the app build.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "core"

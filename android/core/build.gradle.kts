plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.pitest)
}

group = "com.markedoe.ondo"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnit()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Mutation testing (`./gradlew :core:pitest`, weekly in CI): Pitest plants small bugs in the compiled core
// (inverted conditions, removed calls, changed boundaries) and lists those no test catches. Methods the
// Kotlin compiler generates are left out: their mutants only add noise.
pitest {
    targetClasses.set(listOf("com.markedoe.ondo.core.*"))
    excludedMethods.set(listOf("equals", "hashCode", "toString", "copy", "copy\$default", "component*"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
}

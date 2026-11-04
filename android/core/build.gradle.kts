import java.time.Duration

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
    exclude("**/MemoryBudgetTest*")
}

// Each test named as it starts and ends in the log; one over its time (HangGuard, 120 s) fails with every thread's
// stack there and in build/reports/tests/hang-guard; whatever else hangs, Gradle stops before the CI job's 20 minutes.
tasks.withType<Test>().configureEach {
    systemProperty("ondo.hangGuard.dir", "build/reports/tests/hang-guard")
    timeout.set(Duration.ofMinutes(12))
    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Large catalogues and guides within a heap the size an entry-level TV gives an app (`./gradlew :core:memoryTest`).
val memoryTest by tasks.registering(Test::class) {
    description = "Loads large catalogues and guides under a 64 MB heap."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnit()
    filter { includeTestsMatching("*MemoryBudgetTest") }
    maxHeapSize = "64m"
}

// Mutation testing (`./gradlew :core:pitest`, weekly in CI): Pitest plants small bugs in the compiled core
// (inverted conditions, removed calls, changed boundaries) and lists those no test catches. Methods the
// Kotlin compiler generates are left out: their mutants only add noise.
pitest {
    targetClasses.set(listOf("com.markedoe.ondo.core.*"))
    excludedClasses.set(listOf("*\$\$serializer", "*\$Companion"))
    excludedMethods.set(listOf("equals", "hashCode", "toString", "copy", "copy\$default", "component*"))
    // Kotlin's generated null checks; and the timing test, which would time out on every mutant.
    avoidCallsTo.set(setOf("kotlin.jvm.internal"))
    excludedTestClasses.set(listOf("com.markedoe.ondo.core.PerformanceTest"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
}

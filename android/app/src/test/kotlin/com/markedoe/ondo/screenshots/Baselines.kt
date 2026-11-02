package com.markedoe.ondo.screenshots

import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType

/**
 * The reference screenshots, committed (tests run from `android/app`): `./gradlew :app:verifyRoborazziDebug` fails
 * when a screen no longer matches its own, `./gradlew :app:recordRoborazziDebug` writes them anew after a change made
 * on purpose (docs/ANDROID.md, "Screenshots").
 */
const val BASELINES = "src/test/snapshots"

/**
 * How a reference screenshot is taken and compared: at half size (light in the repository, a line of text gone still
 * shows), and equal when at most 0.1 % of its pixels differ (anti-aliasing), not more.
 */
val BaselineOptions = RoborazziOptions(
    recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
)

/**
 * The store listing's screenshots, at full size: written whenever Roborazzi runs (recording or comparing), never
 * compared, as they are not committed (the release workflow makes them anew). Without Roborazzi (a plain test run),
 * not written.
 */
val StoreOptions: RoborazziOptions
    get() {
        val roborazzi = listOf("record", "compare", "verify").any { System.getProperty("roborazzi.test.$it") == "true" }
        return RoborazziOptions(
            taskType = if (roborazzi) RoborazziTaskType.Record else RoborazziTaskType.None,
            recordOptions = RoborazziOptions.RecordOptions(resizeScale = 1.0),
        )
    }

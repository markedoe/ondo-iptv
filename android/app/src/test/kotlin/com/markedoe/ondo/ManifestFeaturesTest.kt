package com.markedoe.ondo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Google Play reads some permissions as hardware the app needs and hides the app from devices without it. Each such
 * permission's hardware is declared optional: the TV boxes on Ethernet only, without a camera or a touch screen,
 * keep Ondo.
 */
class ManifestFeaturesTest {
    /** Permissions Google Play reads as a required feature (developer.android.com, "uses-feature", permissions). */
    private val implied = mapOf(
        "CHANGE_WIFI_MULTICAST_STATE" to "android.hardware.wifi",
        "CHANGE_WIFI_STATE" to "android.hardware.wifi",
        "ACCESS_WIFI_STATE" to "android.hardware.wifi",
        "BLUETOOTH" to "android.hardware.bluetooth",
        "BLUETOOTH_ADMIN" to "android.hardware.bluetooth",
        "CAMERA" to "android.hardware.camera",
        "RECORD_AUDIO" to "android.hardware.microphone",
        "ACCESS_FINE_LOCATION" to "android.hardware.location",
        "ACCESS_COARSE_LOCATION" to "android.hardware.location",
        "READ_PHONE_STATE" to "android.hardware.telephony",
    )

    @Test
    fun noPermissionMakesGooglePlayRequireHardware() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val permissions = Regex("""<uses-permission\s+android:name="android\.permission\.(\w+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertTrue(permissions.isNotEmpty())
        permissions.mapNotNull(implied::get).toSet().forEach { feature ->
            val declared = Regex("""<uses-feature\s+android:name="${Regex.escape(feature)}"\s+android:required="false"""").containsMatchIn(manifest)
            assertTrue("$feature must be declared with android:required=\"false\"", declared)
        }
    }
}

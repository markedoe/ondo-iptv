package com.markedoe.ondo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge to edge as Google Play asks from Android 15: no deprecated window attribute in the themes, and every activity
 * calls enableEdgeToEdge() (Play Console, "deprecated APIs or parameters for edge-to-edge").
 */
class EdgeToEdgeTest {
    private val deprecated = listOf("statusBarColor", "navigationBarColor", "navigationBarDividerColor", "windowTranslucentStatus", "windowTranslucentNavigation", "windowLayoutInDisplayCutoutMode")

    @Test
    fun themesSetNoDeprecatedWindowAttribute() {
        val themes = File("src/main/res").walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }.toList()
        assertTrue(themes.isNotEmpty())
        themes.forEach { file ->
            val text = file.readText()
            deprecated.forEach { attribute -> assertTrue("${file.path} sets $attribute", "\"android:$attribute\"" !in text) }
        }
    }

    @Test
    fun everyActivityGoesEdgeToEdge() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activities = Regex("""<activity\s+android:name="\.([\w.]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue(activities.size >= 2)
        activities.forEach { name ->
            val source = File("src/main/kotlin/com/markedoe/ondo/${name.replace('.', '/')}.kt").readText()
            assertTrue("$name calls enableEdgeToEdge()", "enableEdgeToEdge(" in source)
            listOf("setDecorFitsSystemWindows", "statusBarColor", "navigationBarColor", "setStatusBarColor", "setNavigationBarColor").forEach {
                assertTrue("$name uses $it", it !in source)
            }
        }
    }
}

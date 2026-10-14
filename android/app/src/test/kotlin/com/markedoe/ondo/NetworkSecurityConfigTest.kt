package com.markedoe.ondo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Playlists, provider APIs, guides, logos and streams are often plain HTTP, which Android blocks by
 * default (the twin of the iPhone app's App Transport Security setting). Robolectric does not enforce the
 * network security policy, so this reads what ships: the manifest and its network security config.
 */
class NetworkSecurityConfigTest {
    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path)).documentElement

    @Test
    fun plainHttpIsAllowedEverywhere() {
        val android = "http://schemas.android.com/apk/res/android"
        val application = xml("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/network_security_config", application.getAttributeNS(android, "networkSecurityConfig"))

        val config = xml("src/main/res/xml/network_security_config.xml")
        val base = config.getElementsByTagName("base-config").item(0) as Element
        assertEquals("true", base.getAttribute("cleartextTrafficPermitted"))
        assertTrue("no domain may switch HTTP back off", config.getElementsByTagName("domain-config").length == 0)
    }
}

package com.cedrickgd.morgenbriefing.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Plain http:// is allowed for local names only (docs/AI-PROVIDERS.md D10): the own server on
 * `*.fritz.box` & Co. and, for QA with the debug base URL override, the emulator host 10.0.2.2.
 * Everything else stays https-only.
 */
class NetworkSecurityConfigTest {
    private val config = File("src/main/res/xml/network_security_config.xml")

    /** Domain → includeSubdomains, the allowlist the custom server engine must mirror. */
    private val expected = mapOf(
        "localhost" to false,
        "10.0.2.2" to false,
        "local" to true,
        "lan" to true,
        "home.arpa" to true,
        "fritz.box" to true,
        "internal" to true,
    )

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `cleartext only for local names, https everywhere else`() {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config).documentElement
        val base = root.children("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))

        val domainConfigs = root.children("domain-config")
        assertEquals(1, domainConfigs.size)
        val cleartext = domainConfigs.single()
        assertEquals("true", cleartext.getAttribute("cleartextTrafficPermitted"))
        val domains = cleartext.children("domain").associate { it.textContent.trim() to (it.getAttribute("includeSubdomains") == "true") }
        assertEquals(expected, domains)
        // No own trust anchors: the domain config inherits the base ones (system + ISRG).
        assertEquals(0, cleartext.children("trust-anchors").size)
    }
}

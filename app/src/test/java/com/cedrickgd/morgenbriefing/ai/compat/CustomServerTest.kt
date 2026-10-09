package com.cedrickgd.morgenbriefing.ai.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomServerTest {

    @Test
    fun `addresses get the v1 root only when they have no path`() {
        assertEquals("http://nas.fritz.box:11434/v1", CustomServer.normalize(" http://nas.fritz.box:11434 "))
        assertEquals("http://nas.fritz.box:11434/v1", CustomServer.normalize("http://nas.fritz.box:11434/"))
        assertEquals("http://nas.fritz.box:11434/v1", CustomServer.normalize("http://nas.fritz.box:11434/v1/"))
        assertEquals("https://llm.example.ts.net/api/v1", CustomServer.normalize("https://llm.example.ts.net/api/v1"))
        assertEquals("http://localhost:1234/lmstudio", CustomServer.normalize("http://localhost:1234/lmstudio//"))
        assertNull(CustomServer.normalize("nas.fritz.box:11434"))
        assertNull(CustomServer.normalize("ftp://nas.fritz.box"))
        assertNull(CustomServer.normalize(""))
        assertNull(CustomServer.normalize(null))
    }

    @Test
    fun `issues are found in the order the user can fix them`() {
        assertEquals(CustomUrlIssue.BLANK, CustomServer.issue("  "))
        assertEquals(CustomUrlIssue.NO_SCHEME, CustomServer.issue("nas.fritz.box:11434"))
        assertEquals(CustomUrlIssue.NOT_HTTP, CustomServer.issue("ftp://nas.fritz.box"))
        assertEquals(CustomUrlIssue.INVALID, CustomServer.issue("http://"))
        assertEquals(CustomUrlIssue.INVALID, CustomServer.issue("http://nas fritz.box"))
        assertEquals(CustomUrlIssue.CLEARTEXT_HOST, CustomServer.issue("http://192.168.178.20:11434"))
        assertEquals(CustomUrlIssue.CLEARTEXT_HOST, CustomServer.issue("http://llm.example.com/v1"))
        assertNull(CustomServer.issue("https://192.168.178.20:11434"))
        assertNull(CustomServer.issue("http://nas.fritz.box:11434"))
        assertNull(CustomServer.issue("HTTP://NAS.LOCAL:1234/v1"))
        assertNull(CustomServer.issue("http://10.0.2.2:8080/v1"))
        CustomUrlIssue.entries.forEach { assertTrue(it.name, CustomServer.hint(it).isNotBlank()) }
    }

    @Test
    fun `plain http only for local names`() {
        listOf("localhost", "10.0.2.2", "nas.local", "local", "pc.lan", "nas.home.arpa", "fritz.box", "nas.fritz.box", "nas.fritz.box.", "srv.internal")
            .forEach { assertTrue(it, CustomServer.allowsCleartext(it)) }
        listOf("192.168.178.20", "sub.localhost", "10.0.2.20", "fritz.box.evil.com", "notlocal", "example.com", "", null)
            .forEach { assertFalse(it.toString(), CustomServer.allowsCleartext(it)) }
    }
}

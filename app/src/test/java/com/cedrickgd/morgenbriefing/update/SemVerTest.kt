package com.cedrickgd.morgenbriefing.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    private fun v(text: String) = requireNotNull(SemVer.parse(text)) { "unparsable: $text" }

    @Test
    fun `parses release tags with and without prefix`() {
        assertEquals(SemVer(1, 2, 0), v("v1.2.0"))
        assertEquals(SemVer(1, 2, 0), v("V1.2.0"))
        assertEquals(SemVer(1, 2, 0), v("1.2.0"))
        assertEquals(SemVer(1, 2, 0), v(" v1.2.0 "))
        assertEquals(SemVer(10, 20, 30), v("v10.20.30"))
    }

    @Test
    fun `missing patch counts as zero and build metadata is ignored`() {
        assertEquals(SemVer(1, 2, 0), v("v1.2"))
        assertEquals(SemVer(1, 2, 3), v("1.2.3+build.7"))
        assertEquals(0, v("1.2.3+a").compareTo(v("1.2.3+b")))
    }

    @Test
    fun `parses pre-release identifiers`() {
        val beta = v("v1.3.0-beta.1")
        assertEquals(listOf("beta", "1"), beta.preRelease)
        assertTrue(beta.isPreRelease)
        assertEquals("1.3.0-beta.1", beta.toString())
        assertEquals("1.2.0", v("v1.2.0").toString())
    }

    @Test
    fun `rejects garbage`() {
        listOf(null, "", "v", "1", "v1.", "1.2.3.4", "latest", "v1.2.x", "1.2.3-", "1..2", "v1.2.3 beta")
            .forEach { assertNull("'$it' should not parse", SemVer.parse(it)) }
    }

    @Test
    fun `numeric parts compare numerically`() {
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.2.10") > v("1.2.9"))
        assertEquals(0, v("v1.2.3").compareTo(v("1.2.3")))
    }

    @Test
    fun `pre-releases sort before the release and follow semver precedence`() {
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
        ).map(::v)
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sorted())
        assertTrue(v("1.2.0-beta") < v("1.2.0"))
        assertTrue(v("1.2.0-beta") > v("1.1.9"))
    }

    @Test
    fun `installed version drops the debug suffix`() {
        assertEquals(SemVer(1, 0, 0), SemVer.parseInstalled("1.0.0-debug"))
        assertEquals(SemVer(1, 2, 0, listOf("beta")), SemVer.parseInstalled("1.2.0-beta-debug"))
        assertEquals(SemVer(1, 1, 0), SemVer.parseInstalled("1.1.0"))
        assertNull(SemVer.parseInstalled("dev"))
    }

    @Test
    fun `only newer versions are updates`() {
        assertTrue(SemVer.isUpdate(v("1.0.0"), v("1.2.0")))
        assertTrue(SemVer.isUpdate(v("1.0.0"), v("1.0.1")))
        assertFalse(SemVer.isUpdate(v("1.2.0"), v("1.2.0")))
        assertFalse(SemVer.isUpdate(v("1.2.0"), v("1.1.9")))
    }

    @Test
    fun `pre-releases are only offered to pre-release installs`() {
        assertFalse(SemVer.isUpdate(v("1.0.0"), v("1.2.0-beta")))
        assertTrue(SemVer.isUpdate(v("1.2.0-alpha"), v("1.2.0-beta")))
        assertTrue(SemVer.isUpdate(v("1.2.0-beta"), v("1.2.0")))
        assertFalse(SemVer.isUpdate(v("1.2.0-beta.2"), v("1.2.0-beta.1")))
    }
}

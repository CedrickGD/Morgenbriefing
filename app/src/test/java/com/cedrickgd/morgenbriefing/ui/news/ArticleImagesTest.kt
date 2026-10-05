package com.cedrickgd.morgenbriefing.ui.news

import com.cedrickgd.morgenbriefing.data.model.ArticleImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleImagesTest {

    private val commons =
        "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0b/Solarpark_Borna_2021.jpg/1280px-Solarpark_Borna_2021.jpg"

    @Test
    fun `commons files on upload wikimedia are allowed`() {
        assertEquals(commons, ArticleImage(url = commons).safeUrl())
        assertEquals(commons, safeImageUrl("  $commons \n"))
        val encoded = "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8e/D%C3%BClmen%2C_Solarpark.jpg/1280px-D%C3%BClmen.jpg"
        assertEquals(encoded, safeImageUrl(encoded))
        assertEquals("https://UPLOAD.wikimedia.org:443/wikipedia/commons/a/ab/X.jpg", safeImageUrl("https://UPLOAD.wikimedia.org:443/wikipedia/commons/a/ab/X.jpg"))
    }

    @Test
    fun `other schemes and hosts are rejected`() {
        val rejected = listOf(
            "http://upload.wikimedia.org/wikipedia/commons/a/ab/X.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/X.jpg/1280px-X.jpg",
            "https://commons.wikimedia.org/wiki/File:X.jpg",
            "https://upload.wikimedia.org.evil.example/wikipedia/commons/a/ab/X.jpg",
            "https://evilupload.wikimedia.org/wikipedia/commons/a/ab/X.jpg",
            "https://www.spiegel.de/images/press-photo.jpg",
            "//upload.wikimedia.org/wikipedia/commons/a/ab/X.jpg",
            "upload.wikimedia.org/wikipedia/commons/a/ab/X.jpg",
            "javascript:alert(1)",
            "data:image/png;base64,AAAA",
            "",
        )
        rejected.forEach { assertNull(it, safeImageUrl(it)) }
        assertNull(safeImageUrl(null))
    }

    @Test
    fun `only the commons part of the file server is allowed`() {
        // Local files of other wikis can be non-free (fair use) — never load those.
        assertNull(safeImageUrl("https://upload.wikimedia.org/wikipedia/en/a/ab/Poster.jpg"))
        assertNull(safeImageUrl("https://upload.wikimedia.org/wikipedia/commons/../en/a/ab/Poster.jpg"))
        assertNull(safeImageUrl("https://upload.wikimedia.org/"))
    }

    @Test
    fun `tricks with user info, ports and malformed urls are rejected`() {
        assertNull(safeImageUrl("https://upload.wikimedia.org@evil.example/wikipedia/commons/a/ab/X.jpg"))
        assertNull(safeImageUrl("https://user@upload.wikimedia.org/wikipedia/commons/a/ab/X.jpg"))
        assertNull(safeImageUrl("https://upload.wikimedia.org:8443/wikipedia/commons/a/ab/X.jpg"))
        assertNull(safeImageUrl("https://upload.wikimedia.org/wikipedia/commons/a b.jpg"))
        assertNull(safeImageUrl("https://upload.wikimedia.org/wikipedia/commons/" + "a".repeat(3000)))
    }

    @Test
    fun `request hosts are checked the same way`() {
        assertTrue(isAllowedImageHost("https", "upload.wikimedia.org"))
        assertFalse(isAllowedImageHost("http", "upload.wikimedia.org"))
        assertFalse(isAllowedImageHost("https", "example.org"))
    }

    @Test
    fun `credit line names author and license`() {
        assertEquals(
            "Bild: Raimond Spekking · CC BY-SA 4.0",
            ArticleImage(url = commons, credit = "Raimond Spekking", license = "CC BY-SA 4.0").creditLine(),
        )
        assertEquals("Bild: Wikimedia Commons · CC0", ArticleImage(url = commons, license = " CC0 ").creditLine())
        assertEquals("Bild: NASA", ArticleImage(url = commons, credit = "NASA").creditLine())
    }
}

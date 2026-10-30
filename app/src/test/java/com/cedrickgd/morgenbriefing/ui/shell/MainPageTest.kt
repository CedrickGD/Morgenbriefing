package com.cedrickgd.morgenbriefing.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainPageTest {

    @Test
    fun `pages are in swipe order with Heute first`() {
        assertEquals(listOf(MainPage.TODAY, MainPage.SETTINGS), MainPage.entries)
        assertEquals(0, MainPage.TODAY.index)
        assertEquals(MainPage.TODAY, MainPage.Start)
    }

    @Test
    fun `notification links map to their pages`() {
        assertEquals(MainPage.TODAY, MainPage.fromLink(AppLinks.PAGE_TODAY))
        assertEquals(MainPage.SETTINGS, MainPage.fromLink(AppLinks.PAGE_SETTINGS))
    }

    @Test
    fun `missing or unknown links open Heute`() {
        assertEquals(MainPage.TODAY, MainPage.fromLink(null))
        assertEquals(MainPage.TODAY, MainPage.fromLink(""))
        assertEquals(MainPage.TODAY, MainPage.fromLink("history"))
    }

    @Test
    fun `only an explicit page request navigates the running app`() {
        // Launcher icon: no extra, stay where the user is.
        assertNull(MainPage.requestedBy(null))
        // Notifications name their page; an unknown value still opens Heute.
        assertEquals(MainPage.TODAY, MainPage.requestedBy(AppLinks.PAGE_TODAY))
        assertEquals(MainPage.SETTINGS, MainPage.requestedBy(AppLinks.PAGE_SETTINGS))
        assertEquals(MainPage.TODAY, MainPage.requestedBy("archive"))
    }

    @Test
    fun `out of range pager indices fall back to Heute`() {
        assertEquals(MainPage.SETTINGS, MainPage.ofIndex(1))
        assertEquals(MainPage.TODAY, MainPage.ofIndex(-1))
        assertEquals(MainPage.TODAY, MainPage.ofIndex(2))
    }
}

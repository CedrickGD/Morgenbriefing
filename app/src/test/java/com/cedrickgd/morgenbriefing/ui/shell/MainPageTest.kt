package com.cedrickgd.morgenbriefing.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class MainPageTest {

    @Test
    fun `pages are in swipe order with Heute in the middle`() {
        assertEquals(listOf(MainPage.HISTORY, MainPage.TODAY, MainPage.SETTINGS), MainPage.entries)
        assertEquals(1, MainPage.TODAY.index)
        assertEquals(MainPage.TODAY, MainPage.Start)
    }

    @Test
    fun `notification links map to their pages`() {
        assertEquals(MainPage.HISTORY, MainPage.fromLink(AppLinks.PAGE_HISTORY))
        assertEquals(MainPage.TODAY, MainPage.fromLink(AppLinks.PAGE_TODAY))
        assertEquals(MainPage.SETTINGS, MainPage.fromLink(AppLinks.PAGE_SETTINGS))
    }

    @Test
    fun `missing or unknown links open Heute`() {
        assertEquals(MainPage.TODAY, MainPage.fromLink(null))
        assertEquals(MainPage.TODAY, MainPage.fromLink(""))
        assertEquals(MainPage.TODAY, MainPage.fromLink("archive"))
    }

    @Test
    fun `out of range pager indices fall back to Heute`() {
        assertEquals(MainPage.SETTINGS, MainPage.ofIndex(2))
        assertEquals(MainPage.TODAY, MainPage.ofIndex(-1))
        assertEquals(MainPage.TODAY, MainPage.ofIndex(3))
    }
}

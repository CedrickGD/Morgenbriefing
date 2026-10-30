package com.cedrickgd.morgenbriefing.ui.settings

import com.cedrickgd.morgenbriefing.util.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusLogicTest {

    private fun status(
        notify: Boolean = true,
        exactRelevant: Boolean = true,
        exact: Boolean = true,
        battery: Boolean = true,
    ) = SystemStatus(
        permissionGranted = notify,
        appNotificationsEnabled = notify,
        morningChannelBlocked = false,
        exactAlarmRelevant = exactRelevant,
        exactAlarmsAllowed = exact,
        batteryUnrestricted = battery,
    )

    @Test
    fun `three pills, all green when everything is allowed`() {
        val items = statusItems(status())
        assertEquals(listOf(StatusKind.NOTIFICATIONS, StatusKind.EXACT_ALARM, StatusKind.BATTERY), items.map { it.kind })
        assertTrue(items.all { it.level == StatusLevel.OK })
        val hero = heroText(enabled = true, items = items, day = "morgen", time = "06:30")
        assertTrue(hero.ready)
        assertEquals("Alles bereit für morgen, 06:30", hero.title)
    }

    @Test
    fun `exact alarms do not matter below android 12`() {
        val items = statusItems(status(exactRelevant = false, exact = false))
        assertEquals(StatusLevel.OK, items.single { it.kind == StatusKind.EXACT_ALARM }.level)
    }

    @Test
    fun `blocked notifications are the headline problem`() {
        val items = statusItems(status(notify = false, battery = false))
        val hero = heroText(enabled = true, items = items, day = "morgen", time = "06:30")
        assertFalse(hero.ready)
        assertEquals("Noch kommt morgens nichts an", hero.title)
        assertEquals("2 Kleinigkeiten fehlen noch – tippe auf die gelben Punkte.", hero.subtitle)
    }

    @Test
    fun `one problem gets its own hint and off is off`() {
        val items = statusItems(status(battery = false))
        assertEquals("Fast bereit für heute, 06:30", heroText(true, items, "heute", "06:30").title)
        assertTrue(heroText(true, items, "heute", "06:30").subtitle.contains("Akku"))
        assertEquals("Dein Morgenbriefing ist aus", heroText(false, items, "heute", "06:30").title)
    }
}

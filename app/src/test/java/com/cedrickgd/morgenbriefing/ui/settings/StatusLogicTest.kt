package com.cedrickgd.morgenbriefing.ui.settings

import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.util.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StatusLogicTest {

    private val allGood = SystemStatus(
        permissionGranted = true,
        appNotificationsEnabled = true,
        morningChannelBlocked = false,
        exactAlarmRelevant = true,
        exactAlarmsAllowed = true,
        batteryUnrestricted = true,
    )
    private val connected = SourceStatus.Connected(0L)
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 10, 5, 19, 0, 0, 0, zone)

    private fun levels(items: List<StatusItem>) = items.associate { it.kind to it.level }

    @Test
    fun `everything fine gives green pills and a ready hero`() {
        val items = statusItems(allGood, connected, aiSupported = true, hasAiKey = true)
        assertEquals(StatusKind.entries.toList(), items.map { it.kind })
        assertTrue(items.all { it.level == StatusLevel.OK })
        val hero = heroText(enabled = true, items = items, day = "morgen", time = "05:30")
        assertTrue(hero.ready)
        assertEquals("Alles bereit für morgen, 05:30", hero.title)
    }

    @Test
    fun `the ki pill is optional - neutral without key and hidden on android 7`() {
        val withoutKey = statusItems(allGood, connected, aiSupported = true, hasAiKey = false)
        assertEquals(StatusLevel.NEUTRAL, levels(withoutKey)[StatusKind.AI])
        assertTrue(heroText(true, withoutKey, "morgen", "05:30").ready)
        val old = statusItems(allGood, connected, aiSupported = false, hasAiKey = false)
        assertFalse(old.any { it.kind == StatusKind.AI })
    }

    @Test
    fun `exact alarms only matter from android 12`() {
        val oldAndroid = allGood.copy(exactAlarmRelevant = false, exactAlarmsAllowed = false)
        assertEquals(StatusLevel.OK, levels(statusItems(oldAndroid, connected, false, false))[StatusKind.EXACT_ALARM])
        val denied = allGood.copy(exactAlarmsAllowed = false)
        val items = statusItems(denied, connected, false, false)
        assertEquals(StatusLevel.ATTENTION, levels(items)[StatusKind.EXACT_ALARM])
        val hero = heroText(true, items, "morgen", "05:30")
        assertFalse(hero.ready)
        assertEquals("Fast bereit für morgen, 05:30", hero.title)
        assertEquals("Mit „Exakte Uhrzeit“ kommt dein Briefing auf die Minute.", hero.subtitle)
    }

    @Test
    fun `blocked notifications are the worst case`() {
        val items = statusItems(allGood.copy(permissionGranted = false), connected, false, false)
        assertEquals("nicht erlaubt", items.first { it.kind == StatusKind.NOTIFICATIONS }.state)
        assertEquals("Noch kommt morgens nichts an", heroText(true, items, "morgen", "05:30").title)
        val channelOff = statusItems(allGood.copy(morningChannelBlocked = true), connected, false, false)
        assertEquals("Kanal ausgeschaltet", channelOff.first { it.kind == StatusKind.NOTIFICATIONS }.state)
    }

    @Test
    fun `several problems are counted`() {
        val items = statusItems(allGood.copy(batteryUnrestricted = false), SourceStatus.NoToken, false, false)
        val hero = heroText(true, items, "heute", "06:00")
        assertEquals("Fast bereit für heute, 06:00", hero.title)
        assertEquals("2 Kleinigkeiten fehlen noch – tippe auf die gelben Punkte.", hero.subtitle)
    }

    @Test
    fun `news connection states`() {
        assertEquals(StatusLevel.ATTENTION, levels(statusItems(allGood, SourceStatus.NoToken, false, false))[StatusKind.NEWS])
        assertEquals(StatusLevel.NEUTRAL, levels(statusItems(allGood, SourceStatus.Checking, false, false))[StatusKind.NEWS])
        val failed = statusItems(allGood, SourceStatus.Failed(BriefErrorKind.NETWORK, 0L), false, false)
        assertEquals(StatusLevel.ATTENTION, levels(failed)[StatusKind.NEWS])
        val noToken = heroText(true, statusItems(allGood, SourceStatus.NoToken, false, false), "morgen", "05:30")
        assertEquals("Trag deinen GitHub-Token ein – bis dahin siehst du das Demo-Briefing.", noToken.subtitle)
    }

    @Test
    fun `switched off wins over everything`() {
        val items = statusItems(allGood.copy(permissionGranted = false), SourceStatus.NoToken, true, false)
        val hero = heroText(enabled = false, items = items, day = "morgen", time = "05:30")
        assertFalse(hero.ready)
        assertEquals("Dein Morgenbriefing ist aus", hero.title)
        assertTrue(hero.subtitle.contains("05:30"))
    }

    @Test
    fun `connection labels`() {
        val checkedAt = ZonedDateTime.of(2026, 10, 5, 18, 52, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Verbunden ✓ · geprüft 18:52", sourceStatusLabel(SourceStatus.Connected(checkedAt), now))
        assertEquals("Demo-Modus – kein Token", sourceStatusLabel(SourceStatus.NoToken, now))
        assertEquals("Prüfe die Verbindung …", sourceStatusLabel(SourceStatus.Checking, now))
        assertEquals(
            "Token ungültig · geprüft 18:52",
            sourceStatusLabel(SourceStatus.Failed(BriefErrorKind.UNAUTHORIZED, checkedAt), now),
        )
        assertEquals(
            "Keine Verbindung · geprüft 18:52",
            sourceStatusLabel(SourceStatus.Failed(BriefErrorKind.NETWORK, checkedAt), now),
        )
        val yesterday = ZonedDateTime.of(2026, 10, 4, 23, 10, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Verbunden ✓ · geprüft So, 4. Okt.", sourceStatusLabel(SourceStatus.Connected(yesterday), now))
    }
}

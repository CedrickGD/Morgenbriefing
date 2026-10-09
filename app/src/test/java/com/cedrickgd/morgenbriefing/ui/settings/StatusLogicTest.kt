package com.cedrickgd.morgenbriefing.ui.settings

import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.ProviderSettings
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

    private fun keyed(id: AiProviderId = AiProviderId.ANTHROPIC, untested: Boolean = false, rejected: Boolean = false, hasKey: Boolean = true) =
        AiSettings(provider = id, providers = mapOf(id to ProviderSettings(hasKey = hasKey, keyUntested = untested, keyRejected = rejected)))

    private fun aiPill(ai: AiSettings, supported: Boolean = true) = statusItems(allGood, connected, ai, supported).single { it.kind == StatusKind.AI }

    @Test
    fun `everything fine gives green pills and a ready hero`() {
        val items = statusItems(allGood, connected, keyed(), aiProviderSupported = true)
        assertEquals(StatusKind.entries.toList(), items.map { it.kind })
        assertTrue(items.all { it.level == StatusLevel.OK })
        val hero = heroText(enabled = true, items = items, day = "morgen", time = "05:30")
        assertTrue(hero.ready)
        assertEquals("Alles bereit für morgen, 05:30", hero.title)
    }

    @Test
    fun `the ki pill is optional - neutral without key, and on android 7 it asks for another provider`() {
        val withoutKey = statusItems(allGood, connected, keyed(hasKey = false), aiProviderSupported = true)
        assertEquals(StatusItem(StatusKind.AI, StatusLevel.NEUTRAL, "KI", "aus – kein Key für Claude"), withoutKey.single { it.kind == StatusKind.AI })
        assertTrue(heroText(true, withoutKey, "morgen", "05:30").ready)
        // Shown on every API level now: Claude below Android 8 says so, neutrally.
        val old = statusItems(allGood, connected, keyed(), aiProviderSupported = false)
        assertEquals(StatusItem(StatusKind.AI, StatusLevel.NEUTRAL, "KI", "Claude braucht Android 8"), old.single { it.kind == StatusKind.AI })
        assertTrue(heroText(true, old, "morgen", "05:30").ready)
    }

    @Test
    fun `an untested ki key is neutral and does not claim to work`() {
        val untested = statusItems(allGood, connected, keyed(untested = true), aiProviderSupported = true)
        val ai = untested.single { it.kind == StatusKind.AI }
        assertEquals(StatusLevel.NEUTRAL, ai.level)
        assertEquals("Key noch nicht getestet", ai.state)
        assertTrue(heroText(true, untested, "morgen", "05:30").ready)
        assertEquals(StatusItem(StatusKind.AI, StatusLevel.OK, "KI", "eingerichtet · Claude"), aiPill(keyed()))
    }

    @Test
    fun `a refused ki key needs attention but does not hold up the briefing`() {
        val rejected = statusItems(allGood, connected, keyed(rejected = true), aiProviderSupported = true)
        assertEquals(StatusItem(StatusKind.AI, StatusLevel.ATTENTION, "KI", "Key von Claude abgelehnt"), rejected.single { it.kind == StatusKind.AI })
        assertTrue(heroText(true, rejected, "morgen", "05:30").ready)
        // Refused wins over "never tested"; without a key there is nothing to refuse.
        assertEquals("Key von Claude abgelehnt", aiPill(keyed(untested = true, rejected = true)).state)
        assertEquals(StatusLevel.NEUTRAL, aiPill(keyed(hasKey = false, rejected = true)).level)
    }

    @Test
    fun `the ki pill follows the chat provider`() {
        assertEquals(StatusItem(StatusKind.AI, StatusLevel.OK, "KI", "eingerichtet · Gemini"), aiPill(keyed(AiProviderId.GEMINI)))
        assertEquals("Key von Grok abgelehnt", aiPill(keyed(AiProviderId.XAI, rejected = true)).state)
        assertEquals("aus – kein Key für OpenAI", aiPill(keyed(AiProviderId.OPENAI, hasKey = false)).state)
        // The own server needs an address, not a key.
        assertEquals(
            StatusItem(StatusKind.AI, StatusLevel.NEUTRAL, "KI", "aus – kein Server eingerichtet"),
            aiPill(AiSettings(provider = AiProviderId.CUSTOM)),
        )
        val server = AiSettings(
            provider = AiProviderId.CUSTOM,
            providers = mapOf(AiProviderId.CUSTOM to ProviderSettings(baseUrl = "http://nas.fritz.box:11434/v1")),
        )
        assertEquals(StatusItem(StatusKind.AI, StatusLevel.OK, "KI", "eingerichtet · eigener Server"), aiPill(server))
    }

    @Test
    fun `exact alarms only matter from android 12`() {
        val oldAndroid = allGood.copy(exactAlarmRelevant = false, exactAlarmsAllowed = false)
        assertEquals(StatusLevel.OK, levels(statusItems(oldAndroid, connected, AiSettings(), true))[StatusKind.EXACT_ALARM])
        val denied = allGood.copy(exactAlarmsAllowed = false)
        val items = statusItems(denied, connected, AiSettings(), true)
        assertEquals(StatusLevel.ATTENTION, levels(items)[StatusKind.EXACT_ALARM])
        val hero = heroText(true, items, "morgen", "05:30")
        assertFalse(hero.ready)
        assertEquals("Fast bereit für morgen, 05:30", hero.title)
        assertEquals("Mit „Exakte Uhrzeit“ kommt dein Briefing auf die Minute.", hero.subtitle)
    }

    @Test
    fun `blocked notifications are the worst case`() {
        val items = statusItems(allGood.copy(permissionGranted = false), connected, AiSettings(), true)
        assertEquals("nicht erlaubt", items.first { it.kind == StatusKind.NOTIFICATIONS }.state)
        assertEquals("Noch kommt morgens nichts an", heroText(true, items, "morgen", "05:30").title)
        val channelOff = statusItems(allGood.copy(morningChannelBlocked = true), connected, AiSettings(), true)
        assertEquals("Kanal ausgeschaltet", channelOff.first { it.kind == StatusKind.NOTIFICATIONS }.state)
    }

    @Test
    fun `several problems are counted`() {
        val items = statusItems(allGood.copy(batteryUnrestricted = false), SourceStatus.NoToken, AiSettings(), true)
        val hero = heroText(true, items, "heute", "06:00")
        assertEquals("Fast bereit für heute, 06:00", hero.title)
        assertEquals("2 Kleinigkeiten fehlen noch – tippe auf die gelben Punkte.", hero.subtitle)
    }

    @Test
    fun `news connection states`() {
        assertEquals(StatusLevel.ATTENTION, levels(statusItems(allGood, SourceStatus.NoToken, AiSettings(), true))[StatusKind.NEWS])
        assertEquals(StatusLevel.NEUTRAL, levels(statusItems(allGood, SourceStatus.Checking, AiSettings(), true))[StatusKind.NEWS])
        val failed = statusItems(allGood, SourceStatus.Failed(BriefErrorKind.NETWORK, 0L), AiSettings(), true)
        assertEquals(StatusLevel.ATTENTION, levels(failed)[StatusKind.NEWS])
        val noToken = heroText(true, statusItems(allGood, SourceStatus.NoToken, AiSettings(), true), "morgen", "05:30")
        assertEquals("Trag deinen GitHub-Token ein – bis dahin siehst du das Demo-Briefing.", noToken.subtitle)
    }

    @Test
    fun `switched off wins over everything`() {
        val items = statusItems(allGood.copy(permissionGranted = false), SourceStatus.NoToken, AiSettings(), true)
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

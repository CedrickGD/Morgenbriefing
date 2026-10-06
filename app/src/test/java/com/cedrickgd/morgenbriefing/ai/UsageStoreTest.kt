package com.cedrickgd.morgenbriefing.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * In-memory Preferences DataStore. (The file-backed one cannot replace its file on the Windows
 * JVM the unit tests run on — rename does not overwrite there; on Android it does.)
 */
internal class MemoryPreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

/** A wall clock the test moves by hand (independent of the coroutine test scheduler). */
internal class MutableClock(@Volatile var now: Instant, private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = View(zone)
    override fun instant(): Instant = now

    private inner class View(private val zoneId: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = View(zone)
        override fun instant(): Instant = now
    }
}

class UsageStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val limit = MutableStateFlow(5.0)
    private val dataStore = MemoryPreferencesStore()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun store(clock: Clock): UsageStore = UsageStore(dataStore, limit, scope, clock)

    private fun clockAt(iso: String): Clock = Clock.fixed(Instant.parse(iso), ZoneId.of("Europe/Berlin"))

    @Test
    fun `records cost per month and blocks at the limit`() = runBlocking<Unit> {
        val usage = store(clockAt("2026-10-05T10:00:00Z"))
        usage.record("claude-sonnet-5-5", TokenUsage(inputTokens = 1_000_000, webSearches = 2)) // 2.02 $
        usage.record("claude-opus-5-5", TokenUsage(outputTokens = 100_000)) // 2.00 $

        val snapshot = withTimeout(5_000) { usage.usage.first { it.requests == 2 } }
        assertEquals("2026-10", snapshot.month)
        assertEquals(4.02, snapshot.spentUsd, 1e-9)
        assertEquals(5.0, snapshot.limitUsd, 0.0)
        assertEquals(2, snapshot.webSearches)
        assertFalse(usage.limitReached())
        usage.beforeRequest()

        usage.record("claude-sonnet-5-5", TokenUsage(outputTokens = 100_000)) // 1.00 $ → 5.02
        assertEquals(5.02, usage.spentThisMonth(), 1e-9)
        assertTrue(usage.limitReached())
        try {
            usage.beforeRequest()
            fail("expected AiBlockedException")
        } catch (e: AiBlockedException) {
            assertEquals(AiBlock.LIMIT_REACHED, e.block)
        }
        assertEquals(AiBlock.LIMIT_REACHED, aiBlock(true, true, withTimeout(5_000) { usage.usage.first { it.requests == 3 } }))

        // Limit 0 = no cap.
        limit.value = 0.0
        assertFalse(usage.limitReached())
        withTimeout(5_000) { usage.usage.first { it.limitUsd == 0.0 } }
    }

    @Test
    fun `empty usage is not recorded`() = runBlocking<Unit> {
        val usage = store(clockAt("2026-10-05T10:00:00Z"))
        usage.record("claude-sonnet-5-5", TokenUsage())
        assertEquals(0.0, usage.spentThisMonth(), 0.0)
        assertEquals(0, usage.usage.value.requests)
    }

    @Test
    fun `a new month starts at zero`() = runBlocking<Unit> {
        store(clockAt("2026-09-30T20:00:00Z")).record("claude-sonnet-5-5", TokenUsage(outputTokens = 100_000))
        assertEquals(1.0, store(clockAt("2026-09-30T20:00:00Z")).spentThisMonth(), 1e-9)
        // 22:30 UTC on Sep 30 is already October 1st in Berlin.
        val october = store(clockAt("2026-09-30T22:30:00Z"))
        assertEquals(0.0, october.spentThisMonth(), 0.0)
        val snapshot = withTimeout(5_000) { october.usage.first { it.month == "2026-10" } }
        assertEquals(0, snapshot.requests)
        october.record("claude-sonnet-5-5", TokenUsage(outputTokens = 50_000))
        assertEquals(0.5, october.spentThisMonth(), 1e-9)
        assertEquals(1, withTimeout(5_000) { october.usage.first { it.requests == 1 } }.requests)
    }

    @Test
    fun `last month's limit lifts at the Berlin month start without any write`() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-31T20:00:00Z"))
        val usage = UsageStore(dataStore, limit, backgroundScope, clock)
        usage.record("claude-opus-5-5", TokenUsage(outputTokens = 300_000)) // 6 $
        runCurrent()
        val october = usage.usage.value
        assertEquals("2026-10", october.month)
        assertEquals(6.0, october.spentUsd, 1e-9)
        assertEquals(AiBlock.LIMIT_REACHED, aiBlock(true, true, october))

        // 23:30 UTC on Oct 31 is November 1st in Berlin. While the limit blocks, no request runs
        // and nothing writes the store — the snapshot must still move on by itself.
        clock.now = Instant.parse("2026-10-31T23:30:00Z")
        advanceTimeBy(UsageStore.MONTH_RECHECK.toMillis() + 1)
        runCurrent()
        val november = usage.usage.value
        assertEquals("2026-11", november.month)
        assertEquals(0.0, november.spentUsd, 0.0)
        assertEquals(5.0, november.limitUsd, 0.0)
        assertNull(aiBlock(true, true, november))
        assertFalse(usage.limitReached())
    }

    @Test
    fun `month checks wait at most a minute and land on the month start`() {
        // Oct 31, 2026 is CET (UTC+1): November starts at 23:00 UTC.
        assertEquals(UsageStore.MONTH_RECHECK, UsageStore.nextMonthCheckIn(Instant.parse("2026-10-31T20:00:00Z")))
        assertEquals(Duration.ofSeconds(30), UsageStore.nextMonthCheckIn(Instant.parse("2026-10-31T22:59:30Z")))
        assertEquals(UsageStore.MONTH_RECHECK, UsageStore.nextMonthCheckIn(Instant.parse("2026-10-31T23:00:00Z")))
        // Never a zero (busy) wait.
        assertEquals(Duration.ofMillis(1), UsageStore.nextMonthCheckIn(Instant.parse("2026-10-31T22:59:59.9999Z")))
    }

    @Test
    fun `block precedence`() {
        val spent = UsageSnapshot(month = "2026-10", spentUsd = 6.0, limitUsd = 5.0)
        assertEquals(AiBlock.UNSUPPORTED_DEVICE, aiBlock(false, true, spent))
        assertEquals(AiBlock.NO_KEY, aiBlock(true, false, spent))
        assertEquals(AiBlock.LIMIT_REACHED, aiBlock(true, true, spent))
        assertEquals(null, aiBlock(true, true, spent.copy(limitUsd = 0.0)))
        assertEquals(null, aiBlock(true, true, spent.copy(spentUsd = 1.0)))
        // Unit tests run with Build.VERSION.SDK_INT = 0, like a pre-Oreo device.
        assertFalse(AiSupport.isSupported)
    }
}

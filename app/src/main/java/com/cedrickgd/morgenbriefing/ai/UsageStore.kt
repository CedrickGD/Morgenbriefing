package com.cedrickgd.morgenbriefing.ai

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * This month's AI spending of all providers together (docs/AI-PROVIDERS.md §9): every response's
 * usage is priced with [AiPricing] (or the provider's reported cost) on the day of the request and
 * added up per calendar month in Europe/Berlin; requests priced with fallback rates are counted
 * as [UsageSnapshot.estimatedRequests]. A new month starts at zero — also in a running process:
 * [usage] re-checks the month at least every [MONTH_RECHECK], so a "Monatslimit erreicht" from
 * last month does not outlive the 1st.
 * Backed by its own small Preferences DataStore ("ai_usage").
 *
 * @param limitUsd the user's monthly cap (settings.aiMonthlyLimitUsd), 0 = no cap.
 */
internal class UsageStore(
    private val dataStore: DataStore<Preferences>,
    private val limitUsd: Flow<Double>,
    scope: CoroutineScope,
    private val clock: Clock = Clock.system(BERLIN),
) : UsageTracker, UsageSink {

    private val prefs: Flow<Preferences> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    /**
     * The current Berlin month, re-read at least every [MONTH_RECHECK] and right at the next
     * month start. Nothing else re-emits when the month changes: while the limit blocks, no
     * request runs, so nothing writes the DataStore. Short waits instead of one long delay
     * also cover deep sleep (delays stand still there) and a changed device clock.
     */
    private val months: Flow<String> = flow {
        while (true) {
            emit(currentMonth())
            delay(nextMonthCheckIn(clock.instant()).toMillis())
        }
    }.distinctUntilChanged()

    // The month only triggers a recompute; snapshot() reads the clock itself, so a write that
    // already belongs to the new month is never shown as last month's.
    override val usage: StateFlow<UsageSnapshot> =
        combine(prefs, limitUsd.distinctUntilChanged(), months) { p, limit, _ ->
            snapshot(p, limit)
        }.stateIn(scope, SharingStarted.Eagerly, UsageSnapshot(month = currentMonth()))

    /** Dollars spent this month, read fresh from disk. */
    suspend fun spentThisMonth(): Double = snapshot(prefs.first(), 0.0).spentUsd

    /** True when a cap is set and this month's spending has reached it. */
    suspend fun limitReached(): Boolean {
        val limit = limitUsd.first()
        return limit > 0.0 && spentThisMonth() >= limit
    }

    override suspend fun beforeRequest() {
        if (limitReached()) throw AiBlockedException(AiBlock.LIMIT_REACHED)
    }

    override suspend fun record(provider: AiProviderId, modelId: String, usage: TokenUsage) {
        if (usage.isEmpty) return
        val priced = AiPricing.costUsd(provider, modelId, usage, LocalDate.now(clock.withZone(BERLIN)))
        val month = currentMonth()
        try {
            dataStore.edit { p ->
                if (p[Keys.MONTH] != month) {
                    p[Keys.MONTH] = month
                    p[Keys.SPENT] = 0.0
                    p[Keys.REQUESTS] = 0
                    p[Keys.SEARCHES] = 0
                    p[Keys.INPUT] = 0L
                    p[Keys.OUTPUT] = 0L
                    p[Keys.ESTIMATED] = 0
                }
                p[Keys.SPENT] = (p[Keys.SPENT] ?: 0.0) + priced.usd
                if (priced.estimated) p[Keys.ESTIMATED] = (p[Keys.ESTIMATED] ?: 0) + 1
                p[Keys.REQUESTS] = (p[Keys.REQUESTS] ?: 0) + 1
                p[Keys.SEARCHES] = (p[Keys.SEARCHES] ?: 0) + usage.webSearches.toInt()
                p[Keys.INPUT] = (p[Keys.INPUT] ?: 0L) + usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
                p[Keys.OUTPUT] = (p[Keys.OUTPUT] ?: 0L) + usage.outputTokens
            }
        } catch (e: IOException) {
            // Never break an answer because the counter could not be written.
            Log.w(TAG, "AI usage could not be stored", e)
        }
    }

    private fun snapshot(p: Preferences, limit: Double): UsageSnapshot {
        val month = currentMonth()
        if (p[Keys.MONTH] != month) return UsageSnapshot(month = month, limitUsd = limit)
        return UsageSnapshot(
            month = month,
            spentUsd = p[Keys.SPENT] ?: 0.0,
            limitUsd = limit,
            requests = p[Keys.REQUESTS] ?: 0,
            webSearches = p[Keys.SEARCHES] ?: 0,
            estimatedRequests = p[Keys.ESTIMATED] ?: 0,
        )
    }

    private fun currentMonth(): String = YearMonth.now(clock.withZone(BERLIN)).toString()

    private object Keys {
        val MONTH = stringPreferencesKey("month")
        val SPENT = doublePreferencesKey("spent_usd")
        val REQUESTS = intPreferencesKey("requests")
        val SEARCHES = intPreferencesKey("web_searches")
        val INPUT = longPreferencesKey("input_tokens")
        val OUTPUT = longPreferencesKey("output_tokens")

        /** Requests priced with fallback rates (a model the price table does not know). */
        val ESTIMATED = intPreferencesKey("estimated_requests")
    }

    internal companion object {
        private const val TAG = "UsageStore"

        /** Longest wait between two checks of the current month. */
        val MONTH_RECHECK: Duration = Duration.ofMinutes(1)

        /** Wait until the next month check: until the next Berlin month start, at most [MONTH_RECHECK]. */
        fun nextMonthCheckIn(now: Instant): Duration {
            val nextMonth = YearMonth.from(now.atZone(BERLIN)).plusMonths(1).atDay(1).atStartOfDay(BERLIN).toInstant()
            val untilNextMonth = Duration.between(now, nextMonth)
            return when {
                untilNextMonth >= MONTH_RECHECK -> MONTH_RECHECK
                untilNextMonth.toMillis() < 1 -> Duration.ofMillis(1)
                else -> untilNextMonth
            }
        }
    }
}

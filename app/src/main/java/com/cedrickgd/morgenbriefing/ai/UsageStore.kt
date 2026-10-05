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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import java.time.Clock
import java.time.YearMonth

/**
 * This month's AI spending (docs/AI.md "Modelle & Kosten"): every response's usage is priced
 * with [AiPricing] and added up per calendar month in Europe/Berlin. A new month starts at
 * zero. Backed by its own small Preferences DataStore ("ai_usage").
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

    override val usage: StateFlow<UsageSnapshot> = combine(prefs, limitUsd.distinctUntilChanged()) { p, limit ->
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

    override suspend fun record(modelId: String, usage: TokenUsage) {
        if (usage.isEmpty) return
        val cost = AiPricing.costUsd(modelId, usage)
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
                }
                p[Keys.SPENT] = (p[Keys.SPENT] ?: 0.0) + cost
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
    }

    private companion object {
        const val TAG = "UsageStore"
    }
}

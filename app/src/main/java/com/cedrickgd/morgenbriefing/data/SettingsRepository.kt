package com.cedrickgd.morgenbriefing.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cedrickgd.morgenbriefing.data.local.TokenCipher
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException

/** The one DataStore instance per process (DataStore forbids two instances on the same file). */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Preferences DataStore named "settings". The GitHub token is stored encrypted via
 * [com.cedrickgd.morgenbriefing.data.local.TokenCipher]; [AppSettings.hasToken] reflects it.
 * Places are stored as JSON strings.
 */
class SettingsRepository(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.settingsStore

    /** Always emits; unreadable storage falls back to the defaults instead of crashing. */
    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    /**
     * Atomically maps the stored settings through [transform] and writes every field back.
     * The token is never touched here ([AppSettings.hasToken] is derived, not written).
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    /**
     * Trims whitespace; null/blank clears the token.
     * @throws IOException if DataStore cannot persist the change.
     * @throws java.security.GeneralSecurityException if the keystore cannot encrypt.
     */
    suspend fun setToken(token: String?) {
        val clean = token?.trim().orEmpty()
        if (clean.isEmpty()) {
            store.edit { it.remove(Keys.TOKEN) }
            return
        }
        val encrypted = withContext(Dispatchers.IO) { TokenCipher.encrypt(clean) }
        store.edit { it[Keys.TOKEN] = encrypted }
    }

    /** Decrypted token or null. */
    suspend fun token(): String? {
        val encrypted = try {
            store.data.first()[Keys.TOKEN]
        } catch (_: IOException) {
            null
        }
        if (encrypted.isNullOrBlank()) return null
        val plain = withContext(Dispatchers.IO) { TokenCipher.decrypt(encrypted) }
        if (plain == null) forgetUndecryptableToken(encrypted)
        return plain?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Claude API key (docs/AI.md), stored encrypted like the GitHub token.
     * Trims whitespace; null/blank clears it. [tested] false marks it as saved without a passed
     * test ([AppSettings.aiKeyUntested]) until [markAiKeyWorks]. Saving a key (or none) always
     * drops the rejected mark ([AppSettings.aiKeyRejected]) of the previous one.
     * @throws IOException if DataStore cannot persist the change.
     * @throws java.security.GeneralSecurityException if the keystore cannot encrypt.
     */
    suspend fun setAiKey(key: String?, tested: Boolean = true) {
        val clean = key?.trim().orEmpty()
        if (clean.isEmpty()) {
            store.edit {
                it.remove(Keys.AI_KEY)
                it.remove(Keys.AI_KEY_UNTESTED)
                it.remove(Keys.AI_KEY_REJECTED)
            }
            return
        }
        val encrypted = withContext(Dispatchers.IO) { TokenCipher.encrypt(clean) }
        store.edit {
            it[Keys.AI_KEY] = encrypted
            if (tested) it.remove(Keys.AI_KEY_UNTESTED) else it[Keys.AI_KEY_UNTESTED] = true
            it.remove(Keys.AI_KEY_REJECTED)
        }
    }

    /**
     * Anthropic accepted a request with the stored key: it is neither untested nor rejected any
     * more. No write when it was not marked.
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun markAiKeyWorks() {
        store.edit {
            if (Keys.AI_KEY_UNTESTED in it) it.remove(Keys.AI_KEY_UNTESTED)
            if (Keys.AI_KEY_REJECTED in it) it.remove(Keys.AI_KEY_REJECTED)
        }
    }

    /**
     * Anthropic refused a chat or Live request made with [key] as not authenticated (HTTP 401):
     * marks the stored key as rejected ([AppSettings.aiKeyRejected]) — but only while [key] is
     * still the stored one, so a request that was under way while a new key was saved cannot
     * brand the new key.
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun markAiKeyRejected(key: String) {
        val encrypted = try {
            store.data.first()[Keys.AI_KEY]
        } catch (_: IOException) {
            null
        }
        if (encrypted.isNullOrBlank()) return
        val stored = withContext(Dispatchers.IO) { TokenCipher.decrypt(encrypted) }?.trim()
        if (stored == null || stored != key.trim()) return
        // Same ciphertext as checked above: no other key was saved in between.
        store.edit { if (it[Keys.AI_KEY] == encrypted) it[Keys.AI_KEY_REJECTED] = true }
    }

    /** Decrypted Claude API key or null. */
    suspend fun aiKey(): String? {
        val encrypted = try {
            store.data.first()[Keys.AI_KEY]
        } catch (_: IOException) {
            null
        }
        if (encrypted.isNullOrBlank()) return null
        val plain = withContext(Dispatchers.IO) { TokenCipher.decrypt(encrypted) }
        if (plain == null) forgetUndecryptable(Keys.AI_KEY, encrypted)
        return plain?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun forgetUndecryptableToken(encrypted: String) = forgetUndecryptable(Keys.TOKEN, encrypted)

    /**
     * If the keystore entry is definitely gone (not just a hiccup), the stored ciphertext can
     * never be read again: drop it so the settings screen asks for the secret again.
     */
    private suspend fun forgetUndecryptable(key: Preferences.Key<String>, encrypted: String) {
        val keyExists = withContext(Dispatchers.IO) { TokenCipher.hasKey() }
        if (keyExists != false) return
        try {
            store.edit { prefs -> if (prefs[key] == encrypted) prefs.remove(key) }
        } catch (_: IOException) {
            // Retried on the next read.
        }
    }

    // ---------------------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------------------

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = enumOrDefault(this[Keys.THEME_MODE], defaults.themeMode),
            accent = enumOrDefault(this[Keys.ACCENT], defaults.accent),
            notificationsEnabled = this[Keys.NOTIFICATIONS_ENABLED] ?: defaults.notificationsEnabled,
            notifyHour = this[Keys.NOTIFY_HOUR]?.takeIf { it in 0..23 } ?: defaults.notifyHour,
            notifyMinute = this[Keys.NOTIFY_MINUTE]?.takeIf { it in 0..59 } ?: defaults.notifyMinute,
            home = placeOrDefault(this[Keys.HOME_PLACE], defaults.home),
            day = placeOrDefault(this[Keys.DAY_PLACE], defaults.day),
            dayStartHour = this[Keys.DAY_START_HOUR]?.takeIf { it in 0..23 } ?: defaults.dayStartHour,
            dayEndHour = this[Keys.DAY_END_HOUR]?.takeIf { it in 0..23 } ?: defaults.dayEndHour,
            hasToken = !this[Keys.TOKEN].isNullOrBlank(),
            onboardingDone = this[Keys.ONBOARDING_DONE] ?: defaults.onboardingDone,
            lastNotifiedDate = this[Keys.LAST_NOTIFIED_DATE]?.takeIf { it.isNotBlank() },
            topicOrder = topicOrderOrDefault(this[Keys.TOPIC_ORDER], defaults.topicOrder),
            hiddenTopics = this[Keys.HIDDEN_TOPICS]
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: defaults.hiddenTopics,
            readerTextScale = this[Keys.READER_TEXT_SCALE]?.takeIf { it in 0.85f..1.4f } ?: defaults.readerTextScale,
            hasAiKey = !this[Keys.AI_KEY].isNullOrBlank(),
            aiKeyUntested = !this[Keys.AI_KEY].isNullOrBlank() && this[Keys.AI_KEY_UNTESTED] == true,
            aiKeyRejected = !this[Keys.AI_KEY].isNullOrBlank() && this[Keys.AI_KEY_REJECTED] == true,
            aiModel = enumOrDefault(this[Keys.AI_MODEL], defaults.aiModel),
            aiMonthlyLimitUsd = this[Keys.AI_MONTHLY_LIMIT]?.takeIf { it in 0.0..1000.0 } ?: defaults.aiMonthlyLimitUsd,
            liveAutoRefresh = this[Keys.LIVE_AUTO_REFRESH] ?: defaults.liveAutoRefresh,
        )
    }

    /** Stored order first; catalogue topics added in a newer app version are appended. */
    private fun topicOrderOrDefault(raw: String?, default: List<String>): List<String> {
        val stored = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        if (stored.isEmpty()) return default
        return stored + default.filterNot { it in stored }
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.THEME_MODE] = s.themeMode.name
        this[Keys.ACCENT] = s.accent.name
        this[Keys.NOTIFICATIONS_ENABLED] = s.notificationsEnabled
        this[Keys.NOTIFY_HOUR] = s.notifyHour.coerceIn(0, 23)
        this[Keys.NOTIFY_MINUTE] = s.notifyMinute.coerceIn(0, 59)
        this[Keys.HOME_PLACE] = AppJson.encodeToString(Place.serializer(), s.home)
        this[Keys.DAY_PLACE] = AppJson.encodeToString(Place.serializer(), s.day)
        this[Keys.DAY_START_HOUR] = s.dayStartHour.coerceIn(0, 23)
        this[Keys.DAY_END_HOUR] = s.dayEndHour.coerceIn(0, 23)
        this[Keys.ONBOARDING_DONE] = s.onboardingDone
        val last = s.lastNotifiedDate
        if (last.isNullOrBlank()) remove(Keys.LAST_NOTIFIED_DATE) else this[Keys.LAST_NOTIFIED_DATE] = last
        this[Keys.TOPIC_ORDER] = s.topicOrder.filter { it.isNotBlank() && ',' !in it }.distinct().joinToString(",")
        this[Keys.HIDDEN_TOPICS] = s.hiddenTopics.filter { it.isNotBlank() && ',' !in it }.sorted().joinToString(",")
        this[Keys.READER_TEXT_SCALE] = s.readerTextScale.coerceIn(0.85f, 1.4f)
        this[Keys.AI_MODEL] = s.aiModel.name
        this[Keys.AI_MONTHLY_LIMIT] = s.aiMonthlyLimitUsd.coerceIn(0.0, 1000.0)
        this[Keys.LIVE_AUTO_REFRESH] = s.liveAutoRefresh
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun placeOrDefault(raw: String?, default: Place): Place {
        if (raw.isNullOrBlank()) return default
        return try {
            AppJson.decodeFromString(Place.serializer(), raw)
        } catch (_: SerializationException) {
            default
        } catch (_: IllegalArgumentException) {
            default
        }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFY_HOUR = intPreferencesKey("notify_hour")
        val NOTIFY_MINUTE = intPreferencesKey("notify_minute")
        val HOME_PLACE = stringPreferencesKey("home_place")
        val DAY_PLACE = stringPreferencesKey("day_place")
        val DAY_START_HOUR = intPreferencesKey("day_start_hour")
        val DAY_END_HOUR = intPreferencesKey("day_end_hour")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val LAST_NOTIFIED_DATE = stringPreferencesKey("last_notified_date")
        val TOPIC_ORDER = stringPreferencesKey("topic_order")
        val HIDDEN_TOPICS = stringPreferencesKey("hidden_topics")
        val READER_TEXT_SCALE = floatPreferencesKey("reader_text_scale")
        val AI_KEY = stringPreferencesKey("ai_key_enc")
        val AI_KEY_UNTESTED = booleanPreferencesKey("ai_key_untested")
        val AI_KEY_REJECTED = booleanPreferencesKey("ai_key_rejected")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_MONTHLY_LIMIT = doublePreferencesKey("ai_monthly_limit_usd")
        val LIVE_AUTO_REFRESH = booleanPreferencesKey("live_auto_refresh")
        val TOKEN = stringPreferencesKey("gh_token_enc")
    }
}

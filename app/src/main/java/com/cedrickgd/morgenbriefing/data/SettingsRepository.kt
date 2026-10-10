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
import com.cedrickgd.morgenbriefing.BuildConfig
import com.cedrickgd.morgenbriefing.data.local.TokenCipher
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier
import com.cedrickgd.morgenbriefing.data.model.ModelFallback
import com.cedrickgd.morgenbriefing.data.model.OpenRouterTier
import com.cedrickgd.morgenbriefing.data.model.ProviderSettings
import com.cedrickgd.morgenbriefing.data.model.cleanApiKey
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
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    // v1.2's single Claude key moves into the Anthropic slot before the first read.
    produceMigrations = { listOf(AiProvidersMigration) },
)

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
     * The API key of [provider] (docs/AI-PROVIDERS.md §3), stored encrypted like the GitHub
     * token. Trims whitespace; null/blank clears it with its flags (a removed Anthropic key also
     * takes its workspace id along, a removed Gemini or OpenRouter key its tariff, so the next
     * setup starts fresh). [tested] false marks it as saved without a passed test
     * ([ProviderSettings.keyUntested]) until [markAiKeyWorks]. Saving a key (or none) always
     * drops the rejected mark ([ProviderSettings.keyRejected]) of the previous one.
     * @throws IOException if DataStore cannot persist the change.
     * @throws java.security.GeneralSecurityException if the keystore cannot encrypt.
     */
    suspend fun setAiKey(provider: AiProviderId, key: String?, tested: Boolean = true) {
        val clean = cleanApiKey(key)
        if (clean.isEmpty()) {
            store.edit {
                it.remove(AiPrefKeys.keyEnc(provider))
                it.remove(AiPrefKeys.untested(provider))
                it.remove(AiPrefKeys.rejected(provider))
                it.writeModelFallback(provider, null)
                when (provider) {
                    AiProviderId.ANTHROPIC -> it.remove(AiPrefKeys.ANTHROPIC_WORKSPACE_ID)
                    AiProviderId.GEMINI -> it.remove(AiPrefKeys.GEMINI_TIER)
                    AiProviderId.OPENROUTER -> it.remove(AiPrefKeys.OPENROUTER_TIER)
                    else -> Unit
                }
            }
            return
        }
        val encrypted = withContext(Dispatchers.IO) { TokenCipher.encrypt(clean) }
        store.edit {
            it[AiPrefKeys.keyEnc(provider)] = encrypted
            if (tested) it.remove(AiPrefKeys.untested(provider)) else it[AiPrefKeys.untested(provider)] = true
            it.remove(AiPrefKeys.rejected(provider))
            // The fallback belonged to the previous key; this one tries the chosen model again.
            it.writeModelFallback(provider, null)
        }
    }

    /**
     * The model that runs instead of the chosen one for the stored key of [provider]
     * ([ProviderSettings.modelFallback]); null removes it. With [key], only while [key] is still
     * the stored one (a chat answer that ran while another key was saved must not set it) and,
     * with [geminiTier], while that is still the stored tariff (a switch dropped the fallback, an
     * answer that finishes afterwards must not store it again); without [key] the caller has just
     * saved the key it tested.
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun setModelFallback(provider: AiProviderId, fallback: ModelFallback?, key: String? = null, geminiTier: GeminiTier? = null) {
        if (key == null) {
            store.edit { it.writeModelFallback(provider, fallback) }
            return
        }
        val candidate = cleanApiKey(key)
        if (candidate.isEmpty()) return
        val slot = AiPrefKeys.keyEnc(provider)
        val encrypted = readPrefs()?.get(slot)
        if (encrypted.isNullOrBlank()) return
        val stored = withContext(Dispatchers.IO) { TokenCipher.decrypt(encrypted) }?.trim()
        if (stored == null || stored != candidate) return
        // Same ciphertext as checked above (no other key was saved in between), same tariff.
        store.edit { if (fallbackStillFits(it, provider, encrypted, geminiTier)) it.writeModelFallback(provider, fallback) }
    }

    /**
     * [provider] accepted a request with its stored key: it is neither untested nor rejected any
     * more. No write when it was not marked.
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun markAiKeyWorks(provider: AiProviderId) {
        val untested = AiPrefKeys.untested(provider)
        val rejected = AiPrefKeys.rejected(provider)
        store.edit {
            if (untested in it) it.remove(untested)
            if (rejected in it) it.remove(rejected)
        }
    }

    /**
     * [provider] refused a chat or Live request or a key test made with [key] as not
     * authenticated: marks the stored key as rejected ([ProviderSettings.keyRejected]) — but only
     * while [key] is still the stored one, so a request that was under way while a new key was
     * saved, or a test of another, unsaved key, cannot brand the stored key. No-op without a key
     * (custom server without one).
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun markAiKeyRejected(provider: AiProviderId, key: String?) {
        val candidate = cleanApiKey(key)
        if (candidate.isEmpty()) return
        val slot = AiPrefKeys.keyEnc(provider)
        val encrypted = readPrefs()?.get(slot)
        if (encrypted.isNullOrBlank()) return
        val stored = withContext(Dispatchers.IO) { TokenCipher.decrypt(encrypted) }?.trim()
        if (stored == null || stored != candidate) return
        // Same ciphertext as checked above: no other key was saved in between.
        store.edit { if (it[slot] == encrypted) it[AiPrefKeys.rejected(provider)] = true }
    }

    /** Decrypted API key of [provider] or null. */
    suspend fun aiKey(provider: AiProviderId): String? {
        val slot = AiPrefKeys.keyEnc(provider)
        val encrypted = readPrefs()?.get(slot)
        if (encrypted.isNullOrBlank()) return null
        val plain = withContext(Dispatchers.IO) { TokenCipher.decrypt(encrypted) }
        if (plain == null) forgetUndecryptable(slot, encrypted)
        return plain?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Address of the "Eigener Server" (already normalized by the caller, e.g. with the custom
     * server's `/v1` rule); null/blank clears it.
     * @throws IllegalArgumentException when [url] is not an http(s) address.
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun setCustomBaseUrl(url: String?) {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) {
            store.edit { it.remove(AiPrefKeys.CUSTOM_BASE_URL) }
            return
        }
        val clean = requireNotNull(normalizeBaseUrl(raw)) { "Keine gültige Server-Adresse" }
        store.edit { it[AiPrefKeys.CUSTOM_BASE_URL] = clean }
    }

    /**
     * `anthropic-workspace-id` for an Anthropic key that is not bound to one workspace; trimmed,
     * null/blank clears it.
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun setAnthropicWorkspaceId(id: String?) {
        val clean = id?.trim().orEmpty()
        store.edit {
            if (clean.isEmpty()) it.remove(AiPrefKeys.ANTHROPIC_WORKSPACE_ID) else it[AiPrefKeys.ANTHROPIC_WORKSPACE_ID] = clean
        }
    }

    /**
     * The "Tarif" of the Gemini key ([GeminiTier.FREE] when never set). Another tariff drops the
     * model fallback: billing may open the chosen model.
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun setGeminiTier(tier: GeminiTier) {
        store.edit {
            val before = GeminiTier.fromKey(it[AiPrefKeys.GEMINI_TIER]) ?: GeminiTier.FREE
            if (before != tier) it.writeModelFallback(AiProviderId.GEMINI, null)
            it[AiPrefKeys.GEMINI_TIER] = tier.key
        }
    }

    /**
     * The "Tarif" of the OpenRouter key ([OpenRouterTier.FREE] when never set): the free models
     * without web search, or every model on the account's credits.
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun setOpenRouterTier(tier: OpenRouterTier) {
        store.edit { it[AiPrefKeys.OPENROUTER_TIER] = tier.key }
    }

    /**
     * Debug builds only ("Entwickler: Basis-URL"): points [provider] at [url] (e.g. a mock server
     * at `http://10.0.2.2:8080/v1`) instead of its real API; null/blank clears it. Release builds
     * neither store nor read it.
     * @return false when nothing was stored: a release build, or [url] is no http(s) address.
     * @throws IOException if DataStore cannot persist the change.
     */
    suspend fun setDebugBaseUrl(provider: AiProviderId, url: String?): Boolean {
        if (!BuildConfig.DEBUG) return false
        val raw = url?.trim().orEmpty()
        val slot = AiPrefKeys.debugBaseUrl(provider)
        if (raw.isEmpty()) {
            store.edit { it.remove(slot) }
            return true
        }
        val clean = normalizeBaseUrl(raw) ?: return false
        store.edit { it[slot] = clean }
        return true
    }

    private suspend fun readPrefs(): Preferences? = try {
        store.data.first()
    } catch (_: IOException) {
        null
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
            ai = readAiSettings(this, debugOverrides = BuildConfig.DEBUG),
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
        writeAiSettings(s.ai)
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
        val AI_MONTHLY_LIMIT = doublePreferencesKey("ai_monthly_limit_usd")
        val LIVE_AUTO_REFRESH = booleanPreferencesKey("live_auto_refresh")
        val TOKEN = stringPreferencesKey("gh_token_enc")
    }
}

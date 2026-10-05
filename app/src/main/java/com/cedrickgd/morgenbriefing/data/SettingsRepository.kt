package com.cedrickgd.morgenbriefing.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
     * If the keystore entry is definitely gone (not just a hiccup), the stored ciphertext can
     * never be read again: drop it so the settings screen asks for the token again.
     */
    private suspend fun forgetUndecryptableToken(encrypted: String) {
        val keyExists = withContext(Dispatchers.IO) { TokenCipher.hasKey() }
        if (keyExists != false) return
        try {
            store.edit { prefs -> if (prefs[Keys.TOKEN] == encrypted) prefs.remove(Keys.TOKEN) }
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
        )
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
        val TOKEN = stringPreferencesKey("gh_token_enc")
    }
}

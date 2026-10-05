package com.cedrickgd.morgenbriefing.data

import android.content.Context
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * Preferences DataStore named "settings". The GitHub token is stored encrypted via
 * [com.cedrickgd.morgenbriefing.data.local.TokenCipher]; [AppSettings.hasToken] reflects it.
 * Places are stored as JSON strings.
 */
class SettingsRepository(context: Context) {
    val settings: Flow<AppSettings> = TODO("agent A")

    suspend fun current(): AppSettings = TODO("agent A")

    suspend fun update(transform: (AppSettings) -> AppSettings): Unit = TODO("agent A")

    /** Trims whitespace; null/blank clears the token. */
    suspend fun setToken(token: String?): Unit = TODO("agent A")

    /** Decrypted token or null. */
    suspend fun token(): String? = TODO("agent A")
}

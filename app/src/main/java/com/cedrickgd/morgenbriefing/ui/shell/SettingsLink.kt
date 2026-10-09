package com.cedrickgd.morgenbriefing.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** A place in the settings that another screen can send the user to. */
enum class SettingsTarget {
    /** "News-Quelle", with the GitHub token dialog open. */
    NEWS_TOKEN,

    /** The "KI" section (model, monthly limit, usage). */
    AI,

    /** The "KI" section with the provider picker open ("Anbieter wählen", e.g. Claude below Android 8). */
    AI_PROVIDER,

    /** The "KI" section with the picker of the Live provider open ("Live-Anbieter wählen"). */
    AI_LIVE_PROVIDER,
}

/**
 * Deep links into the settings page: a screen asks for a [SettingsTarget] right before it
 * switches to "Einstellungen" (its `onOpenSettings`), and the settings page scrolls there — and
 * opens the matching dialog — as soon as it is on screen. Provided by the shell via
 * [LocalSettingsLink]; the default instance keeps screens working in isolation (previews).
 */
@Stable
class SettingsLink {
    /** The target still to be shown, if any. */
    var pending by mutableStateOf<SettingsTarget?>(null)
        private set

    fun request(target: SettingsTarget) {
        pending = target
    }

    /** Called by the settings page once it acted on [target]. */
    fun consume(target: SettingsTarget) {
        if (pending == target) pending = null
    }
}

val LocalSettingsLink = staticCompositionLocalOf { SettingsLink() }

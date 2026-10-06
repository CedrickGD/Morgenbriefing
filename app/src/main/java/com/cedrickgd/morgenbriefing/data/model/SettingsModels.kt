package com.cedrickgd.morgenbriefing.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    /** e.g. "Sachsen, Deutschland" — only for display in the picker. */
    val region: String = "",
) {
    companion object {
        val BAD_DUEBEN = Place("Bad Düben", 51.5928, 12.5853, "Sachsen, Deutschland")
        val LEIPZIG = Place("Leipzig", 51.3397, 12.3731, "Sachsen, Deutschland")
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Accent palettes. [DYNAMIC] = Material You wallpaper colors (Android 12+); on older
 * devices it falls back to [MORGENROT]. Seeds are ARGB.
 */
enum class AccentId(val label: String, val seed: Long) {
    DYNAMIC("Material You", 0xFFC6613F),
    MORGENROT("Morgenrot", 0xFFC6613F),
    SONNE("Sonne", 0xFFE0A100),
    OZEAN("Ozean", 0xFF0E7C9B),
    WALD("Wald", 0xFF2E8B57),
    LAVENDEL("Lavendel", 0xFF7B61FF),
    BEERE("Beere", 0xFFC2185B),
    GRAPHIT("Graphit", 0xFF5F6B7A),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentId = AccentId.DYNAMIC,
    val notificationsEnabled: Boolean = true,
    val notifyHour: Int = 5,
    val notifyMinute: Int = 30,
    val home: Place = Place.BAD_DUEBEN,
    val day: Place = Place.LEIPZIG,
    val dayStartHour: Int = 10,
    val dayEndHour: Int = 18,
    /** A GitHub token is stored (the value itself never leaves SettingsRepository). */
    val hasToken: Boolean = false,
    val onboardingDone: Boolean = false,
    /** `YYYY-MM-DD` of the last morning notification, to avoid duplicates. */
    val lastNotifiedDate: String? = null,
    /** Topic ids in the user's preferred order (see [Topics]). */
    val topicOrder: List<String> = Topics.DEFAULT_ORDER,
    /** Topic ids the user switched off. */
    val hiddenTopics: Set<String> = emptySet(),
    /** Article reader text size factor, 0.85–1.4. */
    val readerTextScale: Float = 1f,
    /** A Claude API key is stored (encrypted; the value never leaves SettingsRepository). */
    val hasAiKey: Boolean = false,
    val aiModel: AiModel = AiModel.SONNET,
    /** Monthly AI spending cap in US dollars; 0 = no cap. */
    val aiMonthlyLimitUsd: Double = 5.0,
    /** Run a live news update automatically when "⚡ Live" is opened and the last one is > 30 min old. */
    val liveAutoRefresh: Boolean = false,
)

/** Claude models the user can pick for chat and live news (docs/AI.md). */
enum class AiModel(val apiId: String, val label: String) {
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5"),
    OPUS("claude-opus-5-5", "Claude Opus 5.5"),
}

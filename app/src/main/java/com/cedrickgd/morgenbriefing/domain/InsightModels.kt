package com.cedrickgd.morgenbriefing.domain

data class ClothingItem(val emoji: String, val label: String)

data class ClothingAdvice(
    /** 2–5 pictogram items, e.g. 🧥 Jacke, 👕 T-Shirt, ☂️ Schirm, 🕶️ Sonnenbrille. */
    val items: List<ClothingItem>,
    /** 1–3 friendly sentences for the card. */
    val text: String,
    /** ≤ 8 words for the notification, e.g. "Morgens Jacke, ab Mittag T-Shirt, kein Schirm." */
    val short: String,
)

enum class WarningLevel { INFO, CAUTION, SEVERE }

data class WeatherWarning(
    val level: WarningLevel,
    val emoji: String,
    val title: String,
    val text: String,
)

data class NotificationText(
    val title: String,
    /** Collapsed single line. */
    val text: String,
    /** Expanded BigTextStyle body (weather, clothing, top headlines). */
    val bigText: String,
)

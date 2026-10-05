package com.cedrickgd.morgenbriefing.ai

/**
 * German, user-facing wording for AI states. Shared by the chat, the "⚡ Live" feed and the
 * settings so every place explains the same situation the same way (informal "du").
 */
object AiTexts {
    /** Short headline for a [block] card. */
    fun blockTitle(block: AiBlock): String = when (block) {
        AiBlock.UNSUPPORTED_DEVICE -> "Braucht Android 8 oder neuer"
        AiBlock.NO_KEY -> "Noch kein Claude-Key hinterlegt"
        AiBlock.LIMIT_REACHED -> "Monatslimit erreicht"
    }

    /** One or two friendly sentences under [blockTitle]. */
    fun blockBody(block: AiBlock): String = when (block) {
        AiBlock.UNSUPPORTED_DEVICE ->
            "Die KI-Funktionen laufen erst ab Android 8.0. Alles andere in der App funktioniert ganz normal."
        AiBlock.NO_KEY ->
            "Trag in den Einstellungen deinen API-Key von console.anthropic.com ein – dann kannst du hier " +
                "nachfragen und brandaktuelle Meldungen abrufen."
        AiBlock.LIMIT_REACHED ->
            "Dein KI-Budget für diesen Monat ist aufgebraucht. Du kannst das Limit in den Einstellungen anheben – " +
                "oder am Monatsersten geht es von selbst weiter."
    }

    /** What went wrong, in one sentence. */
    fun error(error: AiError): String = when (error) {
        AiError.InvalidKey -> "Dein Claude-Key wurde abgelehnt. Prüf ihn bitte in den Einstellungen."
        AiError.RateLimited -> "Gerade kommen zu viele Anfragen auf einmal an. Versuch es in einer Minute noch mal."
        AiError.Overloaded -> "Claude ist im Moment überlastet. Versuch es gleich noch einmal."
        AiError.Network -> "Keine Verbindung zu Claude. Prüf dein Internet und versuch es noch mal."
        is AiError.Refusal -> refusal(error.category)
        AiError.Truncated -> "Die Antwort war zu lang und wurde gekürzt."
        is AiError.Unknown -> error.message?.takeIf { it.isNotBlank() }
            ?: "Da ist etwas schiefgelaufen. Versuch es bitte noch einmal."
    }

    /** True when trying the same request again can help. */
    fun isRetryable(error: AiError): Boolean = when (error) {
        AiError.RateLimited, AiError.Overloaded, AiError.Network, is AiError.Unknown -> true
        AiError.InvalidKey, is AiError.Refusal, AiError.Truncated -> false
    }

    /** Friendly explanation of a refusal; [category] comes from `stop_details`. */
    fun refusal(category: String?): String = when (category?.lowercase()) {
        "cyber" -> "Dabei kann ich nicht helfen – das berührt IT-Sicherheitsthemen, die ich hier nicht ausführe."
        "bio" -> "Dabei kann ich nicht helfen – zu biologischen Gefahrenthemen halte ich mich zurück."
        "reasoning_extraction" -> "Meine internen Denkschritte kann ich nicht ausgeben – frag mich gern direkt nach dem Ergebnis."
        "frontier_llm" -> "Dabei kann ich nicht helfen."
        else -> "Dazu möchte ich lieber nichts sagen. Formulier die Frage gern anders."
    }
}

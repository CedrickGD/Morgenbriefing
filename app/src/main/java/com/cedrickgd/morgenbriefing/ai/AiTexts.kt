package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.provider.GeminiLinks
import com.cedrickgd.morgenbriefing.ai.provider.GeminiTiers
import com.cedrickgd.morgenbriefing.ai.provider.LiveSupport
import com.cedrickgd.morgenbriefing.ai.provider.OpenRouterTiers
import com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec
import com.cedrickgd.morgenbriefing.data.model.AiProviderId

/**
 * German, user-facing wording for AI states (docs/AI-PROVIDERS.md §8, §10.6). Shared by the
 * chat, the "⚡ Live" feed and the settings so every place explains the same situation the same
 * way (informal "du"); the provider's [ProviderSpec.brand] names who is meant.
 */
object AiTexts {
    /** Button that leads from a Live block to the choice of a Live provider. */
    const val LIVE_PROVIDER_ACTION = "Live-Anbieter wählen"

    /** Button that leads from [AiBlock.UNSUPPORTED_DEVICE] to the provider choice. */
    const val PROVIDER_ACTION = "Anbieter wählen"

    /**
     * Button under a Gemini key Google refuses ([GeminiProblem.AUTH_KEY_REJECTED]): the OpenRouter
     * key dialog on the free tariff, which makes OpenRouter the chat provider once the key works.
     */
    const val OPENROUTER_PLAN_B = "OpenRouter kostenlos einrichten"

    /** Under an answer that came without web search although it was asked for (Gemini fallback). */
    const val WITHOUT_WEB_SEARCH_NOTE = GeminiTiers.FALLBACK_NOTE

    /** Short headline for a [block] card. */
    fun blockTitle(block: AiBlock, spec: ProviderSpec): String = when (block) {
        AiBlock.UNSUPPORTED_DEVICE -> "${spec.brand.sentence()} braucht Android 8"
        AiBlock.NO_KEY -> if (spec.id == AiProviderId.CUSTOM) "Noch kein Server eingerichtet" else "Noch kein ${spec.brand}-Key hinterlegt"
        AiBlock.LIMIT_REACHED -> "Monatslimit erreicht"
        AiBlock.LIVE_UNAVAILABLE -> if (spec.id == AiProviderId.CUSTOM) "Live mit deinem Server nicht verfügbar" else "Live mit ${spec.brand} nicht verfügbar"
    }

    /** The one button of a [block] card in the chat and in "⚡ Live": what fixes it. */
    fun blockAction(block: AiBlock, spec: ProviderSpec): String = when (block) {
        AiBlock.NO_KEY -> if (spec.id == AiProviderId.CUSTOM) "Server einrichten" else "Key eintragen"
        AiBlock.LIMIT_REACHED -> "Limit anpassen"
        AiBlock.UNSUPPORTED_DEVICE -> PROVIDER_ACTION
        AiBlock.LIVE_UNAVAILABLE -> LIVE_PROVIDER_ACTION
    }

    /** One or two friendly sentences under [blockTitle]. */
    fun blockBody(block: AiBlock, spec: ProviderSpec): String = when (block) {
        AiBlock.UNSUPPORTED_DEVICE ->
            "Wähl in den Einstellungen einen anderen KI-Anbieter, zum Beispiel OpenAI oder Gemini – die laufen auch hier."
        AiBlock.NO_KEY -> when {
            spec.id == AiProviderId.CUSTOM -> "Trag einmal die Adresse deines Servers ein – dann kannst du hier nachfragen."
            spec.live == LiveSupport.YES ->
                "Trag einmal deinen Key ein – dann kannst du hier nachfragen und brandaktuelle Meldungen abrufen."
            else -> "Trag einmal deinen Key ein – dann kannst du hier nachfragen."
        }
        AiBlock.LIMIT_REACHED ->
            "Dein KI-Budget für diesen Monat ist aufgebraucht. Du kannst das Limit in den Einstellungen anheben – " +
                "oder am Monatsersten geht es von selbst weiter."
        // A provider that has Live in general lacks it only on its free tariff (OpenRouter).
        AiBlock.LIVE_UNAVAILABLE -> spec.liveUnavailableText
            ?: if (spec.id == AiProviderId.OPENROUTER) OpenRouterTiers.LIVE_UNAVAILABLE else "Live ist mit ${spec.dativeName} gerade nicht verfügbar."
    }

    /** What went wrong, in one sentence. */
    fun error(error: AiError, spec: ProviderSpec): String {
        val brand = spec.brand
        return when (error) {
            AiError.InvalidKey -> spec.invalidKeyText
                ?: "${brand.sentence()} hat deinen API-Key abgelehnt. Prüf ihn bitte oder trag einen neuen ein."
            AiError.NoCredit -> if (spec.id == AiProviderId.OPENROUTER) {
                OpenRouterTiers.NO_CREDIT
            } else {
                "Dein Guthaben bei $brand ist aufgebraucht. Lade es in der Konsole auf, dann geht es weiter."
            }
            AiError.RateLimited -> "Gerade kommen zu viele Anfragen auf einmal an. Versuch es in einer Minute noch mal."
            AiError.QuotaExhausted -> if (spec.id == AiProviderId.OPENROUTER) {
                OpenRouterTiers.QUOTA_EXHAUSTED
            } else {
                "$brand-Gratiskontingent für den Moment aufgebraucht – in ein paar Minuten bzw. morgen geht's weiter."
            }
            AiError.Overloaded -> "${brand.sentence()} ist im Moment überlastet. Versuch es gleich noch einmal."
            AiError.Network -> if (spec.id == AiProviderId.CUSTOM) {
                "Keine Verbindung zu deinem Server. Prüf dein Netz und versuch es noch mal."
            } else {
                "Keine Verbindung zu $brand. Prüf dein Internet und versuch es noch mal."
            }
            AiError.ServerUnreachable -> "Dein Server antwortet nicht. Läuft er, und ist dein Handy im selben Netz?"
            AiError.WorkspaceRequired ->
                "Dein Claude-Key gehört zu keinem Workspace. Trag in den Einstellungen unter „Erweitert“ die Workspace-ID ein – " +
                    "oder erstell einen Key für den „Default Workspace“."
            AiError.WorkspaceNotFound -> "Diesen Workspace findet Anthropic nicht. Prüf die Workspace-ID in den Einstellungen."
            is AiError.ModelUnavailable -> {
                val model = error.model?.trim()?.takeIf { it.isNotEmpty() }?.let { "Das Modell „$it“" } ?: "Das gewählte Modell"
                if (spec.id == AiProviderId.CUSTOM) {
                    "$model läuft nicht auf deinem Server. Wähl in den Einstellungen eines, das er anbietet."
                } else {
                    "$model gibt es bei $brand nicht (mehr), oder dein Key darf es nicht nutzen. Wähl in den Einstellungen ein anderes."
                }
            }
            is AiError.Refusal -> refusal(error.category)
            AiError.Truncated -> "Die Antwort war zu lang und wurde gekürzt."
            is AiError.Unknown -> error.message?.takeIf { it.isNotBlank() }
                ?: "Da ist etwas schiefgelaufen. Versuch es bitte noch einmal."
            is AiError.GeminiAccess -> gemini(error.problem)
        }
    }

    /** Why Google refused a Gemini request, and what fixes it (docs/providers/gemini.md §12.4). */
    fun gemini(problem: GeminiProblem): String = when (problem) {
        GeminiProblem.AUTH_KEY_REJECTED ->
            "Google nimmt deinen Key gerade nicht an – ein bekannter Fehler bei Google, der viele neue Keys („AQ.…“) trifft, " +
                "auch wenn AI Studio sie als gültig zeigt. Was hilft: ein älterer Key, der mit „AIza“ beginnt – " +
                "oder OpenRouter kostenlos mit Gemma 4, Googles offenem Modell."
        GeminiProblem.KEY_LEAKED ->
            "Google hat deinen Key gesperrt, weil er öffentlich aufgetaucht ist. Erstell in AI Studio einen neuen Key – und gib ihn nicht weiter."
        GeminiProblem.API_BLOCKED ->
            "Die Gemini API ist für dein Google-Projekt gesperrt oder nicht aktiviert. Aktivier sie in der Google Cloud Console – " +
                "oder erstell in AI Studio einen Key in einem anderen Projekt."
        GeminiProblem.KEY_RESTRICTED ->
            "Dein Key ist auf bestimmte Apps, Websites oder IP-Adressen beschränkt. Heb die Beschränkung in der Google Cloud Console auf – " +
                "oder erstell in AI Studio einen neuen Key."
        GeminiProblem.FREE_TIER_UNAVAILABLE ->
            "Der kostenlose Gemini-Tarif ist für dein Land/Projekt nicht freigeschaltet – aktivier die Abrechnung in AI Studio " +
                "oder wähl einen anderen Anbieter."
    }

    /**
     * The page that fixes [error], as (button label, URL) — AI Studio for a new key or billing,
     * the Cloud Console for a blocked API or a restricted key; null when there is none.
     */
    fun errorLink(error: AiError): Pair<String, String>? = when ((error as? AiError.GeminiAccess)?.problem) {
        // An older "AIza…" key is listed there too.
        GeminiProblem.AUTH_KEY_REJECTED -> "Keys in AI Studio" to GeminiLinks.API_KEYS
        GeminiProblem.KEY_LEAKED -> "Neuen Key in AI Studio" to GeminiLinks.API_KEYS
        GeminiProblem.API_BLOCKED -> "Gemini API aktivieren" to GeminiLinks.API_LIBRARY
        GeminiProblem.KEY_RESTRICTED -> "Key-Beschränkung prüfen" to GeminiLinks.CREDENTIALS
        GeminiProblem.FREE_TIER_UNAVAILABLE -> "Abrechnung in AI Studio" to GeminiLinks.PROJECTS
        null -> null
    }

    /**
     * The way out of [error] that needs no Google key: OpenRouter's free tariff with Gemma 4
     * ([OPENROUTER_PLAN_B]) — offered while Google refuses new auth keys.
     */
    fun offersOpenRouter(error: AiError): Boolean = error == AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED)

    /** True when trying the same request again (by hand) can help. */
    fun isRetryable(error: AiError): Boolean = when (error) {
        AiError.RateLimited, AiError.Overloaded, AiError.Network, AiError.ServerUnreachable,
        AiError.QuotaExhausted, is AiError.Unknown -> true
        AiError.InvalidKey, AiError.NoCredit, AiError.WorkspaceRequired, AiError.WorkspaceNotFound,
        is AiError.ModelUnavailable, is AiError.Refusal, AiError.Truncated, is AiError.GeminiAccess -> false
    }

    /** The fix for [error] is in the key dialog (a new key, a workspace id). */
    fun needsKeyChange(error: AiError): Boolean =
        error.rejectsKey || error == AiError.WorkspaceRequired || error == AiError.WorkspaceNotFound

    /** Friendly explanation of a refusal; [category] comes from `stop_details` or a block reason. */
    fun refusal(category: String?): String = when (category?.lowercase()) {
        "cyber" -> "Dabei kann ich nicht helfen – das berührt IT-Sicherheitsthemen, die ich hier nicht ausführe."
        "bio" -> "Dabei kann ich nicht helfen – zu biologischen Gefahrenthemen halte ich mich zurück."
        "reasoning_extraction" -> "Meine internen Denkschritte kann ich nicht ausgeben – frag mich gern direkt nach dem Ergebnis."
        "frontier_llm" -> "Dabei kann ich nicht helfen."
        else -> "Dazu möchte ich lieber nichts sagen. Formulier die Frage gern anders."
    }

    /**
     * The calm note of a chat that cannot search the web: a key on a free tariff ([freeTier]:
     * Gemini, OpenRouter), or a provider without web search. Null when the chat searches.
     */
    fun chatNote(spec: ProviderSpec, webSearch: Boolean, freeTier: Boolean): String? = when {
        webSearch -> null
        freeTier && spec.id == AiProviderId.OPENROUTER -> OpenRouterTiers.NO_WEB_SEARCH_NOTE
        freeTier -> GeminiTiers.NO_WEB_SEARCH_NOTE
        else -> "Ohne Websuche – mit ${spec.dativeName} nicht verfügbar"
    }

    /** "dein Server" at the start of a sentence. */
    private fun String.sentence(): String = replaceFirstChar { it.uppercaseChar() }
}

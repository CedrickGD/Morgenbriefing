package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.ai.AiKeyCheck
import com.cedrickgd.morgenbriefing.ai.AiPricing
import com.cedrickgd.morgenbriefing.ai.provider.EngineKind
import com.cedrickgd.morgenbriefing.ai.provider.GeminiTiers
import com.cedrickgd.morgenbriefing.ai.provider.LiveSupport
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Pure logic of Settings → "KI" (docs/AI-PROVIDERS.md §10): the wording of the key dialog per
 * provider, the provider and model pickers, the separate Live provider, the monthly limit slider
 * and the usage line. No Android types, unit-tested.
 */

/** Why "Speichern & testen" did not store the key. */
enum class AiKeyProblem {
    INVALID,
    NETWORK,
    UNSUPPORTED,
    SAVE_FAILED,
    UNKNOWN,
    WORKSPACE_REQUIRED,
    WORKSPACE_INVALID,
    SERVER_UNREACHABLE,
    SERVER_CERTIFICATE,
    CLEARTEXT_BLOCKED,
    NOT_AN_AI_SERVER,
}

/** State of the API key dialog. */
@Immutable
sealed interface AiKeyState {
    data object Idle : AiKeyState
    data object Checking : AiKeyState

    /** Tested OK and stored. */
    data object Saved : AiKeyState

    /** Stored without a passed test ("Ohne Test speichern"): nobody knows yet whether it works. */
    data object SavedUntested : AiKeyState

    /** Tested and stored, but the account has no credit left: requests fail until it is topped up. */
    data object SavedNoCredit : AiKeyState

    /** The own server answered the connection test with [models] model ids; address (and key) stored. */
    data class ServerConnected(val models: Int) : AiKeyState
    data class Failed(val problem: AiKeyProblem) : AiKeyState
}

/** The dialog's work is done: it offers "Fertig" instead of another test. */
internal val AiKeyState.isSaved: Boolean
    get() = this == AiKeyState.Saved || this == AiKeyState.SavedUntested ||
        this == AiKeyState.SavedNoCredit || this is AiKeyState.ServerConnected

/**
 * What the dialog says once the key is stored: [tested] only after a passed "Speichern & testen";
 * [noCredit] when the test found the key fine but the balance empty.
 */
internal fun savedKeyState(tested: Boolean, noCredit: Boolean = false): AiKeyState = when {
    !tested -> AiKeyState.SavedUntested
    noCredit -> AiKeyState.SavedNoCredit
    else -> AiKeyState.Saved
}

/** Maps the facade's check result; null = the key works (store it, also without credit). */
internal fun AiKeyCheck.toProblem(): AiKeyProblem? = when (this) {
    AiKeyCheck.OK, AiKeyCheck.OK_NO_CREDIT -> null
    AiKeyCheck.INVALID -> AiKeyProblem.INVALID
    AiKeyCheck.NETWORK -> AiKeyProblem.NETWORK
    AiKeyCheck.UNSUPPORTED -> AiKeyProblem.UNSUPPORTED
    AiKeyCheck.WORKSPACE_REQUIRED -> AiKeyProblem.WORKSPACE_REQUIRED
    AiKeyCheck.WORKSPACE_INVALID -> AiKeyProblem.WORKSPACE_INVALID
    AiKeyCheck.SERVER_UNREACHABLE -> AiKeyProblem.SERVER_UNREACHABLE
    AiKeyCheck.SERVER_CERTIFICATE -> AiKeyProblem.SERVER_CERTIFICATE
    AiKeyCheck.CLEARTEXT_BLOCKED -> AiKeyProblem.CLEARTEXT_BLOCKED
    AiKeyCheck.NOT_AN_AI_SERVER -> AiKeyProblem.NOT_AN_AI_SERVER
}

/**
 * The company that issues, checks and bills the key of [spec], inside sentences ("Prüfe den Key
 * bei Google …"). The brand would be wrong here: a Gemini key is a Google key, a Grok key an xAI key.
 */
internal fun keyOwner(spec: ProviderSpec): String = when (spec.id) {
    AiProviderId.ANTHROPIC -> "Anthropic"
    AiProviderId.OPENAI -> "OpenAI"
    AiProviderId.GEMINI -> "Google"
    AiProviderId.XAI -> "xAI"
    AiProviderId.PERPLEXITY -> "Perplexity"
    AiProviderId.OPENROUTER -> "OpenRouter"
    AiProviderId.MISTRAL -> "Mistral"
    AiProviderId.DEEPSEEK -> "DeepSeek"
    AiProviderId.CUSTOM -> "deinem Server"
}

/** The word in front of "-API-Key": "Claude-API-Key", "Gemini-API-Key", "xAI-API-Key". */
internal fun keyName(spec: ProviderSpec): String = when (spec.id) {
    AiProviderId.ANTHROPIC -> "Claude"
    AiProviderId.GEMINI -> "Gemini"
    AiProviderId.CUSTOM -> "Server"
    else -> keyOwner(spec)
}

/** Title of the key dialog of [spec]. */
internal fun aiKeyDialogTitle(spec: ProviderSpec, hasKey: Boolean): String = when {
    spec.id == AiProviderId.CUSTOM -> if (hasKey) "Eigenen Server ändern" else "Eigenen Server einrichten"
    hasKey -> "${keyName(spec)}-API-Key ändern"
    else -> "${keyName(spec)}-API-Key eintragen"
}

/** The first line of the key dialog: whose account the requests run on. */
internal fun aiKeyIntro(spec: ProviderSpec): String = when (spec.id) {
    AiProviderId.ANTHROPIC ->
        "Der Key gehört dir: Anfragen laufen über dein eigenes Anthropic-Konto, unabhängig von einem Claude-Abo."
    AiProviderId.GEMINI ->
        "Der Key gehört dir: Anfragen laufen über dein eigenes Google-Konto – kostenlos oder mit Abrechnung, wie du unten wählst."
    AiProviderId.CUSTOM ->
        "Dein eigener Server mit Ollama, LM Studio & Co.: Fragen und Antworten bleiben in deinem Netz und kosten nichts."
    else -> "Der Key gehört dir: Anfragen laufen über dein eigenes ${keyOwner(spec)}-Konto und werden dort abgerechnet."
}

/** German feedback line under the key field of [spec]'s dialog; null while idle. */
internal fun aiKeyMessage(state: AiKeyState, spec: ProviderSpec): String? {
    val custom = spec.id == AiProviderId.CUSTOM
    val owner = keyOwner(spec)
    return when (state) {
        AiKeyState.Idle -> null
        AiKeyState.Checking -> if (custom) "Verbinde mit deinem Server …" else "Prüfe den Key bei $owner …"
        AiKeyState.Saved -> "✓ Der Key funktioniert und ist gespeichert."
        AiKeyState.SavedUntested -> "Gespeichert – noch nicht getestet. Die erste Anfrage zeigt, ob er funktioniert."
        AiKeyState.SavedNoCredit -> if (spec.id == AiProviderId.GEMINI) {
            "Gespeichert ✓ – aber Google verlangt für diesen Key eine aktive Abrechnung (oder das Guthaben ist leer)."
        } else {
            "Gespeichert ✓ – aber dein Guthaben ist leer. Lade es auf, sonst schlagen Anfragen fehl."
        }
        is AiKeyState.ServerConnected -> when (state.models) {
            0 -> "✓ Verbunden – der Server meldet noch keine Modelle."
            1 -> "✓ Verbunden – 1 Modell gefunden."
            else -> "✓ Verbunden – ${state.models} Modelle gefunden."
        }
        is AiKeyState.Failed -> when (state.problem) {
            AiKeyProblem.INVALID -> spec.invalidKeyText?.let { "✗ $it" }
                ?: if (custom) "✗ Dein Server lehnt den Key ab – stimmt er?" else "✗ $owner lehnt den Key ab – hast du ihn vollständig kopiert?"
            AiKeyProblem.NETWORK ->
                if (custom) "✗ Keine Verbindung zu deinem Server – ist dein Handy im selben Netz?" else "✗ Keine Verbindung zu $owner – bist du online?"
            AiKeyProblem.UNSUPPORTED -> "✗ ${spec.brand} läuft erst ab Android 8."
            AiKeyProblem.SAVE_FAILED -> "✗ Der Key konnte nicht gespeichert werden. Versuch's nochmal."
            AiKeyProblem.UNKNOWN -> "✗ Das hat gerade nicht geklappt – versuch's gleich nochmal."
            AiKeyProblem.WORKSPACE_REQUIRED ->
                "✗ Dieser Key gehört zu keinem Workspace. Trag unten die Workspace-ID ein – oder erstell den Key mit Workspace „Default Workspace“."
            AiKeyProblem.WORKSPACE_INVALID -> "✗ Diese Workspace-ID kennt Anthropic nicht (oder dein Key darf ihn nicht nutzen)."
            AiKeyProblem.SERVER_UNREACHABLE -> "✗ Dein Server antwortet nicht. Stimmt die Adresse, und läuft er im selben Netz?"
            AiKeyProblem.SERVER_CERTIFICATE ->
                "✗ Dein Server antwortet, aber nicht mit einem HTTPS-Zertifikat, dem die App traut (selbst signiert – oder kann er nur http?). " +
                    "Nimm http:// mit einem Namen wie nas.fritz.box oder ein Let's-Encrypt-Zertifikat."
            AiKeyProblem.CLEARTEXT_BLOCKED ->
                "✗ http:// geht nur mit Namen wie nas.fritz.box oder nas.local – nicht mit einer IP-Adresse. Nimm den Namen oder https://."
            AiKeyProblem.NOT_AN_AI_SERVER -> "✗ Unter dieser Adresse läuft kein OpenAI-kompatibler Server. Endet sie auf /v1?"
        }
    }
}

/** "Ohne Test speichern" helps when the test could not reach the provider (offline, server asleep). */
internal fun canSaveUntested(state: AiKeyState): Boolean =
    state == AiKeyState.Failed(AiKeyProblem.NETWORK) || state == AiKeyState.Failed(AiKeyProblem.SERVER_UNREACHABLE)

/**
 * Second line of the key row in Settings → KI. [rejected]: the provider refused the last chat or
 * Live request with the stored key (wins over [untested]).
 */
internal fun aiKeyRowLabel(spec: ProviderSpec, hasKey: Boolean, untested: Boolean, rejected: Boolean = false): String = when {
    !hasKey -> "Noch keiner eingetragen"
    rejected -> "Gespeichert · von ${keyOwner(spec)} abgelehnt"
    untested -> "Gespeichert · noch nicht getestet"
    else -> "Gespeichert ✓ · verschlüsselt"
}

/** The own server's address as the settings row shows it: "nas.fritz.box:11434" (path kept unless it is `/v1`). */
internal fun serverLabel(baseUrl: String?): String {
    val url = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return "Noch keiner eingerichtet"
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return url
    }
    val host = uri.host ?: return url
    val port = if (uri.port > 0) ":${uri.port}" else ""
    val path = uri.rawPath.orEmpty().trimEnd('/').takeUnless { it.isEmpty() || it == "/v1" }.orEmpty()
    return host + port + path
}

/** Why a pasted key does not look like a key of the provider – most likely a copy mistake. */
enum class AiKeyFormatIssue { WRONG_PREFIX, TOO_SHORT, WHITESPACE, ODD_CHARACTER }

/**
 * What is wrong with [raw] as a key of [spec], or null when it looks right. Spaces and line
 * breaks around it do not count (they are dropped when saving); inside it they always do. Keys
 * are plain ASCII: an invisible character a copy brought along (a zero-width space, a BOM) or
 * an umlaut is [AiKeyFormatIssue.ODD_CHARACTER] — such a key cannot be sent at all. The prefix
 * and length are only checked where the provider documents a key format
 * ([ProviderSpec.keyPrefixes]); Gemini keys come as `AIza…` and `AQ.…` and are never judged by
 * their shape. A key that only starts right once its inner spaces or odd characters are gone is
 * a [AiKeyFormatIssue.WHITESPACE] / [AiKeyFormatIssue.ODD_CHARACTER] problem, not a wrong prefix.
 */
internal fun aiKeyFormatIssue(raw: String, spec: ProviderSpec): AiKeyFormatIssue? {
    val key = raw.trim()
    val prefixes = spec.keyPrefixes
    val compact = key.filter { it in '!'..'~' }
    return when {
        prefixes.isNotEmpty() && prefixes.none { compact.startsWith(it) } -> AiKeyFormatIssue.WRONG_PREFIX
        key.any { it.isWhitespace() } -> AiKeyFormatIssue.WHITESPACE
        key.any { it !in '!'..'~' } -> AiKeyFormatIssue.ODD_CHARACTER
        prefixes.isNotEmpty() && key.length < MIN_KEY_LENGTH -> AiKeyFormatIssue.TOO_SHORT
        else -> null
    }
}

/** The hint under the key field for [issue]. */
internal fun aiKeyFormatHint(issue: AiKeyFormatIssue, spec: ProviderSpec): String = when (issue) {
    AiKeyFormatIssue.WRONG_PREFIX ->
        "${keyName(spec)}-Keys beginnen mit ${spec.keyPrefixes.joinToString(" oder ") { "„$it“" }} – stimmt die Kopie?"
    AiKeyFormatIssue.TOO_SHORT -> "Der Key wirkt zu kurz – hast du ihn vollständig kopiert?"
    AiKeyFormatIssue.WHITESPACE -> "Der Key enthält Leerzeichen – kopier ihn ohne Zeilenumbruch."
    AiKeyFormatIssue.ODD_CHARACTER -> "Der Key enthält ein unsichtbares oder ungewöhnliches Zeichen – kopier ihn bitte noch einmal."
}

private const val MIN_KEY_LENGTH = 20

/** Soft hint for an Anthropic workspace id that does not look like one (`wrkspc_…`); null when fine or empty. */
internal fun workspaceIdHint(raw: String): String? {
    val id = raw.trim()
    if (id.isEmpty() || id.startsWith("wrkspc_")) return null
    return "Workspace-IDs beginnen mit „wrkspc_“ – stimmt die Kopie?"
}

// -------------------------------------------------------------------------------------------
// Provider picker and capabilities
// -------------------------------------------------------------------------------------------

/**
 * One capability pill of a provider card ("Chat ✓", "Live ✗", "ab Android 8"). [device]: the pill
 * about the Android version, which shows a phone instead of ✗ when the provider needs Android 8.
 */
@Immutable
internal data class Capability(val label: String, val available: Boolean, val spoken: String, val device: Boolean = false)

/**
 * What [spec] can do, as the provider cards show it: Chat, Websuche, Live, Android 7. [ai] makes
 * it concrete for the stored settings: a Gemini key on the free tier searches nothing.
 */
internal fun capabilities(spec: ProviderSpec, ai: AiSettings? = null): List<Capability> {
    val geminiFree = ai?.isGeminiFree(spec.id) == true
    val search = spec.chatWebSearch && !geminiFree
    val searchLabel = when {
        geminiFree -> "Websuche nur mit Abrechnung"
        spec.id == AiProviderId.GEMINI -> "Google-Suche"
        else -> "Websuche"
    }
    val live = spec.live == LiveSupport.YES
    val android7 = !spec.needsSdk
    return listOf(
        Capability("Chat", available = true, spoken = "Chat verfügbar"),
        Capability(
            searchLabel,
            available = search,
            // TalkBack hears what the pill shows: a free Gemini key gets search with billing.
            spoken = when {
                search -> "Websuche verfügbar"
                geminiFree -> "Websuche nur mit Abrechnung"
                else -> "Websuche nicht verfügbar"
            },
        ),
        Capability("Live", available = live, spoken = if (live) "Live verfügbar" else "Live nicht verfügbar"),
        Capability(
            if (android7) "Android 7" else "ab Android 8",
            available = android7,
            spoken = if (android7) "läuft auf Android 7" else "braucht Android 8",
            device = true,
        ),
    )
}

/** All capability pills as one sentence for TalkBack. */
internal fun capabilitySemantics(capabilities: List<Capability>): String = capabilities.joinToString(", ") { it.spoken }

/** The status line of a provider card in the picker: key, server or device. */
internal fun providerStatusLine(ai: AiSettings, id: AiProviderId, supported: Boolean): String {
    val p = ai.of(id)
    val base = when {
        !supported -> "Braucht Android 8"
        id == AiProviderId.CUSTOM -> if (ai.isConfigured(id)) "Server eingerichtet · ${serverLabel(p.baseUrl)}" else "Kein Server eingerichtet"
        !p.hasKey -> "Kein Key"
        p.keyRejected -> "Key abgelehnt"
        p.keyUntested -> "Key gespeichert · noch nicht getestet"
        else -> "Key gespeichert ✓"
    }
    return if (id == AiProviderId.GEMINI && supported && p.hasKey) "$base · ${tierShort(ai)}" else base
}

/** "kostenlos" / "mit Abrechnung" for the stored Gemini tariff. */
private fun tierShort(ai: AiSettings): String = if (ai.isGeminiFree(AiProviderId.GEMINI)) "kostenlos" else "mit Abrechnung"

/** Under the summary card when the chosen provider cannot run here (Claude below Android 8). */
internal fun unsupportedProviderHint(spec: ProviderSpec): String = "${spec.brand} braucht Android 8 – wähl einen anderen Anbieter."

// -------------------------------------------------------------------------------------------
// Live provider
// -------------------------------------------------------------------------------------------

/**
 * Settings → KI → "⚡ Live": who researches. Null when the effective Live provider cannot run Live
 * (the chat provider has none and no own Live provider is chosen) — the section then explains
 * why ([ProviderSpec.liveUnavailableText]) and offers "Live-Anbieter wählen". Names the brand
 * ("Claude", not "Claude (Anthropic)"), so "(wie Chat)" never nests in another bracket.
 */
internal fun liveProviderLine(ai: AiSettings): String? {
    val spec = ProviderCatalog.spec(ai.effectiveLiveProvider)
    if (spec.live != LiveSupport.YES) return null
    val sameAsChat = ai.liveProvider == null || ai.liveProvider == ai.provider
    return if (sameAsChat) "Live über ${spec.brand} (wie Chat)" else "Live über ${spec.brand}"
}

/** Title of the "Wie Chat" choice of the Live picker, e.g. "Wie Chat (Claude)": the brand, no nested brackets. */
internal fun sameAsChatLabel(chat: ProviderSpec): String = "Wie Chat (${chat.brand})"

/**
 * The choices of the Live provider picker: null = "Wie Chat" (only when the chat provider can
 * research itself), then every provider with Live that runs on this device ([sdkAvailable]).
 */
internal fun liveProviderChoices(ai: AiSettings, sdkAvailable: Boolean): List<AiProviderId?> = buildList {
    if (ProviderCatalog.spec(ai.provider).live == LiveSupport.YES) add(null)
    ProviderCatalog.all
        .filter { it.live == LiveSupport.YES && (!it.needsSdk || sdkAvailable) }
        .forEach { add(it.id) }
}

/**
 * The stored Live provider for a pick in the Live picker: "Wie Chat" (null) and the chat provider
 * itself are both stored as "none", so a later change of the chat provider takes Live along.
 */
internal fun liveProviderToStore(ai: AiSettings, picked: AiProviderId?): AiProviderId? =
    picked?.takeIf { it != ai.provider }

// -------------------------------------------------------------------------------------------
// Models and prices
// -------------------------------------------------------------------------------------------

/** One row of the model picker. [enabled] false: a Pro model with a free Gemini key. */
@Immutable
internal data class ModelChoice(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String?,
    val selected: Boolean,
    val enabled: Boolean,
)

/**
 * The curated models of [id] for the picker, with their price per million tokens (as of [on]).
 * The selected one is the model requests use ([ProviderCatalog.effectiveModel]). With a free
 * Gemini key every selectable model is "kostenlos" (without a note on future prices), and a
 * model without free tier is marked "nur mit Abrechnung" and cannot be picked.
 */
internal fun modelChoices(ai: AiSettings, id: AiProviderId, on: LocalDate): List<ModelChoice> {
    val spec = ProviderCatalog.spec(id)
    val effective = ProviderCatalog.effectiveModel(ai, id)
    return spec.models.map { model ->
        val selectable = ProviderCatalog.isModelSelectable(ai, id, model.id)
        val price = if (selectable) priceLine(ai, id, model.id, on) else GeminiTiers.BILLING_ONLY
        val note = model.priceNote?.takeUnless { ai.costsNothing(id) }?.let { " ($it)" }.orEmpty()
        ModelChoice(
            id = model.id,
            title = model.label,
            subtitle = "${model.subtitle}$note · $price",
            badge = model.badge,
            selected = model.id == effective,
            enabled = selectable,
        )
    }
}

/** The stored model of [id] when it is none of the curated ones ("Eigenes Modell …"), else null. */
internal fun customModelOf(ai: AiSettings, id: AiProviderId): String? {
    val stored = ai.of(id).model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return stored.takeIf { ProviderCatalog.spec(id).model(it) == null }
}

/**
 * The second line of "Eigenes Modell …": the stored free-text model with its price — "nur mit
 * Abrechnung" when a free Gemini key cannot use it (a typed Pro id) — or an example.
 */
internal fun customModelSubtitle(ai: AiSettings, id: AiProviderId, on: LocalDate): String {
    val custom = customModelOf(ai, id) ?: return "z. B. ${customModelExample(id)}"
    val price = if (ProviderCatalog.isModelSelectable(ai, id, custom)) priceLine(ai, id, custom, on) else GeminiTiers.BILLING_ONLY
    return "$custom · $price"
}

/**
 * Under the free-text model field: why [modelId] cannot be taken (a Gemini Pro id with a free
 * key), or null when it can.
 */
internal fun customModelBlockedHint(ai: AiSettings, id: AiProviderId, modelId: String): String? {
    val model = modelId.trim()
    if (model.isEmpty() || ProviderCatalog.isModelSelectable(ai, id, model)) return null
    return GeminiTiers.PRO_NEEDS_BILLING
}

/**
 * The own server's model rows: in the server's order — a tap must not move the rows under the
 * finger — with the [stored] model in front only when the server does not list it (not loaded
 * yet, or typed by hand).
 */
internal fun serverModelOrder(stored: String?, listed: List<String>): List<String> {
    val ids = listed.distinct()
    return if (stored != null && stored !in ids) listOf(stored) + ids else ids
}

/** Example id under "Eigenes Modell …" and in its dialog. */
internal fun customModelExample(id: AiProviderId): String = when (id) {
    AiProviderId.ANTHROPIC -> "claude-opus-5"
    AiProviderId.OPENAI -> "gpt-5.6-terra"
    AiProviderId.GEMINI -> "gemini-3.5-flash"
    AiProviderId.XAI -> "grok-4.6"
    AiProviderId.PERPLEXITY -> "openai/gpt-6-luna"
    AiProviderId.OPENROUTER -> "x-ai/grok-4.7"
    AiProviderId.MISTRAL -> "mistral-large-latest"
    AiProviderId.DEEPSEEK -> "deepseek-v4-flash"
    AiProviderId.CUSTOM -> "llama3.3"
}

/**
 * "2 $ / 10 $ je 1 Mio. Tokens" for [modelId] of [id]; "kostenlos" where requests cost nothing
 * (own server, free Gemini key) and "Preis geschätzt" for a model the price table does not know.
 */
internal fun priceLine(ai: AiSettings, id: AiProviderId, modelId: String, on: LocalDate): String {
    if (ai.costsNothing(id)) return "kostenlos"
    val (rates, estimated) = AiPricing.ratesFor(id, modelId, on)
    if (estimated) return "Preis geschätzt"
    // Non-breaking inside each amount and the unit: a line never ends with "4" and goes on with "$".
    return "${perMillionUsd(rates.input)} / ${perMillionUsd(rates.output)} je${NBSP}1${NBSP}Mio.${NBSP}Tokens"
}

/** Keeps a number and its unit on one line. */
private const val NBSP = ' '

/** Under the free-text model field: the app has to guess the price of [modelId]. Null when it knows it or it costs nothing. */
internal fun customModelPriceHint(ai: AiSettings, id: AiProviderId, modelId: String, on: LocalDate): String? {
    val model = modelId.trim()
    if (model.isEmpty() || ai.costsNothing(id)) return null
    return if (AiPricing.ratesFor(id, model, on).second) "Preis geschätzt – dieses Modell kennt die App nicht." else null
}

/**
 * Prices per million tokens the German way: "2 $", "0,10 $", "0,075 $", "3,75 $" (at most three
 * decimals; a non-breaking space before "$").
 */
internal fun perMillionUsd(value: Double): String {
    if (value.isNaN() || value <= 0.0) return "0$NBSP$"
    val millis = (value * 1000).roundToInt()
    if (millis % 1000 == 0) return "${millis / 1000}$NBSP$"
    val decimals = if (millis % 10 == 0) 2 else 3
    return String.format(Locale.GERMANY, "%.${decimals}f$NBSP$", millis / 1000.0)
}

// -------------------------------------------------------------------------------------------
// Developer: base URL override (debug builds only)
// -------------------------------------------------------------------------------------------

/** Port of `tools/mock-ai-server.mjs` (docs/AI-PROVIDERS.md §14). */
internal const val MOCK_SERVER_PORT = 8787

/**
 * The base URL that points [id] at the local mock server from the emulator (`10.0.2.2` is the
 * host machine): the SDK default path for Claude (the SDK appends `/v1/messages`), `/v1beta`
 * for Gemini, `/v1` for the rest.
 */
internal fun debugBaseUrlExample(id: AiProviderId, port: Int = MOCK_SERVER_PORT): String =
    when (ProviderCatalog.spec(id).engine) {
        EngineKind.CLAUDE_SDK -> "http://10.0.2.2:$port"
        EngineKind.GEMINI -> "http://10.0.2.2:$port/v1beta"
        EngineKind.RESPONSES, EngineKind.CHAT_COMPLETIONS -> "http://10.0.2.2:$port/v1"
    }

/** The providers a debug build can point elsewhere: all but the own server, which has its own address. */
internal val debugBaseUrlProviders: List<AiProviderId> = AiProviderId.entries.filter { it != AiProviderId.CUSTOM }

/**
 * The developer row's second line: the chat provider's override, or how many providers go to a
 * test server ("3 von 8 Anbietern umgeleitet"), or that every provider talks to its real API.
 */
internal fun debugBaseUrlSummary(ai: AiSettings): String {
    val redirected = debugBaseUrlProviders.count { !ai.of(it).debugBaseUrl.isNullOrBlank() }
    val chat = ai.of(ai.provider).debugBaseUrl?.takeIf { it.isNotBlank() && ai.provider != AiProviderId.CUSTOM }
    return when {
        redirected == 0 -> "Echte APIs (keine Umleitung)"
        chat != null && redirected == 1 -> chat
        chat != null -> "$chat · $redirected von ${debugBaseUrlProviders.size} Anbietern umgeleitet"
        else -> "$redirected von ${debugBaseUrlProviders.size} Anbietern umgeleitet"
    }
}

// -------------------------------------------------------------------------------------------
// Monthly limit and usage
// -------------------------------------------------------------------------------------------

/** The monthly cap slider: 0–50 $ in whole dollars, 0 = no cap. */
internal object AiLimit {
    const val MAX = 50
    const val SLIDER_STEPS = MAX - 1

    fun normalize(value: Double): Double {
        if (value.isNaN()) return 0.0
        return value.roundToInt().coerceIn(0, MAX).toDouble()
    }

    /** "Kein Limit", "5 $ im Monat" */
    fun label(value: Double): String {
        val v = normalize(value)
        return if (v <= 0.0) "Kein Limit" else "${usd(v)} im Monat"
    }

    /** The line under the slider: what reaching the cap does, or what having none means. */
    fun hint(value: Double): String = if (normalize(value) <= 0.0) {
        "Ohne Limit schickt die App jede KI-Anfrage – die Kosten trägt dein Konto beim Anbieter."
    } else {
        "Ist es erreicht, schickt die App bis zum Monatsende keine KI-Anfragen mehr. Es gilt für alle Anbieter zusammen; " +
            "der eigene Server und Gemini kostenlos laufen immer weiter."
    }
}

/**
 * Dollar amounts the German way: "5 $", "0,84 $", "12,50 $"; tiny positive amounts become
 * "< 0,01 $" instead of a misleading "0 $".
 */
internal fun usd(value: Double): String {
    if (value.isNaN() || value <= 0.0) return "0 $"
    if (value < 0.005) return "< 0,01 $"
    val cents = (value * 100).roundToInt()
    return if (cents % 100 == 0) {
        "${cents / 100} $"
    } else {
        String.format(Locale.GERMANY, "%.2f $", cents / 100.0)
    }
}

/** "Diesen Monat: 0,84 $ von 5 $ · 23 Anfragen · 7 Websuchen" ([limit] 0 = no "von …"). */
internal fun usageLine(spent: Double, limit: Double, requests: Int, webSearches: Int): String {
    val budget = if (limit > 0.0) "${usd(spent)} von ${usd(limit)}" else usd(spent)
    val req = if (requests == 1) "1 Anfrage" else "${requests.coerceAtLeast(0)} Anfragen"
    val web = if (webSearches == 1) "1 Websuche" else "${webSearches.coerceAtLeast(0)} Websuchen"
    return "Diesen Monat: $budget · $req · $web"
}

/** Share of the cap used, 0..1; null without a cap. */
internal fun usageFraction(spent: Double, limit: Double): Float? {
    if (limit <= 0.0) return null
    if (spent.isNaN() || spent <= 0.0) return 0f
    return (spent / limit).toFloat().coerceIn(0f, 1f)
}

/** "23 Anfragen · 7 Websuchen" — non-breaking around the dot, so a line never starts with "·". */
internal fun usageCounts(requests: Int, webSearches: Int): String {
    val req = if (requests == 1) "1 Anfrage" else "${requests.coerceAtLeast(0)} Anfragen"
    val web = if (webSearches == 1) "1 Websuche" else "${webSearches.coerceAtLeast(0)} Websuchen"
    return "$req · $web"
}

/** Under the usage meter when some requests of the month were priced with fallback rates; null otherwise. */
internal fun estimatedNote(estimatedRequests: Int): String? = when {
    estimatedRequests <= 0 -> null
    estimatedRequests == 1 -> "Preis teils geschätzt: 1 Anfrage lief mit einem Modell, das die App nicht kennt."
    else -> "Preis teils geschätzt: $estimatedRequests Anfragen liefen mit einem Modell, das die App nicht kennt."
}

/**
 * The line under a Gemini tariff in the choice ([GeminiTiers.label] is its title, so the
 * description's leading "Kostenlos:" is not repeated).
 */
internal fun geminiTierSubtitle(tier: GeminiTier): String = when (tier) {
    GeminiTier.FREE -> "Kein Guthaben nötig. Antworten nur aus Artikel und Briefing – ohne Websuche."
    GeminiTier.PAID -> "Prepay im Google-Projekt. Antworten auch mit Google-Suche und Gemini Pro."
}

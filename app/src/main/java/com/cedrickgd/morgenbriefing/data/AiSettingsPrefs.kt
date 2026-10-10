package com.cedrickgd.morgenbriefing.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier
import com.cedrickgd.morgenbriefing.data.model.ModelFallback
import com.cedrickgd.morgenbriefing.data.model.OpenRouterTier
import com.cedrickgd.morgenbriefing.data.model.ProviderSettings
import java.net.URI
import java.net.URISyntaxException

/*
 * The AI part of the "settings" DataStore (docs/AI-PROVIDERS.md §3): key names, the silent
 * migration from the single Claude key of v1.2, and the mapping to and from [AiSettings]. Pure
 * functions over Preferences, unit-tested on the JVM.
 */

/** Preference keys of the AI settings; `<p>` is [AiProviderId.key]. */
internal object AiPrefKeys {
    /** [SCHEMA_VERSION] once [AiProvidersMigration] ran. */
    val SCHEMA = intPreferencesKey("ai_schema")
    val PROVIDER = stringPreferencesKey("ai_provider")

    /** Absent = Live runs with the chat provider. */
    val LIVE_PROVIDER = stringPreferencesKey("ai_live_provider")
    val CUSTOM_BASE_URL = stringPreferencesKey("ai_custom_base_url")

    /** Plain text: a workspace id is not a secret. */
    val ANTHROPIC_WORKSPACE_ID = stringPreferencesKey("ai_anthropic_workspace_id")

    /** [GeminiTier.key]; absent = [GeminiTier.FREE]. */
    val GEMINI_TIER = stringPreferencesKey("ai_gemini_tier")

    /** [OpenRouterTier.key]; absent = [OpenRouterTier.FREE] (a key stored before schema 3 was migrated to credits). */
    val OPENROUTER_TIER = stringPreferencesKey("ai_openrouter_tier")

    /** [ProviderSettings.freeModel] of OpenRouter: the model of the free tariff. */
    val OPENROUTER_FREE_MODEL = stringPreferencesKey("ai_openrouter_free_model")

    /** `TokenCipher` ciphertext of the key (never logged, never in AppSettings). */
    fun keyEnc(p: AiProviderId) = stringPreferencesKey("ai_${p.key}_key_enc")
    fun untested(p: AiProviderId) = booleanPreferencesKey("ai_${p.key}_key_untested")
    fun rejected(p: AiProviderId) = booleanPreferencesKey("ai_${p.key}_key_rejected")
    fun model(p: AiProviderId) = stringPreferencesKey("ai_${p.key}_model")

    /** [ProviderSettings.modelFallback]: the chosen model the provider refused for the stored key … */
    fun fallbackFrom(p: AiProviderId) = stringPreferencesKey("ai_${p.key}_model_fallback_from")

    /** … and the model that runs instead. Both or neither. */
    fun fallbackTo(p: AiProviderId) = stringPreferencesKey("ai_${p.key}_model_fallback_to")

    /** Debug builds only: base URL override for QA (see [ProviderSettings.debugBaseUrl]). */
    fun debugBaseUrl(p: AiProviderId) = stringPreferencesKey("ai_${p.key}_debug_base_url")

    // v1.2: one Claude key and the AiModel enum name.
    val LEGACY_KEY = stringPreferencesKey("ai_key_enc")
    val LEGACY_UNTESTED = booleanPreferencesKey("ai_key_untested")
    val LEGACY_REJECTED = booleanPreferencesKey("ai_key_rejected")
    val LEGACY_MODEL = stringPreferencesKey("ai_model")

    /** 2: one slot per provider (v1.3.0). 3: the OpenRouter tariff (v1.3.2). */
    const val SCHEMA_VERSION = 3
}

/**
 * Moves the v1.2 Claude key, its flags and the model into the Anthropic slot before the first
 * read (DataStore runs migrations atomically), and keeps an OpenRouter key of v1.3.0/1.3.1 on
 * the credits it was set up for (schema 3). Fresh installs take the same path and end up with
 * provider Anthropic and the current schema.
 */
internal object AiProvidersMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[AiPrefKeys.SCHEMA] ?: 0) < AiPrefKeys.SCHEMA_VERSION

    override suspend fun migrate(currentData: Preferences): Preferences = migrateAiPrefs(currentData)

    override suspend fun cleanUp() = Unit
}

/**
 * Pure and idempotent. The ciphertext does not depend on the preference name (one keystore
 * alias in TokenCipher), so the key moves as it is. An Anthropic key stored already is never
 * overwritten. Old model names map to the new ids; anything else falls back to the catalog
 * default. An OpenRouter key stored before schema 3 ran on credits (there was no free tariff):
 * it stays on [OpenRouterTier.CREDITS], while a new OpenRouter setup starts on the free tariff.
 * The global monthly limit and the Live auto-refresh switch are not touched.
 */
internal fun migrateAiPrefs(old: Preferences): Preferences = old.toMutablePreferences().apply {
    val schema = this[AiPrefKeys.SCHEMA] ?: 0
    val openRouter = AiProviderId.OPENROUTER
    if (schema < 3 && !this[AiPrefKeys.keyEnc(openRouter)].isNullOrBlank() && this[AiPrefKeys.OPENROUTER_TIER] == null) {
        this[AiPrefKeys.OPENROUTER_TIER] = OpenRouterTier.CREDITS.key
    }
    val anthropic = AiProviderId.ANTHROPIC
    val legacyKey = this[AiPrefKeys.LEGACY_KEY]
    if (!legacyKey.isNullOrBlank() && this[AiPrefKeys.keyEnc(anthropic)] == null) {
        this[AiPrefKeys.keyEnc(anthropic)] = legacyKey
        if (this[AiPrefKeys.LEGACY_UNTESTED] == true) this[AiPrefKeys.untested(anthropic)] = true
        if (this[AiPrefKeys.LEGACY_REJECTED] == true) this[AiPrefKeys.rejected(anthropic)] = true
    }
    if (this[AiPrefKeys.model(anthropic)] == null) {
        when (this[AiPrefKeys.LEGACY_MODEL]) {
            "SONNET" -> this[AiPrefKeys.model(anthropic)] = "claude-sonnet-5-5"
            "OPUS" -> this[AiPrefKeys.model(anthropic)] = "claude-opus-5-5"
            else -> Unit
        }
    }
    if (this[AiPrefKeys.PROVIDER] == null) this[AiPrefKeys.PROVIDER] = anthropic.key
    remove(AiPrefKeys.LEGACY_KEY)
    remove(AiPrefKeys.LEGACY_UNTESTED)
    remove(AiPrefKeys.LEGACY_REJECTED)
    remove(AiPrefKeys.LEGACY_MODEL)
    this[AiPrefKeys.SCHEMA] = AiPrefKeys.SCHEMA_VERSION
}.toPreferences()

/**
 * The AI settings as stored: one entry per provider; key flags only count with a key, an unknown
 * provider id falls back to Anthropic. [debugOverrides] is `BuildConfig.DEBUG`: release builds
 * never see a stored debug base URL.
 */
internal fun readAiSettings(prefs: Preferences, debugOverrides: Boolean): AiSettings {
    val providers = AiProviderId.entries.associateWith { id ->
        val hasKey = !prefs[AiPrefKeys.keyEnc(id)].isNullOrBlank()
        ProviderSettings(
            hasKey = hasKey,
            keyUntested = hasKey && prefs[AiPrefKeys.untested(id)] == true,
            keyRejected = hasKey && prefs[AiPrefKeys.rejected(id)] == true,
            model = prefs[AiPrefKeys.model(id)]?.trim()?.takeIf { it.isNotEmpty() }?.take(AiSettings.MAX_MODEL_LENGTH),
            baseUrl = if (id == AiProviderId.CUSTOM) prefs[AiPrefKeys.CUSTOM_BASE_URL]?.trim()?.takeIf { it.isNotEmpty() } else null,
            workspaceId = if (id == AiProviderId.ANTHROPIC) {
                prefs[AiPrefKeys.ANTHROPIC_WORKSPACE_ID]?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            },
            geminiTier = if (id == AiProviderId.GEMINI) GeminiTier.fromKey(prefs[AiPrefKeys.GEMINI_TIER]) ?: GeminiTier.FREE else GeminiTier.FREE,
            openRouterTier = if (id == AiProviderId.OPENROUTER) {
                OpenRouterTier.fromKey(prefs[AiPrefKeys.OPENROUTER_TIER]) ?: OpenRouterTier.FREE
            } else {
                OpenRouterTier.FREE
            },
            freeModel = if (id == AiProviderId.OPENROUTER) {
                prefs[AiPrefKeys.OPENROUTER_FREE_MODEL]?.trim()?.takeIf { it.isNotEmpty() }?.take(AiSettings.MAX_MODEL_LENGTH)
            } else {
                null
            },
            debugBaseUrl = if (debugOverrides) prefs[AiPrefKeys.debugBaseUrl(id)]?.let(::normalizeBaseUrl) else null,
            modelFallback = readModelFallback(prefs, id).takeIf { hasKey },
        )
    }
    return AiSettings(
        provider = AiProviderId.fromKey(prefs[AiPrefKeys.PROVIDER]) ?: AiProviderId.ANTHROPIC,
        liveProvider = AiProviderId.fromKey(prefs[AiPrefKeys.LIVE_PROVIDER]),
        providers = providers,
    )
}

/** The stored model fallback of [id], or null unless both of its ids are there. */
private fun readModelFallback(prefs: Preferences, id: AiProviderId): ModelFallback? {
    val from = prefs[AiPrefKeys.fallbackFrom(id)]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val to = prefs[AiPrefKeys.fallbackTo(id)]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return ModelFallback(from.take(AiSettings.MAX_MODEL_LENGTH), to.take(AiSettings.MAX_MODEL_LENGTH))
}

/**
 * A model fallback learned by a request that ran with the stored key [encrypted] (its ciphertext)
 * on the Gemini tariff [geminiTier] still fits [prefs]: the same key is stored and — for Gemini,
 * when the tariff is known — the tariff is the same. A new key or another tariff dropped the
 * fallback; an answer that was still under way then must not bring it back.
 */
internal fun fallbackStillFits(prefs: Preferences, id: AiProviderId, encrypted: String, geminiTier: GeminiTier?): Boolean {
    if (prefs[AiPrefKeys.keyEnc(id)] != encrypted) return false
    if (id != AiProviderId.GEMINI || geminiTier == null) return true
    return (GeminiTier.fromKey(prefs[AiPrefKeys.GEMINI_TIER]) ?: GeminiTier.FREE) == geminiTier
}

/** Writes [fallback] of [id]; null removes it. */
internal fun MutablePreferences.writeModelFallback(id: AiProviderId, fallback: ModelFallback?) {
    val from = fallback?.from?.trim()?.take(AiSettings.MAX_MODEL_LENGTH)?.takeIf { it.isNotEmpty() }
    val to = fallback?.to?.trim()?.take(AiSettings.MAX_MODEL_LENGTH)?.takeIf { it.isNotEmpty() }
    if (from == null || to == null || from == to) {
        remove(AiPrefKeys.fallbackFrom(id))
        remove(AiPrefKeys.fallbackTo(id))
    } else {
        this[AiPrefKeys.fallbackFrom(id)] = from
        this[AiPrefKeys.fallbackTo(id)] = to
    }
}

/**
 * Writes the plain AI fields of [ai]: the chat and Live provider and the model of every provider
 * in [AiSettings.providers] (null removes it, providers not in the map stay untouched), and
 * OpenRouter's free model. A model that changes drops the provider's model fallback: the user's
 * new choice is tried again. Keys, their flags, the base URLs, the workspace id, the tariffs and
 * the fallback itself only change through the dedicated setters of SettingsRepository.
 */
internal fun MutablePreferences.writeAiSettings(ai: AiSettings) {
    this[AiPrefKeys.PROVIDER] = ai.provider.key
    val live = ai.liveProvider
    if (live == null) remove(AiPrefKeys.LIVE_PROVIDER) else this[AiPrefKeys.LIVE_PROVIDER] = live.key
    ai.providers.forEach { (id, settings) ->
        val model = settings.model?.trim()?.take(AiSettings.MAX_MODEL_LENGTH)?.takeIf { it.isNotEmpty() }
        if (model != this[AiPrefKeys.model(id)]) writeModelFallback(id, null)
        if (model == null) remove(AiPrefKeys.model(id)) else this[AiPrefKeys.model(id)] = model
        if (id == AiProviderId.OPENROUTER) {
            val free = settings.freeModel?.trim()?.take(AiSettings.MAX_MODEL_LENGTH)?.takeIf { it.isNotEmpty() }
            if (free == null) remove(AiPrefKeys.OPENROUTER_FREE_MODEL) else this[AiPrefKeys.OPENROUTER_FREE_MODEL] = free
        }
    }
}

/**
 * An `http(s)://host[:port][/path]` address without trailing slashes, or null when [raw] is not
 * one. No other rewriting: the custom server's `/v1` rule lives with the custom server engine.
 */
internal fun normalizeBaseUrl(raw: String): String? {
    val text = raw.trim().trimEnd('/')
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    val uri = try {
        URI(text)
    } catch (_: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null) return null
    return text
}

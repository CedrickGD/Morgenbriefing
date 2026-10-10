package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.provider.LiveSupport
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.ai.provider.ProviderSpec
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import kotlinx.coroutines.flow.Flow

/*
 * Which provider a chat or Live run uses, and whether it can run now (docs/AI-PROVIDERS.md
 * §4.1). The pure parts are unit-tested; AiServices implements [AiProviderAccess] on top of
 * the settings, the stored keys and the backend registry.
 */

/** What a request is for: "Frag nach" or "⚡ Live" (which may have its own provider). */
enum class AiPurpose { CHAT, LIVE }

/** The provider that serves [purpose]. */
internal fun AiSettings.providerFor(purpose: AiPurpose): AiProviderId =
    if (purpose == AiPurpose.LIVE) effectiveLiveProvider else provider

/**
 * Why AI can't run for [purpose] right now; null = go. Order: device (or no backend for the
 * provider in this build), Live support (OpenRouter on the free tariff has none), configuration,
 * monthly limit. Free providers (own server, a key on a free tariff) are never stopped by the limit.
 */
internal fun aiBlock(
    ai: AiSettings,
    purpose: AiPurpose,
    sdkAvailable: Boolean,
    usage: UsageSnapshot,
    hasBackend: Boolean = true,
): AiBlock? {
    val id = ai.providerFor(purpose)
    val spec = ProviderCatalog.spec(id)
    return when {
        (spec.needsSdk && !sdkAvailable) || !hasBackend -> AiBlock.UNSUPPORTED_DEVICE
        purpose == AiPurpose.LIVE && ProviderCatalog.liveSupport(ai, id) != LiveSupport.YES -> AiBlock.LIVE_UNAVAILABLE
        !ai.isConfigured(id) -> AiBlock.NO_KEY
        !ai.costsNothing(id) && usage.limitUsd > 0.0 && usage.spentUsd >= usage.limitUsd -> AiBlock.LIMIT_REACHED
        else -> null
    }
}

/**
 * What a chat or Live view needs to know about its provider before a request: who it is,
 * whether it is blocked, and whether the chat searches the web ([freeTier]: a key on a free
 * tariff — Gemini or OpenRouter — the reason it does not).
 */
internal data class AiStatus(
    val spec: ProviderSpec,
    val block: AiBlock?,
    val webSearch: Boolean,
    val freeTier: Boolean,
)

/** [aiBlock] plus the provider facts the views show. */
internal fun aiStatus(
    ai: AiSettings,
    purpose: AiPurpose,
    sdkAvailable: Boolean,
    usage: UsageSnapshot,
    hasBackend: Boolean = true,
): AiStatus {
    val id = ai.providerFor(purpose)
    return AiStatus(
        spec = ProviderCatalog.spec(id),
        block = aiBlock(ai, purpose, sdkAvailable, usage, hasBackend),
        webSearch = ProviderCatalog.chatWebSearch(ai, id),
        freeTier = ai.isFreeTier(id),
    )
}

/**
 * The setup a key test of [provider] runs with: [ai] with the candidate [extras] — a null field
 * keeps the stored workspace id or server address, "" clears it.
 */
internal fun keyTestSettings(ai: AiSettings, provider: AiProviderId, extras: KeyExtras): AiSettings {
    val stored = ai.of(provider)
    return ai.with(
        provider,
        stored.copy(
            workspaceId = extras.workspaceId?.let { it.trim().ifEmpty { null } } ?: stored.workspaceId.takeIf { extras.workspaceId == null },
            baseUrl = extras.baseUrl?.let { it.trim().ifEmpty { null } } ?: stored.baseUrl.takeIf { extras.baseUrl == null },
        ),
    )
}

/**
 * A key test of [provider] that ended in [check] marks the stored key as rejected, like a refused
 * chat request: only for an outright refusal of the key ([AiKeyCheck.rejectsKey]), only with a key
 * stored, and only when the test ran with the stored setup ([tested] is [stored] for [provider]) —
 * a new workspace id or server address may be what failed. Whether the tested key is the stored
 * one is left to the key store, which compares them (a different, unsaved key is never marked).
 */
internal fun keyTestRejectsStoredKey(check: AiKeyCheck, stored: AiSettings, tested: AiSettings, provider: AiProviderId): Boolean =
    check.rejectsKey() && stored.of(provider).hasKey && tested.of(provider) == stored.of(provider)

/** The outcome of resolving a provider for one chat turn or Live run. */
internal sealed interface Resolution {
    val spec: ProviderSpec

    /**
     * Everything a request needs. [model] is the one requests use ([ProviderCatalog.effectiveModel]:
     * a Gemini model fallback of the key, if any); [selectedModel] is the one the user chose
     * ([ProviderCatalog.selectedModel]) — a new fallback is stored for it and named after it.
     * [usage] is this provider's sink: it skips the monthly limit and prices at 0 $ for free
     * providers, and confirms the key once a request went through. [webSearch]: offer web search
     * in the chat (false for a free Gemini key).
     */
    data class Ready(
        override val spec: ProviderSpec,
        val auth: ProviderAuth,
        val model: String,
        val selectedModel: String,
        val backend: AiBackend,
        val webSearch: Boolean,
        val usage: UsageSink,
    ) : Resolution

    data class Blocked(override val spec: ProviderSpec, val block: AiBlock) : Resolution
}

/** What ChatServiceImpl and LiveNewsServiceImpl need from the facade (faked in tests). */
internal interface AiProviderAccess {
    /** Reads the settings, decrypts the key and picks the backend. The monthly limit is left to [Resolution.Ready.usage]. */
    suspend fun resolve(purpose: AiPurpose): Resolution

    /** The provider of [purpose] and its block, live over the settings and this month's usage. */
    fun status(purpose: AiPurpose): Flow<AiStatus>

    /** A request with [key] ended in an error that [rejectsKey]: Settings → KI says so. Must not throw. */
    suspend fun keyRejected(provider: AiProviderId, key: String?)

    /**
     * [provider] refused [from] for [key] and [to] answered instead (Gemini, GE §12.5): requests
     * with this key use [to] from now on. Must not throw.
     */
    suspend fun modelFallback(provider: AiProviderId, key: String?, from: String, to: String) {}
}

/**
 * A key or connection test of [provider] that ended in [check] marks the stored key as rejected
 * when the provider refused the key itself: [AiKeyCheck.INVALID], or a Gemini key Google does not
 * take ([AiKeyCheck.AUTH_KEY_REJECTED]) or blocked as leaked ([AiKeyCheck.KEY_LEAKED]).
 */
internal fun AiKeyCheck.rejectsKey(): Boolean =
    this == AiKeyCheck.INVALID || this == AiKeyCheck.AUTH_KEY_REJECTED || this == AiKeyCheck.KEY_LEAKED

/**
 * The usage sink of one provider over the shared monthly [store]. A [free] provider (own
 * server, a key on a free tariff) is never stopped by the limit and costs 0 $ (its requests and
 * searches still count). [onRecorded] runs after each recorded request — usage only exists for
 * a request the provider accepted, so it confirms the key.
 */
internal class ProviderUsageSink(
    private val store: UsageSink,
    private val free: Boolean,
    private val onRecorded: suspend (AiProviderId) -> Unit = {},
) : UsageSink {
    override suspend fun beforeRequest() {
        if (!free) store.beforeRequest()
    }

    override suspend fun record(provider: AiProviderId, modelId: String, usage: TokenUsage) {
        store.record(provider, modelId, if (free) usage.copy(reportedCostUsd = 0.0) else usage)
        onRecorded(provider)
    }
}

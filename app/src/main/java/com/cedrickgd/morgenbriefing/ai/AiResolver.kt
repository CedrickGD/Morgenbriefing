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
 * provider in this build), Live support, configuration, monthly limit. Free providers (own
 * server, a free Gemini key) are never stopped by the limit.
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
        purpose == AiPurpose.LIVE && spec.live != LiveSupport.YES -> AiBlock.LIVE_UNAVAILABLE
        !ai.isConfigured(id) -> AiBlock.NO_KEY
        !ai.costsNothing(id) && usage.limitUsd > 0.0 && usage.spentUsd >= usage.limitUsd -> AiBlock.LIMIT_REACHED
        else -> null
    }
}

/**
 * What a chat or Live view needs to know about its provider before a request: who it is,
 * whether it is blocked, and whether the chat searches the web ([freeTier]: a free Gemini key,
 * the reason it does not).
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
        freeTier = ai.isGeminiFree(id),
    )
}

/** The outcome of resolving a provider for one chat turn or Live run. */
internal sealed interface Resolution {
    val spec: ProviderSpec

    /**
     * Everything a request needs. [usage] is this provider's sink: it skips the monthly limit
     * and prices at 0 $ for free providers, and confirms the key once a request went through.
     * [webSearch]: offer web search in the chat (false for a free Gemini key).
     */
    data class Ready(
        override val spec: ProviderSpec,
        val auth: ProviderAuth,
        val model: String,
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

    /** A request with [key] ended in [AiError.InvalidKey]: Settings → KI says so. Must not throw. */
    suspend fun keyRejected(provider: AiProviderId, key: String?)
}

/**
 * The usage sink of one provider over the shared monthly [store]. A [free] provider (own
 * server, free Gemini key) is never stopped by the limit and costs 0 $ (its requests and
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

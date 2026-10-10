package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** A backend made of fake engines (key checks are not part of these tests). */
internal class FakeBackend(override val chat: ChatEngine, override val live: LiveEngine? = null) : AiBackend {
    override val keys: KeyChecker = object : KeyChecker {
        override suspend fun check(auth: ProviderAuth, model: String, usage: UsageSink): KeyCheckOutcome = error("not used")
    }
}

/**
 * [AiProviderAccess] over plain flows, resolving like AiServices does with the real pure parts
 * ([aiBlock], [aiStatus], the catalog): [key] is what the key store holds for every provider,
 * a null [backend] is a provider without backend (e.g. Claude on Android 7).
 */
internal class FakeAiAccess(
    private val settings: StateFlow<AppSettings>,
    private val usageState: StateFlow<UsageSnapshot>,
    private val usage: UsageSink,
    @Volatile var backend: AiBackend?,
    @Volatile var key: String? = Sse.TEST_KEY,
    private val sdkAvailable: Boolean = true,
) : AiProviderAccess {
    val rejected = mutableListOf<Pair<AiProviderId, String?>>()

    /** (provider, key, from, to) of every model fallback the chat reported. */
    val fallbacks = mutableListOf<List<Any?>>()

    /** Whether each of [fallbacks] came from a request on a free tariff. */
    val fallbackFreeTiers = mutableListOf<Boolean>()

    override suspend fun modelFallback(provider: AiProviderId, key: String?, from: String, to: String, freeTier: Boolean) {
        synchronized(fallbacks) {
            fallbacks += listOf(provider, key, from, to)
            fallbackFreeTiers += freeTier
        }
    }

    override suspend fun resolve(purpose: AiPurpose): Resolution {
        val ai = settings.value.ai
        val id = ai.providerFor(purpose)
        val spec = ProviderCatalog.spec(id)
        val backend = backend
        aiBlock(ai, purpose, sdkAvailable, UsageSnapshot(), hasBackend = backend != null)?.let { return Resolution.Blocked(spec, it) }
        if (backend == null) return Resolution.Blocked(spec, AiBlock.UNSUPPORTED_DEVICE)
        if (purpose == AiPurpose.LIVE && backend.live == null) return Resolution.Blocked(spec, AiBlock.LIVE_UNAVAILABLE)
        val key = key
        if (key == null && !id.keyOptional) return Resolution.Blocked(spec, AiBlock.NO_KEY)
        return Resolution.Ready(
            spec = spec,
            auth = ProviderAuth(id, key, freeTier = ai.isFreeTier(id)),
            model = ProviderCatalog.effectiveModel(ai, id),
            selectedModel = ProviderCatalog.selectedModel(ai, id),
            backend = backend,
            webSearch = ProviderCatalog.chatWebSearch(ai, id),
            usage = ProviderUsageSink(usage, free = ai.costsNothing(id)),
        )
    }

    override fun status(purpose: AiPurpose): Flow<AiStatus> =
        combine(settings, usageState) { s, u -> aiStatus(s.ai, purpose, sdkAvailable, u, hasBackend = backend != null) }
            .distinctUntilChanged()

    override suspend fun keyRejected(provider: AiProviderId, key: String?) {
        synchronized(rejected) { rejected += provider to key }
    }
}

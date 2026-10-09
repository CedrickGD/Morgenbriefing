package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.ai.compat.CompatDialect
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.provider.EngineKind
import com.cedrickgd.morgenbriefing.ai.provider.LiveSupport
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every provider without an SDK has a backend, and it can do what the catalog promises. */
class AiBackendRegistryTest {
    private val http = AiHttp(OkHttpClient(), userAgent = "Morgenbriefing-Android/test")

    @Test
    fun `Live is offered exactly where a backend can run it`() {
        ProviderCatalog.all.filter { it.engine != EngineKind.CLAUDE_SDK }.forEach { spec ->
            val backend = httpBackend(spec.id, http)
            assertEquals(spec.id.key, spec.live == LiveSupport.YES, backend.live != null)
        }
    }

    @Test
    fun `the Chat Completions dialects search where the catalog says so`() {
        ProviderCatalog.all.filter { it.engine == EngineKind.CHAT_COMPLETIONS }.forEach { spec ->
            assertEquals(spec.id.key, spec.chatWebSearch, CompatDialect.of(spec.id).webSearch)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Claude is not built here`() {
        httpBackend(AiProviderId.ANTHROPIC, http)
    }
}

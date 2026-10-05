package com.cedrickgd.morgenbriefing.ai.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.Timeout
import com.cedrickgd.morgenbriefing.ai.AiBackend
import com.cedrickgd.morgenbriefing.ai.ChatEngine
import com.cedrickgd.morgenbriefing.ai.KeyChecker
import com.cedrickgd.morgenbriefing.ai.LiveEngine
import java.time.Duration

/*
 * Everything in this package uses the Anthropic Java SDK and must only be loaded on
 * Android 8.0+ (AiSupport.isSupported). AiServices creates ClaudeBackend behind that check;
 * nothing else in the app references these classes.
 */

/** Builds SDK clients; one client per API key is kept and reused (connection pool, mapper). */
internal class ClaudeClients(private val baseUrl: String? = null) {
    private var cachedKey: String? = null
    private var cached: AnthropicClient? = null

    /** The shared client for [apiKey] (a new key replaces the old client). */
    @Synchronized
    fun forKey(apiKey: String): AnthropicClient {
        val current = cached
        if (current != null && cachedKey == apiKey) return current
        return create(apiKey).also {
            cached = it
            cachedKey = apiKey
        }
    }

    /** A fresh client, e.g. for checking a key that is not saved yet. */
    fun create(apiKey: String): AnthropicClient {
        val builder = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .maxRetries(2)
            .timeout(
                Timeout.builder()
                    .connect(Duration.ofSeconds(15))
                    // Server tools can keep a stream quiet for a while; SSE pings arrive in between.
                    .read(Duration.ofSeconds(150))
                    .request(Duration.ofMinutes(10))
                    .build(),
            )
        baseUrl?.let(builder::baseUrl)
        return builder.build()
    }
}

/** The Claude API backend: chat, live research and key checks over one client cache. */
internal class ClaudeBackend(clients: ClaudeClients = ClaudeClients()) : AiBackend {
    override val chat: ChatEngine = ClaudeChatEngine(clients)
    override val live: LiveEngine = ClaudeLiveEngine(clients)
    override val keys: KeyChecker = ClaudeKeyChecker(clients)
}

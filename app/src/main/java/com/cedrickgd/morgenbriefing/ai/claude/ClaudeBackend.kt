package com.cedrickgd.morgenbriefing.ai.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.Timeout
import com.cedrickgd.morgenbriefing.ai.AiBackend
import com.cedrickgd.morgenbriefing.ai.ChatEngine
import com.cedrickgd.morgenbriefing.ai.KeyChecker
import com.cedrickgd.morgenbriefing.ai.LiveEngine
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import java.time.Duration

/*
 * Everything in this package uses the Anthropic Java SDK and must only be loaded on
 * Android 8.0+ (AiSupport.sdkAvailable). AiServices creates ClaudeBackend behind that check;
 * nothing else in the app references these classes.
 */

/**
 * Builds SDK clients; the client of the last configuration (key, workspace id, base URL) is
 * kept and reused (connection pool, mapper).
 *
 * @param baseUrl API base URL when a request does not bring its own ([ProviderAuth.baseUrl]: the
 *   debug override for QA, without `/v1`); null = the SDK default (api.anthropic.com).
 */
internal class ClaudeClients(private val baseUrl: String? = null) {
    /** What a client is built from. Holds the key: never logged, never in [toString]. */
    private class Config(val apiKey: String, val workspaceId: String?, val baseUrl: String?) {
        override fun equals(other: Any?): Boolean =
            other is Config && other.apiKey == apiKey && other.workspaceId == workspaceId && other.baseUrl == baseUrl

        override fun hashCode(): Int = (apiKey.hashCode() * 31 + workspaceId.hashCode()) * 31 + baseUrl.hashCode()

        override fun toString(): String = "Config"
    }

    private var cachedConfig: Config? = null
    private var cached: AnthropicClient? = null

    /** The shared client for [auth] (another key, workspace or base URL replaces the old client). */
    @Synchronized
    fun forAuth(auth: ProviderAuth): AnthropicClient {
        val config = config(auth)
        val current = cached
        if (current != null && cachedConfig == config) return current
        return build(config).also {
            cached = it
            cachedConfig = config
        }
    }

    /** A fresh client, e.g. for checking a key that is not saved yet. */
    fun create(auth: ProviderAuth): AnthropicClient = build(config(auth))

    private fun config(auth: ProviderAuth) = Config(
        apiKey = requireNotNull(auth.apiKey) { "Claude needs an API key" },
        workspaceId = auth.workspaceId?.trim()?.takeIf { it.isNotEmpty() },
        baseUrl = auth.baseUrl ?: baseUrl,
    )

    private fun build(config: Config): AnthropicClient {
        val builder = AnthropicOkHttpClient.builder()
            .apiKey(config.apiKey)
            .maxRetries(2)
            .timeout(
                Timeout.builder()
                    .connect(Duration.ofSeconds(15))
                    // Server tools can keep a stream quiet for a while; SSE pings arrive in between.
                    .read(Duration.ofSeconds(150))
                    .request(Duration.ofMinutes(10))
                    .build(),
            )
        // An identity-linked key that is not bound to one workspace says where it acts (D13).
        config.workspaceId?.let { builder.putHeader(WORKSPACE_HEADER, it) }
        config.baseUrl?.let(builder::baseUrl)
        return builder.build()
    }

    companion object {
        const val WORKSPACE_HEADER = "anthropic-workspace-id"
    }
}

/** The Claude API backend: chat, live research and key checks over one client cache. */
internal class ClaudeBackend(clients: ClaudeClients = ClaudeClients()) : AiBackend {
    override val chat: ChatEngine = ClaudeChatEngine(clients)
    override val live: LiveEngine = ClaudeLiveEngine(clients)
    override val keys: KeyChecker = ClaudeKeyChecker(clients)
}

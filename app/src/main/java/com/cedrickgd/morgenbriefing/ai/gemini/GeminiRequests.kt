package com.cedrickgd.morgenbriefing.ai.gemini

import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.ApiMessage
import com.cedrickgd.morgenbriefing.ai.ChatTurnRequest
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Request bodies and URLs of the Gemini API (`generateContent`, docs/AI-PROVIDERS.md §7.2,
 * GE §4) as JSON element trees (D3). Gemini 3 rules (GE §15): no `temperature`/`topP`/`topK`/
 * `candidateCount`, `thinkingLevel` and never `thinkingBudget`, the last turn is always the
 * user's. The key travels in `x-goog-api-key`, never as `?key=` in the URL.
 */
internal object GeminiRequests {
    /** Chat budget (effort table, §7). */
    const val CHAT_THINKING_LEVEL = "low"
    const val CHAT_MAX_OUTPUT_TOKENS = 8_192

    /** Base URL of [auth]: the debug override (QA, debug builds only) or the catalog's. */
    fun baseUrl(auth: ProviderAuth): String =
        (auth.baseUrl ?: ProviderCatalog.GEMINI.baseUrl ?: error("no base URL")).trimEnd('/')

    /** `POST {base}/models/{model}:streamGenerateContent?alt=sse` (each event one `GenerateContentResponse`). */
    fun streamUrl(auth: ProviderAuth, model: String): String =
        modelUrl(auth, model, ":streamGenerateContent").newBuilder().addQueryParameter("alt", "sse").build().toString()

    /** `GET {base}/models/{model}`: the model's info, the key check without tokens (GE §13). */
    fun modelInfoUrl(auth: ProviderAuth, model: String): String = modelUrl(auth, model, "").toString()

    /** `x-goog-api-key: <key>`; never logged. */
    fun headers(auth: ProviderAuth): Map<String, String> {
        val key = auth.apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: throw AiException(AiError.InvalidKey)
        return mapOf("x-goog-api-key" to key)
    }

    /**
     * "Frag nach": the system prompt as `systemInstruction`, the article or brief context as the
     * first part of the first user turn (byte-stable and first, so Gemini's implicit cache can
     * hit, GE §3.4), the history as plain text (D12) and Google Search only when [search].
     */
    fun chat(request: ChatTurnRequest, search: Boolean): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", request.systemPrompt) } }
        }
        putJsonArray("contents") {
            turns(request.context, request.history).forEach { turn ->
                addJsonObject {
                    put("role", turn.role)
                    putJsonArray("parts") { turn.texts.forEach { text -> addJsonObject { put("text", text) } } }
                }
            }
        }
        if (search) putJsonArray("tools") { addJsonObject { putJsonObject("googleSearch") {} } }
        putJsonObject("generationConfig") {
            putJsonObject("thinkingConfig") { put("thinkingLevel", CHAT_THINKING_LEVEL) }
            put("maxOutputTokens", CHAT_MAX_OUTPUT_TOKENS)
        }
    }

    /** One `Content` of the conversation: `user` or `model` and its text parts. */
    internal data class Turn(val role: String, val texts: List<String>)

    /**
     * The conversation as Gemini wants it: [context] leads the first user turn, consecutive
     * messages of one side are merged into one turn (one part each), empty messages are left
     * out, and a trailing answer is dropped — the last turn must be the user's (a "prefilled"
     * model turn is a 400 on Gemini 3.6+, GE §4).
     */
    fun turns(context: String, history: List<ApiMessage>): List<Turn> {
        val turns = mutableListOf<Turn>()
        val texts = mutableListOf<String>()
        var role: String? = null
        fun close() {
            val r = role ?: return
            if (texts.isNotEmpty()) turns += Turn(r, texts.toList())
            texts.clear()
        }
        if (context.isNotBlank()) {
            role = USER
            texts += context
        }
        history.forEach { message ->
            if (message.text.isBlank()) return@forEach
            val next = if (message.fromUser) USER else MODEL
            if (next != role) {
                close()
                role = next
            }
            texts += message.text
        }
        close()
        while (turns.lastOrNull()?.role == MODEL) turns.removeAt(turns.lastIndex)
        return turns
    }

    /**
     * `{base}/models/{model}{suffix}`; a pasted `models/` prefix is dropped and the id is encoded
     * as one path segment (free-text model ids may hold anything).
     */
    private fun modelUrl(auth: ProviderAuth, model: String, suffix: String): HttpUrl {
        val base = baseUrl(auth).toHttpUrlOrNull()
            ?: throw AiException(AiError.Unknown("Die Adresse der Gemini API ist ungültig."))
        val id = model.trim().removePrefix("models/").ifEmpty { ProviderCatalog.GEMINI.defaultModel }
        return base.newBuilder().addPathSegment("models").addPathSegment(id + suffix).build()
    }

    private const val USER = "user"
    private const val MODEL = "model"
}

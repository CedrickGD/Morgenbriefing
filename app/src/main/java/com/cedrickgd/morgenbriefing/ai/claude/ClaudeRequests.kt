package com.cedrickgd.morgenbriefing.ai.claude

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebFetchTool20250910
import com.anthropic.models.messages.WebFetchTool20260209
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import com.cedrickgd.morgenbriefing.ai.ApiMessage
import com.cedrickgd.morgenbriefing.ai.LivePublish

/**
 * Request building rules (docs/AI.md "API-Regeln"):
 * - no `thinking` field (Sonnet 5.5 / Opus 5.5 think adaptively; disabling is a 400) — depth
 *   via `output_config.effort`: chat LOW, live research MEDIUM;
 * - no prefill and no forced `tool_choice` (auto + clear instructions);
 * - web search/fetch `_20260209` (dynamic filtering built in, no separate code execution); Haiku
 *   only takes the basic `web_search_20250305` / `web_fetch_20250910` and no fallbacks;
 * - stable prefix (tools → system → context) with an explicit cache breakpoint on the last
 *   system block, plus automatic caching for the growing conversation tail;
 * - server-side refusal fallback opted in by default (`fallbacks: "default"` + beta header).
 */
internal object ClaudeRequests {
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    const val BETA_HEADER = "anthropic-beta"

    const val CHAT_MAX_TOKENS = 8_000L
    const val LIVE_MAX_TOKENS = 32_000L

    /** Models that take `fallbacks: "default"` (Claude API, beta `server-side-fallback-2026-07-01`). */
    fun supportsDefaultFallback(model: String): Boolean =
        model.startsWith("claude-sonnet-5-5") || model.startsWith("claude-opus-5-5")

    /**
     * Models that only take the basic server tools `web_search_20250305` / `web_fetch_20250910`:
     * the `_20260209` variants (dynamic filtering) need Opus 4.6+ / Sonnet 4.6+, and Haiku is
     * neither (D14).
     */
    fun usesBasicServerTools(model: String): Boolean = model.startsWith("claude-haiku")

    /**
     * Chat ("Frag nach"): stable system prompt + context, web search (3) and fetch (4) unless
     * [webSearch] is off.
     */
    fun chat(model: String, systemPrompt: String, context: String, webSearch: Boolean = true): MessageCreateParams.Builder {
        val builder = base(model, CHAT_MAX_TOKENS, OutputConfig.Effort.LOW)
        if (webSearch) builder.addWebTools(model, searchUses = 3, fetchUses = 4, fetchContentTokens = 6_000)
        return builder.systemOfTextBlockParams(
            listOf(
                TextBlockParam.builder().text(systemPrompt).build(),
                TextBlockParam.builder().text(context).cacheControl(ephemeral()).build(),
            ),
        )
    }

    /** Live research: web search (10), web fetch (8) and the strict client tool `publish_articles`. */
    fun live(model: String, systemPrompt: String, topicIds: List<String>): MessageCreateParams.Builder =
        base(model, LIVE_MAX_TOKENS, OutputConfig.Effort.MEDIUM)
            .addWebTools(model, searchUses = 10, fetchUses = 8, fetchContentTokens = 5_000)
            .addTool(publishTool(topicIds))
            .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(systemPrompt).cacheControl(ephemeral()).build()))

    /** The tiny request of a key check: no tools, no fallback, a handful of output tokens. */
    fun keyCheck(model: String): MessageCreateParams =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(16L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage("Antworte nur mit OK.")
            .build()

    fun toParams(history: List<ApiMessage>): List<MessageParam> = history.map { message ->
        MessageParam.builder()
            .role(if (message.fromUser) MessageParam.Role.USER else MessageParam.Role.ASSISTANT)
            .content(message.text)
            .build()
    }

    /** The assistant turn so far, to continue after `pause_turn` or to answer a tool call. */
    fun assistantTurn(message: Message): MessageParam? {
        val content = continuationContent(message.content())
        if (content.isEmpty()) return null
        return MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(content).build()
    }

    /** Ids of the client tool calls (`tool_use`) in an assistant turn built by [assistantTurn]. */
    fun toolUseIds(turn: MessageParam): Set<String> =
        turn.content().blockParams().orElse(emptyList())
            .filter { it.isToolUse() }
            .map { it.asToolUse().id() }
            .toSet()

    fun userText(text: String): MessageParam =
        MessageParam.builder().role(MessageParam.Role.USER).content(text).build()

    fun toolResults(results: List<ToolResultBlockParam>): MessageParam =
        MessageParam.builder()
            .role(MessageParam.Role.USER)
            .contentOfBlockParams(results.map(ContentBlockParam::ofToolResult))
            .build()

    fun toolResult(toolUseId: String, text: String, isError: Boolean): ToolResultBlockParam =
        ToolResultBlockParam.builder()
            .toolUseId(toolUseId)
            .content(text)
            .apply { if (isError) isError(true) }
            .build()

    /**
     * Content blocks of an assistant response as request params, passed back unchanged
     * (thinking blocks included). Unknown blocks such as the `fallback` marker are dropped; after
     * a mid-output refusal fallback, the refused model's thinking, tool calls and unanswered
     * server tool calls before the last marker are dropped too (docs: echoing fallback turns).
     */
    internal fun continuationContent(blocks: List<ContentBlock>): List<ContentBlockParam> {
        val lastFallback = blocks.indexOfLast { it.unknownType() == "fallback" }
        val answered = blocks.mapNotNull { block -> if (block.isServerToolUse()) null else block.toolUseId().orElse(null) }.toSet()
        return blocks.mapIndexedNotNull { index, block ->
            if (!block.isKnown()) return@mapIndexedNotNull null
            if (index < lastFallback) {
                if (block.isThinking() || block.isRedactedThinking() || block.isToolUse()) return@mapIndexedNotNull null
                if (block.isServerToolUse() && block.asServerToolUse().id() !in answered) return@mapIndexedNotNull null
            }
            try {
                block.toParam()
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    // -------------------------------------------------------------------------------------

    private fun base(model: String, maxTokens: Long, effort: OutputConfig.Effort): MessageCreateParams.Builder {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .outputConfig(OutputConfig.builder().effort(effort).build())
            // Automatic caching: the breakpoint follows the end of the conversation.
            .cacheControl(ephemeral())
        if (supportsDefaultFallback(model)) {
            builder.putAdditionalHeader(BETA_HEADER, FALLBACK_BETA)
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder
    }

    private fun ephemeral(): CacheControlEphemeral = CacheControlEphemeral.builder().build()

    /** Web search and fetch in the variant [model] takes ([usesBasicServerTools]). */
    private fun MessageCreateParams.Builder.addWebTools(
        model: String,
        searchUses: Long,
        fetchUses: Long,
        fetchContentTokens: Long,
    ): MessageCreateParams.Builder = if (usesBasicServerTools(model)) {
        addTool(WebSearchTool20250305.builder().maxUses(searchUses).userLocation(leipzig()).build())
            .addTool(WebFetchTool20250910.builder().maxUses(fetchUses).maxContentTokens(fetchContentTokens).build())
    } else {
        addTool(WebSearchTool20260209.builder().maxUses(searchUses).userLocation(leipzig()).build())
            .addTool(WebFetchTool20260209.builder().maxUses(fetchUses).maxContentTokens(fetchContentTokens).build())
    }

    private fun leipzig(): UserLocation =
        UserLocation.builder()
            .type(JsonValue.from("approximate"))
            .city("Leipzig")
            .region("Sachsen")
            .country("DE")
            .timezone("Europe/Berlin")
            .build()

    /**
     * `publish_articles` with `strict: true`. Not `eager_input_streaming`: the strict schema
     * guarantee matters more here than seeing the JSON early (progress shows "Schreibe …").
     * The topic enum is the whole catalogue so the tool definition — and the cached prefix —
     * is the same for every run.
     */
    private fun publishTool(topicIds: List<String>): Tool =
        Tool.builder()
            .name(LivePublish.TOOL_NAME)
            .description(LivePublish.TOOL_DESCRIPTION)
            .strict(true)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("articles", JsonValue.from(LivePublish.articlesProperty(topicIds)))
                            .build(),
                    )
                    .required(listOf("articles"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .build()
}

/** True for the content block types this SDK version knows. */
internal fun ContentBlock.isKnown(): Boolean =
    isText() || isThinking() || isRedactedThinking() || isToolUse() || isServerToolUse() ||
        isWebSearchToolResult() || isWebFetchToolResult() || isCodeExecutionToolResult() ||
        isBashCodeExecutionToolResult() || isTextEditorCodeExecutionToolResult() ||
        isToolSearchToolResult() || isContainerUpload()

/** `type` of a block this SDK version does not model (e.g. `fallback`), else null. */
internal fun ContentBlock.unknownType(): String? {
    if (isKnown()) return null
    val fields = _json().orElse(null)?.asObject()?.orElse(null) ?: return null
    return fields["type"]?.asString()?.orElse(null)
}

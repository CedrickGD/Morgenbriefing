package com.cedrickgd.morgenbriefing.ai.claude

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageDeltaUsage
import com.anthropic.models.messages.RawContentBlockDeltaEvent
import com.anthropic.models.messages.RawContentBlockStartEvent
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.ServerToolUseBlock
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Usage
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.SourceCollector
import com.cedrickgd.morgenbriefing.ai.TokenUsage
import com.cedrickgd.morgenbriefing.ai.ToolActivityListener
import com.cedrickgd.morgenbriefing.ai.UsageSink
import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Callbacks of one streamed response. Called on a background thread, in stream order. */
internal interface StreamCallbacks : ToolActivityListener {
    fun onText(delta: String) {}
    fun onSources(sources: List<SourceRef>) {}
}

/** A client tool call (`tool_use`) with its complete input JSON. */
internal data class ToolCall(val id: String, val name: String, val inputJson: String)

/** Outcome of one streamed request. */
internal class StreamedTurn(
    /** The accumulated response (null if the SDK could not assemble it). */
    val message: Message?,
    val stopReason: StopReason?,
    val refusalCategory: String?,
    val toolCalls: List<ToolCall>,
)

/**
 * Joins the text blocks of a whole answer (across `pause_turn` continuations): text that
 * follows a tool call or thinking starts a new paragraph; text blocks that only split for
 * citations are glued together as they are.
 */
internal class AnswerText {
    private val builder = StringBuilder()
    private var afterNonText = false

    val value: String get() = builder.toString()

    /** Called when a text block starts; returns a separator to emit first, if any. */
    fun startTextBlock(): String? {
        val separator = if (afterNonText && builder.isNotEmpty() && !builder.endsWith("\n")) "\n\n" else null
        afterNonText = false
        separator?.let(builder::append)
        return separator
    }

    fun nonTextBlock() {
        afterNonText = true
    }

    fun append(text: String) {
        builder.append(text)
    }
}

/**
 * Runs one streaming request (`client.messages().createStreaming`): feeds every event to the
 * [MessageAccumulator] and to the callbacks (text deltas, "searching …"/"reading …" activity,
 * sources), records the request's usage with the [UsageSink] — also when it fails or is
 * cancelled half-way — and closes the HTTP stream as soon as the coroutine is cancelled.
 */
internal object ClaudeStreamer {
    private const val TAG = "ClaudeStream"

    suspend fun run(
        client: AnthropicClient,
        params: MessageCreateParams,
        requestModel: String,
        callbacks: StreamCallbacks,
        usage: UsageSink,
        sources: SourceCollector,
        text: AnswerText,
    ): StreamedTurn {
        val tracker = EventTracker(callbacks, sources, text)
        val accumulator = MessageAccumulator.create()
        try {
            withContext(Dispatchers.IO) { consume(client, params, tracker, accumulator) }
        } catch (e: Throwable) {
            val cancelled = e is CancellationException || !currentCoroutineContext().isActive
            withContext(NonCancellable) {
                if (tracker.started) record(tracker, requestModel, usage)
            }
            if (cancelled) throw (e as? CancellationException ?: CancellationException("Abgebrochen").apply { initCause(e) })
            throw ClaudeErrors.map(e)
        }
        record(tracker, requestModel, usage)
        if (!tracker.complete) throw AiException(AiError.Network)
        val message = try {
            accumulator.message()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Response could not be assembled", e)
            null
        }
        return StreamedTurn(message, tracker.stopReason, tracker.refusalCategory, tracker.toolCalls.toList())
    }

    private suspend fun consume(
        client: AnthropicClient,
        params: MessageCreateParams,
        tracker: EventTracker,
        accumulator: MessageAccumulator,
    ) {
        // Interruptible: cancelling while the request connects aborts the blocking call.
        val response: StreamResponse<RawMessageStreamEvent> = runInterruptible { client.messages().createStreaming(params) }
        coroutineScope {
            // Reading the stream blocks this thread; a watcher on another IO thread closes the
            // HTTP response the moment the coroutine is cancelled, which ends the blocking read.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    closeQuietly(response)
                }
            }
            try {
                response.stream().use { stream ->
                    val events = stream.iterator()
                    while (events.hasNext()) {
                        val event = events.next()
                        ensureActive()
                        tracker.onEvent(event)
                        try {
                            accumulator.accumulate(event)
                        } catch (e: RuntimeException) {
                            // An event shape this SDK version does not know; the tracker has what we need.
                            Log.w(TAG, "Event could not be accumulated", e)
                        }
                    }
                }
            } finally {
                watcher.cancel()
                closeQuietly(response)
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun record(tracker: EventTracker, requestModel: String, usage: UsageSink) {
        tracker.billed(requestModel).forEach { (model, tokens) -> usage.record(model, tokens) }
    }

    private fun closeQuietly(response: StreamResponse<*>) {
        try {
            response.close()
        } catch (_: Exception) {
            // Already closed.
        }
    }
}

/** Reads the raw stream events: text, tool activity, sources, stop reason and usage. */
private class EventTracker(
    private val callbacks: StreamCallbacks,
    private val sources: SourceCollector,
    private val text: AnswerText,
) {
    var started = false
        private set
    var complete = false
        private set
    var stopReason: StopReason? = null
        private set
    var refusalCategory: String? = null
        private set
    val toolCalls = ArrayList<ToolCall>()

    private var model: String? = null
    private var startUsage: Usage? = null
    private var deltaUsage: MessageDeltaUsage? = null
    private var streamedChars = 0L
    private val blocks = HashMap<Long, Block>()

    private sealed interface Block {
        data object Text : Block
        data object Other : Block
        class ServerTool(val name: String, val initialInput: JsonValue?) : Block {
            val json = StringBuilder()
        }
        class ClientTool(val id: String, val name: String) : Block {
            val json = StringBuilder()
        }
    }

    fun onEvent(event: RawMessageStreamEvent) {
        event.messageStart().ifPresent { start ->
            started = true
            val message = start.message()
            model = runCatching { message.model().asString() }.getOrNull()
            startUsage = runCatching { message.usage() }.getOrNull()
        }
        event.contentBlockStart().ifPresent(::onBlockStart)
        event.contentBlockDelta().ifPresent(::onDelta)
        event.contentBlockStop().ifPresent { onBlockStop(it.index()) }
        event.messageDelta().ifPresent { delta ->
            val d = delta.delta()
            d.stopReason().ifPresent { stopReason = it }
            d.stopDetails().ifPresent { details ->
                refusalCategory = details.category().map { it.asString() }.orElse(null)
            }
            deltaUsage = runCatching { delta.usage() }.getOrNull()
        }
        event.messageStop().ifPresent { complete = true }
    }

    private fun onBlockStart(start: RawContentBlockStartEvent) {
        val index = start.index()
        val block = start.contentBlock()
        when {
            block.isText() -> {
                blocks[index] = Block.Text
                text.startTextBlock()?.let(callbacks::onText)
                val initial = block.asText().text()
                if (initial.isNotEmpty()) emitText(initial)
            }
            block.isServerToolUse() -> {
                text.nonTextBlock()
                val tool = block.asServerToolUse()
                blocks[index] = Block.ServerTool(runCatching { tool.name().asString() }.getOrDefault(""), tool._input())
            }
            block.isToolUse() -> {
                text.nonTextBlock()
                val tool = block.asToolUse()
                blocks[index] = Block.ClientTool(tool.id(), tool.name())
                callbacks.onToolCall(tool.name())
            }
            block.isWebSearchToolResult() -> {
                text.nonTextBlock()
                blocks[index] = Block.Other
                runCatching {
                    block.asWebSearchToolResult().content().resultBlocks().ifPresent { results ->
                        var changed = false
                        results.forEach { r -> changed = sources.addSearched(runCatching { r.url() }.getOrNull(), runCatching { r.title() }.getOrNull()) || changed }
                        if (changed) callbacks.onSources(sources.ranked())
                    }
                }
            }
            block.isWebFetchToolResult() -> {
                text.nonTextBlock()
                blocks[index] = Block.Other
                runCatching {
                    block.asWebFetchToolResult().content().webFetchBlock().ifPresent { page ->
                        val title = runCatching { page.content().title().orElse(null) }.getOrNull()
                        if (sources.addFetched(page.url(), title)) callbacks.onSources(sources.ranked())
                    }
                }
            }
            block.isThinking() || block.isRedactedThinking() || block.isCodeExecutionToolResult() ||
                block.isBashCodeExecutionToolResult() || block.isTextEditorCodeExecutionToolResult() -> {
                text.nonTextBlock()
                blocks[index] = Block.Other
            }
            // Unknown types (e.g. the `fallback` marker) are neutral: the text simply continues.
            else -> blocks[index] = Block.Other
        }
    }

    private fun onDelta(event: RawContentBlockDeltaEvent) {
        val delta = event.delta()
        val block = blocks[event.index()]
        delta.text().ifPresent { emitText(it.text()) }
        delta.inputJson().ifPresent { json ->
            val part = json.partialJson()
            streamedChars += part.length
            when (block) {
                is Block.ServerTool -> block.json.append(part)
                is Block.ClientTool -> block.json.append(part)
                else -> Unit
            }
        }
        delta.thinking().ifPresent { streamedChars += it.thinking().length }
        delta.citations().ifPresent { citations ->
            runCatching {
                citations.citation().webSearchResultLocation().ifPresent { location ->
                    if (sources.addCited(location.url(), location.title().orElse(null))) callbacks.onSources(sources.ranked())
                }
            }
        }
    }

    private fun onBlockStop(index: Long) {
        when (val block = blocks[index]) {
            is Block.ServerTool -> {
                val input = parseObject(block.json.toString()) ?: block.initialInput?.let(::jsonValueObject)
                when (block.name) {
                    ServerToolUseBlock.Name.WEB_SEARCH.asString() -> input?.string("query")?.let(callbacks::onSearch)
                    ServerToolUseBlock.Name.WEB_FETCH.asString() -> input?.string("url")?.let(callbacks::onFetch)
                    else -> Unit
                }
            }
            is Block.ClientTool -> toolCalls += ToolCall(block.id, block.name, block.json.toString().ifBlank { "{}" })
            else -> Unit
        }
    }

    private fun emitText(delta: String) {
        if (delta.isEmpty()) return
        streamedChars += delta.length
        text.append(delta)
        callbacks.onText(delta)
    }

    /**
     * What this request costs, per model that ran it. With the server-side refusal fallback,
     * `usage.iterations` lists every attempt (the top-level numbers only cover the attempt that
     * answered); without it the top-level usage is the whole request. A request that broke off
     * before `message_delta` is estimated from the streamed characters (≈ 3 per token).
     */
    fun billed(requestModel: String): List<Pair<String, TokenUsage>> {
        val responseModel = model ?: requestModel
        val start = startUsage
        val delta = deltaUsage
        val server = delta?.serverToolUse()?.orElse(null) ?: start?.let { runCatching { it.serverToolUse().orElse(null) }.getOrNull() }
        val searches = server?.let { runCatching { it.webSearchRequests() }.getOrDefault(0L) } ?: 0L
        val fetches = server?.let { runCatching { it.webFetchRequests() }.getOrDefault(0L) } ?: 0L

        val iterations = iterations(delta?._additionalProperties()?.get("iterations"))
            ?: iterations(start?._additionalProperties()?.get("iterations"))
        if (!iterations.isNullOrEmpty()) {
            return iterations.mapIndexed { i, (iterModel, tokens) ->
                val withTools = if (i == iterations.lastIndex) tokens.copy(webSearches = searches, webFetches = fetches) else tokens
                (iterModel ?: responseModel) to withTools
            }
        }

        // message_delta carries the final (cumulative) count; without it, estimate.
        val output = delta?.let { runCatching { it.outputTokens() }.getOrNull() }
            ?: maxOf(start?.let { runCatching { it.outputTokens() }.getOrNull() } ?: 0L, streamedChars / 3)
        val usage = TokenUsage(
            inputTokens = delta?.inputTokens()?.orElse(null) ?: start?.let { runCatching { it.inputTokens() }.getOrNull() } ?: 0L,
            outputTokens = output,
            cacheWriteTokens = delta?.cacheCreationInputTokens()?.orElse(null)
                ?: start?.cacheCreationInputTokens()?.orElse(null) ?: 0L,
            cacheReadTokens = delta?.cacheReadInputTokens()?.orElse(null)
                ?: start?.cacheReadInputTokens()?.orElse(null) ?: 0L,
            webSearches = searches,
            webFetches = fetches,
        )
        return listOf(responseModel to usage)
    }

    private fun iterations(value: JsonValue?): List<Pair<String?, TokenUsage>>? {
        val entries = value?.asArray()?.orElse(null) ?: return null
        return entries.mapNotNull { entry ->
            val fields = entry.asObject().orElse(null) ?: return@mapNotNull null
            fun long(name: String): Long = fields[name]?.asNumber()?.orElse(null)?.toLong() ?: 0L
            val iterModel = fields["model"]?.asString()?.orElse(null)
            iterModel to TokenUsage(
                inputTokens = long("input_tokens"),
                outputTokens = long("output_tokens"),
                cacheWriteTokens = long("cache_creation_input_tokens"),
                cacheReadTokens = long("cache_read_input_tokens"),
            )
        }
    }

    private fun parseObject(json: String): JsonObject? {
        if (json.isBlank()) return null
        return try {
            LENIENT.parseToJsonElement(json) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonValueObject(value: JsonValue): JsonObject? {
        val fields = value.asObject().orElse(null) ?: return null
        val strings = fields.mapNotNull { (k, v) -> v.asString().orElse(null)?.let { k to JsonPrimitive(it) } }.toMap()
        return if (strings.isEmpty()) null else JsonObject(strings)
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val LENIENT = Json { isLenient = true }
    }
}

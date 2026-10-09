package com.cedrickgd.morgenbriefing.ai.http

import okio.BufferedSource

/**
 * Reads Server-Sent Events from [source], one reader for every provider family
 * (docs/AI-PROVIDERS.md §6):
 * * lines via [BufferedSource.readUtf8Line] (`\n` and `\r\n`);
 * * `:` comment lines are ignored (`: OPENROUTER PROCESSING`, `: keep-alive`);
 * * `event:` sets the name, `data:` lines are joined with `\n` (one space after the colon is
 *   dropped, as the SSE spec says); `id:`, `retry:` and unknown fields are ignored;
 * * a blank line dispatches the event (only when it had `data:`); end of input dispatches a
 *   pending one;
 * * a `data` of exactly `[DONE]` ends the stream ([done] is then true).
 *
 * Blocking: call it on an IO thread.
 */
internal class SseReader(private val source: BufferedSource) {
    /** The stream ended with `data: [DONE]`. */
    var done: Boolean = false
        private set

    private var finished = false
    private var eventName: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** The next event, or null at the end of the stream (EOF or `[DONE]`). */
    fun next(): SseEvent? {
        if (finished) return null
        while (true) {
            val line = source.readUtf8Line()
            if (line == null) {
                finished = true
                return dispatch()
            }
            if (line.isEmpty()) {
                dispatch()?.let { return it }
                if (finished) return null
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val value = when {
                colon < 0 -> ""
                colon + 1 < line.length && line[colon + 1] == ' ' -> line.substring(colon + 2)
                else -> line.substring(colon + 1)
            }
            when (field) {
                "event" -> eventName = value
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
                else -> Unit // id, retry, unknown fields
            }
        }
    }

    /** The pending event, if it had data; resets the buffer either way. */
    private fun dispatch(): SseEvent? {
        val name = eventName
        val payload = data.toString()
        val had = hasData
        eventName = null
        data.setLength(0)
        hasData = false
        if (!had) return null
        if (payload == DONE) {
            done = true
            finished = true
            return null
        }
        return SseEvent(name?.takeIf { it.isNotEmpty() }, payload)
    }

    private companion object {
        const val DONE = "[DONE]"
    }
}

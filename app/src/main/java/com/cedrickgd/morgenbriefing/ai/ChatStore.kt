package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.data.model.SourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** One persisted conversation (`store/chat/<date>__<article>.json`). */
@Serializable
internal data class StoredChat(
    val version: Int = 1,
    val date: String,
    val articleKey: String? = null,
    val title: String = "",
    /** The exact context text the conversation was started with (keeps the prompt cache stable). */
    val context: String = "",
    val messages: List<StoredChatMessage> = emptyList(),
)

@Serializable
internal data class StoredChatMessage(
    val id: String,
    /** "user" or "assistant". */
    val role: String,
    val text: String,
    /** User turns: the exact text sent to the API (question + date note). */
    val apiText: String? = null,
    val sources: List<SourceRef> = emptyList(),
    /** "done" or "failed" (streaming answers are stored as what was received). */
    val status: String = STATUS_DONE,
    val error: StoredAiError? = null,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
    }
}

@Serializable
internal data class StoredAiError(val kind: String, val detail: String? = null) {
    fun toAiError(): AiError = when (kind) {
        "invalid_key" -> AiError.InvalidKey
        "rate_limited" -> AiError.RateLimited
        "overloaded" -> AiError.Overloaded
        "network" -> AiError.Network
        "refusal" -> AiError.Refusal(detail)
        "truncated" -> AiError.Truncated
        else -> AiError.Unknown(detail)
    }

    companion object {
        fun of(error: AiError): StoredAiError = when (error) {
            AiError.InvalidKey -> StoredAiError("invalid_key")
            AiError.RateLimited -> StoredAiError("rate_limited")
            AiError.Overloaded -> StoredAiError("overloaded")
            AiError.Network -> StoredAiError("network")
            is AiError.Refusal -> StoredAiError("refusal", error.category)
            AiError.Truncated -> StoredAiError("truncated")
            is AiError.Unknown -> StoredAiError("unknown", error.message)
        }
    }
}

/**
 * Chat histories as JSON files under [dir], one per context (an article of a day, or the whole
 * brief of a day). Reads never throw; a corrupt file is dropped. Files of days older than
 * [maxAgeDays] are pruned when a conversation is saved.
 */
internal class ChatStore(
    private val dir: File,
    private val json: Json,
    private val maxAgeDays: Long = 400,
) {
    private val lock = Mutex()

    suspend fun load(context: ChatContext): StoredChat? = withContext(Dispatchers.IO) {
        val file = fileFor(context)
        val text = AiFiles.readOrNull(file) ?: return@withContext null
        try {
            json.decodeFromString(StoredChat.serializer(), text)
        } catch (_: SerializationException) {
            lock.withLock { file.delete() }
            null
        } catch (_: IllegalArgumentException) {
            lock.withLock { file.delete() }
            null
        }
    }

    /** @throws IOException when the disk refuses the write. */
    suspend fun save(chat: StoredChat) = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(StoredChat.serializer(), chat)
        lock.withLock {
            AiFiles.writeAtomically(fileFor(ChatContext(chat.date, chat.articleKey)), payload)
            prune()
        }
    }

    suspend fun delete(context: ChatContext) = withContext(Dispatchers.IO) {
        lock.withLock { fileFor(context).delete() }
    }

    internal fun fileFor(context: ChatContext): File {
        val day = context.date.trim().takeIf { DATE.matches(it) } ?: AiFiles.safeName(context.date)
        val subject = context.articleKey?.takeIf { it.isNotBlank() }?.let(AiFiles::safeName) ?: BRIEF_FILE_PART
        return File(dir, "${day}__$subject.json")
    }

    private fun prune() {
        val oldest = LocalDate.now().minusDays(maxAgeDays)
        dir.listFiles()?.forEach { file ->
            val day = file.name.substringBefore("__", missingDelimiterValue = "")
            val date = try {
                LocalDate.parse(day)
            } catch (_: DateTimeParseException) {
                null
            }
            if (date != null && date.isBefore(oldest)) file.delete()
        }
    }

    private companion object {
        const val BRIEF_FILE_PART = "briefing"
        val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}

package com.cedrickgd.morgenbriefing.ai.claude

import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.ErrorType
import com.cedrickgd.morgenbriefing.ai.AiBlockedException
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import java.io.IOException

/** Maps SDK exceptions to [AiError]s the UI can explain (docs/AI.md "Fehler", docs/AI-PROVIDERS.md §8). */
internal object ClaudeErrors {

    /**
     * [AiException] for every failure; an [AiBlockedException] is passed through unchanged.
     * [model] is the requested model, named when the API does not know it.
     */
    fun map(e: Throwable, model: String? = null): Exception = when (e) {
        is AiException -> e
        is AiBlockedException -> e
        is UnauthorizedException -> AiException(AiError.InvalidKey, e)
        // Before the generic branches: a workspace problem must not read as a broken key or model.
        is AnthropicServiceException -> AiException(classify(e.errorType().orElse(null), e.statusCode(), textOf(e), model, e), e)
        is AnthropicIoException, is AnthropicRetryableException, is IOException -> AiException(AiError.Network, e)
        is AnthropicInvalidDataException -> AiException(AiError.Unknown("Claude hat eine unerwartete Antwort geschickt."), e)
        else -> AiException(AiError.Unknown(null), e)
    }

    /**
     * What a failed key check says about the key: the workspace problems first (an
     * identity-linked key without workspace id is answered with 400, which is no proof of a
     * working key), then billing (the key works, the credit is gone).
     */
    internal enum class KeyVerdict { WORKSPACE_REQUIRED, WORKSPACE_INVALID, NO_CREDIT, OTHER }

    internal fun keyVerdict(e: AnthropicServiceException): KeyVerdict {
        val text = textOf(e)
        return when {
            workspaceRequired(text) -> KeyVerdict.WORKSPACE_REQUIRED
            workspaceInvalid(e.statusCode(), text) -> KeyVerdict.WORKSPACE_INVALID
            billing(e.errorType().orElse(null), e.statusCode(), text) -> KeyVerdict.NO_CREDIT
            else -> KeyVerdict.OTHER
        }
    }

    private fun classify(type: ErrorType?, status: Int, text: String, model: String?, e: AnthropicServiceException): AiError = when {
        workspaceRequired(text) -> AiError.WorkspaceRequired
        workspaceInvalid(status, text) -> AiError.WorkspaceNotFound
        e is RateLimitException || type == ErrorType.RATE_LIMIT_ERROR || status == 429 -> AiError.RateLimited
        type == ErrorType.AUTHENTICATION_ERROR || status == 401 -> AiError.InvalidKey
        e is NotFoundException || type == ErrorType.NOT_FOUND_ERROR || status == 404 -> AiError.ModelUnavailable(model)
        billing(type, status, text) -> AiError.NoCredit
        e is PermissionDeniedException || type == ErrorType.PERMISSION_ERROR || status == 403 ->
            AiError.Unknown("Dein Claude-Key hat keinen Zugriff auf dieses Modell. Prüf die Rechte in der Claude-Konsole.")
        e is InternalServerException || type == ErrorType.OVERLOADED_ERROR || status == 529 -> AiError.Overloaded
        type == ErrorType.API_ERROR || type == ErrorType.TIMEOUT_ERROR || status in 500..599 -> AiError.Overloaded
        status == 413 -> AiError.Unknown("Die Anfrage war zu groß für Claude.")
        e is BadRequestException || status == 400 -> AiError.Unknown("Claude hat die Anfrage abgelehnt (HTTP 400).")
        else -> AiError.Unknown(if (status > 0) "Claude hat die Anfrage abgelehnt (HTTP $status)." else null)
    }

    /** 400 "anthropic-workspace-id is required when authenticating with an identity-linked API key …". */
    private fun workspaceRequired(text: String): Boolean = text.contains("anthropic-workspace-id is required", ignoreCase = true)

    /** 400 "… must be a valid workspace ID" or 404 "Workspace `…` not found". */
    private fun workspaceInvalid(status: Int, text: String): Boolean =
        text.contains("must be a valid workspace ID", ignoreCase = true) ||
            (status == 404 || status <= 0) && WORKSPACE_NOT_FOUND.containsMatchIn(text)

    private fun billing(type: ErrorType?, status: Int, text: String): Boolean =
        type == ErrorType.BILLING_ERROR || status == 402 || text.contains("credit balance", ignoreCase = true)

    /** The exception message plus the raw error body: the API's own words, for the checks above. */
    private fun textOf(e: AnthropicServiceException): String {
        val body = try {
            e.body().toString()
        } catch (_: RuntimeException) {
            ""
        }
        return "${e.message.orEmpty()} $body"
    }

    private val WORKSPACE_NOT_FOUND = Regex("""Workspace\s+\S.*\s+not found""", RegexOption.IGNORE_CASE)
}

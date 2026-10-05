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
import com.anthropic.errors.SseException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.ErrorType
import com.cedrickgd.morgenbriefing.ai.AiBlockedException
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import java.io.IOException

/** Maps SDK exceptions to [AiError]s the UI can explain (docs/AI.md "Fehler"). */
internal object ClaudeErrors {

    /** [AiException] for every failure; an [AiBlockedException] is passed through unchanged. */
    fun map(e: Throwable): Exception = when (e) {
        is AiException -> e
        is AiBlockedException -> e
        is UnauthorizedException -> AiException(AiError.InvalidKey, e)
        is PermissionDeniedException -> AiException(
            AiError.Unknown("Dein Claude-Key hat keinen Zugriff auf dieses Modell. Prüf die Rechte in der Anthropic-Konsole."),
            e,
        )
        is RateLimitException -> AiException(AiError.RateLimited, e)
        is NotFoundException -> AiException(AiError.Unknown("Das gewählte Claude-Modell ist für deinen Key nicht verfügbar."), e)
        is InternalServerException -> AiException(AiError.Overloaded, e)
        is SseException -> AiException(byType(e.errorType().orElse(null), e.statusCode(), e.message), e)
        is BadRequestException -> AiException(badRequest(e), e)
        is AnthropicServiceException -> AiException(byType(e.errorType().orElse(null), e.statusCode(), e.message), e)
        is AnthropicIoException, is AnthropicRetryableException, is IOException -> AiException(AiError.Network, e)
        is AnthropicInvalidDataException -> AiException(AiError.Unknown("Claude hat eine unerwartete Antwort geschickt."), e)
        else -> AiException(AiError.Unknown(null), e)
    }

    private fun byType(type: ErrorType?, status: Int, message: String?): AiError = when {
        type == ErrorType.AUTHENTICATION_ERROR || status == 401 -> AiError.InvalidKey
        type == ErrorType.RATE_LIMIT_ERROR || status == 429 -> AiError.RateLimited
        type == ErrorType.OVERLOADED_ERROR || status == 529 -> AiError.Overloaded
        type == ErrorType.API_ERROR || type == ErrorType.TIMEOUT_ERROR || status in 500..599 -> AiError.Overloaded
        type == ErrorType.BILLING_ERROR || status == 402 -> AiError.Unknown(BILLING)
        type == ErrorType.PERMISSION_ERROR || status == 403 ->
            AiError.Unknown("Dein Claude-Key hat keinen Zugriff auf dieses Modell.")
        status == 413 -> AiError.Unknown("Die Anfrage war zu groß für Claude.")
        message?.contains("credit balance", ignoreCase = true) == true -> AiError.Unknown(BILLING)
        else -> AiError.Unknown(if (status > 0) "Claude hat die Anfrage abgelehnt (HTTP $status)." else null)
    }

    private fun badRequest(e: BadRequestException): AiError {
        val message = e.message.orEmpty()
        return when {
            message.contains("credit balance", ignoreCase = true) -> AiError.Unknown(BILLING)
            e.errorType().orElse(null) == ErrorType.BILLING_ERROR -> AiError.Unknown(BILLING)
            else -> AiError.Unknown("Claude hat die Anfrage abgelehnt (HTTP 400).")
        }
    }

    private const val BILLING =
        "Dein Anthropic-Guthaben ist aufgebraucht. Lade es unter console.anthropic.com auf, dann geht es weiter."
}

package com.cedrickgd.morgenbriefing.ai.http

import com.cedrickgd.morgenbriefing.ai.AiBlockedException
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.GeminiProblem
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A failure as the UI explains it, plus whether sending the same request again can help (only
 * ever before the first stream event, see [RetryPolicy]) and how long the server asked to wait.
 */
internal data class MappedError(val error: AiError, val retryable: Boolean = false, val retryAfter: Duration? = null)

/** Classifies the failures of one provider's requests; null = not an API failure (pass it on). */
internal fun interface ErrorMapper {
    fun map(failure: Throwable): MappedError?
}

/**
 * HTTP status, error body, connection problem or mid-stream error → [AiError] for every provider
 * without an SDK (docs/AI-PROVIDERS.md §8.1, §8.2). Error bodies are parsed leniently and only
 * steer the classification: a provider's raw message is never shown.
 */
internal object ProviderErrors {

    /** The mapper of requests with [auth] for [model] (the model is named when it is unknown). */
    fun mapper(auth: ProviderAuth, model: String?): ErrorMapper = ErrorMapper { map(auth.provider, it, model, auth.freeTier) }

    /**
     * Any failure of a request to [provider]. An [AiException] keeps its error, an
     * [AiBlockedException] is no API failure (null), anything unexpected is [AiError.Unknown].
     * [freeTier]: a free Gemini key (a 429 then means the free quota is used up).
     */
    fun map(provider: AiProviderId, failure: Throwable, model: String?, freeTier: Boolean = false): MappedError? = when (failure) {
        is AiException -> MappedError(failure.error)
        is AiBlockedException -> null
        is HttpFailure -> http(provider, failure.code, failure.body, failure.retryAfter, model, freeTier)
        is IOException -> io(provider, failure)
        else -> MappedError(AiError.Unknown(null))
    }

    /** A non-2xx answer (§8.1). */
    fun http(
        provider: AiProviderId,
        code: Int,
        body: String,
        retryAfter: Duration?,
        model: String?,
        freeTier: Boolean = false,
    ): MappedError {
        val e = ApiErrorBody.parse(body)
        specific(provider, code, e, retryAfter, model, freeTier)?.let { return it }
        val brand = brand(provider)
        return when {
            code == 401 -> MappedError(AiError.InvalidKey)
            code == 402 -> MappedError(AiError.NoCredit)
            code == 404 -> MappedError(AiError.ModelUnavailable(model))
            code == 408 -> MappedError(AiError.Overloaded, retryable = true, retryAfter = retryAfter)
            code == 413 -> MappedError(AiError.Unknown("Die Anfrage war zu groß für $brand."))
            code == 429 -> MappedError(AiError.RateLimited, retryable = true, retryAfter = retryAfter)
            code == 529 || code in 500..599 -> MappedError(AiError.Overloaded, retryable = true, retryAfter = retryAfter)
            else -> MappedError(AiError.Unknown("${brand.sentence()} hat die Anfrage abgelehnt (HTTP $code)."))
        }
    }

    /** The columns of §8.1 that differ from the common rows. */
    private fun specific(
        provider: AiProviderId,
        code: Int,
        e: ApiErrorBody,
        retryAfter: Duration?,
        model: String?,
        freeTier: Boolean,
    ): MappedError? = when (provider) {
        AiProviderId.OPENAI -> when {
            code == 400 && e.namesUnknownModel() -> MappedError(AiError.ModelUnavailable(model))
            code == 403 -> MappedError(AiError.Unknown("OpenAI ist in deiner Region nicht verfügbar."))
            code == 429 && e.hasCode("credit_balance_exhausted", "insufficient_quota") -> MappedError(AiError.NoCredit)
            code == 429 && e.hasCode("organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded") ->
                MappedError(AiError.Unknown("Das Ausgabenlimit deines OpenAI-Projekts ist erreicht."))
            else -> null
        }
        AiProviderId.XAI -> when {
            // A wrong key is a 400 {"code":"invalid-argument","error":"Incorrect API key provided. …"}
            // at xAI; only a missing key gets 401 (observed 2026-10-09, OC §4.8).
            code == 400 && e.namesApiKey() -> MappedError(AiError.InvalidKey)
            code == 403 -> MappedError(AiError.Unknown("Kein Zugriff – Team gesperrt oder Guthaben leer? Schau in console.x.ai nach."))
            else -> null
        }
        AiProviderId.GEMINI -> when {
            code == 400 && e.isGeminiKeyInvalid() -> MappedError(AiError.InvalidKey)
            // "Your API key was reported as leaked. Please use another API key." (ai.google.dev troubleshooting)
            e.message.orEmpty().contains("reported as leaked", ignoreCase = true) -> MappedError(AiError.GeminiAccess(GeminiProblem.KEY_LEAKED))
            // The reasons decide, not the status: a blocked API came as 403 and, for a key sent as
            // a bearer token, as 401 (GE §12.4, forum report 2026-10-08).
            e.hasReason(*GEMINI_API_BLOCKED) -> MappedError(AiError.GeminiAccess(GeminiProblem.API_BLOCKED))
            e.reasons.any { it in GEMINI_KEY_RESTRICTED } -> MappedError(AiError.GeminiAccess(GeminiProblem.KEY_RESTRICTED))
            code == 401 && e.hasReason("ACCESS_TOKEN_TYPE_UNSUPPORTED") -> MappedError(AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED))
            code == 400 && e.status == "FAILED_PRECONDITION" -> MappedError(AiError.GeminiAccess(GeminiProblem.FREE_TIER_UNAVAILABLE))
            e.isGeminiModelProblem(code) -> MappedError(AiError.ModelUnavailable(model))
            code == 403 -> MappedError(AiError.Unknown("Der Key darf die Gemini API nicht nutzen."))
            code == 429 && freeTier -> {
                // G3: the free quota (per minute or per day) is used up. Sent again only when
                // Google names a short wait – never hammer a daily quota.
                val wait = e.retryDelay ?: retryAfter
                MappedError(AiError.QuotaExhausted, retryable = wait != null && wait <= FREE_QUOTA_MAX_WAIT, retryAfter = wait)
            }
            code == 429 -> MappedError(AiError.RateLimited, retryable = true, retryAfter = e.retryDelay ?: retryAfter)
            else -> null
        }
        AiProviderId.OPENROUTER -> when {
            // 402 with Retry-After: an upstream's credit check, temporary (OC §9).
            code == 402 && retryAfter != null -> MappedError(AiError.RateLimited, retryable = true, retryAfter = retryAfter)
            code == 403 -> MappedError(AiError.Unknown("OpenRouter hat die Anfrage abgelehnt (Moderation oder Rechte)."))
            else -> null
        }
        AiProviderId.CUSTOM -> when (code) {
            402 -> MappedError(AiError.Unknown("Dein Server hat die Anfrage abgelehnt (HTTP 402)."))
            403 -> MappedError(AiError.InvalidKey)
            // The brand "dein Server" needs the accusative here ("für deinen Server").
            413 -> MappedError(AiError.Unknown("Die Anfrage war zu groß für deinen Server."))
            else -> null
        }
        AiProviderId.ANTHROPIC, AiProviderId.PERPLEXITY, AiProviderId.MISTRAL, AiProviderId.DEEPSEEK -> null
    }

    /**
     * A connection problem before (or while) the response arrived. The own server's failed TLS
     * setup (a self-signed certificate, or no TLS behind an https:// address) is no network
     * trouble: the text says what to change (§7.6).
     */
    fun io(provider: AiProviderId, e: IOException): MappedError = when {
        // Plain http:// to a host outside the network security config's allowlist (D10).
        e is UnknownServiceException -> MappedError(AiError.Unknown(CLEARTEXT_TEXT))
        provider == AiProviderId.CUSTOM && e.isTlsSetupFailure() -> MappedError(AiError.Unknown(CERTIFICATE_TEXT))
        provider == AiProviderId.CUSTOM && e is SSLException -> MappedError(AiError.ServerUnreachable)
        provider == AiProviderId.CUSTOM && e.isUnreachable() -> MappedError(AiError.ServerUnreachable, retryable = true)
        e is SSLException -> MappedError(AiError.Network)
        else -> MappedError(AiError.Network, retryable = true)
    }

    /**
     * A Responses-family stream ended with `response.failed` or `error` (§8.2); [code] is
     * `response.error.code` / `error.code`.
     */
    fun responsesStreamError(provider: AiProviderId, code: String?): AiError = when (code) {
        "server_error" -> AiError.Overloaded
        "rate_limit_exceeded" -> AiError.RateLimited
        "invalid_prompt", "bio_policy", "misalignment_policy_violation" -> AiError.Refusal(code)
        "insufficient_quota", "credit_balance_exhausted" -> AiError.NoCredit
        "invalid_api_key" -> AiError.InvalidKey
        else -> AiError.Unknown("${brand(provider).sentence()} hat die Antwort abgebrochen.")
    }

    /**
     * A chunk with a top-level `error` inside a 200 stream (Gemini's google.rpc object, OpenRouter's
     * `{code, message}`): classified like the HTTP status it names (§8.2), never retried.
     */
    fun streamError(provider: AiProviderId, chunk: JsonObject, model: String?, freeTier: Boolean = false): AiError {
        val error = chunk["error"] as? JsonObject
        val code = (error?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            ?: (error?.get("status") as? JsonPrimitive)?.content?.let(::rpcStatusCode)
        return if (code == null) {
            AiError.Unknown("${brand(provider).sentence()} hat die Antwort abgebrochen.")
        } else {
            http(provider, code, chunk.toString(), null, model, freeTier).error
        }
    }

    /**
     * The TLS handshake failed: an untrusted (self-signed) certificate, a host name the
     * certificate does not cover, or a server that answered the handshake with plain http.
     * Other [SSLException]s (a read error mid-stream) are connection problems.
     */
    private fun IOException.isTlsSetupFailure(): Boolean =
        this is SSLHandshakeException || this is SSLPeerUnverifiedException || this is SSLProtocolException

    private fun IOException.isUnreachable(): Boolean =
        this is ConnectException || this is UnknownHostException || this is NoRouteToHostException ||
            (this is SocketTimeoutException && message.orEmpty().contains("connect", ignoreCase = true))

    /** google.rpc status names → HTTP codes (for stream errors that only carry the status). */
    private fun rpcStatusCode(status: String): Int? = when (status) {
        "INVALID_ARGUMENT", "FAILED_PRECONDITION", "OUT_OF_RANGE" -> 400
        "UNAUTHENTICATED" -> 401
        "PERMISSION_DENIED" -> 403
        "NOT_FOUND" -> 404
        "RESOURCE_EXHAUSTED" -> 429
        "INTERNAL", "UNKNOWN", "DATA_LOSS" -> 500
        "UNAVAILABLE" -> 503
        "DEADLINE_EXCEEDED" -> 504
        else -> null
    }

    private fun brand(provider: AiProviderId): String = ProviderCatalog.spec(provider).brand

    private fun String.sentence(): String = replaceFirstChar { it.uppercaseChar() }

    /**
     * A Gemini answer that refuses the model itself, not the key, the project or the request
     * (GE §12.5): a 400 in one of Google's phrasings about the model's identity ([MODEL_400]), a
     * 403 that denies access to the model ([MODEL_403]), or a 429 for a quota with limit 0 that is
     * not the search's (the free tier does not offer this model to the project). A 404 is a model
     * problem on every provider (the common rows).
     *
     * Any other 400 `INVALID_ARGUMENT` is about the request, even when it mentions the model
     * ("thinking_level is not supported by this model"): every model of the chain would refuse it
     * the same way, so it stays [AiError.Unknown] with its Details instead of a silent model swap.
     */
    private fun ApiErrorBody.isGeminiModelProblem(code: Int): Boolean = when (code) {
        400 -> status != "FAILED_PRECONDITION" && !hasAccountReason() && MODEL_400.any { message.orEmpty().contains(it, ignoreCase = true) }
        403 -> !hasAccountReason() && MODEL_403.containsMatchIn(message.orEmpty())
        429 -> hasZeroQuota && !isSearchQuota
        else -> false
    }

    private fun ApiErrorBody.hasAccountReason(): Boolean = reasons.any { r -> ACCOUNT_REASONS.any(r::startsWith) }

    /** ErrorInfo reasons about the key, the project or its billing: never a model problem. */
    private val ACCOUNT_REASONS = listOf("API_KEY", "CONSUMER", "SERVICE", "BILLING", "ACCESS_TOKEN")

    /**
     * How Google's 400 names a model that does not exist (for this API version or method) or a
     * model id it cannot read: "models/… is not found for API version v1beta, or is not supported
     * for generateContent", "* GenerateContentRequest.model: unexpected model name format".
     */
    private val MODEL_400 = listOf("is not found for API version", "is not supported for generateContent", "unexpected model name format")

    /** A 403 whose subject is the model: "Your project does not have access to model …", "no access to this model". */
    private val MODEL_403 =
        Regex("""\b(?:(?:do|does)\s+not\s+have|(?:doesn|don)['’]t\s+have|no)\s+access\s+to\s+(?:the\s+|this\s+)?model\b""", RegexOption.IGNORE_CASE)

    /** ErrorInfo reasons of a Gemini API that is blocked or switched off for the key's project. */
    private val GEMINI_API_BLOCKED = arrayOf("API_KEY_SERVICE_BLOCKED", "SERVICE_DISABLED")

    /** ErrorInfo reasons of a key with application restrictions that do not fit the request. */
    private val GEMINI_KEY_RESTRICTED = setOf(
        "API_KEY_ANDROID_APP_BLOCKED",
        "API_KEY_IOS_APP_BLOCKED",
        "API_KEY_HTTP_REFERRER_BLOCKED",
        "API_KEY_IP_ADDRESS_BLOCKED",
    )

    /** The longest wait a free Gemini quota is retried for; anything longer is reported at once. */
    private val FREE_QUOTA_MAX_WAIT = 20.seconds

    const val CLEARTEXT_TEXT =
        "http:// geht nur mit Namen wie nas.fritz.box oder nas.local – nicht mit einer IP-Adresse. Nimm den Namen oder https://."

    /** The own server's HTTPS is not trusted (the app trusts only public CAs and ISRG, §7.6). */
    const val CERTIFICATE_TEXT =
        "Dein Server antwortet, aber nicht mit einem HTTPS-Zertifikat, dem die App traut (selbst signiert – oder kann er nur http?). " +
            "Nimm in den Einstellungen http:// mit einem Namen wie nas.fritz.box oder ein Let's-Encrypt-Zertifikat."
}

/** One google.rpc `QuotaFailure.violations[]` entry: which quota ran out, and its limit. */
internal data class QuotaViolation(
    /** e.g. `generativelanguage.googleapis.com/generate_content_free_tier_requests`. */
    val metric: String?,
    /** e.g. `GenerateRequestsPerDayPerProjectPerModel-FreeTier`. */
    val id: String?,
    /** The limit as text, e.g. "250"; "0" = the quota does not exist for this project and model. */
    val value: String?,
    /** `quotaDimensions`, e.g. `model` → `gemini-3.8-flash`. */
    val dimensions: Map<String, String> = emptyMap(),
)

/**
 * The fields of an error body that steer the classification, from every shape the providers
 * use: `{"error": {message, type, code, param}}` (OpenAI style), a flat
 * `{"object": "error", message, type, code}` (Mistral), google.rpc
 * `{"error": {code, status, message, details: [ErrorInfo, Help, QuotaFailure, RetryInfo, …]}}`
 * — also wrapped in an array (GE §12.1) — and `{"error": "text"}`. Unknown or broken bodies
 * give empty fields. Messages, metadata and help links are only ever used to classify: they are
 * never shown and never part of a diagnostic ([ErrorDiagnostics]).
 */
internal class ApiErrorBody(
    val message: String? = null,
    val type: String? = null,
    /** `error.code` as text (a string or a number, depending on the provider). */
    val code: String? = null,
    val param: String? = null,
    /** google.rpc `status`, e.g. `RESOURCE_EXHAUSTED`. */
    val status: String? = null,
    /** google.rpc `ErrorInfo.reason`s, e.g. `API_KEY_INVALID`. */
    val reasons: List<String> = emptyList(),
    /** google.rpc `RetryInfo.retryDelay`. */
    val retryDelay: Duration? = null,
    /** google.rpc `ErrorInfo.domain` of the first ErrorInfo, e.g. `googleapis.com`. */
    val domain: String? = null,
    /** google.rpc `ErrorInfo.metadata` of the first ErrorInfo (e.g. `service`, `methodName`, `consumer`). */
    val metadata: Map<String, String> = emptyMap(),
    /** google.rpc `Help.links[].url`. */
    val helpLinks: List<String> = emptyList(),
    /** google.rpc `QuotaFailure.violations`. */
    val quotaViolations: List<QuotaViolation> = emptyList(),
) {
    fun hasCode(vararg codes: String): Boolean = codes.any { it == code || it == type }

    /** One of [names] is among the google.rpc `ErrorInfo.reason`s. */
    fun hasReason(vararg names: String): Boolean = names.any { it in reasons }

    /** `error.code` as an HTTP status (google.rpc bodies name it), or null. */
    val httpCode: Int? get() = code?.toIntOrNull()

    /**
     * A quota whose limit is 0 ran out: the project has no such quota at all — on Gemini's free
     * tier the sign that the model (or the search) is not offered to it, not that it was used up.
     */
    val hasZeroQuota: Boolean
        get() = quotaViolations.any { it.value?.trim() == "0" } || ZERO_LIMIT.containsMatchIn(message.orEmpty())

    /** The quota violation that names Google Search or grounding ([hasZeroQuota] for the search, not for the model). */
    val isSearchQuota: Boolean
        get() = quotaViolations.any { v -> SEARCH_QUOTA.any { v.metric.orEmpty().contains(it, ignoreCase = true) || v.id.orEmpty().contains(it, ignoreCase = true) } }

    /** OpenAI 400: "The model `…` does not exist or you do not have access to it." */
    fun namesUnknownModel(): Boolean {
        val text = message.orEmpty()
        return code == "model_not_found" ||
            ((param == "model" || text.contains("model", ignoreCase = true)) && text.contains("does not exist", ignoreCase = true))
    }

    /** Gemini 400 `INVALID_ARGUMENT` with reason `API_KEY_INVALID` ("API key not valid"). */
    fun isGeminiKeyInvalid(): Boolean =
        "API_KEY_INVALID" in reasons || message.orEmpty().contains("API key not valid", ignoreCase = true)

    /** The message is about the API key, e.g. xAI's 400 "Incorrect API key provided. …". */
    fun namesApiKey(): Boolean = message.orEmpty().contains("API key", ignoreCase = true)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** "limit: 0" in a google.rpc quota message (the QuotaFailure detail may be missing). */
        private val ZERO_LIMIT = Regex("""\blimit:\s*0\b(?![.,]\d)""")
        private val SEARCH_QUOTA = listOf("search", "grounding")

        fun parse(body: String): ApiErrorBody {
            if (body.isBlank()) return ApiErrorBody()
            val root = try {
                json.parseToJsonElement(body)
            } catch (_: Exception) {
                return ApiErrorBody()
            }
            val top = (if (root is JsonArray) root.firstOrNull() else root) as? JsonObject ?: return ApiErrorBody()
            return when (val error = top["error"]) {
                is JsonObject -> of(error)
                // xAI: {"code": "invalid-argument", "error": "Incorrect API key provided. …"}
                is JsonPrimitive -> ApiErrorBody(message = error.text(), code = top["code"].text())
                else -> of(top)
            }
        }

        private fun of(o: JsonObject): ApiErrorBody {
            val details = (o["details"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            fun detailsOf(type: String) = details.filter { it["@type"].text().orEmpty().endsWith(type) }
            val errorInfos = detailsOf("ErrorInfo")
            return ApiErrorBody(
                message = o["message"].text() ?: o["detail"].text(),
                type = o["type"].text(),
                code = o["code"].text(),
                param = o["param"].text(),
                status = o["status"].text(),
                reasons = errorInfos.mapNotNull { it["reason"].text() },
                retryDelay = detailsOf("RetryInfo").firstOrNull()?.let { parseSecondsDuration(it["retryDelay"].text()) },
                domain = errorInfos.firstNotNullOfOrNull { it["domain"].text() },
                metadata = errorInfos.firstOrNull()?.get("metadata").textMap(),
                helpLinks = detailsOf("Help").flatMap { help ->
                    (help["links"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("url").text() }
                },
                quotaViolations = detailsOf("QuotaFailure").flatMap { failure ->
                    (failure["violations"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { v ->
                        QuotaViolation(
                            metric = v["quotaMetric"].text(),
                            id = v["quotaId"].text(),
                            value = v["quotaValue"].text(),
                            dimensions = v["quotaDimensions"].textMap(),
                        )
                    }
                },
            )
        }

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }
            ?.content?.trim()?.takeIf { it.isNotEmpty() }

        /** The primitive entries of an object as text; empty for anything else. */
        private fun JsonElement?.textMap(): Map<String, String> =
            (this as? JsonObject)?.entries?.mapNotNull { (k, v) -> v.text()?.let { k to it } }?.toMap().orEmpty()
    }
}

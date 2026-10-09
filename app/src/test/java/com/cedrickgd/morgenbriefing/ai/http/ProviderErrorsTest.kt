package com.cedrickgd.morgenbriefing.ai.http

import com.cedrickgd.morgenbriefing.ai.AiBlock
import com.cedrickgd.morgenbriefing.ai.AiBlockedException
import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.ProviderAuth
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiProviderId.CUSTOM
import com.cedrickgd.morgenbriefing.data.model.AiProviderId.DEEPSEEK
import com.cedrickgd.morgenbriefing.data.model.AiProviderId.GEMINI
import com.cedrickgd.morgenbriefing.data.model.AiProviderId.MISTRAL
import com.cedrickgd.morgenbriefing.data.model.AiProviderId.OPENAI
import com.cedrickgd.morgenbriefing.data.model.AiProviderId.OPENROUTER
import com.cedrickgd.morgenbriefing.data.model.AiProviderId.PERPLEXITY
import com.cedrickgd.morgenbriefing.data.model.AiProviderId.XAI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import kotlin.time.Duration.Companion.seconds

/** docs/AI-PROVIDERS.md §8.1 and §8.2, row by row, with synthetic bodies in each provider's shape. */
class ProviderErrorsTest {
    private val noncustom = AiProviderId.entries.filter { it != AiProviderId.ANTHROPIC && it != CUSTOM }

    private fun http(provider: AiProviderId, code: Int, body: String = "", retryAfter: kotlin.time.Duration? = null, freeTier: Boolean = false) =
        ProviderErrors.http(provider, code, body, retryAfter, model = "m-1", freeTier = freeTier)

    private fun openAiError(code: String?, type: String = "invalid_request_error", message: String = "x", param: String? = null): String =
        """{"error":{"message":"$message","type":"$type","param":${param?.let { "\"$it\"" } ?: "null"},"code":${code?.let { "\"$it\"" } ?: "null"}}}"""

    private fun rpc(code: Int, status: String, message: String, reason: String? = null, retryDelay: String? = null): String {
        val details = buildList {
            reason?.let { add("""{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"$it","domain":"googleapis.com"}""") }
            retryDelay?.let { add("""{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"$it"}""") }
        }.joinToString(",")
        return """{"error":{"code":$code,"message":"$message","status":"$status","details":[$details]}}"""
    }

    @Test
    fun `401 is an invalid key everywhere, 404 an unknown model`() {
        (noncustom + CUSTOM).forEach { p ->
            assertEquals(p.key, MappedError(AiError.InvalidKey), http(p, 401, openAiError(null)))
            assertEquals(p.key, MappedError(AiError.ModelUnavailable("m-1")), http(p, 404, openAiError("model_not_found")))
        }
    }

    @Test
    fun `408, 5xx and 529 are overloaded and retryable`() {
        (noncustom + CUSTOM).forEach { p ->
            listOf(408, 500, 502, 503, 504, 529).forEach { code ->
                val mapped = http(p, code, retryAfter = 2.seconds)
                assertEquals("${p.key} $code", AiError.Overloaded, mapped.error)
                assertTrue(mapped.retryable)
                assertEquals(2.seconds, mapped.retryAfter)
            }
        }
    }

    @Test
    fun `429 is a retryable rate limit unless the provider says otherwise`() {
        listOf(XAI, PERPLEXITY, OPENROUTER, MISTRAL, DEEPSEEK, CUSTOM, OPENAI).forEach { p ->
            val mapped = http(p, 429, openAiError("rate_limit_exceeded", type = "rate_limit_error"), retryAfter = 3.seconds)
            assertEquals(p.key, MappedError(AiError.RateLimited, retryable = true, retryAfter = 3.seconds), mapped)
        }
        assertEquals(MappedError(AiError.RateLimited, retryable = true), http(OPENAI, 429, openAiError("slow_down", type = "rate_limit_error")))
    }

    @Test
    fun `OpenAI 429 billing and spend limits are final`() {
        assertEquals(MappedError(AiError.NoCredit), http(OPENAI, 429, openAiError("credit_balance_exhausted", type = "insufficient_quota")))
        assertEquals(MappedError(AiError.NoCredit), http(OPENAI, 429, openAiError(null, type = "insufficient_quota")))
        listOf("organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded").forEach { code ->
            val mapped = http(OPENAI, 429, openAiError(code))
            assertEquals(AiError.Unknown("Das Ausgabenlimit deines OpenAI-Projekts ist erreicht."), mapped.error)
            assertFalse(mapped.retryable)
        }
    }

    @Test
    fun `400 rows`() {
        assertEquals(
            AiError.ModelUnavailable("m-1"),
            http(OPENAI, 400, openAiError(null, message = "The model `gpt-9` does not exist or you do not have access to it.", param = "model")).error,
        )
        assertEquals(AiError.Unknown("OpenAI hat die Anfrage abgelehnt (HTTP 400)."), http(OPENAI, 400, openAiError(null, param = "temperature")).error)
        assertEquals(AiError.Unknown("Grok hat die Anfrage abgelehnt (HTTP 400)."), http(XAI, 400).error)
        assertEquals(AiError.Unknown("Perplexity hat die Anfrage abgelehnt (HTTP 400)."), http(PERPLEXITY, 400).error)
        assertEquals(AiError.Unknown("Mistral hat die Anfrage abgelehnt (HTTP 400)."), http(MISTRAL, 400).error)
        assertEquals(AiError.Unknown("Dein Server hat die Anfrage abgelehnt (HTTP 400)."), http(CUSTOM, 400).error)
        // Gemini: classic key (400 API_KEY_INVALID), billing precondition, anything else.
        assertEquals(
            MappedError(AiError.InvalidKey),
            http(GEMINI, 400, rpc(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key.", reason = "API_KEY_INVALID")),
        )
        assertEquals(
            AiError.Unknown("Gemini braucht für dich eine aktive Abrechnung in AI Studio."),
            http(GEMINI, 400, rpc(400, "FAILED_PRECONDITION", "Gemini API free tier is not available in your country.")).error,
        )
        assertEquals(AiError.Unknown("Gemini hat die Anfrage abgelehnt (HTTP 400)."), http(GEMINI, 400, rpc(400, "INVALID_ARGUMENT", "bad")).error)
        listOf(400, 422).forEach { code -> noncustom.forEach { assertFalse(http(it, code).retryable) } }
    }

    @Test
    fun `Gemini error bodies are read as object or array`() {
        val body = rpc(401, "UNAUTHENTICATED", "Request had invalid authentication credentials.", reason = "ACCESS_TOKEN_TYPE_UNSUPPORTED")
        assertEquals(MappedError(AiError.InvalidKey), http(GEMINI, 401, body))
        val array = "[" + rpc(400, "INVALID_ARGUMENT", "API key not valid.", reason = "API_KEY_INVALID") + "]"
        assertEquals(MappedError(AiError.InvalidKey), http(GEMINI, 400, array))
        val parsed = ApiErrorBody.parse(array)
        assertEquals("INVALID_ARGUMENT", parsed.status)
        assertEquals(listOf("API_KEY_INVALID"), parsed.reasons)
    }

    @Test
    fun `402 is no credit, except OpenRouter with Retry-After and the own server`() {
        listOf(OPENAI, XAI, PERPLEXITY, GEMINI, OPENROUTER, MISTRAL, DEEPSEEK).forEach { p ->
            assertEquals(p.key, MappedError(AiError.NoCredit), http(p, 402, """{"error":{"code":402,"message":"Insufficient credits"}}"""))
        }
        assertEquals(MappedError(AiError.RateLimited, retryable = true, retryAfter = 5.seconds), http(OPENROUTER, 402, retryAfter = 5.seconds))
        assertEquals(AiError.Unknown("Dein Server hat die Anfrage abgelehnt (HTTP 402)."), http(CUSTOM, 402).error)
    }

    @Test
    fun `403 rows`() {
        assertEquals(AiError.Unknown("OpenAI ist in deiner Region nicht verfügbar."), http(OPENAI, 403).error)
        assertEquals(AiError.Unknown("Kein Zugriff – Team gesperrt oder Guthaben leer? Schau in console.x.ai nach."), http(XAI, 403).error)
        assertEquals(AiError.Unknown("Perplexity hat die Anfrage abgelehnt (HTTP 403)."), http(PERPLEXITY, 403).error)
        assertEquals(AiError.Unknown("Der Key darf die Gemini API nicht nutzen."), http(GEMINI, 403, rpc(403, "PERMISSION_DENIED", "no")).error)
        assertEquals(AiError.Unknown("OpenRouter hat die Anfrage abgelehnt (Moderation oder Rechte)."), http(OPENROUTER, 403).error)
        assertEquals(AiError.Unknown("Mistral hat die Anfrage abgelehnt (HTTP 403)."), http(MISTRAL, 403).error)
        assertEquals(AiError.InvalidKey, http(CUSTOM, 403).error)
        noncustom.forEach { assertFalse(http(it, 403).retryable) }
    }

    @Test
    fun `Gemini 429 on the free tier is the used-up free quota and is not hammered`() {
        val exhausted = rpc(429, "RESOURCE_EXHAUSTED", "You exceeded your current quota.")
        // No hint when it frees up (e.g. the daily quota): reported at once.
        assertEquals(MappedError(AiError.QuotaExhausted), http(GEMINI, 429, exhausted, freeTier = true))
        // A short wait named by Google: one more try after it.
        assertEquals(
            MappedError(AiError.QuotaExhausted, retryable = true, retryAfter = 12.seconds),
            http(GEMINI, 429, rpc(429, "RESOURCE_EXHAUSTED", "Quota exceeded", retryDelay = "12s"), freeTier = true),
        )
        // A long wait: reported at once.
        val long = http(GEMINI, 429, rpc(429, "RESOURCE_EXHAUSTED", "Quota exceeded", retryDelay = "37s"), freeTier = true)
        assertEquals(AiError.QuotaExhausted, long.error)
        assertFalse(long.retryable)
        // Paid tier: a normal, retryable rate limit.
        assertEquals(MappedError(AiError.RateLimited, retryable = true), http(GEMINI, 429, exhausted))
        assertEquals(
            MappedError(AiError.RateLimited, retryable = true, retryAfter = 4.seconds),
            http(GEMINI, 429, rpc(429, "RESOURCE_EXHAUSTED", "Quota exceeded", retryDelay = "4s")),
        )
    }

    @Test
    fun `the mapper of an auth carries the free tier`() {
        val failure = HttpFailure(429, rpc(429, "RESOURCE_EXHAUSTED", "x"), null)
        val free = ProviderErrors.mapper(ProviderAuth(GEMINI, "AIza-test-invalid", freeTier = true), "gemini-3.8-flash")
        val paid = ProviderErrors.mapper(ProviderAuth(GEMINI, "AIza-test-invalid"), "gemini-3.8-flash")
        assertEquals(AiError.QuotaExhausted, free.map(failure)!!.error)
        assertEquals(AiError.RateLimited, paid.map(failure)!!.error)
    }

    @Test
    fun `other statuses name the provider`() {
        assertEquals(AiError.Unknown("Grok hat die Anfrage abgelehnt (HTTP 422)."), http(XAI, 422).error)
        assertEquals(AiError.Unknown("Die Anfrage war zu groß für DeepSeek."), http(DEEPSEEK, 413).error)
        // A reverse proxy's body limit in front of the own server: proper German for "dein Server".
        assertEquals(AiError.Unknown("Die Anfrage war zu groß für deinen Server."), http(CUSTOM, 413).error)
    }

    @Test
    fun `connection problems`() {
        assertEquals(MappedError(AiError.Network, retryable = true), ProviderErrors.io(OPENAI, IOException("connection reset")))
        assertEquals(MappedError(AiError.Network, retryable = true), ProviderErrors.io(GEMINI, UnknownHostException("generativelanguage.googleapis.com")))
        assertEquals(MappedError(AiError.Network), ProviderErrors.io(XAI, SSLHandshakeException("bad cert")))
        assertEquals(MappedError(AiError.ServerUnreachable, retryable = true), ProviderErrors.io(CUSTOM, ConnectException("Connection refused")))
        assertEquals(MappedError(AiError.ServerUnreachable, retryable = true), ProviderErrors.io(CUSTOM, UnknownHostException("nas.fritz.box")))
        assertEquals(MappedError(AiError.ServerUnreachable, retryable = true), ProviderErrors.io(CUSTOM, SocketTimeoutException("connect timed out")))
        assertEquals(MappedError(AiError.Network, retryable = true), ProviderErrors.io(CUSTOM, SocketTimeoutException("timeout")))
        // The own server's TLS setup fails: it answers, the certificate (or no TLS at all) is the problem (§7.6).
        val certificate = MappedError(AiError.Unknown(ProviderErrors.CERTIFICATE_TEXT))
        assertEquals(certificate, ProviderErrors.io(CUSTOM, SSLHandshakeException("Trust anchor for certification path not found.")))
        assertEquals(certificate, ProviderErrors.io(CUSTOM, SSLPeerUnverifiedException("Hostname 192.168.178.20 not verified")))
        assertEquals(certificate, ProviderErrors.io(CUSTOM, SSLProtocolException("WRONG_VERSION_NUMBER")))
        assertTrue(ProviderErrors.CERTIFICATE_TEXT.startsWith("Dein Server antwortet, aber"))
        // A TLS read error mid-stream is a connection problem.
        assertEquals(MappedError(AiError.ServerUnreachable), ProviderErrors.io(CUSTOM, SSLException("Read error: Connection reset by peer")))
        val cleartext = ProviderErrors.io(CUSTOM, UnknownServiceException("CLEARTEXT communication to 192.168.1.5 not permitted"))
        assertEquals(AiError.Unknown(ProviderErrors.CLEARTEXT_TEXT), cleartext.error)
        assertFalse(cleartext.retryable)
    }

    @Test
    fun `map passes exceptions through by kind`() {
        assertNull(ProviderErrors.map(OPENAI, AiBlockedException(AiBlock.LIMIT_REACHED), null))
        assertEquals(MappedError(AiError.Truncated), ProviderErrors.map(OPENAI, AiException(AiError.Truncated), null))
        assertEquals(MappedError(AiError.Unknown(null)), ProviderErrors.map(OPENAI, IllegalStateException("parser"), null))
        assertEquals(AiError.InvalidKey, ProviderErrors.map(OPENAI, HttpFailure(401, "", null), null)!!.error)
        assertEquals(AiError.Network, ProviderErrors.map(OPENAI, IOException("reset"), null)!!.error)
    }

    @Test
    fun `Responses stream errors`() {
        assertEquals(AiError.Overloaded, ProviderErrors.responsesStreamError(OPENAI, "server_error"))
        assertEquals(AiError.RateLimited, ProviderErrors.responsesStreamError(OPENAI, "rate_limit_exceeded"))
        listOf("invalid_prompt", "bio_policy", "misalignment_policy_violation").forEach {
            assertEquals(AiError.Refusal(it), ProviderErrors.responsesStreamError(OPENAI, it))
        }
        assertEquals(AiError.Unknown("Grok hat die Antwort abgebrochen."), ProviderErrors.responsesStreamError(XAI, "vector_store_timeout"))
        assertEquals(AiError.Unknown("Perplexity hat die Antwort abgebrochen."), ProviderErrors.responsesStreamError(PERPLEXITY, null))
    }

    @Test
    fun `stream error chunks are classified like their status`() {
        fun chunk(text: String) = Json.parseToJsonElement(text).jsonObject
        assertEquals(AiError.Overloaded, ProviderErrors.streamError(OPENROUTER, chunk("""{"error":{"code":502,"message":"Provider returned error"}}"""), "m"))
        assertEquals(AiError.NoCredit, ProviderErrors.streamError(OPENROUTER, chunk("""{"error":{"code":402,"message":"credits"}}"""), "m"))
        assertEquals(AiError.Overloaded, ProviderErrors.streamError(GEMINI, chunk("""{"error":{"status":"UNAVAILABLE","message":"overloaded"}}"""), "m"))
        assertEquals(
            AiError.QuotaExhausted,
            ProviderErrors.streamError(GEMINI, chunk("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}"""), "m", freeTier = true),
        )
        assertEquals(AiError.Unknown("OpenRouter hat die Antwort abgebrochen."), ProviderErrors.streamError(OPENROUTER, chunk("""{"error":{"message":"?"}}"""), "m"))
    }

    @Test
    fun `error bodies of every shape are parsed leniently`() {
        val mistral = ApiErrorBody.parse("""{"object":"error","message":"Unauthorized","type":"invalid_request_message_error","param":null,"code":"1000"}""")
        assertEquals("1000", mistral.code)
        assertEquals("Unauthorized", mistral.message)
        assertEquals(402.toString(), ApiErrorBody.parse("""{"error":{"code":402,"message":"x"}}""").code)
        assertEquals("plain", ApiErrorBody.parse("""{"error":"plain"}""").message)
        assertNull(ApiErrorBody.parse("<html>Bad Gateway</html>").message)
        assertNull(ApiErrorBody.parse("").code)
    }
}

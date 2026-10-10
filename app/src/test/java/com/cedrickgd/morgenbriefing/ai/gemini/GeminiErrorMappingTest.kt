package com.cedrickgd.morgenbriefing.ai.gemini

import com.cedrickgd.morgenbriefing.ai.AiError
import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.AiTexts
import com.cedrickgd.morgenbriefing.ai.GeminiProblem
import com.cedrickgd.morgenbriefing.ai.StoredAiError
import com.cedrickgd.morgenbriefing.ai.http.ApiErrorBody
import com.cedrickgd.morgenbriefing.ai.http.ErrorDiagnostics
import com.cedrickgd.morgenbriefing.ai.http.HttpFailure
import com.cedrickgd.morgenbriefing.ai.http.ProviderErrors
import com.cedrickgd.morgenbriefing.ai.provider.GeminiLinks
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.ai.rejectsKey
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds

/**
 * Google's error bodies for every cause the app explains (docs/providers/gemini.md §12): parsed in
 * full, mapped to the right text and link, and summed up in a key-free diagnostic line.
 */
class GeminiErrorMappingTest {
    private val model = "gemini-3.8-flash"

    private fun map(code: Int, body: String, freeTier: Boolean = true) =
        ProviderErrors.http(AiProviderId.GEMINI, code, body, retryAfter = null, model = model, freeTier = freeTier)

    @Test
    fun `the google rpc body is read in full`() {
        val disabled = ApiErrorBody.parse(GeminiErrors.SERVICE_DISABLED)
        assertEquals(403, disabled.httpCode)
        assertEquals("PERMISSION_DENIED", disabled.status)
        assertEquals(listOf("SERVICE_DISABLED"), disabled.reasons)
        assertEquals("googleapis.com", disabled.domain)
        assertEquals("generativelanguage.googleapis.com", disabled.metadata["service"])
        assertEquals("projects/000000000000", disabled.metadata["consumer"])
        assertEquals(
            listOf("https://console.developers.google.com/apis/api/generativelanguage.googleapis.com/overview?project=000000000000"),
            disabled.helpLinks,
        )

        val quota = ApiErrorBody.parse(GeminiErrors.QUOTA_PER_MINUTE)
        assertEquals(37.seconds, quota.retryDelay)
        val violation = quota.quotaViolations.single()
        assertEquals("generativelanguage.googleapis.com/generate_content_free_tier_requests", violation.metric)
        assertEquals("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", violation.id)
        assertEquals("10", violation.value)
        assertEquals("gemini-3.8-flash", violation.dimensions["model"])
        assertEquals(listOf("https://ai.google.dev/gemini-api/docs/rate-limits"), quota.helpLinks)
        assertFalse(quota.hasZeroQuota)
        assertTrue(ApiErrorBody.parse(GeminiErrors.zeroQuota()).hasZeroQuota)
        // "limit: 0" in the message alone is enough (a body without the QuotaFailure detail).
        assertTrue(ApiErrorBody.parse("""{"error":{"code":429,"message":"Quota exceeded … limit: 0, model: x","status":"RESOURCE_EXHAUSTED"}}""").hasZeroQuota)
        assertFalse(ApiErrorBody.parse("""{"error":{"code":429,"message":"limit: 0.5 per second","status":"RESOURCE_EXHAUSTED"}}""").hasZeroQuota)
        assertFalse(ApiErrorBody.parse("""{"error":{"code":429,"message":"limit: 10","status":"RESOURCE_EXHAUSTED"}}""").hasZeroQuota)
    }

    @Test
    fun `each known cause gets its own error`() {
        assertEquals(AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED), map(401, GeminiErrors.AUTH_KEY_REJECTED).error)
        assertEquals(AiError.GeminiAccess(GeminiProblem.API_BLOCKED), map(403, GeminiErrors.API_KEY_SERVICE_BLOCKED).error)
        assertEquals(AiError.GeminiAccess(GeminiProblem.API_BLOCKED), map(403, GeminiErrors.SERVICE_DISABLED).error)
        assertEquals(AiError.GeminiAccess(GeminiProblem.KEY_RESTRICTED), map(403, GeminiErrors.ANDROID_APP_BLOCKED).error)
        assertEquals(AiError.GeminiAccess(GeminiProblem.KEY_LEAKED), map(403, GeminiErrors.KEY_LEAKED).error)
        assertEquals(AiError.GeminiAccess(GeminiProblem.FREE_TIER_UNAVAILABLE), map(400, GeminiErrors.FREE_TIER_REGION).error)
        assertEquals(AiError.ModelUnavailable(model), map(404, GeminiErrors.modelNotFound()).error)
        assertEquals(AiError.ModelUnavailable(model), map(403, GeminiErrors.modelForbidden()).error)
        // A quota of 0 is a model the free tier does not offer, not a used-up quota.
        assertEquals(AiError.ModelUnavailable(model), map(429, GeminiErrors.zeroQuota()).error)
        // A normal free quota: the calm text, retried only for a short wait (37 s is too long).
        val quota = map(429, GeminiErrors.QUOTA_PER_MINUTE)
        assertEquals(AiError.QuotaExhausted, quota.error)
        assertFalse(quota.retryable)
        assertEquals(AiError.RateLimited, map(429, GeminiErrors.QUOTA_PER_MINUTE, freeTier = false).error)
        // A blocked API sent as a bearer token came back as 401 (forum report 2026-10-08): the reason decides.
        assertEquals(
            AiError.GeminiAccess(GeminiProblem.API_BLOCKED),
            map(401, GeminiErrors.API_KEY_SERVICE_BLOCKED.replace("\"code\":403", "\"code\":401").replace("PERMISSION_DENIED", "UNAUTHENTICATED")).error,
        )
        // None of them is retried automatically.
        listOf(
            401 to GeminiErrors.AUTH_KEY_REJECTED,
            403 to GeminiErrors.API_KEY_SERVICE_BLOCKED,
            403 to GeminiErrors.SERVICE_DISABLED,
            400 to GeminiErrors.FREE_TIER_REGION,
            404 to GeminiErrors.modelNotFound(),
        ).forEach { (code, body) -> assertFalse(body, map(code, body).retryable) }
    }

    @Test
    fun `only Google's phrasings about the model itself are a model refusal`() {
        val invalid = { message: String -> GeminiSse.rpc(400, "INVALID_ARGUMENT", message) }
        // The model id is unknown, unreadable or cannot generate: the fallback chain may help.
        listOf(
            "models/gemini-9 is not found for API version v1beta, or is not supported for generateContent. Call ListModels …",
            "models/text-embedding-005 is not supported for generateContent.",
            "* GenerateContentRequest.model: unexpected model name format",
        ).forEach { assertEquals(it, AiError.ModelUnavailable(model), map(400, invalid(it)).error) }
        listOf(
            "Your project does not have access to model gemini-3.1-pro-preview.",
            "You don't have access to this model.",
            "The caller has no access to the model gemini-3.1-pro-preview.",
        ).forEach { assertEquals(it, AiError.ModelUnavailable(model), map(403, GeminiSse.rpc(403, "PERMISSION_DENIED", it)).error) }

        // About the request, not the model: every model of the chain would refuse it the same way.
        val request = AiError.Unknown("Gemini hat die Anfrage abgelehnt (HTTP 400).")
        listOf(
            "thinking_level is not supported by this model.",
            "Function calling is not supported for this model.",
            "Thinking level MINIMAL is not available for model gemini-3.8-flash.",
            "The requested model setting was not found.",
            "Request contains an invalid argument.",
        ).forEach { message ->
            assertEquals(message, request, map(400, invalid(message)).error)
            assertFalse(message, GeminiStreamer.isModelUnavailable(HttpFailure(400, invalid(message), null), model, freeTier = true))
        }
        // A 403 that does not deny the model is the key's or the project's.
        listOf("The caller does not have permission.", "Model access is not available for this API key.").forEach { message ->
            assertEquals(message, AiError.Unknown("Der Key darf die Gemini API nicht nutzen."), map(403, GeminiSse.rpc(403, "PERMISSION_DENIED", message)).error)
        }
        // A model phrasing next to a reason about the key or the project is no model problem.
        assertEquals(
            AiError.GeminiAccess(GeminiProblem.API_BLOCKED),
            map(403, GeminiSse.rpc(403, "PERMISSION_DENIED", "Your project does not have access to model x.", reason = "SERVICE_DISABLED")).error,
        )
        // Their Details stay with them.
        assertEquals("HTTP 400 · INVALID_ARGUMENT", ErrorDiagnostics.http(400, invalid("thinking_level is not supported by this model.")))
    }

    @Test
    fun `the diagnostic names status, reason and quota but never a key, a URL or a project`() {
        val cases = mapOf(
            (401 to GeminiErrors.AUTH_KEY_REJECTED) to "HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED",
            (403 to GeminiErrors.API_KEY_SERVICE_BLOCKED) to "HTTP 403 · PERMISSION_DENIED · API_KEY_SERVICE_BLOCKED",
            (403 to GeminiErrors.SERVICE_DISABLED) to "HTTP 403 · PERMISSION_DENIED · SERVICE_DISABLED",
            (400 to GeminiErrors.FREE_TIER_REGION) to "HTTP 400 · FAILED_PRECONDITION",
            (404 to GeminiErrors.modelNotFound()) to "HTTP 404 · NOT_FOUND",
            (429 to GeminiErrors.QUOTA_PER_MINUTE) to
                "HTTP 429 · RESOURCE_EXHAUSTED · GenerateRequestsPerMinutePerProjectPerModel-FreeTier · limit 10 · retry 37s",
            (429 to GeminiErrors.zeroQuota()) to "HTTP 429 · RESOURCE_EXHAUSTED · GenerateRequestsPerDayPerProjectPerModel-FreeTier · limit 0 · retry 20s",
        )
        cases.forEach { (failure, expected) ->
            val text = ErrorDiagnostics.http(failure.first, failure.second)
            assertEquals(expected, text)
            listOf("http://", "https://", "000000000000", "projects/", "AIza", "AQ.").forEach { assertFalse("$expected: $it", text.contains(it)) }
        }
        // Without a body there is still the status; an unreadable body adds nothing.
        assertEquals("HTTP 503", ErrorDiagnostics.http(503, ""))
        assertEquals("HTTP 502", ErrorDiagnostics.http(502, "<html>Bad Gateway</html>"))
        // A stream error chunk and a connection problem.
        val chunk = Json.parseToJsonElement("""{"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}""").jsonObject
        assertEquals("Stream · 503 · UNAVAILABLE", ErrorDiagnostics.stream(chunk))
        assertEquals("Verbindung · UnknownHostException", ErrorDiagnostics.of(UnknownHostException("generativelanguage.googleapis.com")))
    }

    @Test
    fun `a key echoed into every field of the body never reaches the diagnostic`() {
        listOf(GeminiSse.ECHO_KEY, GeminiSse.ECHO_AQ_KEY).forEach { key ->
            // The fixture really puts the key where the diagnostic reads (and everywhere else).
            val echoed = ApiErrorBody.parse(GeminiErrors.keyEcho(key, status = key))
            assertEquals(key, echoed.status)
            assertEquals(key, echoed.code)
            assertEquals(key, echoed.type)
            assertEquals(listOf(key), echoed.reasons)
            assertEquals(key, echoed.quotaViolations.single().id)
            assertTrue(echoed.message!!.contains(key))
            assertTrue(echoed.metadata.values.any { it.contains(key) })
            assertTrue(echoed.helpLinks.single().contains(key))

            // HTTP answer, stream error chunk: only what is not the key stays.
            assertEquals("HTTP 400", ErrorDiagnostics.http(400, GeminiErrors.keyEcho(key, status = key)))
            assertEquals("HTTP 400 · INVALID_ARGUMENT", ErrorDiagnostics.http(400, GeminiErrors.keyEcho(key)))
            val chunk = Json.parseToJsonElement(GeminiErrors.keyEcho(key)).jsonObject
            assertEquals("Stream · INVALID_ARGUMENT", ErrorDiagnostics.stream(chunk))
            // Classified as the invalid key it is.
            assertEquals(AiError.InvalidKey, map(400, GeminiErrors.keyEcho(key)).error)
        }
        // Other providers' key shapes in an identifier field are dropped too (all fake).
        listOf(
            "sk-0123456789abcdef0123456789abcdef",
            "Xq7Lm2Pz9Rt4Vw8Ka1Bc6De3Fg5Hj0Nn",
            "pplx-0123456789abcdef",
            "xai-FakeTestKey",
        ).forEach { secret ->
            assertEquals(secret, "HTTP 400", ErrorDiagnostics.http(400, """{"error":{"code":"$secret","type":"$secret"}}"""))
        }
        // Real identifiers stay, however long: reasons, OpenAI codes, quota ids.
        listOf(
            "API_KEY_HTTP_REFERRER_BLOCKED",
            "ACCESS_TOKEN_SCOPE_INSUFFICIENT",
            "unsupported_country_region_territory",
            "organization_spend_limit_exceeded",
        ).forEach { id -> assertEquals(id, "HTTP 403 · $id", ErrorDiagnostics.http(403, """{"error":{"code":"$id"}}""")) }
        assertEquals("HTTP 400 · SSLHandshakeException", ErrorDiagnostics.http(400, """{"error":{"type":"SSLHandshakeException"}}"""))
    }

    @Test
    fun `an exception carries the diagnostic of its cause, and a known key is never shown`() {
        val failure = HttpFailure(401, GeminiErrors.AUTH_KEY_REJECTED, null)
        val e = AiException(AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED), failure)
        assertEquals("HTTP 401 · UNAUTHENTICATED · ACCESS_TOKEN_TYPE_UNSUPPORTED", e.diagnostic)
        assertEquals(e.diagnostic, ErrorDiagnostics.of(e))
        // Fields that do not look like identifiers stay out (a message, a URL, a key-like value with a space).
        val odd = """{"error":{"code":400,"status":"bad status with spaces","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"https://example.com/x"}]}}"""
        assertEquals("HTTP 400", ErrorDiagnostics.http(400, odd))
        // Defense in depth: a line that contained the key would be dropped where the key is known.
        val key = "AQ.test-invalid-key-for-redaction"
        assertNull(ErrorDiagnostics.redact("HTTP 400 · $key", key))
        assertEquals("HTTP 401 · UNAUTHENTICATED", ErrorDiagnostics.redact("HTTP 401 · UNAUTHENTICATED", key))
        assertNull(ErrorDiagnostics.redact(null, key))
    }

    @Test
    fun `each cause has its German text and the page that fixes it`() {
        val gemini = ProviderCatalog.GEMINI
        val auth = AiError.GeminiAccess(GeminiProblem.AUTH_KEY_REJECTED)
        // A known Google issue with new auth keys: an older AIza key, or OpenRouter for free.
        val text = AiTexts.error(auth, gemini)
        assertTrue(text, text.contains("bekannter Fehler bei Google"))
        assertTrue(text, text.contains("AQ."))
        assertTrue(text, text.contains("„AIza“"))
        assertTrue(text, text.contains("OpenRouter kostenlos mit Gemma 4"))
        assertEquals("Keys in AI Studio" to GeminiLinks.API_KEYS, AiTexts.errorLink(auth))
        assertTrue(AiTexts.offersOpenRouter(auth))
        assertEquals("OpenRouter kostenlos einrichten", AiTexts.OPENROUTER_PLAN_B)
        assertTrue(auth.rejectsKey)
        assertTrue(AiTexts.needsKeyChange(auth))
        // Only that refusal: a leaked key needs a new Google key.
        val leakedKey = AiError.GeminiAccess(GeminiProblem.KEY_LEAKED)
        assertFalse(AiTexts.offersOpenRouter(leakedKey))
        assertEquals("Neuen Key in AI Studio" to GeminiLinks.API_KEYS, AiTexts.errorLink(leakedKey))
        assertFalse(AiTexts.offersOpenRouter(AiError.InvalidKey))

        val blocked = AiError.GeminiAccess(GeminiProblem.API_BLOCKED)
        assertTrue(AiTexts.error(blocked, gemini).startsWith("Die Gemini API ist für dein Google-Projekt gesperrt oder nicht aktiviert"))
        assertEquals(GeminiLinks.API_LIBRARY, AiTexts.errorLink(blocked)!!.second)
        assertEquals("https://console.cloud.google.com/apis/library/generativelanguage.googleapis.com", GeminiLinks.API_LIBRARY)
        assertFalse(blocked.rejectsKey)

        val region = AiError.GeminiAccess(GeminiProblem.FREE_TIER_UNAVAILABLE)
        assertEquals(
            "Der kostenlose Gemini-Tarif ist für dein Land/Projekt nicht freigeschaltet – aktivier die Abrechnung in AI Studio " +
                "oder wähl einen anderen Anbieter.",
            AiTexts.error(region, gemini),
        )
        assertEquals(GeminiLinks.PROJECTS, AiTexts.errorLink(region)!!.second)
        assertEquals(GeminiLinks.CREDENTIALS, AiTexts.errorLink(AiError.GeminiAccess(GeminiProblem.KEY_RESTRICTED))!!.second)
        val leaked = AiError.GeminiAccess(GeminiProblem.KEY_LEAKED)
        assertTrue(AiTexts.error(leaked, gemini).startsWith("Google hat deinen Key gesperrt, weil er öffentlich aufgetaucht ist."))
        assertEquals(GeminiLinks.API_KEYS, AiTexts.errorLink(leaked)!!.second)
        assertTrue(leaked.rejectsKey)
        // No retry button for any of them: the fix is outside the app.
        GeminiProblem.entries.forEach { assertFalse(it.name, AiTexts.isRetryable(AiError.GeminiAccess(it))) }
        // Other errors have no link.
        assertNull(AiTexts.errorLink(AiError.InvalidKey))
        assertNull(AiTexts.errorLink(AiError.QuotaExhausted))
    }

    @Test
    fun `the cause survives the chat history`() {
        GeminiProblem.entries.forEach { problem ->
            val error = AiError.GeminiAccess(problem)
            assertEquals(error, StoredAiError.of(error).toAiError())
        }
        // A cause a newer app version wrote stays readable.
        assertEquals(AiError.Unknown(null), StoredAiError("gemini_access", "SOMETHING_NEW").toAiError())
    }
}

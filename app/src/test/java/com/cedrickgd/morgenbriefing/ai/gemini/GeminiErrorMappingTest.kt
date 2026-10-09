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
        assertTrue(AiTexts.error(auth, gemini).contains("neuen Key"))
        assertTrue(AiTexts.error(auth, gemini).contains("AQ."))
        assertEquals("Neuen Key in AI Studio" to GeminiLinks.API_KEYS, AiTexts.errorLink(auth))
        assertTrue(auth.rejectsKey)
        assertTrue(AiTexts.needsKeyChange(auth))

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

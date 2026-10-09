package com.cedrickgd.morgenbriefing.ai.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse

/**
 * `streamGenerateContent?alt=sse` streams for MockWebServer, shaped like the documented
 * `GenerateContentResponse` (GE §5.2: every `data:` is one complete response, no event names,
 * no `[DONE]`, the stream ends at EOF) and the google.rpc error bodies observed with fake keys
 * (GE §12.1). Fake keys only.
 */
internal object GeminiSse {
    /** Classic key shape (`AIza…`). */
    const val KEY = "AIza-test-invalid"

    /** Newer AI Studio key shape (`AQ.…`, GE §13). */
    const val AQ_KEY = "AQ.test-invalid"

    const val SUGGESTIONS = """<style>.container{display:flex}</style><div class="container"><a class="chip" href="https://www.google.com/search?q=EZB+Zins">EZB Zins</a></div>"""

    fun response(vararg chunks: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString(""))

    /** One SSE event with a `GenerateContentResponse`. */
    fun chunk(json: String): String = "data: $json\n\n"

    /** A chunk with answer text parts; [thought] parts come first and are thought summaries. */
    fun text(vararg texts: String, thought: String? = null, finish: String? = null, grounding: String? = null, usage: String? = null): String {
        val parts = buildList {
            thought?.let { add("""{"text":${str(it)},"thought":true}""") }
            texts.forEach { add("""{"text":${str(it)}}""") }
        }.joinToString(",")
        return candidate(""""content":{"role":"model","parts":[$parts]}""", finish, grounding, usage)
    }

    /** A chunk that only carries grounding (or a finish reason) without new text. */
    fun candidate(content: String?, finish: String? = null, grounding: String? = null, usage: String? = null): String {
        val fields = listOfNotNull(
            content,
            finish?.let { """"finishReason":"$it"""" },
            grounding?.let { """"groundingMetadata":$it""" },
            """"index":0""",
        ).joinToString(",")
        val extra = usage?.let { ""","usageMetadata":$it""" }.orEmpty()
        return chunk("""{"candidates":[{$fields}]$extra,"modelVersion":"gemini-3.8-flash","responseId":"resp-TEST"}""")
    }

    /**
     * `groundingMetadata` (GE §6.2): [chunks] are (domain title, redirect uri) like the docs
     * example, [queries] the searches, [suggestions] the Search Suggestions HTML.
     */
    fun grounding(queries: List<String> = emptyList(), chunks: List<Pair<String, String>> = emptyList(), suggestions: String? = null): String {
        val fields = buildList {
            if (queries.isNotEmpty()) add(""""webSearchQueries":[${queries.joinToString(",") { str(it) }}]""")
            suggestions?.let { add(""""searchEntryPoint":{"renderedContent":${str(it)}}""") }
            if (chunks.isNotEmpty()) {
                add(""""groundingChunks":[${chunks.joinToString(",") { (title, uri) -> """{"web":{"uri":${str(uri)},"title":${str(title)}}}""" }}]""")
            }
        }
        return "{${fields.joinToString(",")}}"
    }

    fun usage(prompt: Long = 2_412, cached: Long = 0, candidates: Long = 380, thoughts: Long = 210, toolUse: Long = 0): String =
        """{"promptTokenCount":$prompt,"cachedContentTokenCount":$cached,"candidatesTokenCount":$candidates,""" +
            """"thoughtsTokenCount":$thoughts,"toolUsePromptTokenCount":$toolUse,"totalTokenCount":${prompt + candidates + thoughts}}"""

    /** google.rpc error body (GE §12.1), as an object or wrapped in an array. */
    fun rpc(code: Int, status: String, message: String, reason: String? = null, retryDelay: String? = null, array: Boolean = false): String {
        val details = buildList {
            reason?.let {
                add("""{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"$it","domain":"googleapis.com","metadata":{"service":"generativelanguage.googleapis.com"}}""")
            }
            retryDelay?.let { add("""{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"$it"}""") }
        }.joinToString(",")
        val body = """{"error":{"code":$code,"message":${str(message)},"status":"$status","details":[$details]}}"""
        return if (array) "[$body]" else body
    }

    /**
     * An error answer. Gemini labels even a pre-stream error `text/event-stream` but sends a
     * plain JSON body (GE §5.1, observed).
     */
    fun httpError(code: Int, body: String, eventStream: Boolean = true): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", if (eventStream) "text/event-stream" else "application/json; charset=UTF-8")
        .setBody(body)

    fun str(text: String): String = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))

    /** A `generateContent` answer (one `GenerateContentResponse`, no stream): the key test's "OK". */
    fun generated(model: String = "gemini-3.8-flash", text: String = "OK"): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=UTF-8")
        .setBody(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":${str(text)}}]},"finishReason":"STOP","index":0}],""" +
                """"usageMetadata":{"promptTokenCount":6,"candidatesTokenCount":1,"thoughtsTokenCount":12,"totalTokenCount":19},""" +
                """"modelVersion":"$model","responseId":"resp-TEST"}""",
        )

    /** A `models.list` answer with [ids] (each can `generateContent`). */
    fun modelList(vararg ids: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=UTF-8")
        .setBody(
            """{"models":[""" + ids.joinToString(",") { id ->
                """{"name":"models/$id","displayName":"$id","supportedGenerationMethods":["generateContent","countTokens"]}"""
            } + "]}",
        )
}

/**
 * google.rpc error bodies of the Gemini API for every cause the app explains (docs/providers/
 * gemini.md §12.4), shaped like Google's answers — the ErrorInfo/Help/QuotaFailure/RetryInfo
 * details as the API returns them, with a fake project number (000000000000). The messages are
 * paraphrased where Google's exact wording is not documented; the app never shows them.
 */
internal object GeminiErrors {
    /** 401 for a key Google does not take as an API key (an `AQ.…` auth key, forum F1/F2, observed P1). */
    const val AUTH_KEY_REJECTED = """{"error":{"code":401,"message":"Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or other valid authentication credential. See https://developers.google.com/identity/sign-in/web/devconsole-project.","status":"UNAUTHENTICATED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"ACCESS_TOKEN_TYPE_UNSUPPORTED","metadata":{"method":"google.ai.generativelanguage.v1beta.GenerativeService.GenerateContent","service":"generativelanguage.googleapis.com"}}]}}"""

    /** 403: the API is blocked for the key (a key restricted to other APIs, or blocked by Google). */
    const val API_KEY_SERVICE_BLOCKED = """{"error":{"code":403,"message":"Requests to this API generativelanguage.googleapis.com method google.ai.generativelanguage.v1beta.GenerativeService.StreamGenerateContent are blocked.","status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_SERVICE_BLOCKED","domain":"googleapis.com","metadata":{"apiName":"generativelanguage.googleapis.com","consumer":"projects/000000000000","methodName":"google.ai.generativelanguage.v1beta.GenerativeService.StreamGenerateContent","service":"generativelanguage.googleapis.com"}},{"@type":"type.googleapis.com/google.rpc.LocalizedMessage","locale":"en-US","message":"Requests to this API generativelanguage.googleapis.com method google.ai.generativelanguage.v1beta.GenerativeService.StreamGenerateContent are blocked."}]}}"""

    /** 403: the Generative Language API is not enabled in the key's project, with the activation link. */
    const val SERVICE_DISABLED = """{"error":{"code":403,"message":"Generative Language API has not been used in project 000000000000 before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/generativelanguage.googleapis.com/overview?project=000000000000 then retry.","status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED","domain":"googleapis.com","metadata":{"activationUrl":"https://console.developers.google.com/apis/api/generativelanguage.googleapis.com/overview?project=000000000000","consumer":"projects/000000000000","containerInfo":"000000000000","service":"generativelanguage.googleapis.com","serviceTitle":"Generative Language API"}},{"@type":"type.googleapis.com/google.rpc.LocalizedMessage","locale":"en-US","message":"Generative Language API has not been used in project 000000000000 before or it is disabled."},{"@type":"type.googleapis.com/google.rpc.Help","links":[{"description":"Google developers console API activation","url":"https://console.developers.google.com/apis/api/generativelanguage.googleapis.com/overview?project=000000000000"}]}]}}"""

    /** A key Google blocked as leaked (message as quoted on ai.google.dev troubleshooting; the status is not documented). */
    const val KEY_LEAKED = """{"error":{"code":403,"message":"Your API key was reported as leaked. Please use another API key.","status":"PERMISSION_DENIED"}}"""

    /** 403: the key is restricted to other Android apps. */
    const val ANDROID_APP_BLOCKED = """{"error":{"code":403,"message":"Requests from this Android client application <empty> are blocked.","status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_ANDROID_APP_BLOCKED","domain":"googleapis.com","metadata":{"consumer":"projects/000000000000","service":"generativelanguage.googleapis.com"}}]}}"""

    /** 400: the free tier is not offered in the country (G18: "Set up a paid plan through Google AI Studio"). */
    const val FREE_TIER_REGION = """{"error":{"code":400,"message":"Gemini API free tier is not available in your country. Please enable billing on your project in Google AI Studio.","status":"FAILED_PRECONDITION"}}"""

    /** 404: the model is unknown to (or not open for) the key's project. */
    fun modelNotFound(model: String = "gemini-3.8-flash") =
        """{"error":{"code":404,"message":"models/$model is not found for API version v1beta, or is not supported for generateContent. Call ListModels to see the list of available models and their supported methods.","status":"NOT_FOUND"}}"""

    /** 403 about the model: the project may not use it (e.g. a restricted preview model). */
    fun modelForbidden(model: String = "gemini-3.1-pro-preview") =
        """{"error":{"code":403,"message":"Your project does not have access to model $model.","status":"PERMISSION_DENIED"}}"""

    /** 429 of a free-tier quota per minute, with QuotaFailure, Help and RetryInfo. */
    const val QUOTA_PER_MINUTE = """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits.\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 10, model: gemini-3.8-flash\nPlease retry in 37.5s.","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests","quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier","quotaDimensions":{"location":"global","model":"gemini-3.8-flash"},"quotaValue":"10"}]},{"@type":"type.googleapis.com/google.rpc.Help","links":[{"description":"Learn more about Gemini API quotas","url":"https://ai.google.dev/gemini-api/docs/rate-limits"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}"""

    /** 429 with a quota of 0: the free tier does not offer this model to the project. */
    fun zeroQuota(model: String = "gemini-3.8-flash") =
        """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details.\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, model: $model","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests","quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaDimensions":{"location":"global","model":"$model"},"quotaValue":"0"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"20s"}]}}"""
}

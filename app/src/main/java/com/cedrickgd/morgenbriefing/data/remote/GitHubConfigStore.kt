package com.cedrickgd.morgenbriefing.data.remote

import com.cedrickgd.morgenbriefing.BuildConfig
import com.cedrickgd.morgenbriefing.data.model.EmailConfig
import com.cedrickgd.morgenbriefing.data.model.EmailConfigError
import com.cedrickgd.morgenbriefing.data.model.EmailConfigException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.Locale

/** `config/email.json` as stored on the branch, with the blob [sha] needed to overwrite it. */
data class StoredEmailConfig(
    val config: EmailConfig,
    /** Blob sha; null only if GitHub's write response did not include it (the next write re-reads). */
    val sha: String?,
)

/** Reads and writes the e-mail config; [GitHubConfigStore] in the app, fakes in tests. */
interface EmailConfigRemote {
    /** The stored config, or null if the file does not exist (not configured yet). */
    suspend fun read(token: String): StoredEmailConfig?

    /** Creates ([sha] null) or replaces the file; returns what was written plus the new sha. */
    suspend fun write(token: String, config: EmailConfig, sha: String?): StoredEmailConfig
}

/**
 * `config/email.json` on the briefs branch through the GitHub contents API.
 *
 * * read: `GET /repos/{owner}/{repo}/contents/config/email.json?ref={branch}` with
 *   `Accept: application/vnd.github+json` → base64 content + blob sha; 404 → null.
 * * write: `PUT` on the same path with `{message, content: base64(pretty JSON + "\n"), sha?, branch}`.
 *   A 409/422 (sha mismatch, or the file appeared meanwhile) re-reads the sha and retries once.
 *
 * Needs a fine-grained token with *Contents: Read and write* for writing; 403/404 on a write
 * mean the token may only read ([EmailConfigError.NO_WRITE_PERMISSION]). Every failure is an
 * [EmailConfigException]. The token only ever goes into the `Authorization` header — never into
 * a URL, an exception message or a log line.
 */
class GitHubConfigStore(
    private val http: OkHttpClient,
    json: Json,
    val owner: String,
    val repo: String,
    val branch: String = BuildConfig.BRIEF_BRANCH,
    private val apiUrl: String = "https://api.github.com",
) : EmailConfigRemote {
    /** Future fields of the file or the API envelope must never break the app. */
    private val parser = Json(from = json) { ignoreUnknownKeys = true }

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json(from = json) {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    override suspend fun read(token: String): StoredEmailConfig? {
        val response = send(token, contentsUrl(withRef = true), method = "GET", body = null, what = "Lesen")
        return when (response.code) {
            in 200..299 -> decodeFile(response.body)
            404 -> null
            401 -> fail(EmailConfigError.UNAUTHORIZED, response, "Lesen")
            403 -> fail(
                if (isRateLimit(response)) EmailConfigError.RATE_LIMITED else EmailConfigError.UNAUTHORIZED,
                response,
                "Lesen",
            )
            else -> fail(EmailConfigError.UNKNOWN, response, "Lesen")
        }
    }

    override suspend fun write(token: String, config: EmailConfig, sha: String?): StoredEmailConfig {
        val first = put(token, config, sha)
        if (!first.isShaMismatch()) return handleWrite(first, config)
        // The file changed (or appeared) since we read it: take its current sha and try once more.
        val fresh = read(token)
        val second = put(token, config, fresh?.sha)
        if (second.isShaMismatch()) {
            throw EmailConfigException(
                EmailConfigError.CONFLICT,
                "config/email.json wurde beim Speichern zweimal geändert (HTTP ${second.code})",
            )
        }
        return handleWrite(second, config)
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun put(token: String, config: EmailConfig, sha: String?): HttpText {
        val text = pretty.encodeToString(EmailConfig.serializer(), config) + "\n"
        val payload = buildJsonObject {
            put("message", COMMIT_MESSAGE)
            put("content", text.encodeUtf8().base64())
            if (!sha.isNullOrBlank()) put("sha", sha)
            put("branch", branch)
        }
        val body = parser.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE)
        return send(token, contentsUrl(withRef = false), method = "PUT", body = body, what = "Speichern")
    }

    private fun handleWrite(response: HttpText, written: EmailConfig): StoredEmailConfig = when (response.code) {
        in 200..299 -> StoredEmailConfig(written, newSha(response.body))
        401 -> fail(EmailConfigError.UNAUTHORIZED, response, "Speichern")
        403 -> fail(
            if (isRateLimit(response)) EmailConfigError.RATE_LIMITED else EmailConfigError.NO_WRITE_PERMISSION,
            response,
            "Speichern",
        )
        404 -> fail(EmailConfigError.NO_WRITE_PERMISSION, response, "Speichern")
        else -> fail(EmailConfigError.UNKNOWN, response, "Speichern")
    }

    private fun HttpText.isShaMismatch(): Boolean = code == 409 || code == 422

    private fun decodeFile(body: String): StoredEmailConfig {
        val file = try {
            parser.decodeFromString(ContentFile.serializer(), body)
        } catch (e: SerializationException) {
            throw invalid("GitHub lieferte keine Dateiinfo für ${EmailConfig.PATH}", e)
        } catch (e: IllegalArgumentException) {
            throw invalid("GitHub lieferte keine Dateiinfo für ${EmailConfig.PATH}", e)
        }
        if (file.type != null && file.type != "file") throw invalid("${EmailConfig.PATH} ist keine Datei")
        if (file.encoding != null && file.encoding != "base64") {
            throw invalid("${EmailConfig.PATH} hat eine unerwartete Kodierung (${file.encoding})")
        }
        val text = file.content.decodeBase64()?.utf8() ?: throw invalid("${EmailConfig.PATH} ist nicht lesbar")
        val config = try {
            parser.decodeFromString(EmailConfig.serializer(), text)
        } catch (e: SerializationException) {
            throw invalid("${EmailConfig.PATH} ist kein gültiges JSON", e)
        } catch (e: IllegalArgumentException) {
            throw invalid("${EmailConfig.PATH} ist kein gültiges JSON", e)
        }
        return StoredEmailConfig(config.normalized(), file.sha)
    }

    /** The new blob sha from a PUT response; null if GitHub left it out. */
    private fun newSha(body: String): String? = try {
        parser.decodeFromString(WriteResponse.serializer(), body).content?.sha?.takeIf { it.isNotBlank() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun contentsUrl(withRef: Boolean): HttpUrl = apiUrl.toHttpUrl().newBuilder()
        .addPathSegment("repos")
        .addPathSegment(owner)
        .addPathSegment(repo)
        .addPathSegment("contents")
        .addPathSegments(EmailConfig.PATH)
        .apply { if (withRef) addQueryParameter("ref", branch) }
        .build()

    /** One authenticated request; returns any HTTP status, throws only for transport problems. */
    private suspend fun send(token: String, url: HttpUrl, method: String, body: RequestBody?, what: String): HttpText {
        val clean = token.trim()
        if (clean.isEmpty()) throw EmailConfigException(EmailConfigError.NO_TOKEN, "Kein GitHub-Token hinterlegt")
        val request = try {
            Request.Builder()
                .url(url)
                .header("Accept", ACCEPT)
                .header("Authorization", "Bearer $clean")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", USER_AGENT)
                .method(method, body)
                .build()
        } catch (_: IllegalArgumentException) {
            // Header validation failed (e.g. a pasted line break). Deliberately no cause:
            // OkHttp's message could quote the header value, i.e. the token.
            throw EmailConfigException(EmailConfigError.UNAUTHORIZED, "Der GitHub-Token enthält ungültige Zeichen")
        }
        return try {
            http.fetchText(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw EmailConfigException(EmailConfigError.NETWORK, "GitHub nicht erreichbar (E-Mail-Einstellung $what)", e)
        } catch (e: Exception) {
            throw EmailConfigException(EmailConfigError.UNKNOWN, "Unerwarteter Fehler (E-Mail-Einstellung $what)", e)
        }
    }

    /** GitHub answers an exhausted rate limit with 403 and a "rate limit" message. */
    private fun isRateLimit(response: HttpText): Boolean =
        response.body.lowercase(Locale.ROOT).contains("rate limit")

    private fun fail(kind: EmailConfigError, response: HttpText, what: String): Nothing =
        throw EmailConfigException(kind, "GitHub antwortet mit HTTP ${response.code} (E-Mail-Einstellung $what)")

    private fun invalid(message: String, cause: Throwable? = null) =
        EmailConfigException(EmailConfigError.INVALID, message, cause)

    /** The parts of the contents API's file object we need. */
    @Serializable
    private data class ContentFile(
        val type: String? = null,
        val encoding: String? = null,
        val content: String = "",
        val sha: String,
    )

    @Serializable
    private data class WriteResponse(val content: WrittenFile? = null)

    @Serializable
    private data class WrittenFile(val sha: String? = null)

    private companion object {
        const val API_VERSION = "2022-11-28"
        const val ACCEPT = "application/vnd.github+json"
        const val COMMIT_MESSAGE = "config: E-Mail-Briefing aktualisiert"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

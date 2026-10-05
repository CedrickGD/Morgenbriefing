package com.cedrickgd.morgenbriefing.data.remote

import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.BriefIndex
import com.cedrickgd.morgenbriefing.data.model.BriefSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads the briefs branch of the private repo through the GitHub contents API:
 * `GET https://api.github.com/repos/{owner}/{repo}/contents/{path}?ref={branch}` with
 * `Accept: application/vnd.github.raw+json`, `Authorization: Bearer <token>`,
 * `X-GitHub-Api-Version: 2022-11-28`, `Cache-Control: no-cache`.
 * Every failure is a [com.cedrickgd.morgenbriefing.data.model.BriefSourceException].
 *
 * The token is only ever placed in the `Authorization` header — never in a URL, an
 * exception message or a log line.
 */
class GitHubBriefSource(
    private val http: OkHttpClient,
    private val json: Json,
    val owner: String,
    val repo: String,
    val branch: String,
    private val apiUrl: String = "https://api.github.com",
) {
    /** The cloud routine may add fields over time; unknown keys must never break the app. */
    private val parser = Json(from = json) { ignoreUnknownKeys = true }

    /** `latest.json` */
    suspend fun latest(token: String): Brief = content(token, "latest.json", BRIEF).also(::requireValidDate)

    /** `briefs/YYYY-MM-DD.json` */
    suspend fun day(token: String, date: LocalDate): Brief =
        content(token, "briefs/$date.json", BRIEF).also(::requireValidDate)

    /** `index.json` */
    suspend fun index(token: String): BriefIndex = content(token, "index.json", BriefIndex.serializer())

    /** Cheap access check for the settings screen (GET the repo). */
    suspend fun checkAccess(token: String) {
        val url = repoUrl().build()
        request(token, url, accept = "application/vnd.github+json", what = "Repository")
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun <T> content(token: String, path: String, strategy: DeserializationStrategy<T>): T {
        val url = repoUrl()
            .addPathSegment("contents")
            .addPathSegments(path)
            .addQueryParameter("ref", branch)
            .build()
        val body = request(token, url, accept = "application/vnd.github.raw+json", what = path)
        return try {
            parser.decodeFromString(strategy, body)
        } catch (e: SerializationException) {
            throw BriefSourceException(BriefErrorKind.PARSE, "$path ist kein gültiges Briefing-JSON", e)
        } catch (e: IllegalArgumentException) {
            throw BriefSourceException(BriefErrorKind.PARSE, "$path ist kein gültiges Briefing-JSON", e)
        }
    }

    private fun repoUrl(): HttpUrl.Builder = apiUrl.toHttpUrl().newBuilder()
        .addPathSegment("repos")
        .addPathSegment(owner)
        .addPathSegment(repo)

    /** Performs one authenticated GET and returns the body of a 2xx response. */
    private suspend fun request(token: String, url: HttpUrl, accept: String, what: String): String {
        val clean = token.trim()
        if (clean.isEmpty()) throw BriefSourceException(BriefErrorKind.NO_TOKEN, "Kein GitHub-Token hinterlegt")
        val request = try {
            Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("Authorization", "Bearer $clean")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        } catch (_: IllegalArgumentException) {
            // Header validation failed (e.g. a pasted line break). Deliberately no cause:
            // OkHttp's message could quote the header value, i.e. the token.
            throw BriefSourceException(BriefErrorKind.UNAUTHORIZED, "Der GitHub-Token enthält ungültige Zeichen")
        }

        val response = try {
            http.fetchText(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw BriefSourceException(BriefErrorKind.NETWORK, "GitHub nicht erreichbar ($what)", e)
        } catch (e: Exception) {
            throw BriefSourceException(BriefErrorKind.UNKNOWN, "Unerwarteter Fehler beim Laden von $what", e)
        }

        val kind = when (response.code) {
            in 200..299 -> return response.body
            401, 403 -> BriefErrorKind.UNAUTHORIZED
            404 -> BriefErrorKind.NOT_FOUND
            else -> BriefErrorKind.UNKNOWN
        }
        throw BriefSourceException(kind, "GitHub antwortet mit HTTP ${response.code} ($what)")
    }

    private fun requireValidDate(brief: Brief) {
        try {
            LocalDate.parse(brief.date)
        } catch (e: DateTimeParseException) {
            throw BriefSourceException(BriefErrorKind.PARSE, "Briefing hat ein ungültiges Datum: ${brief.date}", e)
        }
    }

    private companion object {
        const val API_VERSION = "2022-11-28"
        val BRIEF = Brief.serializer()
    }
}

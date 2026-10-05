package com.cedrickgd.morgenbriefing.data.remote

import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.BriefIndex
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.LocalDate

/**
 * Reads the briefs branch of the private repo through the GitHub contents API:
 * `GET https://api.github.com/repos/{owner}/{repo}/contents/{path}?ref={branch}` with
 * `Accept: application/vnd.github.raw+json`, `Authorization: Bearer <token>`,
 * `X-GitHub-Api-Version: 2022-11-28`, `Cache-Control: no-cache`.
 * Every failure is a [com.cedrickgd.morgenbriefing.data.model.BriefSourceException].
 */
class GitHubBriefSource(
    private val http: OkHttpClient,
    private val json: Json,
    val owner: String,
    val repo: String,
    val branch: String,
    private val apiUrl: String = "https://api.github.com",
) {
    /** `latest.json` */
    suspend fun latest(token: String): Brief = TODO("agent A")

    /** `briefs/YYYY-MM-DD.json` */
    suspend fun day(token: String, date: LocalDate): Brief = TODO("agent A")

    /** `index.json` */
    suspend fun index(token: String): BriefIndex = TODO("agent A")

    /** Cheap access check for the settings screen (GET the repo). */
    suspend fun checkAccess(token: String) { TODO("agent A") }
}

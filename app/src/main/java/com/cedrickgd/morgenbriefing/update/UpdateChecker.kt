package com.cedrickgd.morgenbriefing.update

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Asks GitHub for the newest release of the (private) app repo:
 * `GET {api}/repos/{owner}/{repo}/releases/latest` (public repo,
 * no token) and decides whether it is an update for [installedVersionName].
 */
class UpdateChecker(
    private val http: OkHttpClient,
    json: Json,
    private val owner: String,
    private val repo: String,
    installedVersionName: String,
    private val apiUrl: HttpUrl = GITHUB_API_URL,
) {
    private val parser = Json(from = json) { ignoreUnknownKeys = true }

    /** The running app's version (`-debug` stripped). Unparsable names count as 0.0.0. */
    val installed: SemVer = SemVer.parseInstalled(installedVersionName) ?: SemVer(0, 0, 0).also {
        Log.w(TAG, "Installed versionName '$installedVersionName' is not a semantic version")
    }

    /** Never throws (except for cancellation): every failure is a [UpdateCheckResult.Failed]. */
    suspend fun check(): UpdateCheckResult {
        val url = apiUrl.repoUrl(owner, repo, "releases", "latest")
        val request = gitHubRequest(url, accept = "application/vnd.github+json")

        val body = try {
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) return UpdateCheckResult.Failed(response.toUpdateError())
                withContext(Dispatchers.IO) { response.body.string() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.i(TAG, "Release check failed: ${e.javaClass.simpleName}")
            return UpdateCheckResult.Failed(UpdateError.NETWORK)
        }

        val release = try {
            parser.decodeFromString(GitHubRelease.serializer(), body)
        } catch (e: SerializationException) {
            Log.w(TAG, "Unexpected release JSON", e)
            return UpdateCheckResult.Failed(UpdateError.SERVER)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unexpected release JSON", e)
            return UpdateCheckResult.Failed(UpdateError.SERVER)
        }
        return ReleaseSelection.evaluate(release, installed)
    }

    private companion object {
        const val TAG = "UpdateChecker"
    }
}

/** Pure release logic: which asset to download and whether a release is an update. */
internal object ReleaseSelection {

    /**
     * The asset to install: a completely uploaded file ending in `.apk`. If a release carries
     * several, one without "debug" in its name wins.
     */
    fun selectApk(assets: List<GitHubAsset>): GitHubAsset? {
        val apks = assets.filter { asset ->
            asset.id > 0 &&
                asset.state.equals("uploaded", ignoreCase = true) &&
                asset.name.trim().endsWith(".apk", ignoreCase = true)
        }
        return apks.firstOrNull { !it.name.contains("debug", ignoreCase = true) } ?: apks.firstOrNull()
    }

    /**
     * Compares [release] with [installed]. A release GitHub marks as pre-release counts as one
     * even if its tag has no `-suffix`; pre-releases are only offered to pre-release installs.
     */
    fun evaluate(release: GitHubRelease, installed: SemVer): UpdateCheckResult {
        val version = SemVer.parse(release.tagName)
            ?: return UpdateCheckResult.Failed(UpdateError.INVALID_RELEASE)
        val preRelease = version.isPreRelease || release.prerelease
        val offered = !release.draft && (!preRelease || installed.isPreRelease) && version > installed
        if (!offered) return UpdateCheckResult.UpToDate(version.toString())

        val asset = selectApk(release.assets) ?: return UpdateCheckResult.Failed(UpdateError.NO_APK)
        return UpdateCheckResult.Available(
            ReleaseInfo(
                tag = release.tagName.trim(),
                version = version.toString(),
                title = release.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Morgenbriefing $version",
                notes = release.body?.trim().orEmpty(),
                publishedAt = release.publishedAt?.takeIf { it.isNotBlank() },
                apk = ApkAsset(id = asset.id, name = asset.name.trim(), size = asset.size.coerceAtLeast(0)),
            ),
        )
    }
}

package com.cedrickgd.morgenbriefing.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A newer release the app can install: the parsed tag plus the APK asset to download.
 * Also persisted by [UpdateStore] (JSON), so the settings screen can show an update the
 * background check found.
 */
@Serializable
data class ReleaseInfo(
    /** Git tag as published, e.g. `v1.2.0`. */
    val tag: String,
    /** Normalized version without prefix, e.g. `1.2.0` (see [SemVer.toString]). */
    val version: String,
    /** Release title; falls back to "Morgenbriefing {version}". */
    val title: String,
    /** Release notes (GitHub markdown), may be empty. */
    val notes: String = "",
    /** ISO-8601 `published_at`, if GitHub sent one. */
    val publishedAt: String? = null,
    val apk: ApkAsset,
) {
    /** Null only if a stored entry was written by an incompatible app version. */
    val semVer: SemVer? get() = SemVer.parse(version)
}

/** The release asset ending in `.apk`. Downloaded by id, never by a URL from the JSON. */
@Serializable
data class ApkAsset(
    val id: Long,
    val name: String,
    /** Size in bytes as reported by GitHub; 0 when unknown. */
    val size: Long = 0,
)

/** Outcome of [UpdateChecker.check]. */
sealed interface UpdateCheckResult {
    /** The newest release is not newer than the installed app (or a pre-release we skip). */
    data class UpToDate(val latestVersion: String) : UpdateCheckResult

    data class Available(val release: ReleaseInfo) : UpdateCheckResult

    data class Failed(val error: UpdateError) : UpdateCheckResult
}

/** Everything that can go wrong between "Nach Updates suchen" and the system installer. */
enum class UpdateError {
    /** No GitHub token stored – the repo is private. */
    NO_TOKEN,

    /** 401/403: token invalid, expired or without access to the repo. */
    UNAUTHORIZED,

    /** 404: no release published yet (or the token cannot see the repo). */
    NOT_FOUND,

    /** GitHub's API rate limit is exhausted (403/429 with `x-ratelimit-remaining: 0`). */
    RATE_LIMITED,

    NETWORK,

    /** Unexpected HTTP status or a response that is not the expected JSON. */
    SERVER,

    /** The latest release's tag is not a version number. */
    INVALID_RELEASE,

    /** The newer release has no `.apk` asset. */
    NO_APK,

    /** Fewer/more bytes than announced arrived. */
    INCOMPLETE_DOWNLOAD,

    /** Not enough free space, or the cache directory cannot be written. */
    STORAGE,

    /** The downloaded file is not a readable APK. */
    APK_UNREADABLE,

    /** The APK belongs to another package (e.g. a release APK on the debug build). */
    WRONG_PACKAGE,

    /** The APK is not signed with the installed app's certificate. */
    WRONG_SIGNATURE,

    /** The APK's versionCode is not higher than the installed one. */
    NOT_NEWER,

    /** No activity on the device can install packages. */
    INSTALLER_MISSING,
}

/** Thrown inside the update package; the [error] is what the UI shows. Never carries the token. */
class UpdateException(val error: UpdateError, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: error.name, cause)

/** Bytes of the APK received so far; [total] is 0 or negative when the size is unknown. */
data class DownloadProgress(val received: Long, val total: Long) {
    /** 0..1, or null while the size is unknown. */
    val fraction: Float? get() = if (total > 0) (received.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
}

// ---------------------------------------------------------------------------------------------
// GitHub REST payloads (only the fields we use; everything else is ignored)
// ---------------------------------------------------------------------------------------------

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val id: Long = 0,
    val name: String = "",
    val size: Long = 0,
    /** `uploaded` once the file is complete; `open` while an upload is still running. */
    val state: String = "uploaded",
)

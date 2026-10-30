package com.cedrickgd.morgenbriefing.update

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads a release APK from the private repo into [directory] (`cache/updates/`, shared with
 * the installer through the FileProvider in `res/xml/update_paths.xml`).
 *
 * `GET {api}/repos/{owner}/{repo}/releases/assets/{id}` with `Accept: application/octet-stream`
 * answers with a redirect to GitHub's storage host. OkHttp follows it and removes the
 * `Authorization` header because the host changes; [TokenHostGuard] enforces the same rule on
 * every hop, so the token never leaves [apiUrl]'s host. Redirects that switch between HTTPS and
 * HTTP are not followed at all.
 */
class ApkDownloader(
    http: OkHttpClient,
    private val owner: String,
    private val repo: String,
    private val directory: File,
    private val apiUrl: HttpUrl = GITHUB_API_URL,
) {
    /** Same connection pool, but no overall call limit (APKs are megabytes); stalls still time out. */
    private val client: OkHttpClient = http.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .addNetworkInterceptor(TokenHostGuard(apiUrl.host))
        .build()

    /** Where [release] ends up: `Morgenbriefing-{tag}.apk`. */
    fun targetFile(release: ReleaseInfo): File = File(directory, "Morgenbriefing-${safeName(release.tag)}.apk")

    /**
     * Downloads [release]'s APK and returns the finished file. A complete file from an earlier
     * attempt is reused; every other file in [directory] is deleted first.
     *
     * @param onProgress called on the IO thread, at most about every [PROGRESS_STEP] of the file.
     * @throws UpdateException with UNAUTHORIZED, NOT_FOUND, RATE_LIMITED, NETWORK, SERVER,
     *   INCOMPLETE_DOWNLOAD or STORAGE.
     */
    suspend fun download(
        release: ReleaseInfo,
        onProgress: (DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = targetFile(release)
        prepareDirectory(keep = target)

        val expected = release.apk.size
        if (expected > 0 && target.isFile && target.length() == expected) {
            onProgress(DownloadProgress(expected, expected))
            return@withContext target
        }
        target.delete()

        val url = apiUrl.repoUrl(owner, repo, "releases", "assets", release.apk.id.toString())
        val request = gitHubRequest(url, accept = "application/octet-stream")

        val partial = File(directory, "${target.name}.part")
        val call = client.newCall(request)
        try {
            coroutineScope {
                // A blocking socket read ignores coroutine cancellation; this sibling closes the
                // call (and with it the socket) as soon as the download is cancelled.
                val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.await().use { response -> receive(response, partial, expected, onProgress) }
                } finally {
                    canceller.cancel()
                }
            }
            if (!partial.renameTo(target)) throw UpdateException(UpdateError.STORAGE, "Could not move the download into place")
            target
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: UpdateException) {
            partial.delete()
            throw e
        } catch (e: IOException) {
            partial.delete()
            currentCoroutineContext().ensureActive()
            throw UpdateException(UpdateError.NETWORK, "Download interrupted", e)
        }
    }

    /** Removes every downloaded file (after a successful update or when nothing is pending). */
    fun clear() {
        directory.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Keeps only [keep] (if it exists); stale APKs and broken `.part` files go. */
    private fun prepareDirectory(keep: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw UpdateException(UpdateError.STORAGE, "Cannot create ${directory.name}")
        }
        directory.listFiles()?.forEach { file -> if (file != keep) file.deleteRecursively() }
    }

    private suspend fun receive(
        response: Response,
        partial: File,
        expected: Long,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        if (!response.isSuccessful) {
            if (response.isRedirect) {
                // Only happens for an HTTPS→HTTP hop, which we refuse to follow.
                throw UpdateException(UpdateError.SERVER, "Refused redirect (HTTP ${response.code})")
            }
            throw UpdateException(response.toUpdateError(), "HTTP ${response.code}")
        }
        val body = response.body
        val announced = body.contentLength()
        if (announced >= 0 && expected > 0 && announced != expected) {
            throw UpdateException(UpdateError.INCOMPLETE_DOWNLOAD, "Content-Length $announced, expected $expected")
        }
        val total = if (announced >= 0) announced else expected
        // usableSpace is 0 when the platform cannot tell; only refuse on a real, too small number.
        if (total > 0 && directory.usableSpace in 1 until total + SPACE_RESERVE) {
            throw UpdateException(UpdateError.STORAGE, "Not enough free space")
        }

        var received = 0L
        var reportedAt = -1L
        val step = if (total > 0) maxOf((total * PROGRESS_STEP).toLong(), MIN_PROGRESS_BYTES) else MIN_PROGRESS_BYTES
        onProgress(DownloadProgress(0, total))
        val output = try {
            partial.outputStream()
        } catch (e: IOException) {
            throw UpdateException(UpdateError.STORAGE, "Cannot write ${partial.name}", e)
        }
        output.use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    try {
                        out.write(buffer, 0, read)
                    } catch (e: IOException) {
                        throw UpdateException(UpdateError.STORAGE, "Writing the APK failed", e)
                    }
                    received += read
                    if (total > 0 && received > total) {
                        throw UpdateException(UpdateError.INCOMPLETE_DOWNLOAD, "More bytes than announced")
                    }
                    if (received - reportedAt >= step) {
                        reportedAt = received
                        onProgress(DownloadProgress(received, total))
                    }
                }
            }
        }
        if (total > 0 && received != total) {
            throw UpdateException(UpdateError.INCOMPLETE_DOWNLOAD, "Received $received of $total bytes")
        }
        if (received == 0L) throw UpdateException(UpdateError.INCOMPLETE_DOWNLOAD, "Empty download")
        onProgress(DownloadProgress(received, if (total > 0) total else received))
        Log.i(TAG, "Downloaded ${partial.name} ($received bytes)")
    }

    internal companion object {
        private const val TAG = "ApkDownloader"
        private const val READ_TIMEOUT_S = 30L
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP = 0.01
        private const val MIN_PROGRESS_BYTES = 128L * 1024
        private const val SPACE_RESERVE = 5L * 1024 * 1024

        /** Tag as a file name part: letters, digits, dot, dash, underscore. */
        fun safeName(tag: String): String =
            tag.trim().replace(Regex("[^0-9A-Za-z._-]"), "_").ifEmpty { "update" }
    }
}

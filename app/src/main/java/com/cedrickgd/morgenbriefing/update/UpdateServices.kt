package com.cedrickgd.morgenbriefing.update

import android.content.Context
import android.util.Log
import com.cedrickgd.morgenbriefing.AppContainer
import com.cedrickgd.morgenbriefing.BuildConfig
import com.cedrickgd.morgenbriefing.MorgenbriefingApp
import java.io.File

/**
 * The updater's object graph, built lazily from the shared [AppContainer] pieces (same OkHttp
 * client and JSON setup). One instance per process, owned by [MorgenbriefingApp].
 */
class UpdateServices(context: Context, container: AppContainer) {
    private val app = context.applicationContext

    val store = UpdateStore(app, container.json)

    val checker = UpdateChecker(
        http = container.http,
        json = container.json,
        owner = BuildConfig.BRIEF_OWNER,
        repo = BuildConfig.BRIEF_REPO,
        installedVersionName = BuildConfig.VERSION_NAME,
    )

    val downloader = ApkDownloader(
        http = container.http,
        owner = BuildConfig.BRIEF_OWNER,
        repo = BuildConfig.BRIEF_REPO,
        directory = File(app.cacheDir, UPDATES_DIR),
    )

    /** Shortcut for the installed version (`-debug` stripped). */
    val installed: SemVer get() = checker.installed

    /**
     * After the app was updated (or a pending release became obsolete), forget the pending
     * release and delete downloaded APKs. Blocking; call off the main thread.
     */
    fun pruneAfterUpdate() {
        try {
            if (store.pendingRelease(installed) == null) {
                store.clearPending()
                downloader.clear()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not tidy up old updates", e)
        }
    }

    companion object {
        private const val TAG = "UpdateServices"

        /** Must match the cache-path in res/xml/update_paths.xml. */
        const val UPDATES_DIR = "updates"
    }
}

/** The process-wide [UpdateServices]. */
val Context.updates: UpdateServices
    get() = (applicationContext as MorgenbriefingApp).updates

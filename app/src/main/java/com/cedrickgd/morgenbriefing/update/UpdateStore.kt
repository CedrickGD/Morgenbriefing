package com.cedrickgd.morgenbriefing.update

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Small persistent memory of the updater (SharedPreferences "updates"): when GitHub was last
 * asked successfully, which newer release is waiting, and which tag was already announced by
 * a notification (each release is announced once). Blocking — call off the main thread.
 */
class UpdateStore(context: Context, private val json: Json) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Epoch millis of the last check that reached GitHub and got an answer, or null. */
    fun lastCheckAt(): Long? = prefs.getLong(KEY_LAST_CHECK_AT, 0L).takeIf { it > 0L }

    /** The newer release found by the last check, if it still is newer than [installed]. */
    fun pendingRelease(installed: SemVer): ReleaseInfo? {
        val raw = prefs.getString(KEY_PENDING, null) ?: return null
        val release = try {
            json.decodeFromString(ReleaseInfo.serializer(), raw)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        val version = release?.semVer
        return if (release != null && version != null && SemVer.isUpdate(installed, version)) release else null
    }

    /** Remembers the outcome of a check; failures leave everything as it was. */
    fun record(result: UpdateCheckResult, now: Long = System.currentTimeMillis()) {
        when (result) {
            is UpdateCheckResult.Available -> prefs.edit {
                putLong(KEY_LAST_CHECK_AT, now)
                putString(KEY_PENDING, json.encodeToString(ReleaseInfo.serializer(), result.release))
            }
            is UpdateCheckResult.UpToDate -> prefs.edit {
                putLong(KEY_LAST_CHECK_AT, now)
                remove(KEY_PENDING)
            }
            is UpdateCheckResult.Failed -> Unit
        }
    }

    /** Forgets the pending release (it was installed or is obsolete). */
    fun clearPending() {
        prefs.edit { remove(KEY_PENDING) }
    }

    fun wasAnnounced(tag: String): Boolean = prefs.getString(KEY_ANNOUNCED_TAG, null) == tag

    fun markAnnounced(tag: String) {
        prefs.edit { putString(KEY_ANNOUNCED_TAG, tag) }
    }

    private companion object {
        const val PREFS_NAME = "updates"
        const val KEY_LAST_CHECK_AT = "last_check_at"
        const val KEY_PENDING = "pending_release"
        const val KEY_ANNOUNCED_TAG = "announced_tag"
    }
}

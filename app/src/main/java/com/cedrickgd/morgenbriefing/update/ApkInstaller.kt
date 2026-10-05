package com.cedrickgd.morgenbriefing.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a verified APK to the system package installer.
 *
 * API 26+ needs the per-app "install unknown apps" permission (REQUEST_INSTALL_PACKAGES is
 * declared); [install] reports [Launch.NeedsPermission] until the user allowed it once in the
 * screen [openPermissionSettings] opens. Below API 26 the installer asks itself.
 */
object ApkInstaller {
    private const val TAG = "ApkInstaller"
    private const val APK_MIME = "application/vnd.android.package-archive"

    sealed interface Launch {
        /** The system install dialog is open. */
        data object Started : Launch

        /** The user first has to allow this app to install updates. */
        data object NeedsPermission : Launch

        data class Failed(val error: UpdateError) : Launch
    }

    /** FileProvider authority from the manifest: `${applicationId}.updates`. */
    fun authority(context: Context): String = "${context.packageName}.updates"

    /** Whether this app may open the package installer (always true below API 26). */
    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /**
     * Opens "Unbekannte Apps installieren" for this app (API 26+), falling back to the app info
     * page. Returns whether a screen opened.
     */
    fun openPermissionSettings(context: Context): Boolean {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "No activity for ${intent.action}")
            } catch (e: SecurityException) {
                Log.i(TAG, "Not allowed to open ${intent.action}")
            }
        }
        return false
    }

    /** Opens the system install dialog for [apk] (a file under `cache/updates/`). */
    fun install(context: Context, apk: File): Launch {
        if (!canRequestInstalls(context)) return Launch.NeedsPermission
        val uri = try {
            FileProvider.getUriForFile(context, authority(context), apk)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "APK is outside the shared update directory", e)
            return Launch.Failed(UpdateError.STORAGE)
        }
        val intent = installIntent(uri)
        return try {
            context.startActivity(intent)
            Launch.Started
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No package installer found", e)
            Launch.Failed(UpdateError.INSTALLER_MISSING)
        } catch (e: SecurityException) {
            Log.w(TAG, "Package installer refused the request", e)
            Launch.NeedsPermission
        }
    }

    /** ACTION_VIEW on API 29+ (ACTION_INSTALL_PACKAGE is deprecated there), else INSTALL_PACKAGE. */
    private fun installIntent(uri: Uri): Intent {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent.ACTION_VIEW
        } else {
            @Suppress("DEPRECATION")
            Intent.ACTION_INSTALL_PACKAGE
        }
        return Intent(action)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

package com.cedrickgd.morgenbriefing.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * What decides whether an APK may replace the installed app: package, version code and the
 * signing certificates (SHA-256 of each certificate).
 */
data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    /** Certificates that sign the APK now. */
    val signers: Set<String>,
    /** Older certificates the APK proves it rotated away from (API 28+ key rotation). */
    val pastSigners: Set<String> = emptySet(),
)

/**
 * Checks a downloaded APK before it is handed to the system installer: it must be this very
 * app (same package name), signed with the installed app's certificate and have a higher
 * version code. Android would reject a wrongly signed APK itself, but only after the user
 * confirmed the install dialog — this check refuses early with a clear message.
 */
object ApkVerifier {
    private const val TAG = "ApkVerifier"

    /** Null when [apk] may be installed, otherwise the reason it must not. Blocking (disk). */
    fun verify(context: Context, apk: File): UpdateError? {
        val installed = readInstalled(context) ?: return UpdateError.WRONG_SIGNATURE.also {
            Log.w(TAG, "Cannot read the installed app's signature")
        }
        val candidate = readArchive(context, apk)
        return compare(installed, candidate)
    }

    /** The decision itself, pure and unit-tested. [candidate] null = unreadable APK. */
    internal fun compare(installed: ApkIdentity, candidate: ApkIdentity?): UpdateError? = when {
        candidate == null -> UpdateError.APK_UNREADABLE
        candidate.packageName != installed.packageName -> UpdateError.WRONG_PACKAGE
        !sameSigner(installed, candidate) -> UpdateError.WRONG_SIGNATURE
        candidate.versionCode <= installed.versionCode -> UpdateError.NOT_NEWER
        else -> null
    }

    /**
     * Single signer: the installed certificate must be the candidate's current one or part of
     * its rotation lineage (what Android itself accepts for an update). Several signers (legacy
     * multi-signer APKs): the sets must match exactly. No certificates at all never match.
     */
    private fun sameSigner(installed: ApkIdentity, candidate: ApkIdentity): Boolean {
        if (installed.signers.isEmpty() || candidate.signers.isEmpty()) return false
        return if (installed.signers.size == 1 && candidate.signers.size == 1) {
            installed.signers.single() in candidate.signers + candidate.pastSigners
        } else {
            installed.signers == candidate.signers
        }
    }

    private fun readInstalled(context: Context): ApkIdentity? = try {
        val info = packageInfo(context.packageManager, context.packageName)
        info?.toIdentity()
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "Own package not found", e)
        null
    }

    private fun readArchive(context: Context, apk: File): ApkIdentity? {
        if (!apk.isFile || apk.length() == 0L) return null
        return try {
            archiveInfo(context.packageManager, apk.absolutePath)?.toIdentity()
        } catch (e: RuntimeException) {
            // Broken archives can make the parser throw instead of returning null on old versions.
            Log.w(TAG, "APK could not be parsed", e)
            null
        }
    }

    private fun PackageInfo.toIdentity(): ApkIdentity? {
        val name = packageName ?: return null
        val (current, past) = certificates(this)
        return ApkIdentity(
            packageName = name,
            versionCode = PackageInfoCompat.getLongVersionCode(this),
            signers = current,
            pastSigners = past,
        )
    }

    /** Current signers and (API 28+) the older certificates of a key rotation. */
    private fun certificates(info: PackageInfo): Pair<Set<String>, Set<String>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            certificatesFromSigningInfo(info)
        } else {
            legacySignatures(info).map(::sha256).toSet() to emptySet()
        }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun certificatesFromSigningInfo(info: PackageInfo): Pair<Set<String>, Set<String>> {
        val signing = info.signingInfo ?: return emptySet<String>() to emptySet()
        val current = signing.apkContentsSigners.orEmpty().map(::sha256).toSet()
        val past = if (signing.hasMultipleSigners()) {
            emptySet()
        } else {
            signing.signingCertificateHistory.orEmpty().map(::sha256).toSet() - current
        }
        return current to past
    }

    @Suppress("DEPRECATION")
    private fun legacySignatures(info: PackageInfo): List<Signature> = info.signatures.orEmpty().filterNotNull()

    @SuppressLint("PackageManagerGetSignatures")
    private fun packageInfo(pm: PackageManager, packageName: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(signatureFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, signatureFlags())
        }

    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(signatureFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, signatureFlags())
        }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    private fun sha256(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

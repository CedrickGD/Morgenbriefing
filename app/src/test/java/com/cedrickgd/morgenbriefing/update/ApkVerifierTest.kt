package com.cedrickgd.morgenbriefing.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkVerifierTest {
    private val installed = ApkIdentity(
        packageName = "com.cedrickgd.morgenbriefing",
        versionCode = 3,
        signers = setOf(KEY_A),
    )

    @Test
    fun `same package, same key and higher version code may be installed`() {
        assertNull(ApkVerifier.compare(installed, installed.copy(versionCode = 4)))
    }

    @Test
    fun `unreadable apk is refused`() {
        assertEquals(UpdateError.APK_UNREADABLE, ApkVerifier.compare(installed, null))
    }

    @Test
    fun `other package is refused`() {
        // e.g. a release APK offered to the ".debug" build
        val debugInstall = installed.copy(packageName = "com.cedrickgd.morgenbriefing.debug")
        assertEquals(UpdateError.WRONG_PACKAGE, ApkVerifier.compare(debugInstall, installed.copy(versionCode = 9)))
    }

    @Test
    fun `other signing key is refused`() {
        val foreign = installed.copy(versionCode = 9, signers = setOf(KEY_B))
        assertEquals(UpdateError.WRONG_SIGNATURE, ApkVerifier.compare(installed, foreign))
        assertEquals(UpdateError.WRONG_SIGNATURE, ApkVerifier.compare(installed, foreign.copy(signers = emptySet())))
        assertEquals(
            UpdateError.WRONG_SIGNATURE,
            ApkVerifier.compare(installed.copy(signers = emptySet()), installed.copy(versionCode = 9, signers = emptySet())),
        )
    }

    @Test
    fun `rotated key with the installed key in its lineage is accepted`() {
        val rotated = ApkIdentity(installed.packageName, versionCode = 9, signers = setOf(KEY_B), pastSigners = setOf(KEY_A))
        assertNull(ApkVerifier.compare(installed, rotated))
        // The reverse (installed app rotated, update signed with the old key) is not an update.
        assertEquals(UpdateError.WRONG_SIGNATURE, ApkVerifier.compare(rotated.copy(versionCode = 1), installed.copy(versionCode = 10)))
    }

    @Test
    fun `multiple signers must match exactly`() {
        val both = installed.copy(signers = setOf(KEY_A, KEY_B))
        assertNull(ApkVerifier.compare(both, both.copy(versionCode = 9)))
        assertEquals(UpdateError.WRONG_SIGNATURE, ApkVerifier.compare(both, installed.copy(versionCode = 9)))
        assertEquals(UpdateError.WRONG_SIGNATURE, ApkVerifier.compare(installed, both.copy(versionCode = 9)))
    }

    @Test
    fun `same or lower version code is refused`() {
        assertEquals(UpdateError.NOT_NEWER, ApkVerifier.compare(installed, installed))
        assertEquals(UpdateError.NOT_NEWER, ApkVerifier.compare(installed, installed.copy(versionCode = 2)))
    }

    @Test
    fun `package and signature are checked before the version`() {
        val wrongEverything = ApkIdentity("evil.app", versionCode = 1, signers = setOf(KEY_B))
        assertEquals(UpdateError.WRONG_PACKAGE, ApkVerifier.compare(installed, wrongEverything))
        assertEquals(
            UpdateError.WRONG_SIGNATURE,
            ApkVerifier.compare(installed, wrongEverything.copy(packageName = installed.packageName)),
        )
    }

    private companion object {
        const val KEY_A = "a1b2c3"
        const val KEY_B = "d4e5f6"
    }
}

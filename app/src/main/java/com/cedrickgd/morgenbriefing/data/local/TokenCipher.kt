package com.cedrickgd.morgenbriefing.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable key in the AndroidKeyStore (alias "mb_token_key",
 * available on API 23+). Output: Base64(iv || ciphertext). [decrypt] returns null if the
 * key is gone (e.g. after a backup restore) instead of throwing.
 */
object TokenCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "mb_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * Encrypts [plain] with a fresh random IV chosen by the keystore.
     * A broken key entry is replaced once before giving up.
     * @throws GeneralSecurityException (or [ProviderException]) if the keystore is unusable.
     */
    fun encrypt(plain: String): String {
        val data = plain.toByteArray(Charsets.UTF_8)
        val sealed = try {
            seal(getOrCreateKey(), data)
        } catch (_: GeneralSecurityException) {
            seal(recreateKey(), data)
        } catch (_: ProviderException) {
            seal(recreateKey(), data)
        }
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** The plain text, or null if [encoded] is malformed, tampered with or the key is gone. */
    fun decrypt(encoded: String): String? = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val key = existingKey()
        if (key == null || bytes.size <= IV_BYTES) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Whether the key entry exists: `false` means everything encrypted earlier is lost for
     * good (e.g. restored onto a new device); `null` means the keystore could not be asked.
     */
    internal fun hasKey(): Boolean? = try {
        keyStore().containsAlias(ALIAS)
    } catch (_: Exception) {
        null
    }

    private fun seal(key: SecretKey, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "Unerwartete IV-Länge vom Keystore" }
        return iv + cipher.doFinal(data)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(ALIAS, null) as? SecretKey

    @Synchronized
    private fun getOrCreateKey(): SecretKey = existingKey() ?: generateKey()

    @Synchronized
    private fun recreateKey(): SecretKey {
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (_: Exception) {
            // Nothing to delete or keystore hiccup — generating a new entry replaces it anyway.
        }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }
}

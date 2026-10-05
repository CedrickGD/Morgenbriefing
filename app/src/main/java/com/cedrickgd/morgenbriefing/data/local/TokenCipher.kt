package com.cedrickgd.morgenbriefing.data.local

/**
 * AES-256-GCM with a non-exportable key in the AndroidKeyStore (alias "mb_token_key",
 * available on API 23+). Output: Base64(iv || ciphertext). [decrypt] returns null if the
 * key is gone (e.g. after a backup restore) instead of throwing.
 */
object TokenCipher {
    fun encrypt(plain: String): String = TODO("agent A")
    fun decrypt(encoded: String): String? = TODO("agent A")
}

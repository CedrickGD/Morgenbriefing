package com.cedrickgd.morgenbriefing.ai

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Small file helpers for the AI stores (same atomic-write pattern as BriefStore). */
internal object AiFiles {
    private const val TMP_SUFFIX = ".tmp"

    /** Writes [content] to a sibling temp file, syncs it and renames it over [target]. */
    fun writeAtomically(target: File, content: String) {
        val parent = target.parentFile ?: throw IOException("Kein Elternordner für ${target.path}")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Ordner ${parent.path} konnte nicht angelegt werden")
        }
        val tmp = File.createTempFile(".${target.name}.", TMP_SUFFIX, parent)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (tmp.renameTo(target)) return
            // Platforms whose rename does not overwrite (Windows JVM in unit tests).
            if (target.exists() && !target.delete()) throw IOException("${target.path} konnte nicht ersetzt werden")
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** File content or null when missing/unreadable. */
    fun readOrNull(file: File): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: IOException) {
        null
    }

    /** A file-name-safe token for [key]: the key itself when it is a short slug, else a hash. */
    fun safeName(key: String): String {
        val trimmed = key.trim()
        if (SLUG.matches(trimmed)) return trimmed
        val digest = MessageDigest.getInstance("SHA-256").digest(trimmed.toByteArray(Charsets.UTF_8))
        return "h" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private val SLUG = Regex("""[a-z0-9][a-z0-9-]{0,79}""")
}

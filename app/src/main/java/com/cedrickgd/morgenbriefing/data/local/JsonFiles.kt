package com.cedrickgd.morgenbriefing.data.local

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Atomic text files (temp file + rename) for the small JSON caches of the app. */
internal object JsonFiles {
    private const val TMP_SUFFIX = ".tmp"

    /** Write to a sibling temp file, flush to disk, then rename over the target. */
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
            replace(tmp, target)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** The file's text, or null when it is missing or unreadable. */
    fun readOrNull(file: File): String? {
        if (!file.isFile) return null
        return try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    /** Deletes stale temp files older than an hour in [folder]. */
    fun pruneTemp(folder: File) {
        val staleBefore = System.currentTimeMillis() - 60L * 60 * 1000
        folder.listFiles()
            ?.filter { it.name.endsWith(TMP_SUFFIX) && it.lastModified() < staleBefore }
            ?.forEach { it.delete() }
    }

    /**
     * rename(2) replaces atomically on Android/Linux. Platforms whose rename refuses to
     * overwrite (Windows JVM in unit tests) fall back to delete + rename, then copy.
     */
    private fun replace(source: File, target: File) {
        if (source.renameTo(target)) return
        if (target.exists() && !target.delete()) {
            throw IOException("${target.path} konnte nicht ersetzt werden")
        }
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
    }
}

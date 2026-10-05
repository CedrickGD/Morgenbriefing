package com.cedrickgd.morgenbriefing.update

/**
 * A semantic version `major.minor.patch[-pre.release][+build]`, as used by release tags
 * (`v1.2.0`, `v1.3.0-beta.1`) and the app's `versionName` (`1.2.0`, debug: `1.2.0-debug`).
 *
 * Ordering follows semver precedence: numbers first, then a pre-release sorts *before* the
 * matching release (`1.2.0-beta < 1.2.0`); build metadata is ignored.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Dot-separated pre-release identifiers, e.g. `["beta", "1"]`; empty for a release. */
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVer> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        return comparePreRelease(preRelease, other.preRelease)
    }

    /** `1.2.0` or `1.3.0-beta.1` (no `v` prefix, no build metadata). */
    override fun toString(): String =
        if (preRelease.isEmpty()) "$major.$minor.$patch" else "$major.$minor.$patch-${preRelease.joinToString(".")}"

    companion object {
        /** Suffix the debug build type appends to `versionName` (see app/build.gradle.kts). */
        const val DEBUG_SUFFIX = "-debug"

        private val PATTERN = Regex(
            "^[vV]?(\\d{1,9})\\.(\\d{1,9})(?:\\.(\\d{1,9}))?" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
        )

        /**
         * Parses `1.2.3`, `v1.2.3`, `1.2` (patch 0), `v1.3.0-beta.1`, `1.2.3+build.7`.
         * Returns null for anything else (blank, missing minor, garbage).
         */
        fun parse(text: String?): SemVer? {
            val match = PATTERN.matchEntire(text?.trim().orEmpty()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return SemVer(
                major = major.toInt(),
                minor = minor.toInt(),
                patch = patch.ifEmpty { "0" }.toInt(),
                preRelease = if (pre.isEmpty()) emptyList() else pre.split('.'),
            )
        }

        /** The installed app's `versionName`, with the debug build's `-debug` suffix removed. */
        fun parseInstalled(versionName: String?): SemVer? =
            parse(versionName?.trim()?.removeSuffix(DEBUG_SUFFIX))

        /**
         * Whether [latest] should be offered to someone running [installed]: it must be newer, and
         * a pre-release is only offered when the installed version is a pre-release as well.
         */
        fun isUpdate(installed: SemVer, latest: SemVer): Boolean =
            (!latest.isPreRelease || installed.isPreRelease) && latest > installed

        private fun comparePreRelease(a: List<String>, b: List<String>): Int {
            // A version without pre-release identifiers ranks higher than one with them.
            when {
                a.isEmpty() && b.isEmpty() -> return 0
                a.isEmpty() -> return 1
                b.isEmpty() -> return -1
            }
            for (i in 0 until minOf(a.size, b.size)) {
                val result = compareIdentifier(a[i], b[i])
                if (result != 0) return result
            }
            return compareValues(a.size, b.size)
        }

        /** Numeric identifiers compare numerically and rank below alphanumeric ones. */
        private fun compareIdentifier(a: String, b: String): Int {
            val na = a.toLongOrNull()
            val nb = b.toLongOrNull()
            return when {
                na != null && nb != null -> compareValues(na, nb)
                na != null -> -1
                nb != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}

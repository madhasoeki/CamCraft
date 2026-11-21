package org.artcraft.camcraft.update

/**
 * Semantic Version parser and comparator matching SemVer 2.0.0 and CamCraft's Rust updater.
 */
data class SemVer(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val pre: String? = null,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        return comparePrerelease(pre, other.pre)
    }

    override fun toString(): String = if (pre != null) {
        "$major.$minor.$patch-$pre"
    } else {
        "$major.$minor.$patch"
    }

    companion object {
        fun parse(raw: String): SemVer {
            val trimmed = raw.trim()
            val noPrefix = when {
                trimmed.startsWith('v') || trimmed.startsWith('V') -> trimmed.substring(1)
                else -> trimmed
            }
            val withoutBuild = noPrefix.substringBefore('+')
            val dashIndex = withoutBuild.indexOf('-')
            val core: String
            val pre: String?
            if (dashIndex >= 0) {
                core = withoutBuild.substring(0, dashIndex)
                val p = withoutBuild.substring(dashIndex + 1)
                require(p.isNotEmpty() && p.split('.').none { it.isEmpty() }) {
                    "Invalid semantic version: $raw"
                }
                pre = p
            } else {
                core = withoutBuild
                pre = null
            }

            val parts = core.split('.')
            require(parts.size == 3) { "Invalid semantic version: $raw" }
            val major = parseCoreSegment(parts[0], raw)
            val minor = parseCoreSegment(parts[1], raw)
            val patch = parseCoreSegment(parts[2], raw)
            return SemVer(major = major, minor = minor, patch = patch, pre = pre)
        }

        fun tryParse(raw: String): SemVer? = runCatching { parse(raw) }.getOrNull()

        private fun parseCoreSegment(seg: String, raw: String): Long {
            require(seg.isNotEmpty() && seg.all { it in '0'..'9' }) {
                "Invalid semantic version: $raw"
            }
            return seg.toLongOrNull() ?: throw IllegalArgumentException("Invalid semantic version: $raw")
        }

        private fun comparePrerelease(a: String?, b: String?): Int {
            if (a == null && b == null) return 0
            if (a == null) return 1
            if (b == null) return -1

            val segsA = a.split('.')
            val segsB = b.split('.')
            val count = maxOf(segsA.size, segsB.size)
            for (i in 0 until count) {
                if (i >= segsA.size) return -1
                if (i >= segsB.size) return 1
                val cmp = comparePreSegment(segsA[i], segsB[i])
                if (cmp != 0) return cmp
            }
            return 0
        }

        private fun comparePreSegment(a: String, b: String): Int {
            val numA = a.toLongOrNull()
            val numB = b.toLongOrNull()
            return when {
                numA != null && numB != null -> numA.compareTo(numB)
                numA != null -> -1
                numB != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}

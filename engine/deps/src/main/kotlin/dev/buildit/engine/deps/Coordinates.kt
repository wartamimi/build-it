package dev.buildit.engine.deps

/** `group:artifact:version`, the way libraries are written in Gradle files. */
data class Coordinate(val group: String, val artifact: String, val version: String) {
    val module: String get() = "$group:$artifact"
    override fun toString() = "$group:$artifact:$version"

    /** Path of this version's folder in a Maven repository. */
    val path: String get() = "${group.replace('.', '/')}/$artifact/$version"

    companion object {
        private val part = Regex("[A-Za-z0-9_.\\-]+")

        fun parse(text: String): Coordinate? {
            val p = text.trim().substringBefore('@').split(':')
            if (p.size != 3 || p.any { !part.matches(it) }) return null
            return Coordinate(p[0], p[1], p[2])
        }
    }
}

/**
 * Maven/Gradle version ordering: numeric parts compare as numbers, and pre-release qualifiers sort
 * before the release (dev < alpha < beta < rc < release).
 */
object Versions : Comparator<String> {
    private fun tokens(v: String): List<Any> = Regex("\\d+|[A-Za-z]+").findAll(v).map { m ->
        m.value.toLongOrNull() ?: m.value.lowercase()
    }.toList()

    private fun rank(s: String) = when (s) {
        "dev" -> 0; "snapshot" -> 1; "alpha", "a" -> 2; "beta", "b" -> 3; "milestone", "m" -> 4; "rc", "cr" -> 5
        "release", "final", "ga" -> 7; "sp" -> 8; else -> 6
    }

    override fun compare(a: String, b: String): Int {
        val x = tokens(a)
        val y = tokens(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i)
            val q = y.getOrNull(i)
            when {
                p == null && q == null -> return 0
                p == null -> if (q == 0L) continue else return if (q is String && rank(q) < 7) 1 else -1
                q == null -> if (p == 0L) continue else return if (p is String && rank(p) < 7) -1 else 1
                p is Long && q is Long -> if (p != q) return p.compareTo(q)
                p is Long -> return 1
                q is Long -> return -1
                else -> { val c = rank(p as String).compareTo(rank(q as String)); if (c != 0) return c; val d = p.compareTo(q as String); if (d != 0) return d }
            }
        }
        return 0
    }

    fun isStable(v: String) = tokens(v).none { it is String && rank(it) < 6 }

    /**
     * Picks a concrete version for a requested one: plain versions as they are, Maven ranges like
     * `[1.0,2.0)` by their lower bound (the version the library was built against), `1.+` by the
     * highest of [available] with that prefix.
     */
    fun select(requested: String, available: () -> List<String>): String? {
        val r = requested.trim()
        if (r.isEmpty()) return null
        if (r.startsWith("[") || r.startsWith("(")) {
            val inner = r.trim('[', ']', '(', ')').split(',').map { it.trim() }
            return inner.firstOrNull { it.isNotEmpty() } ?: inner.lastOrNull { it.isNotEmpty() }
        }
        if (r.endsWith("+")) {
            val prefix = r.dropLast(1)
            return available().filter { it.startsWith(prefix) && isStable(it) }.maxWithOrNull(this)
        }
        return r
    }
}

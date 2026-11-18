package dev.buildit.compat

/**
 * Replacement for `java.lang.Runtime.Version` (Java 9+, absent on Android). ECJ's parser uses it in
 * one place to compare the requested source level with the newest it supports; the build rewrites
 * that reference to this class (see engine/build/build.gradle.kts). The name has the same length
 * as `java/lang/Runtime$Version`, so the class file is patched byte for byte.
 */
class RtVer1 private constructor(private val text: String, private val parts: List<Int>) : Comparable<RtVer1> {
    override fun compareTo(other: RtVer1): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun toString() = text
    override fun equals(other: Any?) = other is RtVer1 && compareTo(other) == 0
    override fun hashCode() = parts.dropLastWhile { it == 0 }.hashCode()

    companion object {
        @JvmStatic fun parse(s: String): RtVer1 {
            val numbers = s.trim().takeWhile { it.isDigit() || it == '.' }
            require(numbers.isNotEmpty() && numbers.split('.').all { it.isNotEmpty() }) { "Invalid version string: '$s'" }
            return RtVer1(s.trim(), numbers.split('.').map(String::toInt))
        }
    }
}

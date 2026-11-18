package dev.buildit.engine.layout

import java.io.File

/**
 * Simple values from a project's `res/values*` folders (strings, colors, dimens, integers, bools),
 * used by the preview to resolve `@string/...`, `@color/...` and `@dimen/...` references.
 */
class ResourceValues(private val default: Map<String, String>, private val night: Map<String, String>) {

    /** Looks up `type/name` (e.g. "color/primary"), following references up to 8 levels. */
    fun resolve(reference: String, nightMode: Boolean = false): String? {
        var ref = reference
        repeat(8) {
            if (!ref.startsWith("@") || ref.startsWith("@android:") || ref.startsWith("@+")) return ref
            val key = ref.removePrefix("@")
            val value = (if (nightMode) night[key] else null) ?: default[key] ?: return null
            ref = value
        }
        return null
    }

    fun names(type: String): List<String> = default.keys.filter { it.startsWith("$type/") }.map { it.substringAfter('/') }.sorted()

    companion object {
        val Empty = ResourceValues(emptyMap(), emptyMap())
        private val TYPES = setOf("string", "color", "dimen", "integer", "bool")

        fun load(resDir: File): ResourceValues = ResourceValues(read(File(resDir, "values")), read(File(resDir, "values-night")))

        fun parse(xml: String, into: MutableMap<String, String>) {
            val root = XmlParser.parse(xml)
            if (root.name != "resources") return
            root.elements.forEach { e ->
                val type = if (e.name == "item") e["type"] else e.name
                val name = e["name"] ?: return@forEach
                if (type in TYPES) {
                    val text = e.children.filterIsInstance<XmlText>().joinToString("") { it.text }
                    into["$type/$name"] = if (type == "string") unescapeString(text) else text.trim()
                }
            }
        }

        private fun read(dir: File): Map<String, String> {
            val map = mutableMapOf<String, String>()
            dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }.forEach { f ->
                runCatching { parse(f.readText(), map) }
            }
            return map
        }

        /** Android string resource escapes: \' \" \n \t \\ and surrounding quotes. */
        fun unescapeString(s: String): String {
            var t = s
            if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length - 1)
            val sb = StringBuilder()
            var i = 0
            while (i < t.length) {
                val c = t[i]
                if (c == '\\' && i + 1 < t.length) {
                    when (val n = t[i + 1]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t')
                        else -> sb.append(n)
                    }
                    i += 2
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
    }
}

/** Parsing of Android dimension and color literals, shared by the preview and the inspector. */
object Values {
    enum class Unit { Dp, Sp, Px, Pt, In, Mm }
    data class Dimension(val value: Float, val unit: Unit)

    private val DIM = Regex("^(-?\\d+(?:\\.\\d+)?)(dp|dip|sp|px|pt|in|mm)$")

    fun parseDimension(s: String): Dimension? {
        val m = DIM.matchEntire(s.trim()) ?: return null
        val unit = when (m.groupValues[2]) {
            "dp", "dip" -> Unit.Dp; "sp" -> Unit.Sp; "px" -> Unit.Px; "pt" -> Unit.Pt; "in" -> Unit.In; else -> Unit.Mm
        }
        return Dimension(m.groupValues[1].toFloat(), unit)
    }

    /** Parses #RGB, #ARGB, #RRGGBB, #AARRGGBB into an ARGB int. */
    fun parseColor(s: String): Int? {
        val t = s.trim()
        if (!t.startsWith("#")) return null
        val hex = t.substring(1)
        if (hex.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        val full = when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }
        return full.toLong(16).toInt()
    }
}

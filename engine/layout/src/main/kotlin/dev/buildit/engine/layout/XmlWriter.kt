package dev.buildit.engine.layout

/**
 * Serializes layouts with stable, conventional formatting so diffs stay small and output is
 * deterministic: 4-space indent, one attribute per line, attribute order xmlns → id → layout_width
 * → layout_height → other layout_* → the rest alphabetically (by local name, then prefix).
 */
object XmlWriter {
    fun write(root: XmlElement): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        element(root, 0)
    }

    private fun StringBuilder.element(e: XmlElement, depth: Int) {
        val indent = "    ".repeat(depth)
        append(indent).append('<').append(e.name)
        val attrs = sortAttributes(e.attributes)
        attrs.forEachIndexed { i, (k, v) ->
            if (i == 0 && attrs.size == 1) append(' ') else append('\n').append(indent).append("    ")
            append(k).append("=\"").append(escapeAttr(v)).append('"')
        }
        if (e.children.isEmpty()) {
            append(" />\n")
            return
        }
        append(">\n")
        var prevWasElement = false
        e.children.forEach { c ->
            when (c) {
                is XmlElement -> {
                    if (prevWasElement || c == e.children.first()) append('\n')
                    element(c, depth + 1); prevWasElement = true
                }
                is XmlComment -> {
                    if (prevWasElement) append('\n')
                    append(indent).append("    <!-- ").append(c.text).append(" -->\n"); prevWasElement = false
                }
                is XmlText -> { append(indent).append("    ").append(escapeText(c.text)).append('\n'); prevWasElement = false }
            }
        }
        if (prevWasElement) append('\n')
        append(indent).append("</").append(e.name).append(">\n")
    }

    fun sortAttributes(attrs: Map<String, String>): List<Pair<String, String>> =
        attrs.entries.map { it.key to it.value }.sortedWith(compareBy({ rank(it.first) }, { local(it.first) }, { it.first }))

    private fun local(n: String) = n.substringAfter(':')

    private fun rank(n: String): Int = when {
        n == "xmlns:android" -> 0
        n.startsWith("xmlns:") -> 1
        n == "android:id" -> 2
        n == "android:layout_width" -> 3
        n == "android:layout_height" -> 4
        local(n).startsWith("layout_") -> 5
        n.startsWith("tools:") -> 7
        else -> 6
    }

    fun escapeAttr(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;").replace("\n", "&#10;")
    private fun escapeText(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

package dev.buildit.engine.layout

/** Minimal XML tree for Android layout files. Immutable; edits produce new trees. */
sealed interface XmlNode

data class XmlComment(val text: String) : XmlNode

data class XmlText(val text: String) : XmlNode

/**
 * An element. [uid] is an in-memory identity used by the editor (selection, commands); it is not
 * written to XML. [attributes] keep their qualified names (e.g. `android:layout_width`).
 */
data class XmlElement(
    val uid: Long,
    val name: String,
    val attributes: LinkedHashMap<String, String> = LinkedHashMap(),
    val children: List<XmlNode> = emptyList(),
) : XmlNode {
    val elements: List<XmlElement> get() = children.filterIsInstance<XmlElement>()

    operator fun get(attr: String): String? = attributes[attr]

    fun withAttribute(name: String, value: String?): XmlElement {
        val copy = LinkedHashMap(attributes)
        if (value == null) copy.remove(name) else copy[name] = value
        return copy(attributes = copy)
    }

    /** Depth-first traversal including this element. */
    fun walk(): Sequence<XmlElement> = sequence {
        yield(this@XmlElement)
        elements.forEach { yieldAll(it.walk()) }
    }

    fun find(uid: Long): XmlElement? = walk().firstOrNull { it.uid == uid }

    /** Returns the parent of [childUid], or null if it is the root or absent. */
    fun parentOf(childUid: Long): XmlElement? = walk().firstOrNull { p -> p.elements.any { it.uid == childUid } }

    /** Replaces the element with [uid] by [transform]'s result anywhere in the tree. */
    fun replace(uid: Long, transform: (XmlElement) -> XmlElement?): XmlElement? {
        if (this.uid == uid) return transform(this)
        var changed = false
        val newChildren = children.mapNotNull { n ->
            if (n is XmlElement) {
                val r = n.replace(uid, transform)
                if (r !== n) changed = true
                r
            } else n
        }
        return if (changed) copy(children = newChildren) else this
    }
}

class XmlParseException(message: String, val line: Int, val column: Int) : Exception("$message (line $line, column $column)")

/**
 * A small, strict XML parser for layout/resource files: elements, attributes, comments, text,
 * CDATA, the XML declaration and standard entities. DTDs and processing instructions other than the
 * declaration are rejected. Pure Kotlin so it behaves identically on the JVM and on ART.
 */
class XmlParser private constructor(private val src: String, private val ids: () -> Long) {
    private var pos = 0
    private var line = 1
    private var col = 1

    companion object {
        fun parse(text: String, ids: () -> Long = UidSource()::next): XmlElement = XmlParser(text, ids).document()
    }

    private fun document(): XmlElement {
        skipBom()
        skipWs()
        if (startsWith("<?xml")) {
            val end = src.indexOf("?>", pos)
            if (end < 0) fail("Unterminated XML declaration")
            advanceTo(end + 2)
        }
        val leading = misc()
        if (!startsWith("<")) fail("Expected a root element")
        val root = element()
        misc()
        if (pos < src.length) fail("Unexpected content after the root element")
        return if (leading.isEmpty()) root else root // leading comments before root are dropped
    }

    private fun misc(): List<XmlNode> {
        val nodes = mutableListOf<XmlNode>()
        while (true) {
            skipWs()
            when {
                startsWith("<!--") -> nodes += comment()
                startsWith("<!DOCTYPE") -> fail("DTDs are not supported")
                startsWith("<?") -> fail("Processing instructions are not supported")
                else -> return nodes
            }
        }
    }

    private fun element(): XmlElement {
        expect('<')
        val name = name()
        val attrs = LinkedHashMap<String, String>()
        while (true) {
            val hadWs = skipWs()
            when {
                startsWith("/>") -> { advance(2); return XmlElement(ids(), name, attrs) }
                startsWith(">") -> { advance(1); break }
                else -> {
                    if (!hadWs) fail("Expected whitespace before attribute")
                    val an = name()
                    skipWs(); expect('='); skipWs()
                    val q = peek()
                    if (q != '"' && q != '\'') fail("Attribute value must be quoted")
                    advance(1)
                    val end = src.indexOf(q, pos)
                    if (end < 0) fail("Unterminated attribute value")
                    val raw = src.substring(pos, end)
                    if (raw.contains('<')) fail("'<' is not allowed in attribute values")
                    if (attrs.containsKey(an)) fail("Duplicate attribute $an")
                    attrs[an] = decode(raw)
                    advanceTo(end + 1)
                }
            }
        }
        val children = mutableListOf<XmlNode>()
        while (true) {
            if (pos >= src.length) fail("Unclosed element <$name>")
            when {
                startsWith("</") -> {
                    advance(2)
                    val close = name()
                    if (close != name) fail("Expected </$name> but found </$close>")
                    skipWs(); expect('>')
                    return XmlElement(ids(), name, attrs, children)
                }
                startsWith("<!--") -> children += comment()
                startsWith("<![CDATA[") -> {
                    val end = src.indexOf("]]>", pos)
                    if (end < 0) fail("Unterminated CDATA")
                    children += XmlText(src.substring(pos + 9, end))
                    advanceTo(end + 3)
                }
                startsWith("<?") || startsWith("<!") -> fail("Unsupported markup")
                startsWith("<") -> children += element()
                else -> {
                    val end = src.indexOf('<', pos).let { if (it < 0) src.length else it }
                    val text = src.substring(pos, end)
                    advanceTo(end)
                    if (text.isNotBlank()) children += XmlText(decode(text.trim()))
                }
            }
        }
    }

    private fun comment(): XmlComment {
        val end = src.indexOf("-->", pos + 4)
        if (end < 0) fail("Unterminated comment")
        val text = src.substring(pos + 4, end)
        advanceTo(end + 3)
        return XmlComment(text.trim())
    }

    private fun name(): String {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] in "_:.-")) advance(1)
        if (start == pos) fail("Expected a name")
        if (!src[start].isLetter() && src[start] != '_') fail("Invalid name")
        return src.substring(start, pos)
    }

    private fun decode(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val semi = s.indexOf(';', i)
            if (semi < 0) fail("Unterminated entity")
            val ent = s.substring(i + 1, semi)
            sb.append(
                when {
                    ent == "lt" -> "<"; ent == "gt" -> ">"; ent == "amp" -> "&"; ent == "quot" -> "\""; ent == "apos" -> "'"
                    ent.startsWith("#x") -> String(Character.toChars(ent.substring(2).toInt(16)))
                    ent.startsWith("#") -> String(Character.toChars(ent.substring(1).toInt()))
                    else -> fail("Unknown entity &$ent;")
                },
            )
            i = semi + 1
        }
        return sb.toString()
    }

    private fun skipBom() { if (src.startsWith("﻿")) pos = 1 }
    private fun skipWs(): Boolean { val s = pos; while (pos < src.length && src[pos].isWhitespace()) advance(1); return pos > s }
    private fun startsWith(s: String) = src.startsWith(s, pos)
    private fun peek(): Char = if (pos < src.length) src[pos] else fail("Unexpected end of file")
    private fun expect(c: Char) { if (peek() != c) fail("Expected '$c'"); advance(1) }
    private fun advanceTo(p: Int) = advance(p - pos)
    private fun advance(n: Int) {
        repeat(n) {
            if (src[pos] == '\n') { line++; col = 1 } else col++
            pos++
        }
    }
    private fun fail(msg: String): Nothing = throw XmlParseException(msg, line, col)
}

/** Monotonic uid source for one editing session. */
class UidSource(start: Long = 1) {
    private var next = start
    fun next(): Long = next++
}

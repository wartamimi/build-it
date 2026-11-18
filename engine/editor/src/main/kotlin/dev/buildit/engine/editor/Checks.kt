package dev.buildit.engine.editor

import dev.buildit.engine.layout.XmlParseException
import dev.buildit.engine.layout.XmlParser
import kotlinx.serialization.json.Json

/** A syntax problem at a 1-based line and column. */
data class EditorDiagnostic(val line: Int, val column: Int, val code: String, val detail: String = "")

/**
 * Fast syntax checks while typing: bracket balance and unterminated literals for Java/Kotlin/
 * Gradle, well-formedness for XML, validity for JSON. These are not compiler diagnostics; those
 * come from the build engine.
 */
object Checks {
    fun check(text: String, language: Language): List<EditorDiagnostic> = when (language) {
        Language.Java, Language.Kotlin, Language.Groovy -> brackets(text, language)
        Language.Xml -> xml(text)
        Language.Json -> json(text)
        Language.Plain -> emptyList()
    }

    private fun brackets(text: String, language: Language): List<EditorDiagnostic> {
        val out = mutableListOf<EditorDiagnostic>()
        val stack = ArrayDeque<Pair<Char, Int>>()
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        Lexer.tokenize(text, language).forEach { t ->
            when (t.kind) {
                TokenKind.Comment -> if (text.startsWith("/*", t.start) && !text.substring(t.start, t.end).let { it.length >= 4 && it.endsWith("*/") }) {
                    out += at(text, t.start, "unterminated_comment")
                }
                TokenKind.String -> {
                    val closed = if (text.startsWith("\"\"\"", t.start)) t.end - t.start >= 6 && text.startsWith("\"\"\"", t.end - 3)
                    else Lexer.quotedEnd(text, t.start, text[t.start], templates = language != Language.Java && text[t.start] == '"').second
                    if (!closed) out += at(text, t.start, "unterminated_string")
                }
                TokenKind.Punctuation -> {
                    val c = text[t.start]
                    when (c) {
                        '(', '[', '{' -> stack.addLast(c to t.start)
                        ')', ']', '}' -> {
                            val open = stack.removeLastOrNull()
                            if (open == null) out += at(text, t.start, "unmatched_close", c.toString())
                            else if (open.first != pairs[c]) { out += at(text, t.start, "mismatched", "${open.first}$c") }
                        }
                    }
                }
                else -> Unit
            }
        }
        stack.forEach { (c, pos) -> out += at(text, pos, "unclosed", c.toString()) }
        return out.sortedWith(compareBy({ it.line }, { it.column }))
    }

    private fun xml(text: String): List<EditorDiagnostic> = try {
        XmlParser.parse(text); emptyList()
    } catch (e: XmlParseException) {
        listOf(EditorDiagnostic(e.line, e.column, "xml", e.message?.substringBefore(" (line").orEmpty()))
    }

    private val offsetPattern = Regex("offset (\\d+)")

    private fun json(text: String): List<EditorDiagnostic> = try {
        Json.parseToJsonElement(text); emptyList()
    } catch (e: Exception) {
        val offset = e.message?.let { offsetPattern.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        listOf(at(text, offset.coerceIn(0, text.length), "json", e.message?.lineSequence()?.firstOrNull().orEmpty()))
    }

    fun at(text: CharSequence, offset: Int, code: String, detail: String = ""): EditorDiagnostic {
        val (line, col) = TextPositions.lineColumn(text, offset)
        return EditorDiagnostic(line, col, code, detail)
    }
}

object TextPositions {
    /** 1-based line and column of [offset]. */
    fun lineColumn(text: CharSequence, offset: Int): Pair<Int, Int> {
        var line = 1
        var lineStart = 0
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') { line++; lineStart = i + 1 }
        return line to (offset - lineStart + 1)
    }

    /** Offset of the start of 1-based [line] (clamped). */
    fun lineStart(text: CharSequence, line: Int): Int {
        if (line <= 1) return 0
        var l = 1
        for (i in text.indices) if (text[i] == '\n') { l++; if (l == line) return i + 1 }
        return text.length
    }
}

package dev.buildit.engine.editor

enum class TokenKind { Keyword, Type, String, Number, Comment, Annotation, Tag, Attribute, AttrValue, Key, Literal, Punctuation }

data class Token(val start: Int, val end: Int, val kind: TokenKind)

enum class Language(val label: String) {
    Java("Java"), Kotlin("Kotlin"), Groovy("Gradle (Groovy)"), Xml("XML"), Json("JSON"), Plain("Text");

    companion object {
        fun forPath(path: String): Language {
            val name = path.substringAfterLast('/').lowercase()
            return when {
                name.endsWith(".java") -> Java
                name.endsWith(".kt") || name.endsWith(".kts") -> Kotlin
                name.endsWith(".gradle") -> Groovy
                name.endsWith(".xml") -> Xml
                name.endsWith(".json") -> Json
                else -> Plain
            }
        }
    }
}

/**
 * Single-pass lexers for syntax highlighting. They never throw: malformed input (an unterminated
 * string, say) is tokenised up to the end of the text so highlighting degrades gracefully.
 */
object Lexer {
    private val javaKeywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
        "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
        "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record", "sealed", "permits", "yield",
    )
    private val kotlinKeywords = setOf(
        "as", "break", "class", "continue", "do", "else", "for", "fun", "if", "in", "interface", "is", "object", "package", "return", "super",
        "this", "throw", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic",
        "field", "file", "finally", "get", "import", "init", "param", "property", "receiver", "set", "setparam", "where", "actual", "abstract",
        "annotation", "companion", "const", "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline", "inner",
        "internal", "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected", "public", "reified", "sealed",
        "suspend", "tailrec", "vararg", "value",
    )
    private val groovyKeywords = javaKeywords + setOf("def", "as", "in", "trait")
    private val literals = setOf("true", "false", "null")

    fun tokenize(text: CharSequence, language: Language): List<Token> = when (language) {
        Language.Java -> cLike(text, javaKeywords, rawStrings = false, charLiterals = true)
        Language.Kotlin -> cLike(text, kotlinKeywords, rawStrings = true, charLiterals = true)
        Language.Groovy -> cLike(text, groovyKeywords, rawStrings = false, charLiterals = false, singleQuoteStrings = true)
        Language.Xml -> xml(text)
        Language.Json -> json(text)
        Language.Plain -> emptyList()
    }

    private fun cLike(t: CharSequence, keywords: Set<String>, rawStrings: Boolean, charLiterals: Boolean, singleQuoteStrings: Boolean = false): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = t.length
        while (i < n) {
            val c = t[i]
            when {
                c == '/' && i + 1 < n && t[i + 1] == '/' -> { val e = lineEnd(t, i); out += Token(i, e, TokenKind.Comment); i = e }
                c == '/' && i + 1 < n && t[i + 1] == '*' -> { val e = indexOf(t, "*/", i + 2)?.plus(2) ?: n; out += Token(i, e, TokenKind.Comment); i = e }
                rawStrings && c == '"' && startsWith(t, i, "\"\"\"") -> { val e = indexOf(t, "\"\"\"", i + 3)?.plus(3) ?: n; out += Token(i, e, TokenKind.String); i = e }
                c == '"' || (singleQuoteStrings && c == '\'') -> { val e = quoted(t, i, c, templates = c == '"' && (rawStrings || singleQuoteStrings)); out += Token(i, e, TokenKind.String); i = e }
                charLiterals && c == '\'' -> { val e = quoted(t, i, '\''); out += Token(i, e, TokenKind.String); i = e }
                c == '@' && i + 1 < n && t[i + 1].isJavaIdentifierStart() -> { val e = ident(t, i + 1); out += Token(i, e, TokenKind.Annotation); i = e }
                c.isDigit() -> { val e = number(t, i); out += Token(i, e, TokenKind.Number); i = e }
                c.isJavaIdentifierStart() -> {
                    val e = ident(t, i)
                    val w = t.substring(i, e)
                    when {
                        w in literals -> out += Token(i, e, TokenKind.Literal)
                        w in keywords -> out += Token(i, e, TokenKind.Keyword)
                        w[0].isUpperCase() -> out += Token(i, e, TokenKind.Type)
                    }
                    i = e
                }
                c in "{}()[];,.<>=+-*/%!&|?:" -> { out += Token(i, i + 1, TokenKind.Punctuation); i++ }
                else -> i++
            }
        }
        return out
    }

    private fun xml(t: CharSequence): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = t.length
        while (i < n) {
            when {
                startsWith(t, i, "<!--") -> { val e = indexOf(t, "-->", i + 4)?.plus(3) ?: n; out += Token(i, e, TokenKind.Comment); i = e }
                startsWith(t, i, "<![CDATA[") -> { val e = indexOf(t, "]]>", i)?.plus(3) ?: n; out += Token(i, e, TokenKind.String); i = e }
                t[i] == '<' -> {
                    var j = i + 1
                    if (j < n && (t[j] == '/' || t[j] == '?' || t[j] == '!')) j++
                    val nameEnd = xmlName(t, j)
                    out += Token(i, nameEnd, TokenKind.Tag)
                    i = nameEnd
                    // Attributes until '>'
                    while (i < n && t[i] != '>' && t[i] != '<') {
                        val c = t[i]
                        when {
                            c == '"' || c == '\'' -> { val e = quoted(t, i, c); out += Token(i, e, TokenKind.AttrValue); i = e }
                            isXmlNameChar(c) -> { val e = xmlName(t, i); out += Token(i, e, TokenKind.Attribute); i = e }
                            c == '/' || c == '?' || c == '=' -> { out += Token(i, i + 1, TokenKind.Punctuation); i++ }
                            else -> i++
                        }
                    }
                    if (i < n && t[i] == '>') { out += Token(i, i + 1, TokenKind.Tag); i++ }
                }
                else -> i++
            }
        }
        return out
    }

    private fun json(t: CharSequence): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = t.length
        while (i < n) {
            val c = t[i]
            when {
                c == '"' -> {
                    val e = quoted(t, i, '"')
                    var k = e
                    while (k < n && t[k].isWhitespace()) k++
                    out += Token(i, e, if (k < n && t[k] == ':') TokenKind.Key else TokenKind.String)
                    i = e
                }
                c == '-' || c.isDigit() -> { val e = number(t, if (c == '-') i + 1 else i); out += Token(i, e, TokenKind.Number); i = e }
                c.isLetter() -> { val e = ident(t, i); if (t.substring(i, e) in literals) out += Token(i, e, TokenKind.Literal); i = e }
                c in "{}[]:," -> { out += Token(i, i + 1, TokenKind.Punctuation); i++ }
                else -> i++
            }
        }
        return out
    }

    // helpers

    private fun lineEnd(t: CharSequence, from: Int): Int { var i = from; while (i < t.length && t[i] != '\n') i++; return i }

    internal fun indexOf(t: CharSequence, s: String, from: Int): Int? {
        var i = from
        while (i <= t.length - s.length) { if (startsWith(t, i, s)) return i; i++ }
        return null
    }

    private fun startsWith(t: CharSequence, at: Int, s: String): Boolean {
        if (at + s.length > t.length) return false
        for (k in s.indices) if (t[at + k] != s[k]) return false
        return true
    }

    /** End of a quoted literal starting at [start]; stops at the line end when unterminated. */
    internal fun quoted(t: CharSequence, start: Int, quote: Char, templates: Boolean = false): Int = quotedEnd(t, start, quote, templates).first

    /** End of a quoted literal and whether its closing quote was found. */
    internal fun quotedEnd(t: CharSequence, start: Int, quote: Char, templates: Boolean = false): Pair<Int, Boolean> {
        var i = start + 1
        while (i < t.length) {
            val c = t[i]
            if (c == '\\') { i += 2; continue }
            if (templates && c == '$' && i + 1 < t.length && t[i + 1] == '{') {
                // Skip a ${...} template expression, which may itself contain quotes and braces.
                var depth = 0
                while (i < t.length) {
                    when (t[i]) {
                        '{' -> depth++
                        '}' -> { depth--; if (depth == 0) break }
                        '"' -> i = quoted(t, i, '"', templates = true) - 1
                        '\n' -> return i to false
                    }
                    i++
                }
                i++
                continue
            }
            if (c == quote) return (i + 1) to true
            if (c == '\n') return i to false
            i++
        }
        return t.length to false
    }

    private fun ident(t: CharSequence, from: Int): Int { var i = from; while (i < t.length && t[i].isJavaIdentifierPart()) i++; return i }

    private fun number(t: CharSequence, from: Int): Int {
        var i = from
        while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '.' || t[i] == '_')) {
            if (t[i] == '.' && (i + 1 >= t.length || !t[i + 1].isDigit())) break
            i++
        }
        return i
    }

    private fun isXmlNameChar(c: Char) = c.isLetterOrDigit() || c == ':' || c == '_' || c == '-' || c == '.'
    private fun xmlName(t: CharSequence, from: Int): Int { var i = from; while (i < t.length && isXmlNameChar(t[i])) i++; return i }
}

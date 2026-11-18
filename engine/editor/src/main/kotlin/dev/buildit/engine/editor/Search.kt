package dev.buildit.engine.editor

data class SearchQuery(
    val text: String,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/** Find and replace over a whole document. Invalid regular expressions yield no matches. */
object Search {
    const val MAX_MATCHES = 10_000

    fun pattern(q: SearchQuery): Regex? {
        if (q.text.isEmpty()) return null
        val body = if (q.regex) q.text else Regex.escape(q.text)
        val src = if (q.wholeWord) "\\b(?:$body)\\b" else body
        val options = buildSet { if (!q.caseSensitive) add(RegexOption.IGNORE_CASE); add(RegexOption.MULTILINE) }
        return runCatching { Regex(src, options) }.getOrNull()
    }

    fun isValid(q: SearchQuery) = q.text.isEmpty() || pattern(q) != null

    fun findAll(text: CharSequence, q: SearchQuery): List<IntRange> =
        pattern(q)?.findAll(text)?.filter { !it.range.isEmpty() }?.take(MAX_MATCHES)?.map { it.range }?.toList().orEmpty()

    /** Index of the first match starting at or after [from], wrapping around; -1 when none. */
    fun next(matches: List<IntRange>, from: Int): Int {
        if (matches.isEmpty()) return -1
        val i = matches.indexOfFirst { it.first >= from }
        return if (i >= 0) i else 0
    }

    fun previous(matches: List<IntRange>, before: Int): Int {
        if (matches.isEmpty()) return -1
        val i = matches.indexOfLast { it.first < before }
        return if (i >= 0) i else matches.lastIndex
    }

    /** Replacement text for one match ($1 groups work in regex mode; literal otherwise). */
    fun replacement(text: CharSequence, match: IntRange, q: SearchQuery, replacement: String): String {
        val p = pattern(q) ?: return replacement
        val m = p.matchAt(text, match.first) ?: return replacement
        return if (q.regex) m.value.replace(p, replacement) else replacement
    }

    data class ReplaceAll(val text: String, val count: Int)

    fun replaceAll(text: String, q: SearchQuery, replacement: String): ReplaceAll {
        val p = pattern(q) ?: return ReplaceAll(text, 0)
        var count = 0
        val result = p.replace(text) { m ->
            if (m.range.isEmpty()) return@replace m.value
            count++
            if (q.regex) m.value.replace(p, replacement) else replacement
        }
        return ReplaceAll(result, count)
    }
}

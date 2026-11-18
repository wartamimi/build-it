package dev.buildit.feature.code

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import dev.buildit.engine.editor.Edit
import dev.buildit.engine.editor.EditHistory
import dev.buildit.engine.editor.Indent
import dev.buildit.engine.editor.Language
import dev.buildit.engine.editor.Lexer
import dev.buildit.engine.editor.Token
import dev.buildit.engine.editor.TokenKind

/** Colors for syntax highlighting and the line-number gutter. */
data class EditorColors(
    val text: Int,
    val background: Int,
    val gutter: Int,
    val gutterText: Int,
    val keyword: Int,
    val type: Int,
    val string: Int,
    val number: Int,
    val comment: Int,
    val annotation: Int,
    val tag: Int,
    val attribute: Int,
    val punctuation: Int,
    val match: Int,
    val currentMatch: Int,
) {
    fun of(kind: TokenKind): Int = when (kind) {
        TokenKind.Keyword, TokenKind.Literal -> keyword
        TokenKind.Type -> type
        TokenKind.String, TokenKind.AttrValue -> string
        TokenKind.Number -> number
        TokenKind.Comment -> comment
        TokenKind.Annotation -> annotation
        TokenKind.Tag, TokenKind.Key -> tag
        TokenKind.Attribute -> attribute
        TokenKind.Punctuation -> punctuation
    }
}

private class SyntaxSpan(color: Int) : ForegroundColorSpan(color)
private class MatchSpan(color: Int) : BackgroundColorSpan(color)

/**
 * Plain-platform code editor: an [EditText] without wrapping, with a line-number gutter, syntax
 * highlighting applied to the visible lines only (so large files stay responsive), undo/redo via
 * [EditHistory], auto-indent on Enter and four-space Tab.
 */
@SuppressLint("ViewConstructor")
class CodeEditText(context: Context, private var history: EditHistory) : EditText(context) {
    var language: Language = Language.Plain
        set(value) { field = value; tokens = null; scheduleHighlight() }
    var colors: EditorColors? = null
        set(value) { field = value; value?.let { setTextColor(it.text); setBackgroundColor(it.background) }; tokens = null; scheduleHighlight(); invalidate() }
    var onEdited: ((String) -> Unit)? = null
    var onHistoryChanged: (() -> Unit)? = null

    private var tokens: List<Token>? = null
    private var applying = false
    private var pendingRemoved = ""
    private var pendingIndentAt = -1
    private var matches: List<IntRange> = emptyList()
    private var currentMatch = -1
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val gutterBg = Paint()
    private val highlightRunnable = Runnable { highlightVisible() }

    init {
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.START
        setHorizontallyScrolling(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isVerticalScrollBarEnabled = true
        setLineSpacing(0f, 1.15f)
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (!applying) pendingRemoved = s.subSequence(start, start + count).toString()
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (applying) return
                val inserted = s.subSequence(start, start + count).toString()
                history.record(Edit(start, pendingRemoved, inserted, System.currentTimeMillis()))
                if (inserted == "\n" && pendingRemoved.isEmpty()) pendingIndentAt = start
                onHistoryChanged?.invoke()
            }

            override fun afterTextChanged(s: Editable) {
                val at = pendingIndentAt
                pendingIndentAt = -1
                if (at >= 0 && !applying && at < s.length && s[at] == '\n') {
                    // Replace the typed newline with newline + indentation (recorded as one more step).
                    // Ask the indenter as if Enter had not been inserted yet, so it sees what follows the cursor.
                    val before = StringBuilder(s.length).append(s, 0, at).append(s, at + 1, s.length)
                    val (insert, cursor) = Indent.newline(before, at)
                    if (insert != "\n") {
                        applying = true
                        s.replace(at + 1, at + 1, insert.substring(1))
                        applying = false
                        history.amendNewline(insert.substring(1))
                        setSelection((at + cursor).coerceAtMost(s.length))
                    }
                }
                tokens = null
                updateGutterWidth()
                scheduleHighlight()
                if (!applying) onEdited?.invoke(s.toString())
            }
        })
    }

    fun setEditorTextSize(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gutterPaint.textSize = textSize * 0.85f
        updateGutterWidth()
    }

    /** Replaces the whole text without recording history (opening a file or reloading it). */
    fun load(text: String, newHistory: EditHistory) {
        history = newHistory
        applying = true
        setText(text)
        applying = false
        tokens = null
        updateGutterWidth()
        scheduleHighlight()
        onHistoryChanged?.invoke()
    }

    fun undo() = applyEdit(history.undo())
    fun redo() = applyEdit(history.redo())

    private fun applyEdit(e: Edit?) {
        e ?: return
        val t = text ?: return
        applying = true
        val end = (e.start + e.removed.length).coerceAtMost(t.length)
        t.replace(e.start.coerceAtMost(t.length), end, e.inserted)
        applying = false
        setSelection((e.start + e.inserted.length).coerceAtMost(t.length))
        tokens = null
        scheduleHighlight()
        onEdited?.invoke(t.toString())
        onHistoryChanged?.invoke()
    }

    /** Replaces a range as one undoable edit (used by find/replace). */
    fun replaceRange(start: Int, end: Int, replacement: String) {
        val t = text ?: return
        t.replace(start.coerceIn(0, t.length), end.coerceIn(0, t.length), replacement)
    }

    fun select(range: IntRange) {
        val len = text?.length ?: 0
        requestFocus()
        setSelection(range.first.coerceIn(0, len), (range.last + 1).coerceIn(0, len))
        bringPointIntoView(range.first.coerceIn(0, len))
    }

    fun goTo(offset: Int) {
        val len = text?.length ?: 0
        requestFocus()
        setSelection(offset.coerceIn(0, len))
        bringPointIntoView(offset.coerceIn(0, len))
    }

    fun setMatches(ranges: List<IntRange>, current: Int) {
        matches = ranges
        currentMatch = current
        applyMatches()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB && !event.isShiftPressed && isEnabled) {
            text?.replace(selectionStart, selectionEnd, Indent.UNIT)
            return true
        }
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> { if (event.isShiftPressed) redo() else undo(); return true }
                KeyEvent.KEYCODE_Y -> { redo(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        if (vert != oldVert) scheduleHighlight(60)
    }

    // ------------------------------------------------------------ gutter

    private fun updateGutterWidth() {
        val lines = (layout?.lineCount ?: lineCount).coerceAtLeast(1)
        val digits = lines.toString().length.coerceAtLeast(2)
        val width = (gutterPaint.measureText("0".repeat(digits)) + dp(16)).toInt()
        if (paddingLeft != width + dp(8)) setPadding(width + dp(8), paddingTop, paddingRight, paddingBottom)
    }

    override fun onDraw(canvas: Canvas) {
        val c = colors
        val l = layout
        if (c != null && l != null) {
            // The canvas is translated by the scroll offset; draw the gutter pinned to the left edge.
            val gutterRight = scrollX + paddingLeft - dp(8).toFloat()
            gutterBg.color = c.gutter
            canvas.drawRect(scrollX.toFloat(), scrollY.toFloat(), gutterRight, (scrollY + height).toFloat(), gutterBg)
            gutterPaint.color = c.gutterText
            gutterPaint.textAlign = Paint.Align.RIGHT
            val first = l.getLineForVertical(scrollY - paddingTop)
            val last = l.getLineForVertical(scrollY + height)
            for (line in first..last) {
                val y = l.getLineBaseline(line) + paddingTop.toFloat()
                canvas.drawText((line + 1).toString(), gutterRight - dp(8), y, gutterPaint)
            }
        }
        super.onDraw(canvas)
    }

    // ------------------------------------------------------------ highlighting

    private fun scheduleHighlight(delay: Long = 120) {
        removeCallbacks(highlightRunnable)
        postDelayed(highlightRunnable, delay)
    }

    /** Applies syntax colors to the visible lines (plus a margin), replacing older spans there. */
    private fun highlightVisible() {
        val c = colors ?: return
        val t = text ?: return
        val l = layout ?: return
        if (language == Language.Plain) return
        val toks = tokens ?: Lexer.tokenize(t, language).also { tokens = it }
        val firstLine = (l.getLineForVertical(scrollY) - MARGIN_LINES).coerceAtLeast(0)
        val lastLine = (l.getLineForVertical(scrollY + height) + MARGIN_LINES).coerceAtMost(l.lineCount - 1)
        val start = l.getLineStart(firstLine)
        val end = l.getLineEnd(lastLine)
        t.getSpans(0, t.length, SyntaxSpan::class.java).forEach { t.removeSpan(it) }
        var i = toks.binarySearch { if (it.end <= start) -1 else if (it.start >= start) 1 else 0 }.let { if (it < 0) -(it + 1) else it }
        while (i < toks.size && toks[i].start < end) {
            val tok = toks[i]
            val s = tok.start.coerceIn(0, t.length)
            val e = tok.end.coerceIn(0, t.length)
            if (s < e) t.setSpan(SyntaxSpan(c.of(tok.kind)), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            i++
        }
        applyMatches()
    }

    private fun applyMatches() {
        val c = colors ?: return
        val t = text ?: return
        t.getSpans(0, t.length, MatchSpan::class.java).forEach { t.removeSpan(it) }
        matches.take(MAX_MATCH_SPANS).forEachIndexed { i, r ->
            val s = r.first.coerceIn(0, t.length)
            val e = (r.last + 1).coerceIn(0, t.length)
            if (s < e) t.setSpan(MatchSpan(if (i == currentMatch) c.currentMatch else c.match), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MARGIN_LINES = 80
        private const val MAX_MATCH_SPANS = 2_000
    }
}

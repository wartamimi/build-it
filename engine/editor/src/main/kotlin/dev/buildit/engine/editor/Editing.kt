package dev.buildit.engine.editor

/** One text change: [removed] at [start] was replaced by [inserted]. */
data class Edit(val start: Int, val removed: String, val inserted: String, val time: Long = 0) {
    fun inverse() = Edit(start, inserted, removed, time)
}

/**
 * Undo/redo history of text edits. Consecutive typing (single-character insertions that continue
 * each other within [mergeWindowMs]) merges into one step, and a word boundary starts a new step.
 */
class EditHistory(private val limit: Int = 500, private val mergeWindowMs: Long = 1000) {
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun record(e: Edit) {
        if (e.removed == e.inserted) return
        redo.clear()
        val last = undo.lastOrNull()
        if (last != null && last.removed.isEmpty() && e.removed.isEmpty() && e.inserted.length == 1 &&
            e.start == last.start + last.inserted.length && e.time - last.time <= mergeWindowMs &&
            e.inserted[0].isWhitespace() == last.inserted.last().isWhitespace()
        ) {
            undo[undo.lastIndex] = last.copy(inserted = last.inserted + e.inserted, time = e.time)
            return
        }
        undo.addLast(e)
        if (undo.size > limit) undo.removeFirst()
    }

    /**
     * Extends the last step's trailing newline with [indent] (auto-indent after Enter), so Enter
     * and its indentation undo together.
     */
    fun amendNewline(indent: String) {
        val last = undo.lastOrNull() ?: return
        if (last.inserted.endsWith("\n")) undo[undo.lastIndex] = last.copy(inserted = last.inserted + indent)
    }

    /** The edit to apply to undo the last step (already moved to the redo stack). */
    fun undo(): Edit? = undo.removeLastOrNull()?.also { redo.addLast(it) }?.inverse()

    /** The edit to apply to redo the last undone step. */
    fun redo(): Edit? = redo.removeLastOrNull()?.also { undo.addLast(it) }

    fun clear() { undo.clear(); redo.clear() }
}

object Indent {
    const val UNIT = "    "

    /**
     * Text to insert for Enter at [cursor]: newline plus the current line's indentation, one level
     * deeper after an opening bracket (and a closing line when the bracket is closed right after
     * the cursor). Returns the text and the cursor offset within it.
     */
    fun newline(text: CharSequence, cursor: Int): Pair<String, Int> {
        var ls = cursor
        while (ls > 0 && text[ls - 1] != '\n') ls--
        var ws = ls
        while (ws < text.length && ws < cursor && (text[ws] == ' ' || text[ws] == '\t')) ws++
        val indent = text.subSequence(ls, ws).toString()
        var p = cursor - 1
        while (p >= ls && text[p] == ' ') p--
        val opener = p >= ls && text[p] in "{([" 
        val closer = cursor < text.length && text[cursor] in "})]"
        return when {
            opener && closer -> ("\n$indent$UNIT\n$indent") to (1 + indent.length + UNIT.length)
            opener -> ("\n$indent$UNIT").let { it to it.length }
            else -> ("\n$indent").let { it to it.length }
        }
    }
}

package dev.buildit.feature.code

import androidx.test.core.app.ApplicationProvider
import dev.buildit.engine.editor.EditHistory
import dev.buildit.engine.editor.Language
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CodeEditTextTest {
    private fun editor(text: String): Pair<CodeEditText, MutableList<String>> {
        val history = EditHistory()
        val edits = mutableListOf<String>()
        val e = CodeEditText(ApplicationProvider.getApplicationContext(), history)
        e.language = Language.Java
        e.load(text, history)
        e.onEdited = { edits += it }
        return e to edits
    }

    @Test fun enterBetweenBracesIndentsAndUndoRestores() {
        val (e, edits) = editor("void f() {}")
        e.setSelection(10)
        e.text!!.insert(10, "\n")
        assertEquals("void f() {\n    \n}", e.text.toString())
        assertEquals(15, e.selectionStart)
        assertEquals("void f() {\n    \n}", edits.last())

        e.undo()
        assertEquals("void f() {}", e.text.toString())
        e.redo()
        assertEquals("void f() {\n    \n}", e.text.toString())
    }

    @Test fun loadDoesNotCountAsAnEdit() {
        val (e, edits) = editor("a")
        e.load("b", EditHistory())
        assertEquals("b", e.text.toString())
        assertEquals(emptyList<String>(), edits)
    }
}

package dev.buildit.engine.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEngineTest {
    private fun kinds(text: String, lang: Language) = Lexer.tokenize(text, lang).map { text.substring(it.start, it.end) to it.kind }

    @Test fun javaTokens() {
        val k = kinds("@Override public void run() { String s = \"a\\\"b\"; // hi\n int n = 42; /* c */ }", Language.Java)
        assertTrue(("@Override" to TokenKind.Annotation) in k)
        assertTrue(("public" to TokenKind.Keyword) in k)
        assertTrue(("String" to TokenKind.Type) in k)
        assertTrue(("\"a\\\"b\"" to TokenKind.String) in k)
        assertTrue(("// hi" to TokenKind.Comment) in k)
        assertTrue(("42" to TokenKind.Number) in k)
        assertTrue(("/* c */" to TokenKind.Comment) in k)
    }

    @Test fun kotlinTemplatesAndRawStrings() {
        val text = "val s = \"x ${'$'}{map[\"k\"]} y\"\nval r = \"\"\"raw \" text\"\"\"\nfun f() = null"
        val k = kinds(text, Language.Kotlin)
        assertTrue(k.toString(), ("\"x ${'$'}{map[\"k\"]} y\"" to TokenKind.String) in k)
        assertTrue(("\"\"\"raw \" text\"\"\"" to TokenKind.String) in k)
        assertTrue(("fun" to TokenKind.Keyword) in k)
        assertTrue(("null" to TokenKind.Literal) in k)
        assertEquals(emptyList<EditorDiagnostic>(), Checks.check(text, Language.Kotlin))
    }

    @Test fun xmlAndJsonTokens() {
        val x = kinds("<!-- c --><TextView android:text=\"Hi\" />", Language.Xml)
        assertTrue(("<!-- c -->" to TokenKind.Comment) in x)
        assertTrue(("<TextView" to TokenKind.Tag) in x)
        assertTrue(("android:text" to TokenKind.Attribute) in x)
        assertTrue(("\"Hi\"" to TokenKind.AttrValue) in x)
        val j = kinds("{\"a\": [1, -2.5, true, \"s\"]}", Language.Json)
        assertTrue(("\"a\"" to TokenKind.Key) in j)
        assertTrue(("-2.5" to TokenKind.Number) in j)
        assertTrue(("true" to TokenKind.Literal) in j)
        assertTrue(("\"s\"" to TokenKind.String) in j)
    }

    @Test fun checksFindStructuralErrors() {
        val java = "class A {\n  void f() {\n    int x = (1 + 2;\n    String s = \"open\n  }\n"
        val codes = Checks.check(java, Language.Java).map { it.code to it.line }
        assertTrue(codes.toString(), ("unterminated_string" to 4) in codes)
        assertTrue(codes.toString(), codes.any { it.first == "mismatched" || it.first == "unclosed" })
        assertEquals(emptyList<EditorDiagnostic>(), Checks.check("class A { void f() { g(\"}\"); } }", Language.Java))
        assertEquals(listOf("unmatched_close"), Checks.check("}", Language.Kotlin).map { it.code })
        val xml = Checks.check("<a>\n<b></a>", Language.Xml).single()
        assertEquals("xml", xml.code)
        assertEquals(2, xml.line)
        assertEquals("json", Checks.check("{\"a\": }", Language.Json).single().code)
        assertTrue(Checks.check("{\"a\": [1]}", Language.Json).isEmpty())
    }

    @Test fun searchAndReplace() {
        val text = "cat Cat concat cat"
        assertEquals(4, Search.findAll(text, SearchQuery("cat")).size)
        assertEquals(3, Search.findAll(text, SearchQuery("cat", caseSensitive = true)).size)
        assertEquals(3, Search.findAll(text, SearchQuery("cat", wholeWord = true)).size)
        assertEquals("dog Cat concat dog", Search.replaceAll(text, SearchQuery("cat", caseSensitive = true, wholeWord = true), "dog").text)
        assertEquals("<cat> <Cat> con<cat> <cat>", Search.replaceAll(text, SearchQuery("(cat)", regex = true), "<$1>").text)
        assertEquals("x\$1b", Search.replaceAll("ab", SearchQuery("a"), "x$1").text)
        assertFalse(Search.isValid(SearchQuery("(", regex = true)))
        assertTrue(Search.findAll(text, SearchQuery("(", regex = true)).isEmpty())
        val m = Search.findAll(text, SearchQuery("cat"))
        assertEquals(0, Search.next(m, 16)) // wraps
        assertEquals(m.lastIndex, Search.previous(m, 0))
    }

    @Test fun historyMergesTypingAndUndoesInOrder() {
        val h = EditHistory()
        h.record(Edit(0, "", "a", 0)); h.record(Edit(1, "", "b", 100)); h.record(Edit(2, "", " ", 200)); h.record(Edit(3, "", "c", 300))
        assertEquals(Edit(3, "c", "", 300), h.undo()) // "c" separate after a space
        assertEquals(Edit(2, " ", "", 200), h.undo())
        assertEquals(Edit(0, "ab", "", 100), h.undo())
        assertNull(h.undo())
        assertEquals(Edit(0, "", "ab", 100), h.redo())
        h.record(Edit(5, "x", "y", 1000))
        assertFalse(h.canRedo)
    }

    @Test fun indentation() {
        assertEquals("\n    " to 5, Indent.newline("void f() {", 10))
        assertEquals("\n        \n    " to 9, Indent.newline("    if (a) {}", 12))
        assertEquals("\n  " to 3, Indent.newline("  x", 3))
        assertEquals(3 to 5, TextPositions.lineColumn("a\nb\ncdef", 8))
        assertEquals(4, TextPositions.lineStart("a\nb\ncdef", 3))
    }
}

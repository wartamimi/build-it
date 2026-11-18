package dev.buildit.engine.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorTest {
    private fun codes(doc: BlockDocument) = BlockValidator.validate(doc, ctx).map { (it.blockId ?: "-") + ":" + it.code }.toSet()

    @Test fun sampleIsValid() {
        val d = BlockValidator.validate(sampleDocument(), ctx)
        assertTrue(d.toString(), d.none { it.severity == BlockDiagnostic.Severity.Error })
    }

    @Test fun reportsReferenceAndTypeErrors() {
        val doc = BlockDocument(
            activity = "MainActivity",
            variables = listOf(Variable("count", ValueType.Number), Variable("greeting", ValueType.Text)),
            stacks = listOf(
                BlockStack(0f, 0f, listOf(b("e", "event_create", statements = mapOf("do" to listOf(
                    b("a", "view_set_text", mapOf("view" to "photo"), mapOf("text" to txt("t", "x"))),
                    b("bb", "var_set", mapOf("var" to "count"), mapOf("value" to txt("t2", "oops"))),
                    b("c", "proc_call", mapOf("name" to "nope")),
                    b("d", "ctrl_if"),
                    b("f", "app_start", mapOf("activity" to "Missing")),
                    b("g", "math_round_unknown"),
                    b("h", "view_set_text_color", mapOf("view" to "greeting", "color" to "red"), emptyMap()),
                ))))),
                BlockStack(0f, 0f, listOf(b("e2", "event_create"))),
                BlockStack(0f, 0f, listOf(b("loose", "app_finish"))),
                BlockStack(0f, 0f, listOf(b("k", "event_click", mapOf("view" to "ghost")))),
            ),
        )
        val c = codes(doc)
        listOf(
            "a:view_kind", "t2:type_mismatch", "c:unknown_procedure", "d:missing_input", "f:unknown_activity", "g:unknown_block",
            "h:invalid_color", "e2:duplicate_event", "loose:not_in_event", "k:unknown_view", "-:variable_clashes_view",
        ).forEach { assertTrue("$it in $c", it in c) }
    }

    @Test fun disabledBlocksAreNotChecked() {
        val doc = BlockDocument(
            activity = "MainActivity",
            stacks = listOf(BlockStack(0f, 0f, listOf(b("e", "event_create", statements = mapOf("do" to listOf(
                Block("x", "view_set_text", mapOf("view" to "ghost"), disabled = true),
            )))))),
        )
        assertEquals(emptySet<String>(), codes(doc))
    }
}

package dev.buildit.engine.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockOpsTest {
    private val ids = Ids()

    private fun doc() = BlockDocument(
        activity = "MainActivity",
        variables = listOf(Variable("count", ValueType.Number)),
        stacks = listOf(BlockStack(0f, 0f, listOf(b("e", "event_create", statements = mapOf("do" to listOf(
            b("a", "app_log", inputs = mapOf("text" to txt("t", "1"))),
            b("bb", "app_log", inputs = mapOf("text" to txt("u", "2"))),
            b("c", "app_finish"),
        )))))),
    )

    @Test fun detachTakesTheBlockAndEverythingBelowIt() {
        val (d, chain) = BlockOps.detach(doc(), "bb")
        assertEquals(listOf("bb", "c"), chain.map { it.id })
        assertEquals(listOf("a"), d.find("e")!!.statements["do"]!!.map { it.id })
    }

    @Test fun insertIntoStatementAndAsNewStack() {
        val (d, chain) = BlockOps.detach(doc(), "bb")
        val d2 = BlockOps.insert(d, chain, Slot.Statement("e", "do", 0))
        assertEquals(listOf("bb", "c", "a"), d2.find("e")!!.statements["do"]!!.map { it.id })
        val d3 = BlockOps.addStack(d, chain, 10f, 20f)
        assertEquals(2, d3.stacks.size)
        // A loose statement stack can receive blocks on top, an event stack can't.
        assertTrue(BlockOps.canInsert(d3, listOf(b("x", "app_finish")), Slot.TopLevel(1, 0)))
        assertFalse(BlockOps.canInsert(d3, listOf(b("x", "app_finish")), Slot.TopLevel(0, 1)))
    }

    @Test fun valueSlotsCheckTypesAndDisplaceInsteadOfDeleting() {
        val d = doc()
        assertFalse(BlockOps.canInsert(d, listOf(b("bool", "logic_bool", mapOf("value" to "true"))), Slot.Value("a", "text")).not())
        val withIf = BlockOps.insert(d, listOf(b("if", "ctrl_if", inputs = mapOf("cond" to b("bt", "logic_bool", mapOf("value" to "true"))))), Slot.Statement("e", "do", 0))
        assertFalse(BlockOps.canInsert(withIf, listOf(num("n", 1.0)), Slot.Value("if", "cond")))
        val replaced = BlockOps.insert(withIf, listOf(b("bf", "logic_bool", mapOf("value" to "false"))), Slot.Value("if", "cond"), 5f to 5f)
        assertEquals("bf", replaced.find("if")!!.inputs["cond"]!!.id)
        assertEquals("bt", replaced.stacks.last().blocks.single().id)
        // Hats never nest; statements never go into value slots.
        assertFalse(BlockOps.canInsert(d, listOf(b("h", "event_resume")), Slot.Statement("e", "do", 0)))
        assertFalse(BlockOps.canInsert(d, listOf(b("f", "app_finish")), Slot.Value("a", "text")))
    }

    @Test fun deleteHealsTheStackAndUndoRedoRestores() {
        val ed = BlockEditor(doc())
        ed.apply { BlockOps.delete(it, "bb") }
        assertEquals(listOf("a", "c"), ed.document.find("e")!!.statements["do"]!!.map { it.id })
        assertNull(ed.document.find("u"))
        assertTrue(ed.undo())
        assertEquals(3, ed.document.find("e")!!.statements["do"]!!.size)
        assertTrue(ed.redo())
        assertEquals(2, ed.document.find("e")!!.statements["do"]!!.size)
        assertFalse(ed.redo())
    }

    @Test fun failedEditLeavesHistoryUntouched() {
        val ed = BlockEditor(doc())
        runCatching { ed.apply { BlockOps.delete(it, "missing") } }
        assertFalse(ed.canUndo)
    }

    @Test fun variablesRenameEverywhereAndCannotBeRemovedWhileUsed() {
        var d = BlockOps.insert(doc(), listOf(b("ch", "var_change", mapOf("var" to "count"), mapOf("by" to num("n", 1.0)))), Slot.Statement("e", "do", 0))
        d = BlockOps.renameVariable(d, "count", "score")
        assertEquals("score", d.find("ch")!!.field("var"))
        assertEquals("score", d.variables.single().name)
        assertEquals("in_use", runCatching { BlockOps.removeVariable(d, "score") }.exceptionOrNull()?.message)
        assertEquals("invalid_name", runCatching { BlockOps.addVariable(d, Variable("class", ValueType.Text)) }.exceptionOrNull()?.message)
        assertEquals("duplicate_name", runCatching { BlockOps.addVariable(d, Variable("score", ValueType.Text)) }.exceptionOrNull()?.message)
    }

    @Test fun createFillsInputsWithLiterals() {
        val blk = BlockRegistry.create("view_set_text", ids::next, views = views)
        assertEquals("greeting", blk.field("view"))
        assertEquals("text_literal", blk.inputs["text"]!!.type)
        val chk = BlockRegistry.create("view_is_checked", ids::next, views = views)
        assertEquals("agree", chk.field("view"))
        val dup = BlockOps.duplicate(doc(), "a", ids::next, 0f, 0f)
        val copy = dup.stacks.last().blocks.single()
        assertEquals("app_log", copy.type)
        assertTrue(copy.id != "a" && copy.inputs["text"]!!.id != "t")
    }
}

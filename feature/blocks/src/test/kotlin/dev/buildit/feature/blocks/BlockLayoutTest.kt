package dev.buildit.feature.blocks

import dev.buildit.engine.blocks.Block
import dev.buildit.engine.blocks.BlockDocument
import dev.buildit.engine.blocks.BlockShape
import dev.buildit.engine.blocks.BlockStack
import dev.buildit.engine.blocks.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockLayoutTest {
    private val layout = BlockLayout(1f, { s -> TextSize(s.length * 7f, 14f) })

    private val doc = BlockDocument(
        activity = "MainActivity",
        stacks = listOf(
            BlockStack(100f, 50f, listOf(Block("e", "event_create", statements = mapOf("do" to listOf(
                Block("if", "ctrl_if_else", inputs = mapOf("cond" to Block("bool", "logic_bool", mapOf("value" to "true"))), statements = mapOf(
                    "then" to listOf(Block("log", "app_log", inputs = mapOf("text" to Block("t", "text_literal", mapOf("text" to "hi"))))),
                )),
                Block("fin", "app_finish"),
            ))))),
            BlockStack(600f, 50f, listOf(Block("loose", "app_toast"))),
        ),
    )

    @Test fun nestedBlocksSitInsideTheirParentsAndStackDownwards() {
        val stacks = layout.layout(doc)
        val event = stacks[0].blocks.single()
        val all = event.walk().associateBy { it.block.id }
        val ifBlock = all.getValue("if")
        val log = all.getValue("log")
        val fin = all.getValue("fin")
        assertEquals(100f + 18f, ifBlock.box.left) // inside the event's mouth
        assertTrue(log.box.left > ifBlock.box.left)
        assertTrue(log.box.top >= ifBlock.box.top)
        assertTrue("finish below if/else", fin.box.top >= ifBlock.box.bottom - 0.01f)
        assertTrue(event.box.bottom >= fin.box.bottom)
        val text = all.getValue("t")
        assertTrue(text.box.left >= log.box.left && text.box.right <= log.box.right)
        // if/else has two mouths and an "else" label.
        assertEquals(2, ifBlock.mouths.size)
        assertTrue(ifBlock.pieces.any { it is Piece.Label && it.text == "else" })
    }

    @Test fun hitTestFindsTheDeepestBlockAndFields() {
        val stacks = layout.layout(doc)
        val t = stacks[0].blocks.single().walk().first { it.block.id == "t" }
        val hit = layout.hit(stacks, t.box.left + 2, t.box.top + t.box.height / 2)
        assertEquals("t", hit?.block?.id)
        val field = t.pieces.filterIsInstance<Piece.Field>().single()
        assertNotNull(layout.fieldAt(t, field.box.left + 1, field.box.top + 1))
        assertEquals(null, layout.hit(stacks, 5f, 5f))
    }

    @Test fun snapTargetsCoverMouthsSiblingsAndEmptyInputs() {
        val stacks = layout.layout(doc)
        val st = layout.targets(stacks, BlockShape.Statement).map { it.slot }
        assertTrue(Slot.Statement("e", "do", 0) in st)
        assertTrue(Slot.Statement("e", "do", 2) in st)
        assertTrue(Slot.Statement("if", "else", 0) in st)
        assertTrue(Slot.TopLevel(1, 0) in st)
        val values = layout.targets(stacks, BlockShape.Value).map { it.slot }
        assertTrue(Slot.Value("loose", "text") in values) // empty input
        assertTrue(Slot.Value("if", "cond") in values) // filled input (replace)
    }
}

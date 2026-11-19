package dev.buildit.feature.blocks

import dev.buildit.engine.blocks.Block
import dev.buildit.engine.blocks.BlockDocument
import dev.buildit.engine.blocks.BlockStack
import dev.buildit.engine.blocks.ViewInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSourcesTest {
    @Test fun eventsAreSortedIntoTheKindsOfTheEventsTab() {
        val doc = BlockDocument(
            activity = "MainActivity",
            stacks = listOf(
                BlockStack(0f, 0f, listOf(Block("a", "event_create"))),
                BlockStack(0f, 0f, listOf(Block("b", "event_click", mapOf("view" to "button1")))),
                BlockStack(0f, 0f, listOf(Block("d", "proc_define", mapOf("name" to "work")))),
                BlockStack(0f, 0f, listOf(Block("e", "app_finish"))),
            ),
        )
        val bySection = eventEntries(doc).associate { it.head.id to it.section }
        assertEquals(EventSection.Screen, bySection["a"])
        assertEquals(EventSection.Views, bySection["b"])
        assertEquals(EventSection.More, bySection["d"])
        assertEquals(EventSection.Loose, bySection["e"])
        assertEquals("button1", eventOwner(doc.stacks[1].blocks[0], "MainActivity"))
        assertEquals("MainActivity", eventOwner(doc.stacks[0].blocks[0], "MainActivity"))
    }

    @Test fun compoundViewsOfferTheCheckedEvent() {
        assertTrue(viewCandidates(ViewInfo("box", "CheckBox")).any { it.first == "event_checked" })
        assertTrue(viewCandidates(ViewInfo("button1", "Button")).none { it.first == "event_checked" })
    }
}

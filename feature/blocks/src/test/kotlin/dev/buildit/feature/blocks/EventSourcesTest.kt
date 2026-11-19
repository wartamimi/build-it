package dev.buildit.feature.blocks

import androidx.test.core.app.ApplicationProvider
import dev.buildit.engine.blocks.Block
import dev.buildit.engine.blocks.BlockDocument
import dev.buildit.engine.blocks.BlockStack
import dev.buildit.engine.blocks.ViewInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventSourcesTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test fun eventsAreGroupedByTheThingTheyBelongTo() {
        val doc = BlockDocument(
            activity = "MainActivity",
            stacks = listOf(
                BlockStack(0f, 0f, listOf(Block("a", "event_create"))),
                BlockStack(0f, 0f, listOf(Block("b", "event_click", mapOf("view" to "button1")))),
                BlockStack(0f, 0f, listOf(Block("c", "event_click", mapOf("view" to "gone")))),
                BlockStack(0f, 0f, listOf(Block("d", "proc_define", mapOf("name" to "work")))),
            ),
        )
        val sources = eventSources(doc, listOf(ViewInfo("button1", "Button"), ViewInfo("box", "CheckBox")), res)
        val byKey = sources.associateBy { it.key }
        assertEquals(listOf(SCREEN_SOURCE, "button1", "box", EventSection.More.name, EventSection.Loose.name), sources.map { it.key })
        assertEquals(listOf("a"), byKey.getValue(SCREEN_SOURCE).entries.map { it.head.id })
        assertEquals(listOf("b"), byKey.getValue("button1").entries.map { it.head.id })
        assertTrue(byKey.getValue("box").candidates.any { it.first == "event_checked" })
        assertTrue(byKey.getValue("button1").candidates.none { it.first == "event_checked" })
        assertEquals(listOf("d"), byKey.getValue(EventSection.More.name).entries.map { it.head.id })
        // The event of a view that is gone is still reachable.
        assertEquals(listOf("c"), byKey.getValue(EventSection.Loose.name).entries.map { it.head.id })
    }
}

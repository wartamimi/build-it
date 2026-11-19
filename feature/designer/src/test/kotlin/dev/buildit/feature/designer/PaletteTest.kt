package dev.buildit.feature.designer

import dev.buildit.engine.layout.ComponentCatalog
import dev.buildit.engine.layout.LayoutCommand
import dev.buildit.engine.layout.LayoutEditor
import dev.buildit.engine.layout.XmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    @Test fun everyPaletteEntryIsACatalogWidgetAndEveryWidgetIsInThePalette() {
        Palette.items.forEach { assertNotNull(it.label, ComponentCatalog.find(it.tag)) }
        assertEquals(Palette.items.size, Palette.items.map { it.key }.toSet().size)
        val tags = Palette.items.map { it.tag }.toSet()
        ComponentCatalog.all.forEach { assertTrue("${it.tag} missing from the palette", it.tag in tags) }
        // Short single-line names, like Sketchware's palette
        assertTrue(Palette.items.all { it.label.length <= 12 })
    }

    @Test fun presetsInsertTheirAttributes() {
        val h = Palette.items.first { it.label == "Linear(H)" }
        val ed = LayoutEditor(XmlParser.parse("""<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent" />"""))
        ed.apply(LayoutCommand.Insert(ed.root.uid, 0, h.tag, h.attrs))
        val added = ed.root.elements.single()
        assertEquals("horizontal", added["android:orientation"])
        assertEquals(Glyph.LinearH, Palette.glyphFor(added.name) { added[it] })
        assertEquals(listOf("Linear(H)", "Linear(V)"), Palette.search("linear").values.flatten().map { it.label })
    }
}

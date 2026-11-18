package dev.buildit.engine.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LayoutEditorTest {
    private fun editor(xml: String = LayoutEditor.newLayout()) = LayoutEditor(XmlParser.parse(xml))

    @Test fun insertAssignsUniqueIdAndDefaults() {
        val e = editor()
        e.apply(LayoutCommand.Insert(e.root.uid, 0, "Button"))
        e.apply(LayoutCommand.Insert(e.root.uid, 1, "Button"))
        val ids = e.root.elements.map { it[Attr.ID] }
        assertEquals(listOf("@+id/button1", "@+id/button2"), ids)
        assertEquals("Button", e.root.elements[0]["android:text"])
        assertEquals(e.root.elements[1].uid, e.lastCreatedUid)
    }

    @Test fun insertAtIndexAndMoveWithinParent() {
        val e = editor()
        listOf("TextView", "Button", "EditText").forEachIndexed { i, t -> e.apply(LayoutCommand.Insert(e.root.uid, i, t)) }
        val edit = e.root.elements[2].uid
        e.apply(LayoutCommand.Move(edit, e.root.uid, 0))
        assertEquals(listOf("EditText", "TextView", "Button"), e.root.elements.map { it.name })
        val text = e.root.elements[1].uid
        e.apply(LayoutCommand.Move(text, e.root.uid, 3))
        assertEquals(listOf("EditText", "Button", "TextView"), e.root.elements.map { it.name })
    }

    @Test fun moveIntoContainerAndRejectMoveIntoSelf() {
        val e = editor()
        e.apply(LayoutCommand.Insert(e.root.uid, 0, "FrameLayout"))
        val frame = e.lastCreatedUid!!
        e.apply(LayoutCommand.Insert(e.root.uid, 1, "Button"))
        val button = e.lastCreatedUid!!
        e.apply(LayoutCommand.Move(button, frame, 0))
        assertEquals("Button", e.root.find(frame)!!.elements.single().name)
        try {
            e.apply(LayoutCommand.Move(frame, frame, 0)); fail()
        } catch (ex: LayoutEditException) { assertEquals("move_into_self", ex.code) }
    }

    @Test fun containerRulesAreEnforced() {
        val e = editor()
        e.apply(LayoutCommand.Insert(e.root.uid, 0, "Button"))
        val button = e.lastCreatedUid!!
        try { e.apply(LayoutCommand.Insert(button, 0, "TextView")); fail() } catch (ex: LayoutEditException) { assertEquals("not_container", ex.code) }
        e.apply(LayoutCommand.Insert(e.root.uid, 1, "ScrollView"))
        val scroll = e.lastCreatedUid!!
        e.apply(LayoutCommand.Insert(scroll, 0, "LinearLayout"))
        try { e.apply(LayoutCommand.Insert(scroll, 1, "TextView")); fail() } catch (ex: LayoutEditException) { assertEquals("too_many_children", ex.code) }
    }

    @Test fun setAttributeValidatesIdsAndSizes() {
        val e = editor()
        e.apply(LayoutCommand.Insert(e.root.uid, 0, "TextView"))
        val a = e.lastCreatedUid!!
        e.apply(LayoutCommand.Insert(e.root.uid, 1, "TextView"))
        val b = e.lastCreatedUid!!
        e.apply(LayoutCommand.SetAttribute(a, "android:text", "Hello"))
        assertEquals("Hello", e.root.find(a)!!["android:text"])
        e.apply(LayoutCommand.SetAttribute(a, "android:text", null))
        assertNull(e.root.find(a)!!["android:text"])
        try { e.apply(LayoutCommand.SetAttribute(b, Attr.ID, "@+id/text1")); fail() } catch (ex: LayoutEditException) { assertEquals("duplicate_id", ex.code) }
        try { e.apply(LayoutCommand.SetAttribute(b, Attr.ID, "@+id/1bad")); fail() } catch (ex: LayoutEditException) { assertEquals("invalid_id", ex.code) }
        try { e.apply(LayoutCommand.SetAttribute(b, Attr.WIDTH, "")); fail() } catch (ex: LayoutEditException) { assertEquals("size_required", ex.code) }
        e.apply(LayoutCommand.SetAttribute(b, "app:layout_constraintTop_toTopOf", "parent"))
        assertEquals(Attr.NS_APP, e.root["xmlns:app"])
    }

    @Test fun undoRedo() {
        val e = editor()
        val original = e.toXml()
        e.apply(LayoutCommand.Insert(e.root.uid, 0, "Button"))
        val withButton = e.toXml()
        assertTrue(e.undo())
        assertEquals(original, e.toXml())
        assertTrue(e.redo())
        assertEquals(withButton, e.toXml())
        e.undo()
        e.apply(LayoutCommand.Insert(e.root.uid, 0, "TextView"))
        assertFalse(e.canRedo)
    }

    @Test fun duplicateCopiesSubtreeWithFreshIds() {
        val e = editor()
        e.apply(LayoutCommand.Insert(e.root.uid, 0, "LinearLayout"))
        val inner = e.lastCreatedUid!!
        e.apply(LayoutCommand.Insert(inner, 0, "Button"))
        e.apply(LayoutCommand.Duplicate(inner))
        assertEquals(2, e.root.elements.size)
        val ids = LayoutEditor.allIds(e.root)
        assertEquals(4, ids.size)
        assertTrue(LayoutEditor.validate(e.root).none { it.code == "duplicate_id" })
    }

    @Test fun removeAndRootProtection() {
        val e = editor()
        e.apply(LayoutCommand.Insert(e.root.uid, 0, "Button"))
        e.apply(LayoutCommand.Remove(e.lastCreatedUid!!))
        assertTrue(e.root.elements.isEmpty())
        try { e.apply(LayoutCommand.Remove(e.root.uid)); fail() } catch (ex: LayoutEditException) { assertEquals("remove_root", ex.code) }
    }

    @Test fun insertKeepsCommentsInPlace() {
        val e = editor(
            """<LinearLayout xmlns:android="${Attr.NS_ANDROID}" android:layout_width="match_parent" android:layout_height="match_parent">
              <!-- first --><TextView android:layout_width="1dp" android:layout_height="1dp"/><!-- second -->
            </LinearLayout>""",
        )
        e.apply(LayoutCommand.Insert(e.root.uid, 1, "Button"))
        val kinds = e.root.children.map { (it as? XmlElement)?.name ?: (it as XmlComment).text }
        assertEquals(listOf("first", "TextView", "Button", "second"), kinds)
    }

    @Test fun validationFindsProblems() {
        val root = XmlParser.parse(
            """<FrameLayout xmlns:android="${Attr.NS_ANDROID}" android:layout_width="match_parent">
                <ImageView android:id="@+id/a" android:layout_width="1dp" android:layout_height="1dp" android:layout_weight="1"/>
                <Button android:id="@+id/a" android:layout_width="1dp" android:layout_height="1dp"><TextView android:layout_width="1dp" android:layout_height="1dp"/></Button>
                <com.x.Custom android:layout_width="1dp" android:layout_height="1dp"/>
            </FrameLayout>""",
        )
        val codes = LayoutEditor.validate(root).map { it.code }.toSet()
        assertEquals(
            setOf("missing_height", "duplicate_id", "children_not_allowed", "unknown_view", "missing_content_description", "weight_outside_linear"),
            codes,
        )
    }

    @Test fun collectedWidgetsRoundTripWithFreshIds() {
        val ed = editor()
        ed.apply(LayoutCommand.Insert(ed.root.uid, 0, "LinearLayout", mapOf("app:layout_constraintTop_toTopOf" to "parent")))
        val box = ed.root.elements[0]
        ed.apply(LayoutCommand.Insert(box.uid, 0, "Button"))
        val saved = LayoutEditor.subtreeXml(ed.root.elements[0])
        assertTrue(saved, saved.contains("xmlns:android") && saved.contains("xmlns:app"))

        // Inserting it again gets new ids, so the layout stays valid
        ed.apply(LayoutCommand.InsertTree(ed.root.uid, 1, XmlParser.parse(saved)))
        val ids = ed.root.walk().mapNotNull { it[Attr.ID] }.toList()
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(4, ids.size)
        assertEquals(ed.root.elements[1].uid, ed.lastCreatedUid)
        assertTrue(ed.root.elements[1].attributes.keys.none { it.startsWith("xmlns") })
        assertTrue(LayoutEditor.validate(ed.root).none { it.severity == LayoutDiagnostic.Severity.Error })
        assertTrue(ed.undo())
        assertEquals(1, ed.root.elements.size)
    }
}

package dev.buildit.engine.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XmlTest {
    private val sample = """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:tools="http://schemas.android.com/tools"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical">
            <!-- Header -->
            <TextView
                android:id="@+id/title"
                android:text="Tom &amp; Jerry &lt;3"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                tools:ignore="HardcodedText" />
            <com.example.CustomView android:layout_width="10dp" android:layout_height="10dp" app:unknown='x'/>
        </LinearLayout>
    """.trimIndent()

    @Test fun parsesElementsAttributesCommentsAndEntities() {
        val root = XmlParser.parse(sample)
        assertEquals("LinearLayout", root.name)
        assertEquals(2, root.elements.size)
        assertTrue(root.children.first() is XmlComment)
        assertEquals("Tom & Jerry <3", root.elements[0]["android:text"])
        assertEquals("x", root.elements[1]["app:unknown"])
    }

    @Test fun roundTripIsStableAndPreservesUnknownContent() {
        val once = XmlWriter.write(XmlParser.parse(sample))
        val twice = XmlWriter.write(XmlParser.parse(once))
        assertEquals(once, twice)
        assertTrue(once.contains("<!-- Header -->"))
        assertTrue(once.contains("com.example.CustomView"))
        assertTrue(once.contains("tools:ignore=\"HardcodedText\""))
        assertTrue(once.contains("Tom &amp; Jerry &lt;3"))
    }

    @Test fun writerOrdersAttributesConventionally() {
        val out = XmlWriter.write(XmlParser.parse(sample))
        val tv = out.substringAfter("<TextView").substringBefore("/>")
        val order = listOf("android:id", "android:layout_width", "android:layout_height", "android:text", "tools:ignore").map { tv.indexOf(it) }
        assertEquals(order.sorted(), order)
    }

    @Test fun reportsErrorsWithPosition() {
        listOf("<A><B></A>", "<A b=c/>", "<A b=\"1\" b=\"2\"/>", "<!DOCTYPE x><A/>", "<A>", "<A/><B/>", "<A b=\"&nope;\"/>").forEach {
            try {
                XmlParser.parse(it)
                fail("expected failure for $it")
            } catch (e: XmlParseException) {
                assertTrue(e.line >= 1)
            }
        }
    }

    @Test fun parsesTemplateLayout() {
        val xml = dev.buildit.engine.layout.LayoutEditor.newLayout("FrameLayout")
        val root = XmlParser.parse(xml)
        assertEquals("FrameLayout", root.name)
        assertEquals("match_parent", root[Attr.WIDTH])
    }
}

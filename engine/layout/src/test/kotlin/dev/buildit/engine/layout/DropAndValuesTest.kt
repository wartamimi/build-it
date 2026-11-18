package dev.buildit.engine.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DropAndValuesTest {
    private val xml = """
        <LinearLayout xmlns:android="${Attr.NS_ANDROID}" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical">
            <TextView android:layout_width="match_parent" android:layout_height="wrap_content"/>
            <FrameLayout android:layout_width="match_parent" android:layout_height="wrap_content">
                <Button android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            </FrameLayout>
            <Button android:layout_width="wrap_content" android:layout_height="wrap_content"/>
        </LinearLayout>
    """.trimIndent()
    private val root = XmlParser.parse(xml)
    private val text = root.elements[0]
    private val frame = root.elements[1]
    private val inner = frame.elements[0]
    private val last = root.elements[2]
    private val boxes = mapOf(
        root.uid to Box(0f, 0f, 100f, 300f),
        text.uid to Box(0f, 0f, 100f, 50f),
        frame.uid to Box(0f, 50f, 100f, 150f),
        inner.uid to Box(0f, 50f, 40f, 80f),
        last.uid to Box(0f, 150f, 40f, 180f),
    )

    @Test fun hitFindsDeepest() {
        assertEquals(inner.uid, DropResolver.hit(root, boxes, 10f, 60f))
        assertEquals(frame.uid, DropResolver.hit(root, boxes, 90f, 100f))
        assertEquals(root.uid, DropResolver.hit(root, boxes, 90f, 250f))
        assertNull(DropResolver.hit(root, boxes, 200f, 250f))
    }

    @Test fun dropIntoLinearUsesVerticalPosition() {
        assertEquals(DropTarget(root.uid, 0), DropResolver.resolve(root, boxes, 50f, 10f))
        assertEquals(DropTarget(root.uid, 3), DropResolver.resolve(root, boxes, 50f, 250f))
        assertEquals(DropTarget(root.uid, 2), DropResolver.resolve(root, boxes, 50f, 155f))
    }

    @Test fun dropOverButtonGoesToItsContainer() {
        assertEquals(DropTarget(frame.uid, 1), DropResolver.resolve(root, boxes, 90f, 140f))
        assertEquals(frame.uid, DropResolver.resolve(root, boxes, 10f, 60f)!!.parentUid)
    }

    @Test fun movingElementSkipsItselfAndIndexWorksWithMove() {
        val e = LayoutEditor(root)
        val target = DropResolver.resolve(e.root, boxes, 50f, 250f, draggedUid = text.uid)!!
        e.apply(LayoutCommand.Move(text.uid, target.parentUid, target.index))
        assertEquals(listOf("FrameLayout", "Button", "TextView"), e.root.elements.map { it.name })
    }

    @Test fun resourceValues() {
        val map = mutableMapOf<String, String>()
        ResourceValues.parse(
            """<resources><string name="hi">Don\'t \"go\"</string><color name="brand">#FF0000</color><color name="alias">@color/brand</color><dimen name="pad">16dp</dimen></resources>""",
            map,
        )
        val night = mutableMapOf<String, String>()
        ResourceValues.parse("""<resources><color name="brand">#00FF00</color></resources>""", night)
        val v = ResourceValues(map, night)
        assertEquals("Don't \"go\"", v.resolve("@string/hi"))
        assertEquals("#FF0000", v.resolve("@color/alias"))
        assertEquals("#00FF00", v.resolve("@color/alias", nightMode = true))
        assertEquals("16dp", v.resolve("@dimen/pad"))
        assertNull(v.resolve("@string/missing"))
    }

    @Test fun parseLiterals() {
        assertEquals(Values.Dimension(16f, Values.Unit.Dp), Values.parseDimension("16dp"))
        assertEquals(Values.Dimension(1.5f, Values.Unit.Sp), Values.parseDimension("1.5sp"))
        assertNull(Values.parseDimension("16"))
        assertEquals(0xFFFF0000.toInt(), Values.parseColor("#F00"))
        assertEquals(0x80112233.toInt(), Values.parseColor("#80112233"))
        assertNull(Values.parseColor("#12345"))
    }
}

package dev.buildit.feature.designer

import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.core.app.ApplicationProvider
import dev.buildit.engine.layout.ResourceValues
import dev.buildit.engine.layout.XmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreviewRendererTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun render(xml: String) = PreviewRenderer(ctx, PreviewConfig(), ResourceValues.Empty, null).let { r ->
        val root = XmlParser.parse(xml)
        root to r.render(root)
    }

    @Test fun buildsRealViewsWithAttributes() {
        val (root, result) = render(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal">
                <TextView android:id="@+id/title" android:layout_width="wrap_content" android:layout_height="wrap_content"
                    android:text="Hello" android:textSize="20sp"/>
                <Button android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="Go"/>
            </LinearLayout>
            """.trimIndent(),
        )
        val ll = result.root as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, ll.orientation)
        assertEquals(3, result.views.size)
        val tv = result.views[root.elements[0].uid] as TextView
        assertEquals("Hello", tv.text.toString())
        val btn = result.views[root.elements[1].uid] as Button
        assertEquals(1f, (btn.layoutParams as LinearLayout.LayoutParams).weight)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun libraryViewsRenderAndUnknownViewsAreReportedNotFaked() {
        val (root, result) = render(
            """
            <androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <com.example.MyChart android:layout_width="100dp" android:layout_height="100dp"/>
            </androidx.constraintlayout.widget.ConstraintLayout>
            """.trimIndent(),
        )
        assertTrue(result.root is ConstraintLayout)
        val unknown = root.elements[0].uid
        assertTrue(result.issues.any { it.uid == unknown && it.code == "not_previewable" })
        assertTrue(result.views.containsKey(unknown))
    }
}

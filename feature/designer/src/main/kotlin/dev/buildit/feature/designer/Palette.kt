package dev.buildit.feature.designer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The widget palette of the phone designer, laid out the way Sketchware users expect: a few
 * sections with short names (Linear(H), Scroll(V), ...) and a small drawing per widget. The
 * drawings are Build It's own, made from simple shapes on a 24-unit grid.
 */

enum class PaletteSection(val label: Int) {
    Layouts(R.string.ds_pal_layouts), Widgets(R.string.ds_pal_widgets), Lists(R.string.ds_pal_lists), AndroidX(R.string.ds_pal_androidx), Other(R.string.ds_pal_other)
}

enum class Glyph {
    LinearH, LinearV, ScrollV, ScrollH, Frame, Relative, Constraint, Card, Group,
    Button, Text, Edit, Image, ImageButton, Check, Radio, Switch, Toggle, Spinner, AutoComplete, Seek, Rating, Progress, Space,
    List, Grid, Recycler, Web, Video, Calendar, Toolbar, AppBar, BottomNav, Drawer, View,
}

/** One palette entry: a catalog tag plus the attributes a preset adds (Linear(H) is a horizontal LinearLayout). */
data class PaletteItem(
    val key: String,
    val tag: String,
    val label: String,
    val glyph: Glyph,
    val section: PaletteSection,
    val attrs: Map<String, String> = emptyMap(),
)

object Palette {
    private const val CARD = "androidx.cardview.widget.CardView"
    private fun item(key: String, tag: String, label: String, glyph: Glyph, section: PaletteSection, attrs: Map<String, String> = emptyMap()) =
        PaletteItem(key, tag, label, glyph, section, attrs)

    val items: List<PaletteItem> = listOf(
        item("linear-h", "LinearLayout", "Linear(H)", Glyph.LinearH, PaletteSection.Layouts, mapOf("android:orientation" to "horizontal")),
        item("linear-v", "LinearLayout", "Linear(V)", Glyph.LinearV, PaletteSection.Layouts, mapOf("android:orientation" to "vertical")),
        item("scroll-h", "HorizontalScrollView", "Scroll(H)", Glyph.ScrollH, PaletteSection.Layouts),
        item("scroll-v", "ScrollView", "Scroll(V)", Glyph.ScrollV, PaletteSection.Layouts),
        item("frame", "FrameLayout", "Frame", Glyph.Frame, PaletteSection.Layouts),
        item("relative", "RelativeLayout", "Relative", Glyph.Relative, PaletteSection.Layouts),
        item("constraint", "androidx.constraintlayout.widget.ConstraintLayout", "Constraint", Glyph.Constraint, PaletteSection.Layouts),
        item("card", CARD, "CardView", Glyph.Card, PaletteSection.Layouts),
        item("radio-group", "RadioGroup", "RadioGroup", Glyph.Group, PaletteSection.Layouts),

        item("button", "Button", "Button", Glyph.Button, PaletteSection.Widgets),
        item("text", "TextView", "TextView", Glyph.Text, PaletteSection.Widgets),
        item("edit", "EditText", "EditText", Glyph.Edit, PaletteSection.Widgets),
        item("image", "ImageView", "ImageView", Glyph.Image, PaletteSection.Widgets),
        item("image-button", "ImageButton", "ImageButton", Glyph.ImageButton, PaletteSection.Widgets),
        item("check", "CheckBox", "CheckBox", Glyph.Check, PaletteSection.Widgets),
        item("radio", "RadioButton", "RadioButton", Glyph.Radio, PaletteSection.Widgets),
        item("switch", "Switch", "Switch", Glyph.Switch, PaletteSection.Widgets),
        item("toggle", "ToggleButton", "Toggle", Glyph.Toggle, PaletteSection.Widgets),
        item("spinner", "Spinner", "Spinner", Glyph.Spinner, PaletteSection.Widgets),
        item("auto", "AutoCompleteTextView", "AutoComplete", Glyph.AutoComplete, PaletteSection.Widgets),
        item("seek", "SeekBar", "SeekBar", Glyph.Seek, PaletteSection.Widgets),
        item("rating", "RatingBar", "RatingBar", Glyph.Rating, PaletteSection.Widgets),
        item("progress", "ProgressBar", "ProgressBar", Glyph.Progress, PaletteSection.Widgets),
        item("space", "Space", "Space", Glyph.Space, PaletteSection.Widgets),

        item("list", "ListView", "ListView", Glyph.List, PaletteSection.Lists),
        item("grid", "GridView", "GridView", Glyph.Grid, PaletteSection.Lists),
        item("web", "WebView", "WebView", Glyph.Web, PaletteSection.Lists),
        item("video", "VideoView", "VideoView", Glyph.Video, PaletteSection.Lists),
        item("calendar", "CalendarView", "Calendar", Glyph.Calendar, PaletteSection.Lists),

        item("recycler", "androidx.recyclerview.widget.RecyclerView", "RecyclerView", Glyph.Recycler, PaletteSection.AndroidX),
        item("material-card", "com.google.android.material.card.MaterialCardView", "MaterialCard", Glyph.Card, PaletteSection.AndroidX),
        item("toolbar", "androidx.appcompat.widget.Toolbar", "Toolbar", Glyph.Toolbar, PaletteSection.AndroidX),
        item("appbar", "com.google.android.material.appbar.AppBarLayout", "AppBar", Glyph.AppBar, PaletteSection.AndroidX),
        item("bottom-nav", "com.google.android.material.bottomnavigation.BottomNavigationView", "BottomNav", Glyph.BottomNav, PaletteSection.AndroidX),
        item("drawer", "androidx.drawerlayout.widget.DrawerLayout", "Drawer", Glyph.Drawer, PaletteSection.AndroidX),

        item("view", "View", "View", Glyph.View, PaletteSection.Other),
    )

    fun find(key: String) = items.firstOrNull { it.key == key }

    /** Items matching [query] by short label or tag, grouped by section in palette order. */
    fun search(query: String): Map<PaletteSection, List<PaletteItem>> =
        items.filter { query.isBlank() || it.label.contains(query, true) || it.tag.substringAfterLast('.').contains(query, true) }.groupBy { it.section }

    /** The glyph for a view in the layout (property bar, drag ghost). */
    fun glyphFor(tag: String, attrs: (String) -> String? = { null }): Glyph = when (tag.substringAfterLast('.')) {
        "LinearLayout" -> if (attrs("android:orientation") == "horizontal") Glyph.LinearH else Glyph.LinearV
        else -> items.firstOrNull { it.tag.substringAfterLast('.') == tag.substringAfterLast('.') }?.glyph ?: Glyph.View
    }
}

/** Accent colour of a palette section, used for its drawings. */
fun sectionColor(s: PaletteSection): Color = when (s) {
    PaletteSection.Layouts -> Color(0xFF2E7D32)
    PaletteSection.Widgets -> Color(0xFF1565C0)
    PaletteSection.Lists -> Color(0xFF6A1B9A)
    PaletteSection.AndroidX -> Color(0xFFE65100)
    PaletteSection.Other -> Color(0xFF546E7A)
}

@Composable
fun WidgetGlyph(glyph: Glyph, color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}

private fun DrawScope.drawGlyph(g: Glyph, c: Color) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round)
    val thin = Stroke(width = 1.4f * u, cap = StrokeCap.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 2f, fill: Boolean = false, color: Color = c) =
        drawRoundRect(color, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = if (fill) Fill else stroke)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, s: Stroke = stroke) = drawLine(c, p(x1, y1), p(x2, y2), s.width, s.cap)
    fun dot(x: Float, y: Float, r: Float, fill: Boolean = true) =
        drawCircle(c, r * u, p(x, y), style = if (fill) Fill else stroke)
    val soft = c.copy(alpha = 0.22f)
    when (g) {
        Glyph.LinearH -> { box(2f, 5f, 20f, 14f); box(5f, 8f, 4f, 8f, 1f, true); box(10f, 8f, 4f, 8f, 1f, true); box(15f, 8f, 4f, 8f, 1f, true) }
        Glyph.LinearV -> { box(5f, 2f, 14f, 20f); box(8f, 5f, 8f, 4f, 1f, true); box(8f, 10f, 8f, 4f, 1f, true); box(8f, 15f, 8f, 4f, 1f, true) }
        Glyph.ScrollV -> { box(4f, 2f, 14f, 20f); line(21f, 4f, 21f, 20f, thin); box(19.6f, 6f, 2.8f, 6f, 1.4f, true); line(7f, 7f, 15f, 7f); line(7f, 12f, 15f, 12f); line(7f, 17f, 13f, 17f) }
        Glyph.ScrollH -> { box(2f, 4f, 20f, 14f); line(4f, 21f, 20f, 21f, thin); box(6f, 19.6f, 6f, 2.8f, 1.4f, true); line(7f, 7f, 7f, 15f); line(12f, 7f, 12f, 15f); line(17f, 7f, 17f, 13f) }
        Glyph.Frame -> { box(2f, 3f, 20f, 18f); box(6f, 7f, 10f, 8f, 1.5f, true, soft); box(9f, 10f, 10f, 8f, 1.5f) }
        Glyph.Relative -> { box(2f, 3f, 20f, 18f); box(5f, 6f, 6f, 5f, 1f, true); box(13f, 13f, 6f, 5f, 1f, true); line(11f, 8.5f, 16f, 8.5f, thin); line(16f, 8.5f, 16f, 13f, thin) }
        Glyph.Constraint -> { box(2f, 3f, 20f, 18f); box(8f, 9f, 8f, 6f, 1.5f, true); line(2f, 12f, 8f, 12f, thin); line(16f, 12f, 22f, 12f, thin); line(12f, 3f, 12f, 9f, thin) }
        Glyph.Card -> { box(3f, 6f, 18f, 13f, 3f, true, soft); box(2f, 4f, 18f, 13f, 3f); line(5f, 9f, 13f, 9f); line(5f, 13f, 16f, 13f, thin) }
        Glyph.Group -> { box(2f, 3f, 20f, 18f); dot(7f, 9f, 2.2f, false); dot(7f, 9f, 1f); line(11f, 9f, 18f, 9f); dot(7f, 15f, 2.2f, false); line(11f, 15f, 18f, 15f) }
        Glyph.Button -> { box(2f, 7f, 20f, 10f, 4f, true); drawLine(Color.White, p(8f, 12f), p(16f, 12f), 1.8f * u, StrokeCap.Round) }
        Glyph.Text -> { line(5f, 5f, 19f, 5f); line(12f, 5f, 12f, 19f); line(9f, 19f, 15f, 19f, thin) }
        Glyph.Edit -> { box(2f, 6f, 20f, 12f, 2f); line(6f, 12f, 13f, 12f, thin); line(16f, 9f, 16f, 15f) }
        Glyph.Image -> {
            box(2f, 4f, 20f, 16f); dot(8f, 9f, 2f)
            drawPath(Path().apply { moveTo(4f * u, 18f * u); lineTo(10f * u, 12f * u); lineTo(14f * u, 15f * u); lineTo(17f * u, 12f * u); lineTo(20f * u, 18f * u); close() }, c)
        }
        Glyph.ImageButton -> { box(2f, 4f, 20f, 16f, 4f, true, soft); box(2f, 4f, 20f, 16f, 4f); dot(9f, 10f, 1.8f); drawPath(Path().apply { moveTo(6f * u, 17f * u); lineTo(11f * u, 12.5f * u); lineTo(18f * u, 17f * u); close() }, c) }
        Glyph.Check -> { box(4f, 4f, 16f, 16f, 3f); drawPath(Path().apply { moveTo(8f * u, 12f * u); lineTo(11f * u, 15f * u); lineTo(16.5f * u, 9f * u) }, c, style = stroke) }
        Glyph.Radio -> { dot(12f, 12f, 8f, false); dot(12f, 12f, 4f) }
        Glyph.Switch -> { box(2f, 7f, 20f, 10f, 5f, true, soft); box(2f, 7f, 20f, 10f, 5f); dot(17f, 12f, 3.6f) }
        Glyph.Toggle -> { box(2f, 7f, 20f, 10f, 2f); box(12f, 9f, 8f, 6f, 1.5f, true); line(5f, 12f, 9f, 12f, thin) }
        Glyph.Spinner -> { box(2f, 6f, 20f, 12f, 2f); line(5f, 12f, 11f, 12f, thin); drawPath(Path().apply { moveTo(15f * u, 10.5f * u); lineTo(19f * u, 10.5f * u); lineTo(17f * u, 13.5f * u); close() }, c) }
        Glyph.AutoComplete -> { box(2f, 3f, 20f, 8f, 2f); line(5f, 7f, 11f, 7f, thin); box(2f, 13f, 20f, 8f, 2f, true, soft); line(5f, 17f, 15f, 17f, thin) }
        Glyph.Seek -> { line(3f, 12f, 21f, 12f, thin); line(3f, 12f, 11f, 12f); dot(11f, 12f, 3f) }
        Glyph.Rating -> {
            fun star(cx: Float, cy: Float, r: Float, fill: Boolean) {
                val path = Path()
                for (i in 0 until 10) {
                    val a = Math.toRadians(-90.0 + i * 36.0); val rr = if (i % 2 == 0) r else r * 0.45f
                    val x = (cx + rr * Math.cos(a).toFloat()) * u; val y = (cy + rr * Math.sin(a).toFloat()) * u
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); if (fill) drawPath(path, c) else drawPath(path, c, style = thin)
            }
            star(6f, 12f, 4.6f, true); star(18f, 12f, 4.6f, false)
        }
        Glyph.Progress -> drawArc(c, -90f, 270f, false, p(4f, 4f), Size(16f * u, 16f * u), style = stroke)
        Glyph.Space -> { line(3f, 12f, 21f, 12f, thin); line(3f, 8f, 3f, 16f); line(21f, 8f, 21f, 16f) }
        Glyph.List -> { box(2f, 3f, 20f, 18f); line(5f, 8f, 19f, 8f, thin); line(5f, 12f, 19f, 12f, thin); line(5f, 16f, 19f, 16f, thin) }
        Glyph.Grid -> { box(3f, 3f, 8f, 8f, 1.5f, true); box(13f, 3f, 8f, 8f, 1.5f); box(3f, 13f, 8f, 8f, 1.5f); box(13f, 13f, 8f, 8f, 1.5f, true) }
        Glyph.Recycler -> { box(3f, 3f, 18f, 5f, 1.5f, true); box(3f, 10f, 18f, 5f, 1.5f); box(3f, 17f, 18f, 4f, 1.5f, true, soft) }
        Glyph.Web -> { dot(12f, 12f, 9f, false); line(3f, 12f, 21f, 12f, thin); drawOval(c, p(8f, 3f), Size(8f * u, 18f * u), style = thin) }
        Glyph.Video -> { box(2f, 5f, 20f, 14f); drawPath(Path().apply { moveTo(10f * u, 9f * u); lineTo(15.5f * u, 12f * u); lineTo(10f * u, 15f * u); close() }, c) }
        Glyph.Calendar -> { box(3f, 5f, 18f, 16f); box(3f, 5f, 18f, 4f, 2f, true); line(8f, 3f, 8f, 6f); line(16f, 3f, 16f, 6f); dot(8f, 14f, 1.2f); dot(12f, 14f, 1.2f); dot(16f, 14f, 1.2f); dot(8f, 18f, 1.2f) }
        Glyph.Toolbar -> { box(2f, 4f, 20f, 7f, 1.5f, true); box(2f, 4f, 20f, 16f, 2f); line(5f, 15f, 13f, 15f, thin) }
        Glyph.AppBar -> { box(2f, 3f, 20f, 9f, 1.5f, true); box(2f, 3f, 20f, 18f, 2f); drawLine(Color.White, p(5f, 7.5f), p(10f, 7.5f), 1.6f * u, StrokeCap.Round) }
        Glyph.BottomNav -> { box(2f, 3f, 20f, 18f, 2f); box(2f, 15f, 20f, 6f, 1.5f, true); listOf(6f, 12f, 18f).forEach { x -> drawCircle(Color.White, 1.2f * u, p(x, 18f)) } }
        Glyph.Drawer -> { box(2f, 3f, 20f, 18f, 2f); box(2f, 3f, 9f, 18f, 1.5f, true); line(14f, 8f, 19f, 8f, thin); line(14f, 12f, 19f, 12f, thin) }
        Glyph.View -> { box(3f, 3f, 18f, 18f, 2f, true, soft); box(3f, 3f, 18f, 18f, 2f) }
    }
}

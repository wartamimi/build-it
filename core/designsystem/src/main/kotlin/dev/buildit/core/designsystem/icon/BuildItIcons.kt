package dev.buildit.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icon set for Build It: simple line icons drawn on one 24dp grid with rounded ends. */
object BiIcons {
    // Line icons drawn on one 24dp grid with rounded ends, so every icon has the same light look.

    val Home: ImageVector by lazy {
        outline("Home") {
            moveTo(3.5f, 11f); lineTo(12f, 4f); lineTo(20.5f, 11f)
            moveTo(6f, 9.5f); lineTo(6f, 19f); quadTo(6f, 20f, 7f, 20f); lineTo(17f, 20f); quadTo(18f, 20f, 18f, 19f); lineTo(18f, 9.5f)
            moveTo(10f, 20f); lineTo(10f, 15f); lineTo(14f, 15f); lineTo(14f, 20f)
        }
    }
    val Projects: ImageVector by lazy {
        outline("Projects") {
            moveTo(9f, 6f); lineTo(20f, 6f); moveTo(9f, 12f); lineTo(20f, 12f); moveTo(9f, 18f); lineTo(20f, 18f)
            moveTo(4.5f, 6f); lineTo(4.51f, 6f); moveTo(4.5f, 12f); lineTo(4.51f, 12f); moveTo(4.5f, 18f); lineTo(4.51f, 18f)
        }
    }
    val Settings: ImageVector by lazy {
        outline("Settings") {
            circle(12f, 12f, 3f)
            circle(12f, 12f, 6.5f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                moveTo(12f + 6.5f * kotlin.math.cos(a).toFloat(), 12f + 6.5f * kotlin.math.sin(a).toFloat())
                lineTo(12f + 9f * kotlin.math.cos(a).toFloat(), 12f + 9f * kotlin.math.sin(a).toFloat())
            }
        }
    }
    val Back: ImageVector by lazy { outline("Back") { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) } }
    val Add: ImageVector by lazy { outline("Add", 2f) { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } }
    val Build: ImageVector by lazy {
        outline("Build") {
            // A hammer
            moveTo(4f, 6.5f); lineTo(13f, 6.5f); lineTo(15.5f, 9f); lineTo(4f, 9f); close()
            moveTo(9f, 9f); lineTo(9f, 20f)
            moveTo(13f, 6.5f); lineTo(13f, 4.5f)
        }
    }
    val Delete: ImageVector by lazy {
        outline("Delete") {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(9f, 7f); lineTo(9f, 4.5f); lineTo(15f, 4.5f); lineTo(15f, 7f)
            moveTo(6f, 7f); lineTo(7f, 19f); quadTo(7.1f, 20f, 8f, 20f); lineTo(16f, 20f); quadTo(16.9f, 20f, 17f, 19f); lineTo(18f, 7f)
            moveTo(10f, 11f); lineTo(10f, 16f); moveTo(14f, 11f); lineTo(14f, 16f)
        }
    }
    val Edit: ImageVector by lazy {
        outline("Edit") {
            moveTo(4f, 20f); lineTo(4.5f, 16f); lineTo(15.5f, 5f); quadTo(17f, 3.5f, 18.5f, 5f); lineTo(19f, 5.5f); quadTo(20.5f, 7f, 19f, 8.5f); lineTo(8f, 19.5f); close()
            moveTo(13.5f, 7f); lineTo(17f, 10.5f)
        }
    }
    val Info: ImageVector by lazy {
        outline("Info") { circle(12f, 12f, 9f); moveTo(12f, 11f); lineTo(12f, 16.5f); moveTo(12f, 7.75f); lineTo(12.01f, 7.75f) }
    }
    val More: ImageVector by lazy {
        outline("More", 2.6f) { moveTo(12f, 5.5f); lineTo(12.01f, 5.5f); moveTo(12f, 12f); lineTo(12.01f, 12f); moveTo(12f, 18.5f); lineTo(12.01f, 18.5f) }
    }
    val Refresh: ImageVector by lazy {
        outline("Refresh") {
            moveTo(19.5f, 12f); arcTo(7.5f, 7.5f, 0f, true, true, 17f, 6.4f)
            moveTo(17.5f, 3f); lineTo(17.5f, 7f); lineTo(13.5f, 7f)
        }
    }
    val Search: ImageVector by lazy { outline("Search") { circle(10.5f, 10.5f, 6f); moveTo(15f, 15f); lineTo(20f, 20f) } }
    val Share: ImageVector by lazy {
        outline("Share") {
            circle(17.5f, 5.5f, 2.5f); circle(6.5f, 12f, 2.5f); circle(17.5f, 18.5f, 2.5f)
            moveTo(8.7f, 10.7f); lineTo(15.3f, 6.8f); moveTo(8.7f, 13.3f); lineTo(15.3f, 17.2f)
        }
    }
    val Close: ImageVector by lazy { outline("Close", 2f) { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }
    val Check: ImageVector by lazy { outline("Check", 2f) { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }

    /** Folder outline. */
    val Folder: ImageVector by lazy {
        outline("Folder") {
            moveTo(3f, 6f); lineTo(3f, 18f); quadTo(3f, 19f, 4f, 19f); lineTo(20f, 19f); quadTo(21f, 19f, 21f, 18f)
            lineTo(21f, 9f); quadTo(21f, 8f, 20f, 8f); lineTo(12f, 8f); lineTo(10f, 5f); lineTo(4f, 5f); quadTo(3f, 5f, 3f, 6f); close()
        }
    }

    /** Download/import arrow into a tray. */
    val Import: ImageVector by lazy {
        outline("Import") {
            moveTo(12f, 4f); lineTo(12f, 15f)
            moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
            moveTo(4f, 15f); lineTo(4f, 19f); lineTo(20f, 19f); lineTo(20f, 15f)
        }
    }

    /** Upload/export arrow out of a tray. */
    val Export: ImageVector by lazy {
        outline("Export") {
            moveTo(12f, 15f); lineTo(12f, 4f)
            moveTo(7.5f, 8.5f); lineTo(12f, 4f); lineTo(16.5f, 8.5f)
            moveTo(4f, 15f); lineTo(4f, 19f); lineTo(20f, 19f); lineTo(20f, 15f)
        }
    }

    /** Copy: two offset rectangles. */
    val Duplicate: ImageVector by lazy {
        outline("Duplicate") {
            moveTo(8f, 8f); lineTo(19f, 8f); lineTo(19f, 19f); lineTo(8f, 19f); close()
            moveTo(5f, 15f); lineTo(5f, 5f); lineTo(15f, 5f)
        }
    }

    /** Clock face for backups/history. */
    val History: ImageVector by lazy {
        outline("History") {
            moveTo(12f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, true, 11.99f, 3.5f)
            moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
        }
    }

    /**
     * The Build It mark: an open bracket and a stacked block, i.e. code and visual building in
     * one glyph.
     */
    val Logo: ImageVector by lazy {
        outline("BuildItLogo", stroke = 2.2f) {
            moveTo(8f, 4f); lineTo(4f, 4f); lineTo(4f, 20f); lineTo(8f, 20f)
            moveTo(10f, 13f); lineTo(20f, 13f); lineTo(20f, 19f); lineTo(10f, 19f); close()
            moveTo(13f, 6f); lineTo(20f, 6f); lineTo(20f, 10f); lineTo(13f, 10f); close()
        }
    }

    /** Curved arrow pointing back. */
    val Undo: ImageVector by lazy {
        outline("Undo") {
            moveTo(9f, 6f); lineTo(5f, 10f); lineTo(9f, 14f)
            moveTo(5f, 10f); lineTo(14f, 10f); arcTo(5f, 5f, 0f, false, true, 14f, 20f); lineTo(11f, 20f)
        }
    }

    /** Curved arrow pointing forward. */
    val Redo: ImageVector by lazy {
        outline("Redo") {
            moveTo(15f, 6f); lineTo(19f, 10f); lineTo(15f, 14f)
            moveTo(19f, 10f); lineTo(10f, 10f); arcTo(5f, 5f, 0f, false, false, 10f, 20f); lineTo(13f, 20f)
        }
    }

    val MoveUp: ImageVector by lazy { outline("MoveUp") { moveTo(6f, 14f); lineTo(12f, 8f); lineTo(18f, 14f) } }
    val MoveDown: ImageVector by lazy { outline("MoveDown") { moveTo(6f, 10f); lineTo(12f, 16f); lineTo(18f, 10f) } }

    /** Floppy-less save: tray with downward arrow. */
    val Save: ImageVector by lazy {
        outline("Save") {
            moveTo(12f, 4f); lineTo(12f, 14f); moveTo(8f, 10f); lineTo(12f, 14f); lineTo(16f, 10f)
            moveTo(4f, 15f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 15f)
        }
    }

    /** Stacked sheets: component tree. */
    val Layers: ImageVector by lazy {
        outline("Layers") {
            moveTo(12f, 4f); lineTo(20f, 8.5f); lineTo(12f, 13f); lineTo(4f, 8.5f); close()
            moveTo(4f, 12.5f); lineTo(12f, 17f); lineTo(20f, 12.5f)
            moveTo(4f, 16f); lineTo(12f, 20.5f); lineTo(20f, 16f)
        }
    }

    /** Four tiles: component library. */
    val Widgets: ImageVector by lazy {
        outline("Widgets") {
            moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
            moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
            moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
            moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
        }
    }

    /** Sliders: properties inspector. */
    val Tune: ImageVector by lazy {
        outline("Tune") {
            moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f)
            moveTo(9f, 5f); lineTo(9f, 9f); moveTo(15f, 10f); lineTo(15f, 14f); moveTo(7f, 15f); lineTo(7f, 19f)
        }
    }

    /** Triangle with exclamation: problems. */
    val Warning: ImageVector by lazy {
        outline("Warning") {
            moveTo(12f, 4f); lineTo(21f, 20f); lineTo(3f, 20f); close()
            moveTo(12f, 10f); lineTo(12f, 14f); moveTo(12f, 17f); lineTo(12f, 17.01f)
        }
    }

    /** Phone outline: device / preview. */
    val Device: ImageVector by lazy {
        outline("Device") {
            moveTo(7f, 3f); lineTo(17f, 3f); lineTo(17f, 21f); lineTo(7f, 21f); close()
            moveTo(11f, 18f); lineTo(13f, 18f)
        }
    }

    /** Rotate: orientation toggle. */
    val Rotate: ImageVector by lazy {
        outline("Rotate") {
            moveTo(19f, 12f); arcTo(7f, 7f, 0f, true, true, 16.9f, 7f)
            moveTo(17f, 3f); lineTo(17f, 7f); lineTo(13f, 7f)
        }
    }

    /** Crescent: night preview. */
    val Night: ImageVector by lazy {
        outline("Night") {
            moveTo(20f, 14.5f); arcTo(8f, 8f, 0f, true, true, 9.5f, 4f); arcTo(6.5f, 6.5f, 0f, false, false, 20f, 14.5f)
        }
    }

    /** Triangle: run the app. */
    val Run: ImageVector by lazy { outline("Run") { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() } }

    /** Three lines: open the project tools drawer. */
    val Menu: ImageVector by lazy { outline("Menu") { moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f) } }

    /** Lightning bolt: events. */
    val Event: ImageVector by lazy { outline("Event") { moveTo(13f, 3f); lineTo(5f, 13f); lineTo(11f, 13f); lineTo(10f, 21f); lineTo(19f, 10f); lineTo(13f, 10f); close() } }

    /** Hexagon with a dot: components (non-visual parts of a screen). */
    val Component: ImageVector by lazy {
        outline("Component") {
            moveTo(12f, 3f); lineTo(20f, 7.5f); lineTo(20f, 16.5f); lineTo(12f, 21f); lineTo(4f, 16.5f); lineTo(4f, 7.5f); close()
            moveTo(12f, 11f); lineTo(12f, 13f)
        }
    }

    /** Stacked notched bars: the block editor. */
    val Blocks: ImageVector by lazy {
        outline("Blocks") {
            moveTo(4f, 5f); lineTo(8f, 5f); lineTo(9f, 6.5f); lineTo(11f, 6.5f); lineTo(12f, 5f); lineTo(20f, 5f); lineTo(20f, 10f); lineTo(4f, 10f); close()
            moveTo(4f, 13f); lineTo(8f, 13f); lineTo(9f, 14.5f); lineTo(11f, 14.5f); lineTo(12f, 13f); lineTo(16f, 13f); lineTo(16f, 18f); lineTo(4f, 18f); close()
        }
    }

    /** Frame with a hill and sun: images. */
    val Image: ImageVector by lazy {
        outline("Image") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            moveTo(4f, 16f); lineTo(9f, 11f); lineTo(14f, 16f); moveTo(13f, 14f); lineTo(15f, 12f); lineTo(20f, 17f)
            moveTo(15.5f, 8.5f); lineTo(15.5f, 8.51f)
        }
    }

    /** Note: sounds. */
    val Sound: ImageVector by lazy {
        outline("Sound") {
            moveTo(9f, 17f); lineTo(9f, 5f); lineTo(19f, 3f); lineTo(19f, 15f)
            moveTo(9f, 17f); arcTo(2.5f, 2.5f, 0f, true, true, 4f, 17f); arcTo(2.5f, 2.5f, 0f, true, true, 9f, 17f)
            moveTo(19f, 15f); arcTo(2.5f, 2.5f, 0f, true, true, 14f, 15f); arcTo(2.5f, 2.5f, 0f, true, true, 19f, 15f)
        }
    }

    /** Letter A with baseline: fonts. */
    val Font: ImageVector by lazy { outline("Font") { moveTo(5f, 19f); lineTo(12f, 4f); lineTo(19f, 19f); moveTo(8f, 13f); lineTo(16f, 13f) } }

    /** Angle brackets: source code. */
    val Code: ImageVector by lazy { outline("Code") { moveTo(8f, 7f); lineTo(3f, 12f); lineTo(8f, 17f); moveTo(16f, 7f); lineTo(21f, 12f); lineTo(16f, 17f); moveTo(14f, 5f); lineTo(10f, 19f) } }

    /** Prompt: console / logs. */
    val Terminal: ImageVector by lazy {
        outline("Terminal") { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close(); moveTo(7f, 9f); lineTo(10f, 12f); lineTo(7f, 15f); moveTo(12f, 15f); lineTo(16f, 15f) }
    }

    /** Four-point star: assistant. */
    val Assistant: ImageVector by lazy {
        outline("Assistant") { moveTo(12f, 3f); lineTo(14f, 10f); lineTo(21f, 12f); lineTo(14f, 14f); lineTo(12f, 21f); lineTo(10f, 14f); lineTo(3f, 12f); lineTo(10f, 10f); close() }
    }

    /** Books on a shelf: libraries. */
    val Library: ImageVector by lazy {
        outline("Library") { moveTo(4f, 4f); lineTo(4f, 20f); moveTo(8f, 4f); lineTo(8f, 20f); moveTo(12f, 5f); lineTo(16f, 19f); moveTo(3f, 20f); lineTo(21f, 20f) }
    }

    /** Two stacked screens: the screens (activities) of a project. */
    val Screens: ImageVector by lazy {
        outline("Screens") { moveTo(9f, 3f); lineTo(19f, 3f); lineTo(19f, 17f); moveTo(5f, 7f); lineTo(15f, 7f); lineTo(15f, 21f); lineTo(5f, 21f); close() }
    }

    /** Arrow leaving a box: open another screen or app. */
    val OpenOut: ImageVector by lazy {
        outline("OpenOut") { moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); moveTo(20f, 4f); lineTo(11f, 13f); moveTo(18f, 14f); lineTo(18f, 20f); lineTo(4f, 20f); lineTo(4f, 6f); lineTo(10f, 6f) }
    }

    /** Phone with shake marks: vibration. */
    val Vibrate: ImageVector by lazy {
        outline("Vibrate") { moveTo(8f, 4f); lineTo(16f, 4f); lineTo(16f, 20f); lineTo(8f, 20f); close(); moveTo(4f, 8f); lineTo(4f, 16f); moveTo(20f, 8f); lineTo(20f, 16f) }
    }

    /** Calendar page. */
    val Calendar: ImageVector by lazy {
        outline("Calendar") { moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 20f); lineTo(4f, 20f); close(); moveTo(4f, 10f); lineTo(20f, 10f); moveTo(8f, 3f); lineTo(8f, 7f); moveTo(16f, 3f); lineTo(16f, 7f) }
    }

    /** Motion lines behind a dot: animation. */
    val Motion: ImageVector by lazy {
        outline("Motion") { moveTo(3f, 8f); lineTo(9f, 8f); moveTo(5f, 12f); lineTo(11f, 12f); moveTo(3f, 16f); lineTo(9f, 16f); moveTo(17f, 12f); arcTo(3f, 3f, 0f, true, true, 11f, 12f); arcTo(3f, 3f, 0f, true, true, 17f, 12f) }
    }

    /** Concentric arcs: sensor readings. */
    val Sensor: ImageVector by lazy {
        outline("Sensor") { moveTo(12f, 12f); lineTo(12f, 12.01f); moveTo(8f, 8f); arcTo(5.5f, 5.5f, 0f, false, false, 8f, 16f); moveTo(16f, 8f); arcTo(5.5f, 5.5f, 0f, false, true, 16f, 16f); moveTo(5f, 5f); arcTo(10f, 10f, 0f, false, false, 5f, 19f); moveTo(19f, 5f); arcTo(10f, 10f, 0f, false, true, 19f, 19f) }
    }

    /** Speaker with waves: speech output. */
    val Speak: ImageVector by lazy {
        outline("Speak") { moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close(); moveTo(16f, 9f); arcTo(4f, 4f, 0f, false, true, 16f, 15f); moveTo(18.5f, 6.5f); arcTo(7.5f, 7.5f, 0f, false, true, 18.5f, 17.5f) }
    }

    /** Microphone: speech input. */
    val Mic: ImageVector by lazy {
        outline("Mic") { moveTo(9f, 6f); arcTo(3f, 3f, 0f, false, true, 15f, 6f); lineTo(15f, 12f); arcTo(3f, 3f, 0f, false, true, 9f, 12f); close(); moveTo(5f, 11f); arcTo(7f, 7f, 0f, false, false, 19f, 11f); moveTo(12f, 18f); lineTo(12f, 21f) }
    }

    /** Page with a folded corner: files. */
    val File: ImageVector by lazy {
        outline("File") { moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(6f, 21f); close(); moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f) }
    }

    /** Globe: network requests. */
    val Network: ImageVector by lazy {
        outline("Network") { moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); moveTo(3f, 12f); lineTo(21f, 12f); moveTo(12f, 3f); arcTo(14f, 14f, 0f, false, true, 12f, 21f); arcTo(14f, 14f, 0f, false, true, 12f, 3f) }
    }

    /** Bell: notifications. */
    val Bell: ImageVector by lazy {
        outline("Bell") { moveTo(6f, 17f); lineTo(6f, 11f); arcTo(6f, 6f, 0f, false, true, 18f, 11f); lineTo(18f, 17f); lineTo(20f, 19f); lineTo(4f, 19f); close(); moveTo(10f, 21f); lineTo(14f, 21f) }
    }

    /** Clock face: time. */
    val Clock: ImageVector by lazy {
        outline("Clock") { moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15f, 14f) }
    }

    private fun androidx.compose.ui.graphics.vector.PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy); arcTo(r, r, 0f, false, false, cx - r, cy); arcTo(r, r, 0f, false, false, cx + r, cy); close()
    }

    private fun outline(name: String, stroke: Float = 1.8f, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()
}

package dev.buildit.core.designsystem.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icon set for Build It. Material core icons plus a few custom glyphs drawn on a 24dp grid. */
object BiIcons {
    val Home = Icons.Filled.Home
    val Projects = Icons.AutoMirrored.Filled.List
    val Settings = Icons.Filled.Settings
    val Add = Icons.Filled.Add
    val Build = Icons.Filled.Build
    val Delete = Icons.Filled.Delete
    val Edit = Icons.Filled.Edit
    val Info = Icons.Filled.Info
    val More = Icons.Filled.MoreVert
    val Refresh = Icons.Filled.Refresh
    val Search = Icons.Filled.Search
    val Share = Icons.Filled.Share
    val Close = Icons.Filled.Close
    val Check = Icons.Filled.Check

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

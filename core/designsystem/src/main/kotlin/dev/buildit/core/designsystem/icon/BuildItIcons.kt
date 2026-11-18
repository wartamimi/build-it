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

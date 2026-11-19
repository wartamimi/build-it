package dev.buildit.feature.designer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.layout.DropResolver
import dev.buildit.engine.layout.LayoutCommand
import dev.buildit.engine.layout.Values
import dev.buildit.engine.project.WindowStyle

/** Shared handle so the library's drag-and-drop can address the canvas. */
class CanvasHandle {
    var host: PreviewHost? = null

    /** Converts window coordinates to preview pixels, or null when outside the canvas. */
    fun toPreview(windowX: Float, windowY: Float): Pair<Float, Float>? {
        val h = host ?: return null
        val loc = IntArray(2)
        h.getLocationInWindow(loc)
        val lx = windowX - loc[0]
        val ly = windowY - loc[1]
        if (lx < 0 || ly < 0 || lx > h.width || ly > h.height) return null
        val y = ly / h.scale - h.topPx
        if (y < 0) return null
        return lx / h.scale to y
    }
}

@Composable
fun DesignerCanvas(
    state: DesignerUiState,
    handle: CanvasHandle,
    onSelect: (Long?) -> Unit,
    onCommand: (LayoutCommand) -> Unit,
    onIssues: (List<PreviewIssue>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val root = state.root ?: return
    val latestRoot by rememberUpdatedState(root)
    val select by rememberUpdatedState(onSelect)
    val command by rememberUpdatedState(onCommand)
    val issues by rememberUpdatedState(onIssues)
    var dragUid by remember { mutableStateOf<Long?>(null) }
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(modifier.fillMaxSize().background(surface).padding(Spacing.md), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PreviewHost(ctx).also { host ->
                    handle.host = host
                    host.onTap = { x, y -> select(DropResolver.hit(latestRoot, host.boxes(), x, y)) }
                    host.onDragStart = { x, y ->
                        val uid = DropResolver.hit(latestRoot, host.boxes(), x, y)
                        if (uid != null && uid != latestRoot.uid) { dragUid = uid; select(uid); true } else false
                    }
                    host.onDragMove = { x, y ->
                        host.highlightUid = DropResolver.resolve(latestRoot, host.boxes(), x, y, dragUid)?.parentUid
                    }
                    host.onDragEnd = { x, y, cancelled ->
                        val uid = dragUid
                        dragUid = null
                        host.highlightUid = null
                        if (!cancelled && uid != null) {
                            DropResolver.resolve(latestRoot, host.boxes(), x, y, uid)?.let { t ->
                                command(LayoutCommand.Move(uid, t.parentUid, t.index))
                            }
                        }
                    }
                }
            },
            update = { host ->
                handle.host = host
                val key = "${state.revision}|${state.preview}|${state.values.hashCode()}"
                if (host.tag != key) {
                    host.tag = key
                    val renderer = PreviewRenderer(host.context, state.preview, state.values, state.resDir)
                    val result = renderer.render(root)
                    val (wDp, hDp) = state.preview.device.size(state.preview.landscape)
                    val density = state.preview.device.densityDpi / 160f
                    host.surfaceColor = surfaceColorFor(renderer)
                    host.setContent(result.root, result.views, (wDp * density).toInt(), (hDp * density).toInt(), chromeFor(state, density))
                    host.zoom = state.preview.zoom
                    issues(result.issues)
                }
                host.selectedUid = state.selectedUid
            },
        )
    }
}

/**
 * The window decoration of the screen being edited: status bar unless fullscreen, and the action
 * bar for the default window style, in the app's primary colours with the app name.
 */
private fun chromeFor(state: DesignerUiState, density: Float): PreviewHost.Chrome {
    val window = state.screen?.window ?: WindowStyle.NoActionBar
    if (window == WindowStyle.Fullscreen) return PreviewHost.Chrome()
    val night = state.preview.night
    fun color(vararg names: String) = names.firstNotNullOfOrNull { n -> state.values.resolve("@color/$n", night)?.let(Values::parseColor) }
    val primary = color("colorPrimary", "primary", "purple_500") ?: 0xFF37474F.toInt() // Theme.Material.Light.DarkActionBar
    val dark = color("colorPrimaryDark", "primary_dark", "purple_700") ?: if (color("colorPrimary", "primary", "purple_500") == null) 0xFF263238.toInt() else darken(primary)
    val title = state.values.resolve("@string/app_name", night) ?: state.screen?.name.orEmpty()
    return PreviewHost.Chrome(
        statusPx = (24 * density).toInt(),
        barPx = if (window == WindowStyle.Default) (56 * density).toInt() else 0,
        color = primary, darkColor = dark, title = title, density = density,
    )
}

private fun darken(c: Int): Int {
    val f = 0.8f
    return (c and 0xFF000000.toInt()) or ((((c shr 16) and 0xFF) * f).toInt() shl 16) or ((((c shr 8) and 0xFF) * f).toInt() shl 8) or ((c and 0xFF) * f).toInt()
}

private fun surfaceColorFor(renderer: PreviewRenderer): Int {
    val tv = android.util.TypedValue()
    return if (renderer.context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true) && tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT) tv.data
    else androidx.compose.ui.graphics.Color.White.toArgb()
}

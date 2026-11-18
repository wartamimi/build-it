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
        return lx / h.scale to ly / h.scale
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
                    host.setContent(result.root, result.views, (wDp * density).toInt(), (hDp * density).toInt())
                    host.zoom = state.preview.zoom
                    issues(result.issues)
                }
                host.selectedUid = state.selectedUid
            },
        )
    }
}

private fun surfaceColorFor(renderer: PreviewRenderer): Int {
    val tv = android.util.TypedValue()
    return if (renderer.context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true) && tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT) tv.data
    else androidx.compose.ui.graphics.Color.White.toArgb()
}

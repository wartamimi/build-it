package dev.buildit.feature.blocks

import android.content.ClipDescription
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.buildit.engine.blocks.Block
import dev.buildit.engine.blocks.BlockCategory
import dev.buildit.engine.blocks.BlockDocument
import dev.buildit.engine.blocks.BlockOps
import dev.buildit.engine.blocks.BlockRegistry
import dev.buildit.engine.blocks.BlockShape
import dev.buildit.engine.blocks.BlockStack
import dev.buildit.engine.blocks.Slot
import kotlin.math.hypot

/** Viewport of the block canvas (pan in screen pixels, zoom factor). */
@Stable
class BlockCanvasState {
    var pan by mutableStateOf(Offset(24f, 24f))
    var zoom by mutableFloatStateOf(1f)
    var size by mutableStateOf(IntSize.Zero)

    fun toWorld(p: Offset) = Offset((p.x - pan.x) / zoom, (p.y - pan.y) / zoom)

    /** World point at the centre of the visible area (where palette blocks are added). */
    fun center(): Offset = toWorld(Offset(size.width / 2f, size.height / 3f))

    fun centerOn(x: Float, y: Float) { pan = Offset(size.width / 2f - x * zoom, size.height / 3f - y * zoom) }

    fun zoomBy(factor: Float, pivot: Offset = Offset(size.width / 2f, size.height / 2f)) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val world = toWorld(pivot)
        zoom = next
        pan = Offset(pivot.x - world.x * next, pivot.y - world.y * next)
    }

    fun reset() { zoom = 1f; pan = Offset(24f, 24f) }

    companion object {
        const val MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 2.5f
    }
}

private class Drag(
    val original: BlockDocument,
    val base: BlockDocument,
    val chain: List<Block>,
    /** Pointer position minus chain origin, in world units. */
    val grab: Offset,
) {
    var origin by mutableStateOf(Offset.Zero)
    var target by mutableStateOf<SnapTarget?>(null)
    var overTrash by mutableStateOf(false)
    /** Last pointer position on screen, for scrolling the canvas while the finger rests near an edge. */
    var pointer by mutableStateOf(Offset.Zero)
    /** The chain laid out at (0, 0) once; drawing translates it to [origin]. */
    var laid: List<LaidBlock>? = null
}

/** Label of the clip a palette block carries while it is dragged onto the canvas; the text is the block type. */
const val PALETTE_CLIP_LABEL = "dev.buildit.block"

/** A palette block being dragged over the canvas (platform drag and drop). */
private class NewDrag(val shape: BlockShape) {
    var world by mutableStateOf(Offset.Zero)
    var target by mutableStateOf<SnapTarget?>(null)
}

@Composable
fun BlockCanvas(
    document: BlockDocument,
    selectedId: String?,
    errorIds: Set<String>,
    canvas: BlockCanvasState,
    onSelect: (String?) -> Unit,
    onFieldTap: (Block, String) -> Unit,
    onDragCommit: (original: BlockDocument, result: BlockDocument) -> Unit,
    modifier: Modifier = Modifier,
    description: String = "",
    /**
     * A palette block of [type] was dropped at a world position. [slotFor] finds the slot it snaps to
     * in a document (null: a new stack at the drop position).
     */
    onDropNew: (type: String, x: Float, y: Float, slotFor: (BlockDocument, List<Block>) -> Slot?) -> Unit = { _, _, _, _ -> },
    /** Stacks to show (null: all). The event editor shows only its event and the loose stacks. */
    showStack: ((BlockStack) -> Boolean)? = null,
) {
    val density = LocalDensity.current.density
    val painter = rememberBlockPainter()
    val measurer = painter.measurer
    val textStyle = painter.style
    val layout = painter.layout
    var drag by remember { mutableStateOf<Drag?>(null) }
    val doc by rememberUpdatedState(document)
    val select by rememberUpdatedState(onSelect)
    val fieldTap by rememberUpdatedState(onFieldTap)
    val commit by rememberUpdatedState(onDragCommit)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val snapRadius = 40f * density
    val trashRadius = 36f * density

    val show by rememberUpdatedState(showStack)
    /** The laid-out stacks that are shown (hidden ones can't be hit or snapped to). */
    fun laidOut(d: BlockDocument): List<LaidStack> = layout.layout(d).let { all -> show?.let { f -> all.filter { f(d.stacks[it.index]) } } ?: all }
    val shown = drag?.base ?: document
    val stacks = remember(shown, layout, showStack) { laidOut(shown) }
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val selection = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val trashColor = MaterialTheme.colorScheme.errorContainer
    val ghost = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val shadow = Color.Black.copy(alpha = 0.18f)
    val haptics = LocalHapticFeedback.current

    fun trashCenter() = Offset(canvas.size.width - trashRadius * 1.6f, canvas.size.height - trashRadius * 1.6f)

    /** Nearest compatible slot for [chain] whose origin is at [origin], within the snap radius. */
    fun snap(base: BlockDocument, chain: List<Block>?, shape: BlockShape, origin: Offset): SnapTarget? =
        if (shape == BlockShape.Hat) null else layout.targets(laidOut(base), shape)
            .filter { chain == null || BlockOps.canInsert(base, chain, it.slot) }
            .minByOrNull { hypot(it.x - origin.x, it.y - origin.y) }
            ?.takeIf { hypot(it.x - origin.x, it.y - origin.y) < snapRadius / canvas.zoom.coerceAtMost(1f) }

    fun moveDrag(d: Drag, p: Offset) {
        d.pointer = p
        d.origin = canvas.toWorld(p) - d.grab
        d.overTrash = (p - trashCenter()).getDistance() < trashRadius * 1.4f
        val shape = BlockRegistry.find(d.chain.first().type)?.shape
        val next = if (d.overTrash || shape == null) null else snap(d.base, d.chain, shape, d.origin)
        if (next != null && next.slot != d.target?.slot) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        d.target = next
    }

    // Scroll while a dragged block is held near an edge, so blocks can travel beyond the screen.
    val edge = 48f * density
    LaunchedEffect(drag) {
        val d = drag ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val p = d.pointer
            val dx = when { p.x < edge -> 1f; p.x > canvas.size.width - edge -> -1f; else -> 0f }
            val dy = when { p.y < edge -> 1f; p.y > canvas.size.height - edge -> -1f; else -> 0f }
            if ((dx != 0f || dy != 0f) && !d.overTrash) {
                canvas.pan += Offset(dx, dy) * (10f * density)
                moveDrag(d, p)
            }
        }
    }

    var newDrag by remember { mutableStateOf<NewDrag?>(null) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    val dropNew by rememberUpdatedState(onDropNew)
    val dropTarget = remember(layout) {
        object : DragAndDropTarget {
            private fun local(event: DragAndDropEvent) = event.toAndroidDragEvent().let { Offset(it.x, it.y) } - rootOffset
            private fun type(event: DragAndDropEvent) = event.toAndroidDragEvent().clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

            override fun onEntered(event: DragAndDropEvent) {
                val t = event.toAndroidDragEvent().clipDescription?.label?.toString()?.removePrefix("$PALETTE_CLIP_LABEL:")
                newDrag = BlockRegistry.find(t.orEmpty())?.let { NewDrag(it.shape) }
            }

            override fun onMoved(event: DragAndDropEvent) {
                val n = newDrag ?: return
                n.world = canvas.toWorld(local(event))
                n.target = snap(doc, null, n.shape, n.world)
            }

            override fun onExited(event: DragAndDropEvent) { newDrag = null }
            override fun onEnded(event: DragAndDropEvent) { newDrag = null }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val t = type(event) ?: return false
                val w = canvas.toWorld(local(event))
                newDrag = null
                dropNew(t, w.x, w.y, slot@{ d, chain ->
                    val shape = BlockRegistry.find(chain.first().type)?.shape ?: return@slot null
                    snap(d, chain, shape, w)?.slot
                })
                return true
            }
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { canvas.size = it }
            .onGloballyPositioned { rootOffset = it.positionInRoot() }
            .dragAndDropTarget(
                shouldStartDragAndDrop = { e ->
                    e.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                        e.toAndroidDragEvent().clipDescription?.label?.toString()?.startsWith(PALETTE_CLIP_LABEL) == true
                },
                target = dropTarget,
            )
            .semantics { contentDescription = description }
            .pointerInput(layout) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startWorld = canvas.toWorld(down.position)
                    val current = laidOut(doc)
                    val hit = layout.hit(current, startWorld.x, startWorld.y)
                    var moved = false
                    var zooming = false
                    var last = down.position
                    fun tap() {
                        if (hit == null) select(null) else {
                            val field = layout.fieldAt(hit, startWorld.x, startWorld.y)
                            if (field != null) fieldTap(hit.block, field.name) else select(hit.block.id)
                        }
                    }
                    // Blocks move only after a long press, like Sketchware; event blocks (hats) stay put.
                    // Moving the finger earlier pans the canvas instead.
                    if (hit != null && !hit.hat) {
                        val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var e: androidx.compose.ui.input.pointer.PointerEvent
                            do {
                                e = awaitPointerEvent()
                                val ps = e.changes.filter { it.pressed }
                            } while (ps.size == 1 && (ps.first().position - down.position).getDistance() <= touchSlop)
                            e
                        }
                        if (early == null) {
                            val (base, chain) = BlockOps.detach(doc, hit.block.id)
                            val origin = Offset(hit.box.left, hit.box.top)
                            drag = Drag(doc, base, chain, startWorld - origin).also { it.origin = origin; it.pointer = down.position }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            select(hit.block.id)
                        } else {
                            val ps = early.changes.filter { it.pressed }
                            early.changes.forEach { it.consume() }
                            when {
                                ps.isEmpty() -> { tap(); return@awaitEachGesture }
                                ps.size > 1 -> zooming = true
                                else -> { moved = true; canvas.pan += ps.first().position - down.position; last = ps.first().position }
                            }
                        }
                    }
                    var dragMoved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1 && drag == null) {
                            zooming = true
                            val z = event.calculateZoom()
                            val c = event.calculateCentroid()
                            if (z != 1f) canvas.zoomBy(z, c)
                            canvas.pan += pressed.map { it.positionChange() }.reduce { a, b -> a + b } / pressed.size.toFloat()
                        } else if (!zooming) {
                            val p = pressed.first().position
                            val d = drag
                            if (d != null) {
                                if (dragMoved || (p - down.position).getDistance() > touchSlop) { dragMoved = true; moveDrag(d, p) }
                            } else {
                                if (!moved && (p - down.position).getDistance() > touchSlop) moved = true
                                if (moved) canvas.pan += p - last
                            }
                            last = p
                        }
                        event.changes.forEach { it.consume() }
                    }
                    val d = drag
                    if (d != null) {
                        drag = null
                        // A long press without moving only selects the block.
                        if (dragMoved) {
                            val result = when {
                                d.overTrash -> d.base
                                d.target != null -> BlockOps.insert(d.base, d.chain, d.target!!.slot, d.origin.x + 24f to d.origin.y + 24f)
                                else -> BlockOps.addStack(d.base, d.chain, d.origin.x, d.origin.y)
                            }
                            commit(d.original, result)
                        }
                    } else if (!moved && !zooming) tap()
                }
            },
    ) {
        drawRect(surface)
        drawGrid(canvas, grid)
        val visible = Area(-canvas.pan.x / canvas.zoom, -canvas.pan.y / canvas.zoom, (size.width - canvas.pan.x) / canvas.zoom, (size.height - canvas.pan.y) / canvas.zoom)
        withTransform({ translate(canvas.pan.x, canvas.pan.y); scale(canvas.zoom, canvas.zoom, Offset.Zero) }) {
            stacks.forEach { s -> if (s.bounds.intersects(visible)) s.blocks.forEach { drawBlock(it, measurer, textStyle, selectedId, errorIds, selection, error, layout.hatCap) } }
            drag?.let { d ->
                val laid = d.laid ?: layout.layoutChain(d.chain, 0f, 0f).also { d.laid = it }
                // Where the chain will land: a grey copy of its outline at the target
                d.target?.let { t -> translate(t.x, t.y) { laid.forEach { drawGhost(it, ghost, layout.hatCap) } } }
                translate(d.origin.x, d.origin.y) {
                    translate(3f * density, 5f * density) { laid.forEach { drawGhost(it, shadow, layout.hatCap) } }
                    laid.forEach { drawBlock(it, measurer, textStyle, null, emptySet(), selection, error, layout.hatCap, alpha = 0.92f) }
                }
            }
            newDrag?.target?.let { t ->
                drawRoundRect(selection, Offset(t.x - 4f, t.y - 3f), Size(60f * density, 6f), CornerRadius(3f, 3f))
            }
        }
        drag?.let { d ->
            val c = trashCenter()
            drawCircle(if (d.overTrash) error else trashColor, trashRadius * if (d.overTrash) 1.2f else 1f, c)
            drawLine(Color.White, c + Offset(-trashRadius / 2.5f, -trashRadius / 2.5f), c + Offset(trashRadius / 2.5f, trashRadius / 2.5f), strokeWidth = 3f * density)
            drawLine(Color.White, c + Offset(trashRadius / 2.5f, -trashRadius / 2.5f), c + Offset(-trashRadius / 2.5f, trashRadius / 2.5f), strokeWidth = 3f * density)
        }
    }
}

/** A block's outline filled with one colour (drop preview, drag shadow). */
private fun DrawScope.drawGhost(lb: LaidBlock, color: Color, hatCap: Float) {
    val path = (lb.paintCache as? androidx.compose.ui.graphics.Path) ?: blockOutline(lb, hatCap / 12f, hatCap).also { lb.paintCache = it }
    drawPath(path, color)
    lb.valueChildren.values.forEach { drawGhost(it, color, hatCap) }
    lb.mouths.forEach { m -> m.blocks.forEach { drawGhost(it, color, hatCap) } }
}

private fun DrawScope.drawGrid(canvas: BlockCanvasState, color: Color) {
    val step = 32f * density * canvas.zoom
    if (step < 8f) return
    var x = canvas.pan.x % step
    while (x < size.width) { var y = canvas.pan.y % step; while (y < size.height) { drawCircle(color, 1.5f, Offset(x, y)); y += step }; x += step }
}

/** Block message in the app language; falls back to English if a translation's placeholders don't match. */
internal fun localizedParts(def: dev.buildit.engine.blocks.BlockDef, res: android.content.res.Resources): List<dev.buildit.engine.blocks.MessagePart> {
    if (def.type == "proc_call" && def.inputs.isNotEmpty()) {
        // A call with parameters: translated "call {name}" followed by the parameter inputs
        val base = BlockRegistry["proc_call"]
        val head = localizedParts(base, res)
        return head + def.parts.drop(base.parts.size)
    }
    return blockMessageRes(def.type)?.let(res::getString)?.takeIf(def::matches)?.let(def::parse) ?: def.parts
}

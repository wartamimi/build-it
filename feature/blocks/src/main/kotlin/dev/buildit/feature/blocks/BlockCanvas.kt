package dev.buildit.feature.blocks

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.withTransform
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
}

fun categoryColor(c: BlockCategory?): Color = when (c) {
    BlockCategory.Events -> Color(0xFFB8860B)
    BlockCategory.Control -> Color(0xFFC0602A)
    BlockCategory.Logic -> Color(0xFF3F6EC4)
    BlockCategory.Math -> Color(0xFF2E7D4F)
    BlockCategory.Text -> Color(0xFF167A7A)
    BlockCategory.Variables -> Color(0xFFB0457A)
    BlockCategory.Lists -> Color(0xFF7653B8)
    BlockCategory.Components -> Color(0xFF4554B0)
    BlockCategory.Parts -> Color(0xFF8C4FA0)
    BlockCategory.App -> Color(0xFF6D5B4A)
    BlockCategory.Code -> Color(0xFF55606E)
    null -> Color(0xFF8A2C2C)
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
) {
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val textStyle = remember { TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val layout = remember(measurer, density, configuration) {
        val res = context.resources
        BlockLayout(
            density,
            measure = { s -> measurer.measure(s, textStyle).size.let { TextSize(it.width.toFloat(), it.height.toFloat()) } },
            partsOf = { def -> localizedParts(def, res) },
            labelOf = { l -> statementLabelRes(l)?.let(res::getString) ?: l },
        )
    }
    var drag by remember { mutableStateOf<Drag?>(null) }
    val doc by rememberUpdatedState(document)
    val select by rememberUpdatedState(onSelect)
    val fieldTap by rememberUpdatedState(onFieldTap)
    val commit by rememberUpdatedState(onDragCommit)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val snapRadius = 40f * density
    val trashRadius = 36f * density

    val shown = drag?.base ?: document
    val stacks = remember(shown, layout) { layout.layout(shown) }
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val selection = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val trashColor = MaterialTheme.colorScheme.errorContainer

    fun trashCenter() = Offset(canvas.size.width - trashRadius * 1.6f, canvas.size.height - trashRadius * 1.6f)

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { canvas.size = it }
            .semantics { contentDescription = description }
            .pointerInput(layout) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startWorld = canvas.toWorld(down.position)
                    val current = layout.layout(doc)
                    val hit = layout.hit(current, startWorld.x, startWorld.y)
                    var moved = false
                    var zooming = false
                    var last = down.position
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
                            if (!moved && (p - down.position).getDistance() > touchSlop) {
                                moved = true
                                if (hit != null) {
                                    // Start dragging the block (and the statements below it).
                                    val (base, chain) = BlockOps.detach(doc, hit.block.id)
                                    val origin = Offset(hit.box.left, hit.box.top + if (hit.hat) 0f else 0f)
                                    drag = Drag(doc, base, chain, startWorld - origin).also { it.origin = origin }
                                    select(hit.block.id)
                                }
                            }
                            if (moved) {
                                val d = drag
                                if (d != null) {
                                    val w = canvas.toWorld(p)
                                    d.origin = w - d.grab
                                    d.overTrash = (p - trashCenter()).getDistance() < trashRadius * 1.4f
                                    val shape = BlockRegistry.find(d.chain.first().type)?.shape
                                    d.target = if (d.overTrash || shape == null || shape == BlockShape.Hat) null else
                                        layout.targets(layout.layout(d.base), shape)
                                            .filter { BlockOps.canInsert(d.base, d.chain, it.slot) }
                                            .minByOrNull { hypot(it.x - d.origin.x, it.y - d.origin.y) }
                                            ?.takeIf { hypot(it.x - d.origin.x, it.y - d.origin.y) < snapRadius / canvas.zoom.coerceAtMost(1f) }
                                } else {
                                    canvas.pan += p - last
                                }
                            }
                            last = p
                        }
                        event.changes.forEach { it.consume() }
                    }
                    val d = drag
                    if (d != null) {
                        val result = when {
                            d.overTrash -> d.base
                            d.target != null -> BlockOps.insert(d.base, d.chain, d.target!!.slot, d.origin.x + 24f to d.origin.y + 24f)
                            else -> BlockOps.addStack(d.base, d.chain, d.origin.x, d.origin.y)
                        }
                        drag = null
                        commit(d.original, result)
                    } else if (!moved && !zooming) {
                        if (hit == null) select(null) else {
                            val field = layout.fieldAt(hit, startWorld.x, startWorld.y)
                            if (field != null) fieldTap(hit.block, field.name) else select(hit.block.id)
                        }
                    }
                }
            },
    ) {
        drawRect(surface)
        drawGrid(canvas, grid)
        val visible = Area(-canvas.pan.x / canvas.zoom, -canvas.pan.y / canvas.zoom, (size.width - canvas.pan.x) / canvas.zoom, (size.height - canvas.pan.y) / canvas.zoom)
        withTransform({ translate(canvas.pan.x, canvas.pan.y); scale(canvas.zoom, canvas.zoom, Offset.Zero) }) {
            stacks.forEach { s -> if (s.bounds.intersects(visible)) s.blocks.forEach { drawBlock(it, measurer, textStyle, selectedId, errorIds, selection, error) } }
            drag?.let { d ->
                d.target?.let { t ->
                    drawRoundRect(selection, Offset(t.x - 4f, t.y - 3f), Size(60f * density, 6f), CornerRadius(3f, 3f))
                }
                layout.layoutChain(d.chain, d.origin.x, d.origin.y).forEach { drawBlock(it, measurer, textStyle, null, emptySet(), selection, error, alpha = 0.85f) }
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

private fun DrawScope.drawGrid(canvas: BlockCanvasState, color: Color) {
    val step = 32f * density * canvas.zoom
    if (step < 8f) return
    var x = canvas.pan.x % step
    while (x < size.width) { var y = canvas.pan.y % step; while (y < size.height) { drawCircle(color, 1.5f, Offset(x, y)); y += step }; x += step }
}

private fun DrawScope.drawBlock(
    lb: LaidBlock,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    selectedId: String?,
    errorIds: Set<String>,
    selection: Color,
    error: Color,
    alpha: Float = 1f,
) {
    val base = categoryColor(lb.def?.category).let { if (lb.block.disabled) it.copy(alpha = 0.35f) else it }.copy(alpha = alpha)
    val radius = CornerRadius(8f * density, 8f * density)
    lb.shapes.forEach { s -> drawRoundRect(base, Offset(s.left, s.top), Size(s.width, s.height), radius) }
    val outline = when {
        lb.block.id in errorIds -> error
        lb.block.id == selectedId -> selection
        else -> null
    }
    outline?.let { c -> lb.shapes.forEach { s -> drawRoundRect(c, Offset(s.left, s.top), Size(s.width, s.height), radius, style = Stroke(width = 2.5f * density)) } }
    lb.pieces.forEach { p ->
        when (p) {
            is Piece.Label -> drawText(measurer, p.text.ifEmpty { " " }, Offset(p.box.left, p.box.top), style)
            is Piece.Field -> {
                drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(p.box.left, p.box.top), Size(p.box.width, p.box.height), CornerRadius(6f * density, 6f * density))
                val t = measurer.measure(p.text, if (p.placeholder) style.copy(fontStyle = FontStyle.Italic) else style)
                drawText(t, topLeft = Offset(p.box.left + (p.box.width - t.size.width) / 2, p.box.top + (p.box.height - t.size.height) / 2))
            }
            is Piece.EmptySlot -> drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(p.box.left, p.box.top), Size(p.box.width, p.box.height), CornerRadius(6f * density, 6f * density))
        }
    }
    lb.valueChildren.values.forEach { drawBlock(it, measurer, style, selectedId, errorIds, selection, error, alpha) }
    lb.mouths.forEach { m -> m.blocks.forEach { drawBlock(it, measurer, style, selectedId, errorIds, selection, error, alpha) } }
}

/** Block message in the app language; falls back to English if a translation's placeholders don't match. */
internal fun localizedParts(def: dev.buildit.engine.blocks.BlockDef, res: android.content.res.Resources): List<dev.buildit.engine.blocks.MessagePart> =
    blockMessageRes(def.type)?.let(res::getString)?.takeIf(def::matches)?.let(def::parse) ?: def.parts

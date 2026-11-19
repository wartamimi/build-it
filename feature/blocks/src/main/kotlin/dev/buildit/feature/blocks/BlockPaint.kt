package dev.buildit.feature.blocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.buildit.engine.blocks.Block
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import dev.buildit.engine.blocks.BlockCategory
import dev.buildit.engine.blocks.BlockShape
import dev.buildit.engine.blocks.FieldKind
import dev.buildit.engine.blocks.ValueType

/*
 * How blocks look, in the style Sketchware users know: flat, bright colours per category, a notch
 * on top of every statement and a matching tab underneath so blocks visibly click together, a
 * rounded cap on event blocks, pointed boolean values and round number values, and white boxes
 * for typed-in text and numbers. The geometry comes from [BlockLayout]; this file only paints it.
 */

fun categoryColor(c: BlockCategory?): Color = when (c) {
    BlockCategory.Events -> Color(0xFFD9732B)
    BlockCategory.Control -> Color(0xFFE1A92A)
    BlockCategory.Logic -> Color(0xFF5CB722)
    BlockCategory.Math -> Color(0xFF3C9F4B)
    BlockCategory.Text -> Color(0xFF2E9C8A)
    BlockCategory.Variables -> Color(0xFFEE7D16)
    BlockCategory.Lists -> Color(0xFFCC5B22)
    BlockCategory.Components -> Color(0xFF4A6CD4)
    BlockCategory.Parts -> Color(0xFF2CA5E2)
    BlockCategory.App -> Color(0xFF8A55D7)
    BlockCategory.Code -> Color(0xFF607D8B)
    null -> Color(0xFFB03A3A)
}

/** A darker shade of [c] for edges, shadows and drop-down fields. */
fun Color.shade(f: Float = 0.78f) = Color(red * f, green * f, blue * f, alpha)

private val literalTypes = setOf("math_number", "text_literal")
private val fieldInk = Color(0xFF263238)

/** Notch/tab trapezoid: 16 units wide at [y], 10 units wide 4 units lower. */
private fun tab(u: Float, x: Float, y: Float) = Path().apply {
    moveTo(x, y); lineTo(x + 16 * u, y); lineTo(x + 13 * u, y + 4 * u); lineTo(x + 3 * u, y + 4 * u); close()
}

private fun rect(a: Area, r: Float) = Path().apply { addRoundRect(RoundRect(Rect(a.left, a.top, a.right, a.bottom), CornerRadius(r))) }

private fun union(a: Path, b: Path) = Path.combine(PathOperation.Union, a, b)
private fun minus(a: Path, b: Path) = Path.combine(PathOperation.Difference, a, b)

/** Pointed ends, for true/false values and boolean inputs. */
private fun hexagon(a: Area) = Path().apply {
    val k = minOf(a.height * 0.38f, a.width / 4)
    val mid = (a.top + a.bottom) / 2
    moveTo(a.left + k, a.top); lineTo(a.right - k, a.top); lineTo(a.right, mid); lineTo(a.right - k, a.bottom); lineTo(a.left + k, a.bottom); lineTo(a.left, mid); close()
}

/** Outline of a laid-out block (without its nested blocks). */
internal fun blockOutline(lb: LaidBlock, u: Float, hatCap: Float): Path {
    val def = lb.def
    val r = 4 * u
    if (def?.shape == BlockShape.Value) {
        val a = lb.shapes.first()
        return when (def.output) {
            ValueType.Boolean -> hexagon(a)
            ValueType.Number -> rect(a, a.height / 2)
            else -> rect(a, r)
        }
    }
    val x = lb.box.left
    val header = lb.shapes.first()
    var p = rect(if (lb.hat) header.copy(top = header.top + hatCap) else header, r)
    if (lb.hat) {
        // Rounded cap of an event block
        val top = header.top + hatCap
        val w = minOf(header.width, 110 * u)
        p = union(p, Path().apply {
            moveTo(x, top + r); cubicTo(x + w * 0.15f, header.top - hatCap * 0.2f, x + w * 0.75f, header.top - hatCap * 0.2f, x + w, top); lineTo(x + w, top + r); close()
        })
    }
    // Arms of C-shaped blocks reach a little into the header and bars so the joints stay square.
    val arms = lb.mouths.size
    lb.shapes.drop(1).forEachIndexed { i, s ->
        p = union(p, rect(if (i < arms) s.copy(top = s.top - r, bottom = s.bottom + r) else s, r))
    }
    val bottom = lb.shapes.maxOf { it.bottom }
    val nx = x + 12 * u
    if (!lb.hat) p = minus(p, tab(u, nx, header.top))
    // Events end flat: nothing connects under them
    if (!lb.hat) p = union(p, tab(u, nx, bottom))
    lb.mouths.forEach { m ->
        p = union(p, tab(u, m.x + 12 * u, m.top))
        p = minus(p, tab(u, m.x + 12 * u, m.top + m.height))
    }
    return p
}

internal fun DrawScope.drawBlock(
    lb: LaidBlock,
    measurer: TextMeasurer,
    style: TextStyle,
    selectedId: String?,
    errorIds: Set<String>,
    selection: Color,
    error: Color,
    hatCap: Float,
    alpha: Float = 1f,
) {
    val u = hatCap / 12f // the layout unit (hatCap is 12 units)
    val def = lb.def
    val path = (lb.paintCache as? Path) ?: blockOutline(lb, u, hatCap).also { lb.paintCache = it }
    val literal = def?.type in literalTypes
    val dim = if (lb.block.disabled) 0.4f else 1f
    val base = (if (literal) Color.White else categoryColor(def?.category)).copy(alpha = alpha * dim)
    val edge = (if (literal) Color(0xFFB0BEC5) else categoryColor(def?.category).shade(0.72f)).copy(alpha = alpha * dim)

    if (!literal) translate(0f, 1.6f * u) { drawPath(path, edge) }
    drawPath(path, base)
    drawPath(path, edge, style = Stroke(width = 1f * u))
    when {
        lb.block.id in errorIds -> error
        lb.block.id == selectedId -> selection
        else -> null
    }?.let { c ->
        drawPath(path, Color.White.copy(alpha = alpha), style = Stroke(width = 4.5f * u))
        drawPath(path, c.copy(alpha = alpha), style = Stroke(width = 2.5f * u))
    }

    val dark = style.copy(color = fieldInk.copy(alpha = alpha * dim))
    val light = style.copy(color = Color.White.copy(alpha = alpha * dim))
    lb.pieces.forEach { p ->
        when (p) {
            is Piece.Label -> drawText(measurer.measure(p.text.ifEmpty { " " }, light, softWrap = false), topLeft = Offset(p.box.left, p.box.top))
            is Piece.Field -> {
                val kind = def?.fieldDef(p.name)?.kind
                val typed = kind is FieldKind.Text || kind is FieldKind.Number || kind is FieldKind.Code
                val st = (if (typed) dark else light).let { if (p.placeholder) it.copy(fontStyle = FontStyle.Italic) else it }
                if (!literal) {
                    val a = p.box
                    val bg = if (typed) Color.White.copy(alpha = 0.95f * alpha * dim) else categoryColor(def?.category).shade(0.8f).copy(alpha = alpha * dim)
                    val radius = if (kind is FieldKind.Number) a.height / 2 else 4 * u
                    drawRoundRect(bg, Offset(a.left, a.top), androidx.compose.ui.geometry.Size(a.width, a.height), CornerRadius(radius))
                }
                val t = measurer.measure(p.text, st)
                drawText(t, topLeft = Offset(p.box.left + (p.box.width - t.size.width) / 2, p.box.top + (p.box.height - t.size.height) / 2))
            }
            is Piece.EmptySlot -> {
                val type = def?.inputs?.firstOrNull { it.name == p.name }?.type
                if (type == ValueType.Boolean) drawPath(hexagon(p.box), categoryColor(def.category).shade(0.7f).copy(alpha = alpha * dim))
                else drawRoundRect(Color.White.copy(alpha = 0.9f * alpha * dim), Offset(p.box.left, p.box.top), androidx.compose.ui.geometry.Size(p.box.width, p.box.height),
                    CornerRadius(if (type == ValueType.Number) p.box.height / 2 else 4 * u))
            }
        }
    }
    lb.valueChildren.values.forEach { drawBlock(it, measurer, style, selectedId, errorIds, selection, error, hatCap, alpha) }
    lb.mouths.forEach { m -> m.blocks.forEach { drawBlock(it, measurer, style, selectedId, errorIds, selection, error, hatCap, alpha) } }
}

/** Size of blocks relative to the original 1.0 layout (0.17.0 and earlier). */
const val BLOCK_SCALE = 0.78f

/** Block geometry and text for one screen density and language, shared by the canvas and the palette. */
class BlockPainter(val layout: BlockLayout, val measurer: TextMeasurer, val style: TextStyle)

@Composable
fun rememberBlockPainter(): BlockPainter {
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(measurer, density, configuration) {
        // Compact like Sketchware: blocks sit at the same scale as the rest of the screen
        val style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
        val res = context.resources
        BlockPainter(
            BlockLayout(
                density * BLOCK_SCALE,
                measure = { s -> measurer.measure(s, style).size.let { TextSize(it.width.toFloat(), it.height.toFloat()) } },
                partsOf = { def -> localizedParts(def, res) },
                labelOf = { l -> statementLabelRes(l)?.let(res::getString) ?: l },
            ),
            measurer, style,
        )
    }
}

/** A block drawn exactly as on the canvas, sized to fit (palette entries). */
@Composable
fun BlockPreview(block: Block, painter: BlockPainter, modifier: Modifier = Modifier, scale: Float = 1f) {
    val density = LocalDensity.current.density
    val laid = remember(block, painter) { painter.layout.layoutChain(listOf(block), 0f, 0f) }
    val box = laid.map { it.box }.reduce(Area::union)
    // Room for the tab under statements and the drop shadow
    val w = box.right + 2 * density
    val h = box.bottom + 7 * density
    Canvas(modifier.size((w * scale / density).dp, (h * scale / density).dp)) {
        if (scale != 1f) drawContext.transform.scale(scale, scale, Offset.Zero)
        laid.forEach { drawBlock(it, painter.measurer, painter.style, null, emptySet(), Color.Unspecified, Color.Unspecified, painter.layout.hatCap) }
    }
}

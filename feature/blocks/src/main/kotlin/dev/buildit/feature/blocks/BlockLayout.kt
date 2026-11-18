package dev.buildit.feature.blocks

import dev.buildit.engine.blocks.Block
import dev.buildit.engine.blocks.BlockDef
import dev.buildit.engine.blocks.BlockDocument
import dev.buildit.engine.blocks.BlockRegistry
import dev.buildit.engine.blocks.BlockShape
import dev.buildit.engine.blocks.Procedures
import dev.buildit.engine.blocks.defOf
import dev.buildit.engine.blocks.FieldKind
import dev.buildit.engine.blocks.MessagePart
import dev.buildit.engine.blocks.Slot
import kotlin.math.max

/** Axis-aligned rectangle in world (canvas) coordinates. */
data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun intersects(o: Area) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun union(o: Area) = Area(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

    companion object {
        fun of(x: Float, y: Float, w: Float, h: Float) = Area(x, y, x + w, y + h)
    }
}

sealed interface Piece {
    val box: Area
    data class Label(override val box: Area, val text: String) : Piece
    data class Field(override val box: Area, val name: String, val text: String, val placeholder: Boolean) : Piece
    /** An empty value input. */
    data class EmptySlot(override val box: Area, val name: String) : Piece
}

data class Mouth(val input: String, val x: Float, val top: Float, val height: Float, val blocks: List<LaidBlock>)

class LaidBlock(
    val block: Block,
    val def: BlockDef?,
    /** Bounding box of this block including nested blocks. */
    val box: Area,
    /** Filled rectangles that together form the block's shape. */
    val shapes: List<Area>,
    val pieces: List<Piece>,
    val valueChildren: Map<String, LaidBlock>,
    val mouths: List<Mouth>,
    val hat: Boolean,
) {
    /** Depth-first, parents before children. */
    fun walk(): Sequence<LaidBlock> = sequence {
        yield(this@LaidBlock)
        valueChildren.values.forEach { yieldAll(it.walk()) }
        mouths.forEach { m -> m.blocks.forEach { yieldAll(it.walk()) } }
    }
}

class LaidStack(val index: Int, val blocks: List<LaidBlock>, val bounds: Area)

/** A place a dragged chain may connect to, with the point that must come close to it. */
data class SnapTarget(val slot: Slot, val x: Float, val y: Float)

data class TextSize(val width: Float, val height: Float)

/**
 * Computes block geometry for the canvas from the document. Pure: text measurement is injected,
 * so the layout is unit-tested on the JVM. Units are pixels at zoom 1; [unit] is the density.
 */
class BlockLayout(
    private val unit: Float,
    private val measure: (String) -> TextSize,
    /** Message parts per block, e.g. from a translated template; defaults to the English message. */
    private val partsOf: (BlockDef) -> List<MessagePart> = { it.parts },
    private val labelOf: (String) -> String = { it },
) {
    private val padX = 10 * unit
    private val padY = 6 * unit
    private val gap = 6 * unit
    private val fieldPad = 8 * unit
    private val minRow = 40 * unit
    private val minValue = 32 * unit
    private val slotW = 36 * unit
    private val slotH = 24 * unit
    private val arm = 18 * unit
    private val emptyMouth = 24 * unit
    private val endBar = 14 * unit
    private val labelBar = 30 * unit
    private val minInner = 80 * unit
    val hatCap = 12 * unit

    private val sizes = HashMap<String, TextSize>()
    /** The document being laid out: calls take their inputs from the procedure they call. */
    private var doc: BlockDocument? = null

    private fun defOf(b: Block): BlockDef? = doc?.defOf(b) ?: BlockRegistry.find(b.type)

    fun layout(doc: BlockDocument): List<LaidStack> {
        this.doc = doc
        return doc.stacks.mapIndexed { i, s ->
            var y = s.y
            val blocks = s.blocks.map { b -> placeStatement(b, s.x, y).also { y = it.box.bottom } }
            val bounds = blocks.map { it.box }.reduceOrNull(Area::union) ?: Area.of(s.x, s.y, 0f, 0f)
            LaidStack(i, blocks, bounds)
        }
    }

    /** Lays out a detached chain (being dragged) at the given origin. */
    fun layoutChain(chain: List<Block>, x: Float, y: Float): List<LaidBlock> {
        var cy = y
        return chain.map { b ->
            val shape = BlockRegistry.find(b.type)?.shape
            (if (shape == BlockShape.Value) placeValue(b, x, cy) else placeStatement(b, x, cy)).also { cy = it.box.bottom }
        }
    }

    // ------------------------------------------------------------ measuring

    private fun text(s: String) = measure(s.ifEmpty { " " })

    private data class Item(val part: MessagePart, val w: Float, val h: Float)

    private fun items(b: Block, def: BlockDef?): List<Item> {
        if (def == null) return listOf(text(b.type).let { Item(MessagePart.Label(b.type), it.width, it.height) })
        return partsOf(def).map { p ->
            when (p) {
                is MessagePart.Label -> text(p.text).let { Item(p, it.width, it.height) }
                is MessagePart.Field -> text(fieldText(b, def, p.name).first).let { Item(p, it.width + fieldPad * 2, it.height + padY) }
                is MessagePart.Input -> {
                    val child = b.inputs[p.name]
                    if (child != null) valueSize(child).let { Item(p, it.width, it.height) } else Item(p, slotW, slotH)
                }
            }
        }
    }

    private fun rowSize(items: List<Item>, minH: Float): TextSize {
        val w = padX * 2 + items.sumOf { it.w.toDouble() }.toFloat() + gap * max(0, items.size - 1)
        val h = max(minH, (items.maxOfOrNull { it.h } ?: 0f) + padY * 2)
        return TextSize(w, h)
    }

    private fun valueSize(b: Block): TextSize = rowSize(items(b, defOf(b)), minValue)

    // ------------------------------------------------------------ placing

    private fun placeRow(b: Block, def: BlockDef?, x: Float, y: Float, h: Float): Pair<List<Piece>, Map<String, LaidBlock>> {
        val pieces = mutableListOf<Piece>()
        val children = LinkedHashMap<String, LaidBlock>()
        var cx = x + padX
        items(b, def).forEach { item ->
            val top = y + (h - item.h) / 2
            when (val p = item.part) {
                is MessagePart.Label -> pieces += Piece.Label(Area.of(cx, top, item.w, item.h), p.text)
                is MessagePart.Field -> {
                    val (t, placeholder) = fieldText(b, def!!, p.name)
                    pieces += Piece.Field(Area.of(cx, top, item.w, item.h), p.name, t, placeholder)
                }
                is MessagePart.Input -> {
                    val child = b.inputs[p.name]
                    if (child != null) children[p.name] = placeValue(child, cx, top)
                    else pieces += Piece.EmptySlot(Area.of(cx, top, item.w, item.h), p.name)
                }
            }
            cx += item.w + gap
        }
        return pieces to children
    }

    private fun placeValue(b: Block, x: Float, y: Float): LaidBlock {
        val def = defOf(b)
        val size = valueSize(b)
        val box = Area.of(x, y, size.width, size.height)
        val (pieces, children) = placeRow(b, def, x, y, size.height)
        return LaidBlock(b, def, box, listOf(box), pieces, children, emptyList(), hat = false)
    }

    private fun placeStatement(b: Block, x: Float, y0: Float): LaidBlock {
        val def = defOf(b)
        if (def?.shape == BlockShape.Value) return placeValue(b, x, y0)
        val hat = def?.shape == BlockShape.Hat
        val top = if (hat) y0 + hatCap else y0
        val header = rowSize(items(b, def), minRow)
        val (rowPieces, children) = placeRow(b, def, x, top, header.height)
        val pieces = rowPieces.toMutableList()
        val shapes = mutableListOf<Area>()
        val mouths = mutableListOf<Mouth>()
        var y = top + header.height
        var innerWidth = 0f
        val bars = mutableListOf<Pair<Float, Float>>() // y, height
        val labels = mutableListOf<Pair<String, Float>>()
        def?.statements.orEmpty().forEachIndexed { i, st ->
            if (i > 0) { labels += labelOf(st.label) to y; bars += y to labelBar; y += labelBar }
            var cy = y
            val inner = b.statements[st.name].orEmpty().map { c -> placeStatement(c, x + arm, cy).also { cy = it.box.bottom } }
            val h = max(emptyMouth, cy - y)
            innerWidth = max(innerWidth, inner.maxOfOrNull { it.box.width } ?: 0f)
            mouths += Mouth(st.name, x + arm, y, h, inner)
            shapes += Area.of(x, y, arm, h)
            y += h
        }
        if (mouths.isNotEmpty()) { bars += y to endBar; y += endBar }
        val width = max(header.width, if (mouths.isNotEmpty()) arm + max(innerWidth, minInner) else 0f)
        val headerBox = Area(x, if (hat) y0 else top, x + width, top + header.height)
        shapes.add(0, headerBox)
        bars.forEach { (by, bh) -> shapes += Area.of(x, by, width, bh) }
        labels.forEach { (t, ly) -> text(t).let { s -> pieces += Piece.Label(Area.of(x + padX, ly + (labelBar - s.height) / 2, s.width, s.height), t) } }
        val box = Area(x, y0, x + width, y).let { bx -> mouths.flatMap { it.blocks }.fold(bx) { acc, c -> acc.union(c.box) } }
        return LaidBlock(b, def, box, shapes, pieces, children, mouths, hat)
    }

    // ------------------------------------------------------------ queries

    /** Deepest block under the point (value children before parents, last stacks on top). */
    fun hit(stacks: List<LaidStack>, x: Float, y: Float): LaidBlock? {
        fun visit(lb: LaidBlock): LaidBlock? {
            lb.valueChildren.values.forEach { c -> visit(c)?.let { return it } }
            lb.mouths.forEach { m -> m.blocks.forEach { c -> visit(c)?.let { return it } } }
            return lb.takeIf { lb.shapes.any { s -> s.contains(x, y) } }
        }
        stacks.asReversed().forEach { s -> if (s.bounds.contains(x, y)) s.blocks.forEach { b -> visit(b)?.let { return it } } }
        return null
    }

    fun fieldAt(lb: LaidBlock, x: Float, y: Float): Piece.Field? = lb.pieces.filterIsInstance<Piece.Field>().firstOrNull { it.box.contains(x, y) }

    /** Places where a chain whose first block has [shape] could connect. */
    fun targets(stacks: List<LaidStack>, shape: BlockShape): List<SnapTarget> = buildList {
        stacks.forEach { s ->
            if (shape == BlockShape.Value) {
                s.blocks.forEach { b ->
                    b.walk().forEach { lb ->
                        lb.pieces.filterIsInstance<Piece.EmptySlot>().forEach { add(SnapTarget(Slot.Value(lb.block.id, it.name), it.box.left, it.box.top)) }
                        lb.valueChildren.forEach { (n, c) -> add(SnapTarget(Slot.Value(lb.block.id, n), c.box.left, c.box.top)) }
                    }
                }
                return@forEach
            }
            if (shape != BlockShape.Statement) return@forEach
            // Above the first block of a loose stack, and after each top-level block.
            s.blocks.firstOrNull()?.let { add(SnapTarget(Slot.TopLevel(s.index, 0), it.box.left, it.box.top)) }
            s.blocks.forEachIndexed { i, b -> add(SnapTarget(Slot.TopLevel(s.index, i + 1), b.box.left, b.box.bottom)) }
            s.blocks.forEach { b ->
                b.walk().forEach { lb ->
                    lb.mouths.forEach { m ->
                        add(SnapTarget(Slot.Statement(lb.block.id, m.input, 0), m.x, m.top))
                        m.blocks.forEachIndexed { i, c -> add(SnapTarget(Slot.Statement(lb.block.id, m.input, i + 1), c.box.left, c.box.bottom)) }
                    }
                }
            }
        }
    }

    companion object {
        /** Text shown for a field, and whether it is a "choose…" placeholder. */
        fun fieldText(b: Block, def: BlockDef, name: String): Pair<String, Boolean> {
            val v = b.field(name)
            return when (def.fieldDef(name)?.kind) {
                is FieldKind.Code -> (v.lineSequence().firstOrNull()?.take(28)?.let { if (v.contains('\n') || v.length > 28) "$it…" else it } ?: "").ifEmpty { "…" } to v.isBlank()
                is FieldKind.Number -> (v.toDoubleOrNull()?.let { d -> if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString() } ?: v) to false
                is FieldKind.Text -> (if (v.length > 24) v.take(24) + "…" else v) to false
                FieldKind.Params -> Procedures.parse(v).orEmpty().let { ps -> if (ps.isEmpty()) "＋" to true else ps.joinToString(", ", "(", ")") { it.name } to false }
                is FieldKind.Color -> "■ $v" to false
                else -> if (v.isEmpty()) "▾" to true else "$v ▾" to false
            }
        }
    }
}

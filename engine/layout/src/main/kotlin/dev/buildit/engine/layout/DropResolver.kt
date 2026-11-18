package dev.buildit.engine.layout

/** Axis-aligned box in preview pixel coordinates. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

data class DropTarget(val parentUid: Long, val index: Int)

/**
 * Decides where a dragged component lands, from the rendered geometry of each element. Pure logic,
 * independent of Android Views, so it is unit-tested.
 */
object DropResolver {
    /** Deepest element whose box contains the point (for tap selection). */
    fun hit(root: XmlElement, boxes: Map<Long, Box>, x: Float, y: Float): Long? {
        fun visit(e: XmlElement): Long? {
            val b = boxes[e.uid] ?: return null
            if (!b.contains(x, y)) return null
            e.elements.asReversed().forEach { c -> visit(c)?.let { return it } }
            return e.uid
        }
        return visit(root)
    }

    /**
     * Finds the deepest container under the point that can accept a child (excluding [draggedUid]
     * and its subtree), and the child index nearest the point along the container's axis.
     */
    fun resolve(root: XmlElement, boxes: Map<Long, Box>, x: Float, y: Float, draggedUid: Long? = null): DropTarget? {
        fun accepts(e: XmlElement): Boolean {
            val def = ComponentCatalog.find(e.name) ?: return false
            if (!def.isContainer) return false
            val count = e.elements.count { it.uid != draggedUid }
            return def.maxChildren == null || count < def.maxChildren || e.elements.any { it.uid == draggedUid }
        }
        fun visit(e: XmlElement): DropTarget? {
            if (e.uid == draggedUid) return null
            val b = boxes[e.uid] ?: return null
            if (!b.contains(x, y)) return null
            e.elements.asReversed().forEach { c -> visit(c)?.let { return it } }
            if (!accepts(e)) return null
            val horizontal = (e.name == "LinearLayout" && e["android:orientation"] == "horizontal") || e.name == "HorizontalScrollView"
            val ordered = e.elements.filter { it.uid != draggedUid }
            var index = ordered.size
            for ((i, c) in ordered.withIndex()) {
                val cb = boxes[c.uid] ?: continue
                val before = if (horizontal) x < cb.centerX else y < cb.centerY
                if (before) { index = i; break }
            }
            // Index in the full child list (before the dragged element is removed), as Move expects.
            val fullIndex = if (index >= ordered.size) e.elements.size else e.elements.indexOfFirst { it.uid == ordered[index].uid }
            return DropTarget(e.uid, fullIndex)
        }
        return visit(root) ?: if (accepts(root)) DropTarget(root.uid, root.elements.size) else null
    }
}

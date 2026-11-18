package dev.buildit.engine.ai

/** One line of a diff. Line numbers are 1-based; 0 where the line does not exist on that side. */
data class DiffLine(val kind: Kind, val text: String, val oldLine: Int, val newLine: Int) {
    enum class Kind { Same, Added, Removed }
}

/** A run of changed lines with up to [LineDiff.CONTEXT] unchanged lines around it. */
data class Hunk(val lines: List<DiffLine>) {
    val added get() = lines.count { it.kind == DiffLine.Kind.Added }
    val removed get() = lines.count { it.kind == DiffLine.Kind.Removed }
}

/** Line diff (Myers' O(ND) algorithm after trimming the common prefix and suffix). */
object LineDiff {
    const val CONTEXT = 3
    private const val MAX_EDITS = 4_000

    fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')

    fun diff(old: String, new: String): List<DiffLine> = diff(lines(old), lines(new))

    fun diff(a: List<String>, b: List<String>): List<DiffLine> {
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val out = ArrayList<DiffLine>(a.size + b.size)
        for (i in 0 until pre) out += DiffLine(DiffLine.Kind.Same, a[i], i + 1, i + 1)
        val midA = a.subList(pre, a.size - suf)
        val midB = b.subList(pre, b.size - suf)
        for (op in myers(midA, midB)) {
            out += when (op.kind) {
                DiffLine.Kind.Same -> DiffLine(op.kind, midA[op.i], pre + op.i + 1, pre + op.j + 1)
                DiffLine.Kind.Removed -> DiffLine(op.kind, midA[op.i], pre + op.i + 1, 0)
                DiffLine.Kind.Added -> DiffLine(op.kind, midB[op.j], 0, pre + op.j + 1)
            }
        }
        for (k in 0 until suf) {
            val i = a.size - suf + k
            val j = b.size - suf + k
            out += DiffLine(DiffLine.Kind.Same, a[i], i + 1, j + 1)
        }
        return out
    }

    fun hunks(lines: List<DiffLine>, context: Int = CONTEXT): List<Hunk> {
        val changed = lines.indices.filter { lines[it].kind != DiffLine.Kind.Same }
        if (changed.isEmpty()) return emptyList()
        val result = mutableListOf<Hunk>()
        var start = (changed.first() - context).coerceAtLeast(0)
        var last = changed.first()
        for (c in changed.drop(1)) {
            if (c - last > 2 * context) {
                result += Hunk(lines.subList(start, (last + context + 1).coerceAtMost(lines.size)))
                start = c - context
            }
            last = c
        }
        result += Hunk(lines.subList(start, (last + context + 1).coerceAtMost(lines.size)))
        return result
    }

    private class Op(val kind: DiffLine.Kind, val i: Int, val j: Int)

    private fun myers(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        if (n == 0) return List(m) { Op(DiffLine.Kind.Added, 0, it) }
        if (m == 0) return List(n) { Op(DiffLine.Kind.Removed, it, 0) }
        val max = n + m
        // The trace keeps one V array per edit step; bound its memory (~100 MB worst case).
        val limit = minOf(max, MAX_EDITS, 25_000_000 / (2 * max + 2))
        val offset = max
        val v = IntArray(2 * max + 2)
        val trace = ArrayList<IntArray>()
        var found = false
        for (d in 0..limit) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) { found = true; break }
                k += 2
            }
            if (found) break
        }
        if (!found) return replaceAll(n, m)
        // Walk the trace backwards to recover the edit script.
        val ops = ArrayList<Op>()
        var x = n
        var y = m
        for (d in trace.size - 1 downTo 0) {
            val vd = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vd[offset + k - 1] < vd[offset + k + 1])) k + 1 else k - 1
            val prevX = if (d == 0) 0 else vd[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { x--; y--; ops += Op(DiffLine.Kind.Same, x, y) }
            if (d > 0) {
                if (x == prevX) { y--; ops += Op(DiffLine.Kind.Added, x, y) } else { x--; ops += Op(DiffLine.Kind.Removed, x, y) }
            }
        }
        ops.reverse()
        return ops
    }

    private fun replaceAll(n: Int, m: Int): List<Op> =
        List(n) { Op(DiffLine.Kind.Removed, it, 0) } + List(m) { Op(DiffLine.Kind.Added, n, it) }
}

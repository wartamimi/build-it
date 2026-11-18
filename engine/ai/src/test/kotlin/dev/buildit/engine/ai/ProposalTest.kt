package dev.buildit.engine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProposalTest {
    @Test fun `splits prose and file blocks`() {
        val reply = """
            |I added a button.
            |<<<FILE path="app/src/main/res/layout/activity_main.xml">>>
            |<LinearLayout/>
            |<<<END>>>
            |Then wire it:
            |<<<FILE path="app/src/main/java/a/Main.java">>>
            |class Main {}
            |<<<END>>>
            |Done.""".trimMargin()
        val p = ProposalParser.parse(reply)
        assertEquals(listOf("app/src/main/res/layout/activity_main.xml", "app/src/main/java/a/Main.java"), p.files.map { it.path })
        assertEquals("<LinearLayout/>\n", p.files[0].text)
        assertTrue(p.files.all { it.complete })
        assertEquals("I added a button.\n[app/src/main/res/layout/activity_main.xml]\nThen wire it:\n[app/src/main/java/a/Main.java]\nDone.", p.prose)
    }

    @Test fun `a reply cut off inside a file marks it incomplete`() {
        val p = ProposalParser.parse("x\n<<<FILE path=\"a.txt\">>>\nline1\nli")
        assertFalse(p.files.single().complete)
    }

    @Test fun `diff reproduces both sides`() {
        val r = Random(7)
        repeat(300) {
            val a = List(r.nextInt(0, 40)) { "l" + r.nextInt(6) }
            val b = a.toMutableList().apply {
                repeat(r.nextInt(0, 8)) {
                    when (r.nextInt(3)) {
                        0 -> if (isNotEmpty()) removeAt(r.nextInt(size))
                        1 -> add(r.nextInt(size + 1), "n" + r.nextInt(4))
                        else -> if (isNotEmpty()) set(r.nextInt(size), "c" + r.nextInt(4))
                    }
                }
            }
            val d = LineDiff.diff(a, b)
            assertEquals(a, d.filter { it.kind != DiffLine.Kind.Added }.map { it.text })
            assertEquals(b, d.filter { it.kind != DiffLine.Kind.Removed }.map { it.text })
            assertEquals(a.indices.map { it + 1 }, d.filter { it.kind != DiffLine.Kind.Added }.map { it.oldLine })
            assertEquals(b.indices.map { it + 1 }, d.filter { it.kind != DiffLine.Kind.Removed }.map { it.newLine })
        }
    }

    @Test fun `diff is minimal for a single change and hunks keep three lines of context`() {
        val a = (1..20).map { "line $it" }
        val b = a.toMutableList().apply { set(9, "changed"); add(19, "extra") }
        val d = LineDiff.diff(a, b)
        assertEquals(3, d.count { it.kind != DiffLine.Kind.Same })
        val h = LineDiff.hunks(d)
        assertEquals(2, h.size)
        assertEquals(listOf("line 7", "line 8", "line 9", "line 10", "changed", "line 11", "line 12", "line 13"), h[0].lines.map { it.text })
        assertEquals(1, h[1].added)
        assertEquals(emptyList<Hunk>(), LineDiff.hunks(LineDiff.diff(a, a)))
    }
}

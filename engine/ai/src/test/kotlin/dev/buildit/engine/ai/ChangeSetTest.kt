package dev.buildit.engine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChangeSetTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(): File = tmp.newFolder("p").apply {
        File(this, "app/src/main/java/a").mkdirs()
        File(this, "app/src/main/java/a/Main.java").writeText("class Main {\n}\n")
        File(this, "buildit.json").writeText("{}")
    }

    @Test fun `paths that leave the project or touch Build It files are refused`() {
        val cs = ChangeSet(project())
        val changes = cs.prepare(
            listOf("../evil.txt", "/etc/passwd", "app/../../x", "buildit.json", ".buildit/generated.json", "build/outputs/x.apk", "app/build/tmp.txt")
                .map { ProposedFile(it, "x\n", true) },
            emptyList(),
        )
        assertEquals(
            listOf(FileChange.Blocked.InvalidPath, FileChange.Blocked.InvalidPath, FileChange.Blocked.InvalidPath, FileChange.Blocked.Protected,
                FileChange.Blocked.Protected, FileChange.Blocked.BuildOutput, FileChange.Blocked.BuildOutput),
            changes.map { it.blocked },
        )
        changes.forEach { assertTrue(cs.apply(it) is ApplyResult.Failed) }
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test fun `apply writes only when the file is unchanged since the diff`() {
        val dir = project()
        val cs = ChangeSet(dir)
        val path = "app/src/main/java/a/Main.java"
        val (edit, created) = cs.prepare(
            listOf(ProposedFile(path, "class Main {\n  int x;\n}\n", true), ProposedFile("./app/src/main/java/a/New.java", "class New {}\n", true)),
            listOf(ContextFile(path, "class Main {\n}\n")),
        )
        assertFalse(edit.changedSinceSent)
        assertEquals(1, edit.hunks.single().added)
        assertTrue(created.isNew)
        assertEquals("app/src/main/java/a/New.java", created.path)

        File(dir, path).writeText("class Main { /* user edit */ }\n")
        assertEquals(ApplyResult.Stale, cs.apply(edit))
        assertEquals("class Main { /* user edit */ }\n", File(dir, path).readText())

        assertEquals(ApplyResult.Applied, cs.apply(created))
        assertEquals("class New {}\n", File(dir, "app/src/main/java/a/New.java").readText())
        assertEquals(ApplyResult.Stale, cs.apply(created))
    }

    @Test fun `a file edited after it was sent is flagged, and incomplete files cannot be applied`() {
        val dir = project()
        val path = "app/src/main/java/a/Main.java"
        val c = ChangeSet(dir).prepare(
            listOf(ProposedFile(path, "class Main {}\n", true), ProposedFile("b.txt", "part", false)),
            listOf(ContextFile(path, "older content\n")),
        )
        assertTrue(c[0].changedSinceSent)
        assertNull(c[0].blocked)
        assertEquals(FileChange.Blocked.Incomplete, c[1].blocked)
    }
}

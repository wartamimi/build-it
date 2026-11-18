package dev.buildit.feature.console

import android.content.ContentValues
import android.net.Uri
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ConsoleTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun reset() { ConsoleLog.verifier = { false }; ConsoleLog.shared.clear("com.example.hello") }

    private fun values(kind: String, text: String) = ContentValues().apply {
        put("session", 1L); put("pid", 4242); put("time", 1000L); put("kind", kind); put("text", text)
    }

    @Test fun providerAcceptsOnlyVerifiedCallersAndParsesEntries() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app.packageManager).setPackagesForUid(Process.myUid(), "com.example.hello")
        val provider = Robolectric.buildContentProvider(ConsoleProvider::class.java).create().get()
        val uri = Uri.parse("content://x.console/entries")
        val batch = arrayOf(
            values("log", "11-18 12:01:02.345  4242  4250 E MainActivity: Something failed"),
            values("lifecycle", "com.example.hello.MainActivity resumed"),
            values("crash", "java.lang.IllegalStateException: boom\n\tat com.example.hello.MainActivity.onCreate(MainActivity.java:23)\n"),
        )

        assertEquals(0, provider.bulkInsert(uri, batch))
        assertTrue(ConsoleLog.shared.entries.value.none { it.packageName == "com.example.hello" })

        ConsoleLog.verifier = { it == "com.example.hello" }
        assertEquals(0, provider.bulkInsert(Uri.parse("content://x.console/other"), batch))
        assertEquals(3, provider.bulkInsert(uri, batch))
        val e = ConsoleLog.shared.entries.value.filter { it.packageName == "com.example.hello" }
        assertEquals(listOf(ConsoleLevel.Error, ConsoleLevel.Event, ConsoleLevel.Crash), e.map { it.level })
        assertEquals("MainActivity", e[0].tag)
        assertEquals("Something failed", e[0].text)
        assertEquals(4242, e[0].pid)
        assertNull(provider.query(uri, null, null, null, null))
    }

    @Test fun stackFramesOpenTheProjectSource() {
        val dir = tmp.newFolder("p")
        File(dir, "app/src/main/java/com/example/hello").mkdirs()
        File(dir, "app/src/main/java/com/example/hello/MainActivity.java").writeText("class MainActivity {}")
        val text = "java.lang.RuntimeException\n\tat android.app.Activity.performCreate(Activity.java:8000)\n\tat com.example.hello.MainActivity\$1.onClick(MainActivity.java:31)"
        val frames = SourceLinks.frames(text)
        assertEquals(listOf("Activity.java", "MainActivity.java"), frames.map { it.fileName })
        assertNull(SourceLinks.resolve(dir, frames[0]))
        assertEquals("app/src/main/java/com/example/hello/MainActivity.java", SourceLinks.resolve(dir, frames[1]))
        assertEquals(31, frames[1].line)
    }

    @Test fun levelFilterAndCapacity() {
        assertTrue(ConsoleViewModel.levelPasses(ConsoleLevel.Crash, ConsoleLevel.Error))
        assertFalse(ConsoleViewModel.levelPasses(ConsoleLevel.Info, ConsoleLevel.Warn))
        assertTrue(ConsoleViewModel.levelPasses(ConsoleLevel.Assert, ConsoleLevel.Error))
        val log = ConsoleLog()
        log.add(List(ConsoleLog.CAPACITY + 10) { RawEntry("a", 1, 1, 0, "log", "line $it", null) })
        assertEquals(ConsoleLog.CAPACITY, log.entries.value.size)
        assertEquals("line 10", log.entries.value.first().text)
        assertEquals(ConsoleLevel.Info, log.entries.value.first().level) // unparseable lines are kept as info
    }
}

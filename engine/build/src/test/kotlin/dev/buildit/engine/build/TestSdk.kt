package dev.buildit.engine.build

import java.io.File
import java.util.Properties

/** Locates the local Android SDK (ANDROID_HOME, ANDROID_SDK_ROOT or local.properties). */
object TestSdk {
    val root: File? by lazy {
        val roots = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).toMutableList()
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val lp = File(dir, "local.properties")
            if (lp.isFile) Properties().apply { lp.inputStream().use(::load) }.getProperty("sdk.dir")?.let(roots::add)
            dir = dir.parentFile
        }
        roots.map(::File).firstOrNull { File(it, "platforms").isDirectory }
    }

    /** android.jar of API 36 when present, else the newest platform. */
    val androidJar: File? by lazy {
        val platforms = File(root ?: return@lazy null, "platforms")
        File(platforms, "android-36/android.jar").takeIf { it.isFile }
            ?: platforms.listFiles().orEmpty().sortedDescending().map { File(it, "android.jar") }.firstOrNull { it.isFile }
    }

    /** The SDK's own aapt2, used only to inspect our output in tests. */
    val aapt2: File? by lazy {
        File(root ?: return@lazy null, "build-tools").listFiles().orEmpty().sortedDescending().map { File(it, "aapt2") }.firstOrNull { it.canExecute() }
    }

    /** Skips locally when the SDK piece is missing, but fails on CI, where it must be installed. */
    fun require(present: Boolean, what: String) {
        if (System.getenv("CI") == "true") org.junit.Assert.assertTrue("$what is required on CI", present)
        else org.junit.Assume.assumeTrue("$what not found", present)
    }

    fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
        return out
    }
}

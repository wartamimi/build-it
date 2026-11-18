package dev.buildit.engine.build

import com.android.tools.r8.CompilationFailedException
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import com.android.tools.r8.origin.PathOrigin
import com.android.tools.r8.position.TextPosition
import org.eclipse.jdt.internal.compiler.batch.FileSystem
import org.eclipse.jdt.internal.compiler.batch.Main
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.android.tools.r8.Diagnostic as D8Diagnostic

/**
 * Java compiler (Eclipse ECJ, in process). android.jar is supplied as the system library so the
 * compiler never looks for a JDK module image, which does not exist on Android.
 */
object JavaCompiler {
    private val line = Regex("^(.+?):(\\d+): (error|warning): (.*)$")

    fun compile(sources: List<File>, androidJar: File, outDir: File, diagnostics: Diagnostics, relative: (File) -> String, stubs: File? = null): Boolean =
        run(listOf("-17"), sources, listOfNotNull(androidJar, stubs), outDir, diagnostics, relative)

    /** Compiles the [LambdaStubs] (Java 8 rules, which allow defining java.* classes). */
    internal fun compileStubs(sources: List<File>, androidJar: File, outDir: File, diagnostics: Diagnostics): Boolean =
        run(listOf("-8"), sources, listOf(androidJar), outDir, diagnostics) { it.path }

    private fun run(level: List<String>, sources: List<File>, system: List<File>, outDir: File, diagnostics: Diagnostics, relative: (File) -> String): Boolean {
        outDir.mkdirs()
        val androidJar = system.first()
        val out = StringWriter()
        val err = StringWriter()
        val main = object : Main(PrintWriter(out), PrintWriter(err), false, null, null) {
            override fun handleBootclasspath(bootclasspaths: ArrayList<String>?, customEncoding: String?): ArrayList<FileSystem.Classpath> =
                ArrayList(system.map { FileSystem.getClasspath(it.path, null, null) })

            override fun getJavaHome(): File = File(androidJar.parentFile, "no-jdk")
        }
        val args = level + listOf("-encoding", "UTF-8", "-proc:none", "-g", "-nowarn", "-Xemacs", "-d", outDir.path) + sources.map { it.path }
        val ok = main.compile(args.toTypedArray())
        val text = out.toString() + err.toString()
        var matched = false
        text.lineSequence().forEach { l ->
            val m = line.matchEntire(l) ?: return@forEach
            matched = true
            val (path, ln, kind, message) = m.destructured
            val f = File(path)
            if (kind == "error") diagnostics.error(message, relative(f), ln.toInt()) else diagnostics.warning(message, relative(f), ln.toInt())
        }
        if (!ok && !matched) diagnostics.error("Java compilation failed: ${text.lineSequence().firstOrNull { it.isNotBlank() } ?: "no output"}")
        return ok
    }
}

/** DEX conversion with D8 (in process), including Java 8+ language desugaring for [minSdk]. */
object Dexer {
    fun dex(classesDir: File, androidJar: File, minSdk: Int, debug: Boolean, outDir: File, diagnostics: Diagnostics): Boolean {
        outDir.deleteRecursively(); outDir.mkdirs()
        val classes = classesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.map { it.toPath() }.toList()
        if (classes.isEmpty()) { diagnostics.error("No compiled classes to convert"); return false }
        val handler = object : DiagnosticsHandler {
            override fun error(d: D8Diagnostic) = report(d, true)
            override fun warning(d: D8Diagnostic) = report(d, false)
            private fun report(d: D8Diagnostic, error: Boolean) {
                val file = (d.origin as? PathOrigin)?.part()
                val ln = (d.position as? TextPosition)?.line ?: 0
                if (error) diagnostics.error("D8: ${d.diagnosticMessage}", file, ln) else diagnostics.warning("D8: ${d.diagnosticMessage}", file, ln)
            }
        }
        return try {
            val cmd = D8Command.builder(handler)
                .addProgramFiles(classes)
                .addLibraryFiles(androidJar.toPath())
                .setMinApiLevel(minSdk)
                .setMode(if (debug) CompilationMode.DEBUG else CompilationMode.RELEASE)
                .setOutput(outDir.toPath(), OutputMode.DexIndexed)
                .build()
            D8.run(cmd)
            true
        } catch (e: CompilationFailedException) {
            if (!diagnostics.hasErrors) diagnostics.error("D8 failed: ${e.message}")
            false
        }
    }
}

/**
 * Writes the unsigned APK. resources.arsc is stored uncompressed (required for targetSdk 30+);
 * apksig aligns stored entries when signing.
 */
object ApkWriter {
    fun write(out: File, manifest: ByteArray, table: ByteArray, files: Map<String, ByteArray>, dexDir: File, assetsDir: File?) {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun put(name: String, bytes: ByteArray, store: Boolean = false) {
                val e = ZipEntry(name)
                e.time = FIXED_TIME
                if (store) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
                    e.crc = CRC32().apply { update(bytes) }.value
                }
                z.putNextEntry(e); z.write(bytes); z.closeEntry()
            }
            put("AndroidManifest.xml", manifest)
            put("resources.arsc", table, store = true)
            files.toSortedMap().forEach { (n, b) -> put(n, b, store = n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".webp")) }
            dexDir.listFiles().orEmpty().filter { it.name.endsWith(".dex") }.sortedBy { it.name }.forEach { put(it.name, it.readBytes()) }
            assetsDir?.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.sortedBy { it.path }?.forEach { f ->
                put("assets/" + f.relativeTo(assetsDir).invariantSeparatorsPath, f.readBytes())
            }
        }
    }

    // 2008-01-01, so builds of the same input give the same bytes.
    private const val FIXED_TIME = 1199145600000L
}

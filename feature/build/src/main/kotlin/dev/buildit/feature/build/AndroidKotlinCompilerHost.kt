package dev.buildit.feature.build

import android.os.Build
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import dalvik.system.DexClassLoader
import dev.buildit.engine.build.KotlinCompilerHost
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Runs the Kotlin compiler inside Build It on Android. The compiler's JARs (downloaded from Maven
 * Central) are converted to DEX once, in chunks so the converter's memory stays bounded, and kept
 * in [cacheDir]. Each chunk is a read-only ZIP holding its classes.dex; the first chunk of a JAR
 * also carries the JAR's other files (the compiler reads its extension descriptors and the
 * standard library's builtins as class-path resources).
 *
 * The class loader's parent is the boot class loader, so the compiler sees its own Kotlin standard
 * library and coroutines, never Build It's (which R8 has shrunk).
 */
class AndroidKotlinCompilerHost(
    private val cacheDir: File,
    /** android.jar for D8's desugaring; any recent platform works. */
    private val androidJar: () -> File?,
    private val classesPerChunk: Int = 2500,
) : KotlinCompilerHost {
    private var loaded: Pair<List<File>, ClassLoader>? = null

    @Synchronized
    override fun classLoader(jars: List<File>, log: (String) -> Unit, isCancelled: () -> Boolean): ClassLoader {
        loaded?.let { (key, loader) -> if (key == jars) return loader }
        val zips = jars.flatMap { jar -> dexed(jar, log, isCancelled) }
        val optimized = File(cacheDir, "oat").apply { mkdirs() }
        @Suppress("DEPRECATION")
        val loader = DexClassLoader(zips.joinToString(File.pathSeparator) { it.path }, optimized.path, null, ClassLoader.getSystemClassLoader().parent)
        loaded = jars to loader
        return loader
    }

    private fun key(jar: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("${jar.name}:${jar.length()}:${jar.lastModified()}:${Build.VERSION.SDK_INT}:$classesPerChunk".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(20)
    }

    /** DEX ZIPs of [jar], converting it the first time. */
    private fun dexed(jar: File, log: (String) -> Unit, isCancelled: () -> Boolean): List<File> {
        val dir = File(cacheDir, "kotlinc/${key(jar)}")
        val done = File(dir, ".complete")
        if (done.isFile) return chunks(dir)
        dir.deleteRecursively(); dir.mkdirs()
        val platform = androidJar() ?: error("No Android platform installed to prepare the Kotlin compiler")
        ZipFile(jar).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            val classes = entries.filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") && it.name != "module-info.class" }
            val resources = entries.filter { !it.name.endsWith(".class") && !isSignature(it.name) }
            val groups = classes.chunked(classesPerChunk).ifEmpty { listOf(emptyList()) }
            groups.forEachIndexed { i, group ->
                if (isCancelled()) throw dev.buildit.engine.build.BuildCancelledException()
                log("Preparing the Kotlin compiler: ${jar.name} (${i + 1}/${groups.size})")
                val input = File(dir, "in-$i.jar")
                ZipOutputStream(input.outputStream().buffered()).use { out -> group.forEach { e -> copy(zip, e, out) } }
                val dexOut = File(dir, "dex-$i").apply { mkdirs() }
                if (group.isNotEmpty()) {
                    D8.run(
                        D8Command.builder(Quiet)
                            .addProgramFiles(input.toPath())
                            .addLibraryFiles(platform.toPath())
                            .setMinApiLevel(Build.VERSION.SDK_INT)
                            .setMode(CompilationMode.RELEASE)
                            .setOutput(dexOut.toPath(), OutputMode.DexIndexed)
                            .build(),
                    )
                }
                val chunk = File(dir, "chunk-%03d.zip".format(i))
                ZipOutputStream(chunk.outputStream().buffered()).use { out ->
                    dexOut.listFiles().orEmpty().filter { it.name.endsWith(".dex") }.sortedBy { it.name }.forEach { f ->
                        out.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(out) }; out.closeEntry()
                    }
                    if (i == 0) resources.forEach { e -> copy(zip, e, out) }
                }
                // Android 14+ refuses to load writable code files
                chunk.setReadOnly()
                input.delete(); dexOut.deleteRecursively()
            }
        }
        done.writeText(jar.name)
        return chunks(dir)
    }

    private fun chunks(dir: File) = dir.listFiles().orEmpty().filter { it.name.startsWith("chunk-") }.sortedBy { it.name }

    private fun isSignature(name: String) = name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))

    private fun copy(zip: ZipFile, e: ZipEntry, out: ZipOutputStream) {
        out.putNextEntry(ZipEntry(e.name))
        zip.getInputStream(e).use { it.copyTo(out) }
        out.closeEntry()
    }

    /** Missing optional classes in the compiler are normal; real errors throw. */
    private object Quiet : DiagnosticsHandler {
        override fun warning(d: com.android.tools.r8.Diagnostic) = Unit
        override fun info(d: com.android.tools.r8.Diagnostic) = Unit
    }
}

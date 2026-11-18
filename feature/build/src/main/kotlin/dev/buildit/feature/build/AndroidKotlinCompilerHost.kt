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
 *
 * Android lacks a few JDK APIs the compiler touches: java.lang.management and javax.management
 * (timing and memory reports), java.beans.Introspector and StAX (javax.xml.stream, for its
 * extension descriptors). While converting, references to them are renamed, byte for byte, to
 * same-length packages: Build It's own small stand-ins (`jvbuildit.*`, reached through [ShimParent])
 * and the StAX API jar from Maven Central, renamed the same way.
 */
class AndroidKotlinCompilerHost(
    private val cacheDir: File,
    /** android.jar for D8's desugaring; any recent platform works. */
    private val androidJar: () -> File?,
    private val classesPerChunk: Int = 2500,
) : KotlinCompilerHost {
    override val extraLibraries = listOf(STAX)
    private var loaded: Pair<List<File>, ClassLoader>? = null

    @Synchronized
    override fun classLoader(jars: List<File>, log: (String) -> Unit, isCancelled: () -> Boolean): ClassLoader {
        loaded?.let { (key, loader) -> if (key == jars) return loader }
        val zips = jars.flatMap { jar -> dexed(jar, log, isCancelled) }
        val optimized = File(cacheDir, "oat").apply { mkdirs() }
        @Suppress("DEPRECATION")
        val loader = DexClassLoader(zips.joinToString(File.pathSeparator) { it.path }, optimized.path, null, ShimParent(javaClass.classLoader!!))
        loaded = jars to loader
        return loader
    }

    private fun key(jar: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("${jar.name}:${jar.length()}:${jar.lastModified()}:${Build.VERSION.SDK_INT}:$classesPerChunk:$FORMAT".toByteArray())
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
            val stax = jar.name.startsWith("stax-api-")
            // From the StAX jar only the javax.xml.stream API; Android has javax.xml.namespace.
            val entries = zip.entries().toList().filter { !it.isDirectory && (!stax || it.name.startsWith("javax/xml/stream/")) }
            val classes = entries.filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") && it.name != "module-info.class" }
            // The compiler locates its own JAR by looking up PathUtil.class as a resource.
            val resources = entries.filter { (!it.name.endsWith(".class") || it.name in CLASS_RESOURCES) && !isSignature(it.name) }
            val groups = classes.chunked(classesPerChunk).ifEmpty { listOf(emptyList()) }
            groups.forEachIndexed { i, group ->
                if (isCancelled()) throw dev.buildit.engine.build.BuildCancelledException()
                log("Preparing the Kotlin compiler: ${jar.name} (${i + 1}/${groups.size})")
                val input = File(dir, "in-$i.jar")
                ZipOutputStream(input.outputStream().buffered()).use { out ->
                    group.forEach { e ->
                        out.putNextEntry(ZipEntry(Relocation.name(e.name)))
                        out.write(Relocation.patch(zip.getInputStream(e).use { it.readBytes() }))
                        out.closeEntry()
                    }
                }
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

    /** Delegates the stand-ins to Build It's class loader; everything else comes from the boot class path. */
    private class ShimParent(private val app: ClassLoader) : ClassLoader(getSystemClassLoader().parent) {
        override fun findClass(name: String): Class<*> =
            if ((name.startsWith("jvbuildit.") || name.startsWith("jvbuilditb.")) && !name.startsWith("jvbuildit.xmlstr.")) app.loadClass(name) else throw ClassNotFoundException(name)
    }

    /** Missing optional classes in the compiler are normal; real errors throw. */
    private object Quiet : DiagnosticsHandler {
        override fun warning(d: com.android.tools.r8.Diagnostic) = Unit
        override fun info(d: com.android.tools.r8.Diagnostic) = Unit
    }

    internal companion object {
        val STAX = dev.buildit.engine.deps.Coordinate("javax.xml.stream", "stax-api", "1.0-2")
        /** Bumped when the conversion changes, so cached DEX is redone. */
        const val FORMAT = 4
        val CLASS_RESOURCES = setOf("org/jetbrains/kotlin/utils/PathUtil.class")
    }
}

/** Same-length renames of JDK packages Android lacks; see [AndroidKotlinCompilerHost]. */
internal object Relocation {
    val renames: List<Pair<ByteArray, ByteArray>> = listOf(
        "java/lang/management/" to "jvbuildit/management/",
        "javax/management/" to "jvbuildit/jxmgmt/",
        "java/beans/Introspector" to "jvbuilditb/Introspector",
        "javax/xml/stream/" to "jvbuildit/xmlstr/",
    ).map { (a, b) -> check(a.length == b.length) { "$a -> $b" }; a.toByteArray() to b.toByteArray() }

    fun name(entry: String): String = renames.fold(entry) { n, (a, b) -> n.replace(String(a), String(b)) }

    /** Class files only; names inside the constant pool keep their length, so offsets stay valid. */
    fun patch(bytes: ByteArray): ByteArray {
        if (bytes.size < 4 || bytes[0] != 0xCA.toByte() || bytes[1] != 0xFE.toByte()) return bytes
        var out = bytes
        for ((from, to) in renames) {
            var i = indexOf(out, from, 0)
            while (i >= 0) {
                if (out === bytes) out = bytes.copyOf()
                System.arraycopy(to, 0, out, i, to.size)
                i = indexOf(out, from, i + to.size)
            }
        }
        return out
    }

    private fun indexOf(h: ByteArray, n: ByteArray, start: Int): Int {
        val first = n[0]
        var i = start
        val last = h.size - n.size
        while (i <= last) {
            if (h[i] == first) {
                var j = 1
                while (j < n.size && h[i + j] == n[j]) j++
                if (j == n.size) return i
            }
            i++
        }
        return -1
    }
}

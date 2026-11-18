package dev.buildit.engine.build

import com.android.tools.r8.CompilationFailedException
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import dev.buildit.engine.layout.XmlParser
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * A library ready for the build: an AAR unpacked once into the cache, or a plain JAR.
 * [label] is `group:artifact:version`, [module] is `group:artifact`.
 */
class PreparedLibrary(
    val label: String,
    val module: String,
    val jars: List<File>,
    val res: File?,
    val manifest: File?,
    val assets: File?,
    val jni: File?,
    /** Java package of the library's R class (from its manifest). */
    val packageName: String?,
) {
    companion object {
        /**
         * Unpacks [file] (an `.aar` or `.jar`) under [cacheDir]. Unpacked AARs are reused: released
         * artifacts never change, and a marker file is written only after a complete extraction.
         */
        fun prepare(label: String, module: String, file: File, cacheDir: File): PreparedLibrary {
            if (file.name.endsWith(".jar")) return PreparedLibrary(label, module, listOf(file), null, null, null, null, null)
            val dir = File(cacheDir, "aar/" + sha1("${file.absolutePath}:${file.length()}").take(16) + "-" + file.nameWithoutExtension)
            val done = File(dir, ".complete")
            if (!done.isFile) {
                dir.deleteRecursively()
                dir.mkdirs()
                ZipFile(file).use { z ->
                    z.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                        val target = File(dir, e.name).canonicalFile
                        if (!target.path.startsWith(dir.canonicalPath + File.separator)) throw IllegalStateException("${file.name}: unsafe entry ${e.name}")
                        target.parentFile.mkdirs()
                        z.getInputStream(e).use { inp -> target.outputStream().use { inp.copyTo(it) } }
                    }
                }
                done.writeText(label)
            }
            val jars = listOfNotNull(File(dir, "classes.jar").takeIf { it.isFile }) +
                File(dir, "libs").listFiles().orEmpty().filter { it.name.endsWith(".jar") }.sortedBy { it.name }
            val manifest = File(dir, "AndroidManifest.xml").takeIf { it.isFile }
            val pkg = manifest?.let { runCatching { XmlParser.parse(it.readText())["package"] }.getOrNull() }
            return PreparedLibrary(
                label, module, jars,
                File(dir, "res").takeIf { it.isDirectory },
                manifest,
                File(dir, "assets").takeIf { it.isDirectory },
                File(dir, "jni").takeIf { it.isDirectory },
                pkg,
            )
        }

        fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Dexes library JARs once per JAR (and minSdk/mode) and keeps the result in a cache, so only the
 * app's own classes are converted on each build.
 */
object LibraryDexer {
    /**
     * Each JAR is dexed on its own with only android.jar as library: other JARs are not passed as
     * classpath (they may repeat classes, which D8 rejects there), so desugaring that needs other
     * libraries' types is limited; Build It projects have minSdk 24+, where little is needed.
     */
    fun dex(jar: File, androidJar: File, minSdk: Int, debug: Boolean, cacheDir: File, diagnostics: Diagnostics, label: String): List<File>? {
        val key = PreparedLibrary.sha1("${jar.absolutePath}:${jar.length()}:${jar.lastModified()}:$minSdk:$debug:${androidJar.length()}").take(20)
        val out = File(cacheDir, "dex/$key")
        val done = File(out, ".complete")
        if (done.isFile) return dexFiles(out)
        out.deleteRecursively(); out.mkdirs()
        val handler = object : DiagnosticsHandler {
            override fun error(d: com.android.tools.r8.Diagnostic) { diagnostics.error("D8 ($label): ${d.diagnosticMessage}") }
            override fun warning(d: com.android.tools.r8.Diagnostic) = Unit // missing optional classes in libraries are normal
            override fun info(d: com.android.tools.r8.Diagnostic) = Unit
        }
        return try {
            D8.run(
                D8Command.builder(handler)
                    .addProgramFiles(jar.toPath())
                    .addLibraryFiles(androidJar.toPath())
                    .setMinApiLevel(minSdk)
                    .setMode(if (debug) CompilationMode.DEBUG else CompilationMode.RELEASE)
                    .setOutput(out.toPath(), OutputMode.DexIndexed)
                    .build(),
            )
            done.writeText(label)
            dexFiles(out)
        } catch (e: CompilationFailedException) {
            if (!diagnostics.hasErrors) diagnostics.error("D8 failed for $label: ${e.message}")
            out.deleteRecursively()
            null
        }
    }

    private fun dexFiles(dir: File) = dir.listFiles().orEmpty().filter { it.name.endsWith(".dex") }.sortedWith(compareBy({ it.name.length }, { it.name }))
}

/** Non-class files from library JARs that belong in the APK (service files, Kotlin metadata, ...). */
object JavaResources {
    private fun skip(name: String): Boolean {
        val upper = name.uppercase()
        return name.endsWith(".class") || name.endsWith("/") ||
            upper == "META-INF/MANIFEST.MF" || upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")) ||
            upper.startsWith("META-INF/VERSIONS/") || upper.startsWith("META-INF/MAVEN/") || upper.endsWith("MODULE-INFO.CLASS") ||
            upper.startsWith("META-INF/PROGUARD/") || upper.startsWith("META-INF/COM.ANDROID.TOOLS/") ||
            upper == "META-INF/LICENSE" || upper == "META-INF/LICENSE.TXT" || upper == "META-INF/NOTICE" || upper == "META-INF/NOTICE.TXT" ||
            upper == "META-INF/DEPENDENCIES" || upper == "META-INF/INDEX.LIST"
    }

    /** APK path -> bytes; when two JARs carry the same path the first one wins. */
    fun collect(jars: List<File>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        jars.forEach { jar ->
            ZipFile(jar).use { z ->
                z.entries().asSequence().filter { !it.isDirectory && !skip(it.name) && !it.name.startsWith("res/") && !it.name.contains("..") }.forEach { e ->
                    if (!out.containsKey(e.name)) out[e.name] = z.getInputStream(e).use { it.readBytes() }
                }
            }
        }
        return out
    }
}

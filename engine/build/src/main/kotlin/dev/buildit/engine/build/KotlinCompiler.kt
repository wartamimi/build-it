package dev.buildit.engine.build

import dev.buildit.engine.deps.Coordinate
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Makes the Kotlin compiler's classes loadable. The compiler is not part of Build It: its JARs
 * (kotlin-compiler-embeddable and what it needs) are downloaded from Maven Central like a library.
 * On the JVM (tests) they are loaded as they are; on Android they are first converted to DEX
 * (see the app's host). The class loader must not see Build It's own classes.
 */
fun interface KotlinCompilerHost {
    fun classLoader(jars: List<File>, log: (String) -> Unit, isCancelled: () -> Boolean): ClassLoader

    /** Libraries the host needs next to the compiler (JDK APIs that its platform lacks). */
    val extraLibraries: List<Coordinate> get() = emptyList()
}

/** JVM host: the JARs as they are, with the platform class loader as parent. */
object JvmKotlinCompilerHost : KotlinCompilerHost {
    private val cache = HashMap<List<File>, ClassLoader>()

    @Synchronized
    override fun classLoader(jars: List<File>, log: (String) -> Unit, isCancelled: () -> Boolean): ClassLoader =
        cache.getOrPut(jars) { java.net.URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()) }
}

/**
 * Kotlin compilation for the on-device build (TECHNICAL_DECISIONS D22). Kotlin sources are compiled
 * first, with the Java sources passed along so both can refer to each other; ECJ then compiles the
 * Java sources against the Kotlin classes.
 */
object KotlinCompiler {
    const val VERSION = "2.4.21"
    val compiler = Coordinate("org.jetbrains.kotlin", "kotlin-compiler-embeddable", VERSION)
    val stdlib = Coordinate("org.jetbrains.kotlin", "kotlin-stdlib", VERSION)

    // e: file:///p/A.kt:3:5 message   |   /p/A.kt:3:5: error: message
    private val cliLine = Regex("^([ew]): (?:file://)?(.+?):(\\d+):(\\d+):? (.*)$")
    private val plainLine = Regex("^(?:file://)?(.+?):(\\d+):(\\d+): (error|warning): (.*)$")

    /**
     * Compiles [kotlinSources] (with [javaSources] visible) into [outDir]. [classpath] holds
     * android.jar first, then the libraries (kotlin-stdlib among them).
     */
    fun compile(
        loader: ClassLoader,
        kotlinSources: List<File>,
        javaSources: List<File>,
        classpath: List<File>,
        outDir: File,
        diagnostics: Diagnostics,
        relative: (File) -> String,
        moduleName: String = "app",
    ): Boolean {
        outDir.deleteRecursively(); outDir.mkdirs()
        val args = listOf(
            "-no-jdk", "-no-stdlib", "-no-reflect",
            "-jvm-target", "17",
            "-module-name", moduleName,
            "-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions",
            "-classpath", classpath.joinToString(File.pathSeparator) { it.path },
            "-d", outDir.path,
        ) + (kotlinSources + javaSources).map { it.path }
        val out = ByteArrayOutputStream()
        val exit = try {
            val cls = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", true, loader)
            val tool = cls.getDeclaredConstructor().newInstance()
            val exec = cls.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
            PrintStream(out, true, "UTF-8").use { ps -> exec.invoke(tool, ps, args.toTypedArray()) }.toString()
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException
            diagnostics.error("Kotlin compiler failed: ${cause.javaClass.name}: ${cause.message}")
            return false
        } catch (e: LinkageError) {
            diagnostics.error("Kotlin compiler could not start on this device: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        val text = out.toString("UTF-8")
        var matched = false
        text.lineSequence().forEach { l ->
            val cli = cliLine.matchEntire(l)
            val plain = if (cli == null) plainLine.matchEntire(l) else null
            val (path, ln, msg, error) = when {
                cli != null -> listOf(cli.groupValues[2], cli.groupValues[3], cli.groupValues[5], (cli.groupValues[1] == "e").toString())
                plain != null -> listOf(plain.groupValues[1], plain.groupValues[2], plain.groupValues[5], (plain.groupValues[4] == "error").toString())
                else -> return@forEach
            }
            matched = true
            if (error.toBoolean()) diagnostics.error(msg, relative(File(path)), ln.toInt()) else diagnostics.warning(msg, relative(File(path)), ln.toInt())
        }
        val ok = exit == "OK"
        if (!ok && !matched) {
            // An internal error: keep the exception and its first frames, without the compiler's notices.
            val detail = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("warning:") }.take(25).joinToString("\n").take(4000)
            diagnostics.error("Kotlin compilation failed ($exit): ${detail.ifEmpty { "no output" }}")
        }
        return ok
    }
}

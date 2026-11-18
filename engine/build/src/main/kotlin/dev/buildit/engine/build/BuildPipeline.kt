package dev.buildit.engine.build

import dev.buildit.engine.build.res.FrameworkResources
import dev.buildit.engine.build.res.LibraryManifest
import dev.buildit.engine.build.res.ManifestOptions
import dev.buildit.engine.build.res.ResSource
import dev.buildit.engine.build.res.ResourceCompiler
import dev.buildit.engine.deps.Coordinate
import dev.buildit.engine.deps.MavenCache
import dev.buildit.engine.deps.ResolveCancelledException
import dev.buildit.engine.deps.Resolver
import dev.buildit.engine.project.AppConfig
import java.io.File

enum class BuildStep { Check, Libraries, Resources, Compile, Dex, Package, Sign, Verify }

enum class BuildType { Debug, Release }

/** What to build and with what. [androidJar] must match the project's compileSdk where possible. */
data class BuildRequest(
    val projectDir: File,
    val app: AppConfig,
    val androidJar: File,
    val type: BuildType,
    val key: () -> SigningKey,
    /** Module directory inside the project (Build It projects have one application module). */
    val module: String = "app",
    /** Where prepared tool files (compiler stubs) are cached; defaults to the project's build dir. */
    val toolCache: File? = null,
    /** Debug builds connect to this console host (see [DebugBridge]); null leaves the bridge out. */
    val console: DebugBridge.Host? = null,
    /** Where libraries are downloaded and kept; needed when the project has libraries or Kotlin sources. */
    val maven: MavenCache? = null,
    /** Loads the Kotlin compiler; needed when the project has Kotlin sources. */
    val kotlin: KotlinCompilerHost? = null,
)

sealed interface BuildEvent {
    data class StepStarted(val step: BuildStep) : BuildEvent
    data class StepFinished(val step: BuildStep, val millis: Long) : BuildEvent
    data class Log(val text: String) : BuildEvent
}

class BuildResult(
    val success: Boolean,
    val apk: File?,
    val diagnostics: List<BuildDiagnostic>,
    val cancelled: Boolean = false,
    val failedStep: BuildStep? = null,
)

class BuildCancelledException : Exception("Build cancelled")

/**
 * The on-device build: check sources -> compile resources (Kotlin, no aapt2) -> generate R and
 * BuildConfig -> compile Kotlin (kotlinc, when there are .kt files) and Java (ECJ) -> dex (D8) ->
 * package -> sign (apksig) -> verify. Every step
 * is real; any failure stops the build with diagnostics pointing at project files.
 *
 * Outputs go to `<project>/build/buildit/`; the APK is `build/outputs/<name>-<type>.apk`.
 */
class BuildPipeline(private val frameworkCache: (File) -> FrameworkResources = ::cachedFramework) {

    fun run(request: BuildRequest, onEvent: (BuildEvent) -> Unit = {}, isCancelled: () -> Boolean = { false }): BuildResult {
        val d = Diagnostics()
        val project = request.projectDir
        val module = File(project, request.module)
        val main = File(module, "src/main")
        val work = File(project, "build/buildit/${request.type.name.lowercase()}")
        val app = request.app
        fun rel(f: File): String = f.absoluteFile.relativeToOrNull(project.absoluteFile)?.invariantSeparatorsPath ?: f.path
        var current = BuildStep.Check
        fun <T> step(s: BuildStep, block: () -> T): T {
            if (isCancelled()) throw BuildCancelledException()
            current = s
            onEvent(BuildEvent.StepStarted(s))
            val start = System.nanoTime()
            val r = block()
            onEvent(BuildEvent.StepFinished(s, (System.nanoTime() - start) / 1_000_000))
            return r
        }
        fun fail() = BuildResult(false, null, d.all, failedStep = current)

        return try {
            // 1. Check what the on-device builder supports before doing any work.
            var kotlinSources = emptyList<File>()
            val sources = step(BuildStep.Check) {
                val javaDirs = listOf(File(main, "java"), File(main, "kotlin")).filter { it.isDirectory }
                val all = javaDirs.flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
                kotlinSources = all.filter { it.name.endsWith(".kt") }
                if (kotlinSources.isNotEmpty() && (request.kotlin == null || request.maven == null)) {
                    d.error("This build has no Kotlin compiler configured", rel(kotlinSources.first()))
                }
                val declared = app.dependencies.filter { it.enabled }.map { it.coordinate }.toSet()
                dependencies(module).forEach { (dep, line, file) ->
                    if (dep !in declared) d.error("Dependency $dep is not in the project's library list: add it in the Libraries tab (Build It builds from that list)", rel(file), line)
                }
                app.dependencies.filter { it.enabled && Coordinate.parse(it.coordinate) == null }.forEach { d.error("Library '${it.coordinate}' is not a valid group:artifact:version") }
                if (!request.androidJar.isFile) d.error("Android platform (android.jar) is not installed")
                all.filter { it.name.endsWith(".java") }
            }
            if (d.hasErrors) return fail()
            onEvent(BuildEvent.Log("${sources.size} Java file(s)" + if (kotlinSources.isEmpty()) "" else ", ${kotlinSources.size} Kotlin file(s)"))

            // 1b. Libraries: resolve (from the local cache, downloading what is missing) and unpack.
            val toolCache = request.toolCache ?: File(project, "build/buildit/tools")
            val libraries = step(BuildStep.Libraries) {
                val declaredRoots = app.dependencies.filter { it.enabled }.mapNotNull { Coordinate.parse(it.coordinate) }
                // Kotlin code needs the Kotlin standard library at run time
                val roots = if (kotlinSources.isNotEmpty() && declaredRoots.none { it.module == KotlinCompiler.stdlib.module }) declaredRoots + KotlinCompiler.stdlib else declaredRoots
                if (roots.isEmpty()) return@step emptyList()
                val maven = request.maven ?: run { d.error("This build has no library cache configured"); return@step emptyList() }
                val resolution = try {
                    Resolver(maven).resolve(roots, progress = { onEvent(BuildEvent.Log(it)) }, isCancelled = isCancelled)
                } catch (_: ResolveCancelledException) { throw BuildCancelledException() }
                resolution.problems.forEach { d.error("Libraries: $it") }
                resolution.artifacts.map { a -> PreparedLibrary.prepare(a.coordinate.toString(), a.coordinate.module, a.file, toolCache) }
            }
            if (d.hasErrors) return fail()
            if (libraries.isNotEmpty()) onEvent(BuildEvent.Log("${libraries.size} librar${if (libraries.size == 1) "y" else "ies"}"))
            val libJars = libraries.flatMap { it.jars }

            // 2. Resources and manifest.
            val options = ManifestOptions(app.packageName, app.effectiveNamespace, app.versionCode, app.versionName, app.minSdk, app.targetSdk, request.type == BuildType.Debug,
                debugBridge = request.console.takeIf { request.type == BuildType.Debug })
            val resources = step(BuildStep.Resources) {
                val fw = frameworkCache(request.androidJar)
                val res = mutableListOf<ResSource>()
                // Lowest priority first: the last library resolved, ..., the first, then the app.
                libraries.asReversed().forEach { l -> l.res?.let { res += ResSource(it, l.label) } }
                File(module, "google-services.json").takeIf { it.isFile }?.let { json ->
                    val xml = GoogleServices.valuesXml(json, app.packageName, d, rel(json)) ?: return@step null
                    val dir = File(work, "generated/google-services/res").apply { deleteRecursively() }
                    File(dir, "values/google-services.xml").apply { parentFile.mkdirs() }.writeText(xml)
                    res += ResSource(dir, "google-services.json")
                    onEvent(BuildEvent.Log("google-services.json: Firebase values generated"))
                }
                res += ResSource(File(main, "res"))
                val modules = libraries.map { it.module }.toSet()
                ResourceCompiler(fw, d, ::rel, modules).compile(
                    res, File(main, "AndroidManifest.xml"),
                    libraries.mapNotNull { l -> l.manifest?.let { LibraryManifest(it, l.label) } },
                    options, libraries.mapNotNull { it.packageName },
                )
            } ?: return fail()
            onEvent(BuildEvent.Log("${resources.ids.size} resource(s), ${resources.files.size} file(s)"))

            // 3. Generated sources + Java.
            val classes = File(work, "classes")
            step(BuildStep.Compile) {
                val gen = File(work, "generated").apply { deleteRecursively() }
                val pkgDir = File(gen, app.effectiveNamespace.replace('.', '/')).apply { mkdirs() }
                File(pkgDir, "R.java").writeText(resources.rJava)
                File(pkgDir, "BuildConfig.java").writeText(buildConfig(app, request.type))
                val extra = mutableListOf(File(pkgDir, "R.java"), File(pkgDir, "BuildConfig.java"))
                resources.libraryRJava.forEach { (pkg, src) ->
                    extra += File(gen, pkg.replace('.', '/') + "/R.java").apply { parentFile.mkdirs(); writeText(src) }
                }
                if (options.debugBridge != null) {
                    val bridge = File(gen, DebugBridge.CLASS.replace('.', '/') + ".java").apply { parentFile.mkdirs() }
                    bridge.writeText(DebugBridge.source(options.debugBridge))
                    extra += bridge
                }
                classes.deleteRecursively()
                if (kotlinSources.isNotEmpty()) {
                    val resolution = try {
                        Resolver(request.maven!!).resolve(listOf(KotlinCompiler.compiler) + request.kotlin!!.extraLibraries, progress = { onEvent(BuildEvent.Log(it)) }, isCancelled = isCancelled)
                    } catch (_: ResolveCancelledException) { throw BuildCancelledException() }
                    resolution.problems.forEach { d.error("Kotlin compiler: $it") }
                    if (d.hasErrors) return@step false
                    onEvent(BuildEvent.Log("Kotlin ${KotlinCompiler.VERSION}: preparing the compiler"))
                    val loader = request.kotlin!!.classLoader(resolution.artifacts.map { it.file }, { onEvent(BuildEvent.Log(it)) }, isCancelled)
                    if (isCancelled()) throw BuildCancelledException()
                    onEvent(BuildEvent.Log("Compiling ${kotlinSources.size} Kotlin file(s)"))
                    if (!KotlinCompiler.compile(loader, kotlinSources, sources + extra, listOf(request.androidJar) + libJars, classes, d, ::rel)) return@step false
                }
                val stubs = LambdaStubs.classes(request.androidJar, request.toolCache ?: File(project, "build/buildit/tools"))
                JavaCompiler.compile(sources + extra, request.androidJar, classes, d, ::rel, stubs, libJars + if (kotlinSources.isNotEmpty()) listOf(classes) else emptyList())
            }.let { ok -> if (!ok || d.hasErrors) return fail() }

            // 4. Dex.
            val dex = File(work, "dex")
            val libraryDex = mutableListOf<File>()
            step(BuildStep.Dex) {
                if (!Dexer.dex(classes, request.androidJar, app.minSdk, request.type == BuildType.Debug, dex, d, libJars)) return@step false
                libraries.forEach { l ->
                    l.jars.forEach { jar ->
                        if (isCancelled()) throw BuildCancelledException()
                        libraryDex += LibraryDexer.dex(jar, request.androidJar, app.minSdk, request.type == BuildType.Debug, toolCache, d, l.label) ?: return@step false
                    }
                }
                true
            }.let { ok -> if (!ok) return fail() }

            // 5. Package.
            val unsigned = File(work, "unsigned.apk")
            step(BuildStep.Package) {
                val native = LinkedHashMap<String, File>()
                (listOf(File(main, "jniLibs")) + libraries.mapNotNull { it.jni }).filter { it.isDirectory }.forEach { dir ->
                    dir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { f ->
                        native.putIfAbsent("lib/" + f.relativeTo(dir).invariantSeparatorsPath, f)
                    }
                }
                ApkWriter.write(
                    unsigned, resources.manifest, resources.table, resources.files, dex, File(main, "assets"),
                    libraryDex = libraryDex, javaResources = JavaResources.collect(libJars), nativeLibs = native,
                    libraryAssets = libraries.mapNotNull { it.assets },
                )
            }

            // 6. Sign.
            val name = File(project.name).name.ifBlank { "app" }
            val apk = File(project, "build/outputs/$name-${request.type.name.lowercase()}.apk")
            step(BuildStep.Sign) {
                apk.parentFile.mkdirs()
                val tmp = File(apk.path + ".tmp")
                ApkSigning.sign(unsigned, tmp, request.key(), app.minSdk)
                apk.delete()
                if (!tmp.renameTo(apk)) { tmp.copyTo(apk, overwrite = true); tmp.delete() }
            }

            // 7. Verify the signature exactly as the platform would.
            step(BuildStep.Verify) {
                ApkSigning.verify(apk, app.minSdk).forEach { d.error("Signature check: $it") }
            }
            if (d.hasErrors) return fail()
            onEvent(BuildEvent.Log("${apk.name}: ${apk.length() / 1024} KB"))
            BuildResult(true, apk, d.all)
        } catch (_: BuildCancelledException) {
            BuildResult(false, null, d.all, cancelled = true, failedStep = current)
        } catch (e: Exception) {
            d.error("${current.name} failed: ${e.message ?: e.javaClass.simpleName}")
            fail()
        } catch (e: OutOfMemoryError) {
            d.error("${current.name} ran out of memory; close other apps and try again")
            fail()
        }
    }

    private fun buildConfig(app: AppConfig, type: BuildType) = """
        |/* Generated by Build It. Do not edit. */
        |package ${app.effectiveNamespace};
        |
        |public final class BuildConfig {
        |    private BuildConfig() {}
        |    public static final boolean DEBUG = ${type == BuildType.Debug};
        |    public static final String APPLICATION_ID = "${app.packageName}";
        |    public static final String BUILD_TYPE = "${type.name.lowercase()}";
        |    public static final int VERSION_CODE = ${app.versionCode};
        |    public static final String VERSION_NAME = "${app.versionName.replace("\\", "\\\\").replace("\"", "\\\"")}";
        |}
        |""".trimMargin()

    /** Library dependencies declared in the module's Gradle file, with their line. */
    private fun dependencies(module: File): List<Triple<String, Int, File>> {
        val file = listOf("build.gradle.kts", "build.gradle").map { File(module, it) }.firstOrNull { it.isFile } ?: return emptyList()
        val decl = Regex("^\\s*(implementation|api|compileOnly|runtimeOnly|annotationProcessor|kapt|ksp)\\b\\s*\\(?\\s*(.+?)\\s*\\)?\\s*$")
        return file.readLines().mapIndexedNotNull { i, l ->
            val m = decl.find(l.substringBefore("//")) ?: return@mapIndexedNotNull null
            Triple(m.groupValues[2].trim('"', '\'', ' '), i + 1, file)
        }
    }

    companion object {
        private val cache = HashMap<String, FrameworkResources>()

        /** Parsing the framework table takes ~0.5 s; keep it per android.jar for the process. */
        @Synchronized fun cachedFramework(jar: File): FrameworkResources {
            val key = "${jar.absolutePath}:${jar.length()}:${jar.lastModified()}"
            return cache.getOrPut(key) { FrameworkResources.load(jar) }
        }
    }
}

package dev.buildit.engine.build

import dev.buildit.engine.build.res.FrameworkResources
import dev.buildit.engine.build.res.ManifestOptions
import dev.buildit.engine.build.res.ResourceCompiler
import dev.buildit.engine.project.AppConfig
import java.io.File

enum class BuildStep { Check, Resources, Compile, Dex, Package, Sign, Verify }

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
 * BuildConfig -> compile Java (ECJ) -> dex (D8) -> package -> sign (apksig) -> verify. Every step
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
            val sources = step(BuildStep.Check) {
                val javaDirs = listOf(File(main, "java"), File(main, "kotlin")).filter { it.isDirectory }
                val all = javaDirs.flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
                all.filter { it.name.endsWith(".kt") }.forEach {
                    d.error("Kotlin sources are not supported by the on-device builder yet; use Java for this project or build it with Android Studio", rel(it))
                }
                dependencies(module).forEach { (dep, line, file) ->
                    d.error("Dependency $dep: the on-device builder does not resolve libraries yet", rel(file), line)
                }
                if (!request.androidJar.isFile) d.error("Android platform (android.jar) is not installed")
                if (File(main, "jniLibs").isDirectory) d.warning("Native libraries in jniLibs are not packaged yet", rel(File(main, "jniLibs")))
                all.filter { it.name.endsWith(".java") }
            }
            if (d.hasErrors) return fail()
            onEvent(BuildEvent.Log("${sources.size} Java file(s)"))

            // 2. Resources and manifest.
            val options = ManifestOptions(app.packageName, app.effectiveNamespace, app.versionCode, app.versionName, app.minSdk, app.targetSdk, request.type == BuildType.Debug)
            val resources = step(BuildStep.Resources) {
                val fw = frameworkCache(request.androidJar)
                ResourceCompiler(fw, d, ::rel).compile(File(main, "res"), File(main, "AndroidManifest.xml"), options)
            } ?: return fail()
            onEvent(BuildEvent.Log("${resources.ids.size} resource(s), ${resources.files.size} file(s)"))

            // 3. Generated sources + Java.
            val classes = File(work, "classes")
            step(BuildStep.Compile) {
                val gen = File(work, "generated").apply { deleteRecursively() }
                val pkgDir = File(gen, app.effectiveNamespace.replace('.', '/')).apply { mkdirs() }
                File(pkgDir, "R.java").writeText(resources.rJava)
                File(pkgDir, "BuildConfig.java").writeText(buildConfig(app, request.type))
                classes.deleteRecursively()
                val stubs = LambdaStubs.classes(request.androidJar, request.toolCache ?: File(project, "build/buildit/tools"))
                JavaCompiler.compile(sources + listOf(File(pkgDir, "R.java"), File(pkgDir, "BuildConfig.java")), request.androidJar, classes, d, ::rel, stubs)
            }.let { ok -> if (!ok || d.hasErrors) return fail() }

            // 4. Dex.
            val dex = File(work, "dex")
            step(BuildStep.Dex) { Dexer.dex(classes, request.androidJar, app.minSdk, request.type == BuildType.Debug, dex, d) }
                .let { ok -> if (!ok) return fail() }

            // 5. Package.
            val unsigned = File(work, "unsigned.apk")
            step(BuildStep.Package) { ApkWriter.write(unsigned, resources.manifest, resources.table, resources.files, dex, File(main, "assets")) }

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

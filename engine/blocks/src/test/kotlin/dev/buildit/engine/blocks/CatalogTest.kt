package dev.buildit.engine.blocks

import dev.buildit.engine.project.SourceLanguage
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.Properties
import javax.tools.ToolProvider

/**
 * Puts every block of the registry into one document with default fields and inputs, then checks
 * it validates and that the generated Java and Kotlin compile against the real android.jar.
 */
class CatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val catalogViews = listOf(
        ViewInfo("label", "TextView"), ViewInfo("agree", "CheckBox"), ViewInfo("photo", "ImageView"), ViewInfo("level", "SeekBar"),
    )
    private val catalogCtx = BlockContext(catalogViews, activities = listOf("MainActivity", "SettingsActivity"), sounds = listOf("ding"), images = listOf("logo"))

    private fun everyBlockDocument(): BlockDocument {
        val ids = Ids()
        val variables = listOf(
            Variable("name", ValueType.Text), Variable("count", ValueType.Number), Variable("done", ValueType.Boolean),
            Variable("items", ValueType.TextList), Variable("numbers", ValueType.NumberList), Variable("table", ValueType.TextMap),
        )
        val components = ComponentType.entries.map { Component("c" + it.name, it) }
        fun create(type: String) = BlockRegistry.create(type, ids::next, variables, catalogViews, components, sounds = catalogCtx.sounds, images = catalogCtx.images)
            .let { block ->
                val def = BlockRegistry[type]
                // Collection inputs have no default literal: plug in a variable of that type
                val inputs = block.inputs + def.inputs.filter { it.name !in block.inputs }
                    .associate { i -> i.name to get(ids.next(), variables.first { it.type == i.type }.name) }
                val fields = when (type) {
                    "proc_call" -> mapOf("name" to "doSomething")
                    "app_start", "intent_set_screen" -> block.fields + ("activity" to "SettingsActivity")
                    "code_stmt" -> mapOf("code" to "done = true;")
                    "code_expr" -> mapOf("code" to "count")
                    else -> block.fields
                }
                block.copy(inputs = inputs, fields = fields)
            }
        fun inLoop(block: Block) = create("ctrl_repeat").copy(statements = mapOf("do" to listOf(block)))

        val body = mutableListOf<Block>()
        val hats = mutableListOf<Block>()
        BlockRegistry.all.forEach { def ->
            when (def.shape) {
                BlockShape.Hat -> if (def.type == "proc_define") {
                    fun param(name: String) = create("app_log").copy(inputs = mapOf("text" to create("proc_param").copy(fields = mapOf("param" to name))))
                    hats += create(def.type).copy(
                        fields = mapOf("name" to "doSomething", "params" to "n:number,t:text,flag:boolean,words:textList"),
                        statements = mapOf("do" to listOf(param("n"), param("t"), param("flag"), param("words"))),
                    )
                } else if (def.type != "event_create") {
                    // Component events: read every value the event provides
                    val params = Components.event(def.type)?.second?.params.orEmpty()
                    val reads = params.map { p -> create("app_log").copy(inputs = mapOf("text" to create("proc_param").copy(fields = mapOf("param" to p.name)))) }
                    hats += create(def.type).let { h -> if (reads.isEmpty()) h else h.copy(statements = mapOf("do" to reads)) }
                }
                BlockShape.Statement -> body += create(def.type).let { if (def.terminal) inLoop(it) else it }
                BlockShape.Value -> if (def.type != "proc_param") body += create("app_log").copy(inputs = mapOf("text" to create(def.type)))
            }
        }
        val stacks = (listOf(create("event_create").copy(statements = mapOf("do" to body))) + hats)
            .mapIndexed { i, h -> BlockStack(0f, i * 300f, listOf(h)) }
        val doc = BlockDocument(activity = "MainActivity", variables = variables, stacks = stacks, components = components)
        return Procedures.syncCalls(doc, "doSomething", ids::next).let { d ->
            // the list parameter has no default literal: pass the list variable
            val call = d.allBlocks().first { it.type == "proc_call" }
            BlockOps.update(d, call.id) { it.copy(inputs = it.inputs + ("arg_words" to get(ids.next(), "items"))) }
        }
    }

    private fun generate(language: SourceLanguage): String {
        val r = BlockSync.generateCode(manifest(language), everyBlockDocument(), catalogCtx)
        assertTrue(r.toString(), r is BlockSync.Result.Generated)
        return (r as BlockSync.Result.Generated).code
    }

    @Test fun everyBlockValidates() {
        val errors = BlockValidator.validate(everyBlockDocument(), catalogCtx).filter { it.severity == BlockDiagnostic.Severity.Error }
        assertEquals(emptyList<BlockDiagnostic>(), errors)
    }

    @Test fun templatesUseOnlyTheirOwnPlaceholders() {
        val re = Regex("\\{(\\w+)\\^?\\}")
        BlockRegistry.all.filter { it.code != null }.forEach { def ->
            val names = (def.inputs.map { it.name } + def.fields.map { it.name }).toSet()
            listOf(def.code!!.java, def.code.kotlin).forEach { tpl ->
                re.findAll(tpl).map { it.groupValues[1] }.forEach { assertTrue("${def.type}: unknown {$it}", it in names) }
            }
        }
    }

    @Test fun procedureParametersBecomeArguments() {
        val java = generate(SourceLanguage.Java)
        assertTrue(java, java.contains("private void runDoSomething(final double n, final String t, final boolean flag, ArrayList<String> words) {"))
        assertTrue(java, java.contains("runDoSomething(0.0, \"\", true, items);"))
        val kotlin = generate(SourceLanguage.Kotlin)
        assertTrue(kotlin, kotlin.contains("private fun runDoSomething(n: Double, t: String, flag: Boolean, words: ArrayList<String>) {"))
    }

    @Test fun removedParametersKeepTheirBlocks() {
        val ids = Ids()
        val doc = BlockDocument(
            activity = "MainActivity",
            stacks = listOf(
                BlockStack(0f, 0f, listOf(b("d", "proc_define", mapOf("name" to "go", "params" to "a:number")))),
                BlockStack(0f, 200f, listOf(b("e", "event_create", statements = mapOf("do" to listOf(
                    b("c", "proc_call", mapOf("name" to "go"), mapOf("arg_a" to num("n", 7.0))),
                ))))),
            ),
        )
        val removed = Procedures.syncCalls(BlockOps.setField(doc, "d", "params", "b:text"), "go", ids::next)
        val call = removed.find("c")!!
        assertEquals(setOf("arg_b"), call.inputs.keys)
        assertTrue(removed.stacks.any { s -> s.blocks.single().id == "n" })
        assertEquals("param_outside_procedure", BlockValidator.validate(BlockOps.insert(removed, listOf(b("p", "proc_param", mapOf("param" to "a"))), Slot.Value("c", "arg_b")), catalogCtx)
            .first { it.blockId == "p" }.code)
    }

    @Test fun loopOnlyAndUnreachableBlocksAreErrors() {
        val ids = Ids()
        val doc = BlockDocument(
            activity = "MainActivity",
            stacks = listOf(BlockStack(0f, 0f, listOf(b("e", "event_create", statements = mapOf("do" to listOf(
                b("brk", "ctrl_break"),
                b("ret", "ctrl_return"),
                BlockRegistry.create("app_finish", ids::next).copy(id = "after"),
            )))))),
        )
        val codes = BlockValidator.validate(doc, catalogCtx).associate { it.blockId to it.code }
        assertEquals("outside_loop", codes["brk"])
        assertEquals("unreachable", codes["ret"])
        assertEquals("unreachable", codes["after"])
    }

    @Test fun everyBlockCompilesAsJava() {
        val androidJar = androidJarOrNull()
        assumeTrue("android.jar not found (set ANDROID_HOME)", androidJar != null)
        val compiler = ToolProvider.getSystemJavaCompiler()
        assumeTrue("no system Java compiler", compiler != null)
        val pkg = File(tmp.newFolder("java"), "com/example/demo").apply { mkdirs() }
        File(pkg, "MainActivity.java").writeText(generate(SourceLanguage.Java))
        File(pkg, "SettingsActivity.java").writeText("package com.example.demo; public class SettingsActivity extends android.app.Activity {}")
        File(pkg, "R.java").writeText(
            """
            package com.example.demo;
            public final class R {
                public static final class layout { public static final int activity_main = 1; }
                public static final class raw { public static final int ding = 2; }
                public static final class drawable { public static final int logo = 3; }
                public static final class id { public static final int label = 4, agree = 5, photo = 6, level = 7; }
            }
            """.trimIndent(),
        )
        val out = tmp.newFolder("javaOut")
        val errors = ByteArrayOutputStream()
        val files = pkg.listFiles()!!.map { it.path }
        val rc = compiler!!.run(null, null, errors, "-classpath", androidJar!!.path, "-d", out.path, "--release", "17", *files.toTypedArray())
        assertEquals(errors.toString() + "\n" + File(pkg, "MainActivity.java").readText(), 0, rc)
    }

    @Test fun everyBlockCompilesAsKotlin() {
        val androidJar = androidJarOrNull()
        assumeTrue("android.jar not found (set ANDROID_HOME)", androidJar != null)
        val src = tmp.newFolder("kotlin")
        File(src, "MainActivity.kt").writeText(generate(SourceLanguage.Kotlin))
        File(src, "Support.kt").writeText(
            """
            package com.example.demo
            class SettingsActivity : android.app.Activity()
            object R {
                object layout { const val activity_main = 1 }
                object raw { const val ding = 2 }
                object drawable { const val logo = 3 }
                object id { const val label = 4; const val agree = 5; const val photo = 6; const val level = 7 }
            }
            """.trimIndent(),
        )
        val stdlib = File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI())
        val out = tmp.newFolder("kotlinOut")
        val errors = ByteArrayOutputStream()
        val rc = K2JVMCompiler().exec(
            PrintStream(errors), "-no-stdlib", "-no-reflect", "-jvm-target", "17",
            "-classpath", androidJar!!.path + File.pathSeparator + stdlib.path, "-d", out.path, src.path,
        )
        assertEquals(errors.toString() + "\n" + File(src, "MainActivity.kt").readText(), ExitCode.OK, rc)
    }
}

internal fun androidJarOrNull(): File? {
    val roots = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).toMutableList()
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        val lp = File(dir, "local.properties")
        if (lp.isFile) Properties().apply { lp.inputStream().use(::load) }.getProperty("sdk.dir")?.let(roots::add)
        dir = dir.parentFile
    }
    return roots.asSequence().map { File(it, "platforms") }.filter { it.isDirectory }
        .flatMap { p -> p.listFiles().orEmpty().sortedDescending().map { File(it, "android.jar") } }
        .firstOrNull { it.isFile }
}

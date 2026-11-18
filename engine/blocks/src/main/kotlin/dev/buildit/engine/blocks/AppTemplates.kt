package dev.buildit.engine.blocks

import dev.buildit.engine.project.ModuleConfig
import dev.buildit.engine.project.ProjectManifest
import dev.buildit.engine.project.ProjectTemplate
import dev.buildit.engine.project.ProjectTemplates
import dev.buildit.engine.project.SourceLanguage
import java.io.File

/**
 * Small ready-made apps to start from. Each is an ordinary project: a layout the designer edits and
 * a block program the blocks editor edits, with the activity source generated from the blocks the
 * same way as when the user saves them. Nothing here is special-cased later.
 */
object AppTemplates {
    enum class Kind(val id: String) { Counter("counter"), Calculator("calculator"), Todo("todo"), Notes("notes"), Stopwatch("stopwatch"), Dice("dice") }

    fun of(kind: Kind, language: SourceLanguage): ProjectTemplate = BlockAppTemplate(kind, language)

    /** The empty project first, then every app, for [language]. */
    fun forLanguage(language: SourceLanguage): List<ProjectTemplate> =
        listOf(if (language == SourceLanguage.Java) ProjectTemplates.EmptyActivity else ProjectTemplates.EmptyActivityKotlin) +
            Kind.entries.map { of(it, language) }

    fun kindOf(t: ProjectTemplate): Kind? = (t as? BlockAppTemplate)?.kind

    /** The layout and blocks of an app (also used by tests). */
    fun content(kind: Kind): Pair<String, BlockDocument> {
        val b = Builder()
        return when (kind) {
            Kind.Counter -> layout(
                text("number", "0", size = 48),
                row(button("minus", "−"), button("plus", "+")),
                button("reset", "Reset", style = Style.Outlined),
            ) to b.doc(
                listOf(Variable("count", ValueType.Number)), emptyList(),
                b.click("plus", b.change("count", 1.0), b.show("number", b.v("count"))),
                b.click("minus", b.change("count", -1.0), b.show("number", b.v("count"))),
                b.click("reset", b.set("count", b.num(0.0)), b.show("number", b.v("count"))),
            )
            Kind.Calculator -> layout(
                edit("first", "First number", number = true),
                edit("second", "Second number", number = true),
                row(button("add", "+"), button("subtract", "−"), button("multiply", "×"), button("divide", "÷")),
                text("result", "Result", size = 28),
            ) to b.doc(
                emptyList(), emptyList(),
                *listOf("add" to "+", "subtract" to "−", "multiply" to "×", "divide" to "÷").map { (id, op) ->
                    b.click(
                        id,
                        b.ifElse(
                            b.and(b.isNumber(b.textOf("first")), b.isNumber(b.textOf("second"))),
                            listOf(b.show("result", b.join(b.lit("= "), b.arith(b.parse(b.textOf("first")), op, b.parse(b.textOf("second")))))),
                            listOf(b.toast(b.lit("Enter two numbers"))),
                        ),
                    )
                }.toTypedArray(),
            )
            Kind.Todo -> layout(
                row(edit("input", "New task", weight = true), button("add", "Add")),
                list("tasks"),
                text("hint", "Tap a task to remove it", size = 12),
            ) to b.doc(
                listOf(Variable("items", ValueType.TextList)),
                listOf(Component("store", ComponentType.Storage)),
                b.create(
                    b.ifThen(b.not(b.isEmpty(b.load("store", "tasks"))), b.setList("items", b.split(b.load("store", "tasks"), "\n"))),
                    b.setItems("tasks", "items"),
                ),
                b.click(
                    "add",
                    b.ifThen(
                        b.not(b.isEmpty(b.trim(b.textOf("input")))),
                        b.addItem("items", b.trim(b.textOf("input"))),
                        b.show("input", b.lit("")),
                        b.refresh("tasks"),
                        b.save("store", "tasks", b.joinList("items", "\n")),
                    ),
                ),
                b.itemClick(
                    "tasks",
                    b.removeItem("items", b.param("position")),
                    b.refresh("tasks"),
                    b.save("store", "tasks", b.joinList("items", "\n")),
                    b.toast(b.join(b.lit("Done: "), b.param("item"))),
                ),
            )
            Kind.Notes -> layout(
                edit("note", "Write something…", multiLine = true),
                button("save", "Save"),
            ) to b.doc(
                emptyList(),
                listOf(Component("store", ComponentType.Storage)),
                b.create(b.show("note", b.load("store", "note"))),
                b.click("save", b.save("store", "note", b.textOf("note")), b.toast(b.lit("Saved"))),
                b.pause(b.save("store", "note", b.textOf("note"))),
            )
            Kind.Stopwatch -> layout(
                text("time", "0.0 s", size = 48),
                row(button("start", "Start"), button("stop", "Stop", style = Style.Outlined), button("reset", "Reset", style = Style.Outlined)),
            ) to b.doc(
                listOf(Variable("tenths", ValueType.Number)),
                listOf(Component("ticker", ComponentType.Timer)),
                b.click("start", b.stopTimer("ticker"), b.startTimer("ticker", 100.0)),
                b.click("stop", b.stopTimer("ticker")),
                b.click("reset", b.stopTimer("ticker"), b.set("tenths", b.num(0.0)), b.show("time", b.lit("0.0 s"))),
                b.tick("ticker", b.change("tenths", 1.0), b.show("time", b.join(b.format(b.arith(b.v("tenths"), "÷", b.num(10.0)), 1.0), b.lit(" s")))),
            )
            Kind.Dice -> layout(
                text("face", "🎲", size = 72),
                text("total", "Tap Roll", size = 16),
                button("roll", "Roll"),
            ) to b.doc(
                listOf(Variable("rolled", ValueType.Number), Variable("rolls", ValueType.Number)), emptyList(),
                b.click(
                    "roll",
                    b.set("rolled", b.random(1.0, 6.0)),
                    b.change("rolls", 1.0),
                    b.show("face", b.v("rolled")),
                    b.show("total", b.join(b.lit("Rolls: "), b.v("rolls"))),
                ),
            )
        }
    }

    // ---- layout ----

    private enum class Style { Filled, Outlined }

    private fun layout(vararg children: String): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"""")
        appendLine("""    android:layout_width="match_parent"""")
        appendLine("""    android:layout_height="match_parent"""")
        appendLine("""    android:gravity="center_horizontal"""")
        appendLine("""    android:orientation="vertical"""")
        appendLine("""    android:padding="16dp">""")
        children.forEach { appendLine(); append(it.prependIndent("    ")); appendLine() }
        appendLine()
        appendLine("</LinearLayout>")
    }

    private fun attrs(vararg a: Pair<String, String>) = a.joinToString("\n") { (k, v) -> """    android:$k="${esc(v)}"""" }

    private fun text(id: String, value: String, size: Int) =
        "<TextView\n" + attrs("id" to "@+id/$id", "layout_width" to "wrap_content", "layout_height" to "wrap_content", "layout_margin" to "8dp", "text" to value, "textSize" to "${size}sp") + " />"

    private fun button(id: String, label: String, style: Style = Style.Filled) =
        "<Button\n" + attrs("id" to "@+id/$id", "layout_width" to "wrap_content", "layout_height" to "wrap_content", "layout_margin" to "4dp", "text" to label) +
            (if (style == Style.Outlined) "\n    android:alpha=\"0.85\"" else "") + " />"

    private fun edit(id: String, hint: String, number: Boolean = false, multiLine: Boolean = false, weight: Boolean = false): String {
        val a = mutableListOf("id" to "@+id/$id", "layout_width" to if (weight) "0dp" else "match_parent", "layout_height" to if (multiLine) "0dp" else "wrap_content", "hint" to hint)
        if (weight || multiLine) a += "layout_weight" to "1"
        when {
            number -> a += "inputType" to "numberDecimal|numberSigned"
            multiLine -> { a += "inputType" to "textMultiLine|textCapSentences"; a += "gravity" to "top" }
            else -> a += "inputType" to "textCapSentences"
        }
        return "<EditText\n" + attrs(*a.toTypedArray()) + " />"
    }

    private fun list(id: String) =
        "<ListView\n" + attrs("id" to "@+id/$id", "layout_width" to "match_parent", "layout_height" to "0dp", "layout_weight" to "1") + " />"

    private fun row(vararg children: String) = buildString {
        appendLine("<LinearLayout")
        appendLine(attrs("layout_width" to "match_parent", "layout_height" to "wrap_content", "gravity" to "center", "orientation" to "horizontal") + ">")
        children.forEach { appendLine(); appendLine(it.prependIndent("    ")) }
        appendLine()
        append("</LinearLayout>")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    /** Views in a template layout, for the block context. */
    internal fun views(layout: String): List<ViewInfo> =
        Regex("""<([A-Za-z.]+)\s[^>]*?android:id="@\+id/([a-z_0-9]+)"""").findAll(layout).map { ViewInfo(it.groupValues[2], it.groupValues[1]) }.toList()

    // ---- blocks ----

    private class Builder {
        private var n = 0
        private fun id() = "t${++n}"
        private var y = 40f

        fun doc(variables: List<Variable>, components: List<Component>, vararg stacks: Block) =
            BlockDocument(activity = "MainActivity", variables = variables, components = components, stacks = stacks.map { b -> BlockStack(40f, y.also { y += 220f }, listOf(b)) })

        private fun hat(type: String, fields: Map<String, String>, body: List<Block>) = Block(id(), type, fields, statements = mapOf("do" to body))
        fun create(vararg body: Block) = hat("event_create", emptyMap(), body.toList())
        fun pause(vararg body: Block) = hat("event_pause", emptyMap(), body.toList())
        fun click(view: String, vararg body: Block) = hat("event_click", mapOf("view" to view), body.toList())
        fun tick(timer: String, vararg body: Block) = hat("event_timer", mapOf("timer" to timer), body.toList())
        fun itemClick(view: String, vararg body: Block) = hat("event_item_clicked", mapOf("view" to view), body.toList())

        fun num(v: Double) = Block(id(), "math_number", mapOf("num" to if (v == Math.rint(v)) v.toLong().toString() else v.toString()))
        fun lit(s: String) = Block(id(), "text_literal", mapOf("text" to s))
        fun v(name: String) = Block(id(), "var_get", mapOf("var" to name))
        fun param(name: String) = Block(id(), "proc_param", mapOf("param" to name))
        fun set(name: String, value: Block) = Block(id(), "var_set", mapOf("var" to name), inputs = mapOf("value" to value))
        fun change(name: String, by: Double) = Block(id(), "var_change", mapOf("var" to name), inputs = mapOf("by" to num(by)))
        fun show(view: String, text: Block) = Block(id(), "view_set_text", mapOf("view" to view), inputs = mapOf("text" to text))
        fun textOf(view: String) = Block(id(), "view_get_text", mapOf("view" to view))
        fun toast(text: Block) = Block(id(), "app_toast", inputs = mapOf("text" to text))
        fun join(a: Block, b: Block) = Block(id(), "text_join", inputs = mapOf("a" to a, "b" to b))
        fun trim(t: Block) = Block(id(), "text_trim", inputs = mapOf("text" to t))
        fun arith(a: Block, op: String, b: Block) = Block(id(), "math_arith", mapOf("op" to op), inputs = mapOf("a" to a, "b" to b))
        fun parse(t: Block) = Block(id(), "math_parse", inputs = mapOf("text" to t))
        fun format(a: Block, digits: Double) = Block(id(), "math_format", inputs = mapOf("a" to a, "digits" to num(digits)))
        fun random(from: Double, to: Double) = Block(id(), "math_random", inputs = mapOf("from" to num(from), "to" to num(to)))
        fun isNumber(t: Block) = Block(id(), "logic_is_number", inputs = mapOf("text" to t))
        fun isEmpty(t: Block) = Block(id(), "logic_is_empty", inputs = mapOf("text" to t))
        fun not(a: Block) = Block(id(), "logic_not", inputs = mapOf("a" to a))
        fun and(a: Block, b: Block) = Block(id(), "logic_op", mapOf("op" to "and"), inputs = mapOf("a" to a, "b" to b))
        fun ifThen(cond: Block, vararg then: Block) = Block(id(), "ctrl_if", inputs = mapOf("cond" to cond), statements = mapOf("then" to then.toList()))
        fun ifElse(cond: Block, then: List<Block>, otherwise: List<Block>) = Block(id(), "ctrl_if_else", inputs = mapOf("cond" to cond), statements = mapOf("then" to then, "else" to otherwise))
        fun addItem(list: String, item: Block) = Block(id(), "list_add", mapOf("list" to list), inputs = mapOf("item" to item))
        fun removeItem(list: String, index: Block) = Block(id(), "list_remove", mapOf("list" to list), inputs = mapOf("index" to index))
        fun setList(list: String, items: Block) = Block(id(), "list_set_all", mapOf("list" to list), inputs = mapOf("items" to items))
        fun split(t: Block, sep: String) = Block(id(), "text_split", inputs = mapOf("text" to t, "separator" to lit(sep)))
        fun joinList(list: String, sep: String) = Block(id(), "text_join_list", mapOf("list" to list), inputs = mapOf("separator" to lit(sep)))
        fun setItems(view: String, list: String) = Block(id(), "view_set_items", mapOf("view" to view, "list" to list))
        fun refresh(view: String) = Block(id(), "view_refresh_items", mapOf("view" to view))
        fun save(store: String, key: String, value: Block) = Block(id(), "storage_set", mapOf("store" to store), inputs = mapOf("value" to value, "key" to lit(key)))
        fun load(store: String, key: String) = Block(id(), "storage_get", mapOf("store" to store), inputs = mapOf("key" to lit(key)))
        fun startTimer(timer: String, ms: Double) = Block(id(), "timer_start", mapOf("timer" to timer, "mode" to "repeating"), inputs = mapOf("ms" to num(ms)))
        fun stopTimer(timer: String) = Block(id(), "timer_stop", mapOf("timer" to timer))
    }
}

private class BlockAppTemplate(val kind: AppTemplates.Kind, override val language: SourceLanguage) : ProjectTemplate {
    override val id: String = "app_${kind.id}_${language.name.lowercase()}"
    override val module: ModuleConfig = ModuleConfig.defaultApp()

    override fun generate(projectDir: File, manifest: ProjectManifest) {
        (if (language == SourceLanguage.Java) ProjectTemplates.EmptyActivity else ProjectTemplates.EmptyActivityKotlin).generate(projectDir, manifest)
        val (layout, doc) = AppTemplates.content(kind)
        File(projectDir, "app/src/main/res/layout/activity_main.xml").writeText(layout)
        BlockStore(projectDir).save(doc)
        val ctx = BlockContext(AppTemplates.views(layout), listOf("MainActivity"))
        when (val r = BlockSync.write(projectDir, manifest, doc, ctx, force = true)) {
            is BlockSync.Result.Generated -> Unit
            is BlockSync.Result.Invalid -> error("Template ${kind.id} has invalid blocks: ${r.diagnostics.joinToString { "${it.code} at ${it.blockId}" }}")
            is BlockSync.Result.UserModified -> error("unreachable")
        }
    }
}

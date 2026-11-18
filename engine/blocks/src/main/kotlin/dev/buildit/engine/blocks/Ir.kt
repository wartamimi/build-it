package dev.buildit.engine.blocks

/**
 * Language-neutral intermediate representation. Lowering resolves types, inserts conversions and
 * collects what the program uses (views, helpers); the Java and Kotlin backends only print it.
 */
sealed interface Expr {
    val type: ValueType
}

data class BoolLit(val value: Boolean) : Expr { override val type = ValueType.Boolean }
data class NumLit(val value: Double) : Expr { override val type = ValueType.Number }
data class TextLit(val value: String) : Expr { override val type = ValueType.Text }
data class VarRef(val name: String, override val type: ValueType) : Expr

enum class BinOp(val symbol: String, val result: ValueType) {
    Add("+", ValueType.Number), Sub("-", ValueType.Number), Mul("*", ValueType.Number), Div("/", ValueType.Number), Mod("%", ValueType.Number),
    Eq("==", ValueType.Boolean), Ne("!=", ValueType.Boolean), Lt("<", ValueType.Boolean), Le("<=", ValueType.Boolean),
    Gt(">", ValueType.Boolean), Ge(">=", ValueType.Boolean), And("&&", ValueType.Boolean), Or("||", ValueType.Boolean),
}

data class Binary(val op: BinOp, val a: Expr, val b: Expr) : Expr { override val type = op.result }
data class Not(val a: Expr) : Expr { override val type = ValueType.Boolean }
data class TextEquals(val a: Expr, val b: Expr) : Expr { override val type = ValueType.Boolean }
/** Converts a non-text value to text (numbers print without a trailing ".0" when whole). */
data class ToText(val a: Expr) : Expr { override val type = ValueType.Text }
data class Concat(val a: Expr, val b: Expr) : Expr { override val type = ValueType.Text }
data class TextLength(val a: Expr) : Expr { override val type = ValueType.Number }
data class RandomInt(val from: Expr, val to: Expr) : Expr { override val type = ValueType.Number }
data class Round(val a: Expr) : Expr { override val type = ValueType.Number }
data class ParseNumber(val a: Expr) : Expr { override val type = ValueType.Number }
/** 1-based item access, as shown on the block. */
data class ListGet(val list: String, val index: Expr) : Expr { override val type = ValueType.Text }
data class ListSize(val list: String) : Expr { override val type = ValueType.Number }
data class ViewText(val view: String) : Expr { override val type = ValueType.Text }
data class ViewChecked(val view: String) : Expr { override val type = ValueType.Boolean }
data class RawExpr(val code: String) : Expr { override val type = ValueType.Text }
data class StorageGet(val store: String, val key: Expr) : Expr { override val type = ValueType.Text }

sealed interface Stmt
data class If(val cond: Expr, val then: List<Stmt>, val otherwise: List<Stmt>?) : Stmt
data class Repeat(val times: Expr, val body: List<Stmt>, val counter: String) : Stmt
data class While(val cond: Expr, val body: List<Stmt>) : Stmt
data class ForRange(val variable: String, val from: Expr, val to: Expr, val body: List<Stmt>) : Stmt
data class SetVar(val name: String, val value: Expr) : Stmt
data class ChangeVar(val name: String, val by: Expr) : Stmt
data class ListAdd(val list: String, val item: Expr) : Stmt
data class ListClear(val list: String) : Stmt
data class SetViewText(val view: String, val text: Expr) : Stmt
data class SetVisibility(val view: String, val visibility: String) : Stmt
data class SetEnabled(val view: String, val value: Expr) : Stmt
data class SetChecked(val view: String, val value: Expr) : Stmt
data class SetTextColor(val view: String, val color: String) : Stmt
data class Toast(val text: Expr) : Stmt
data class Log(val text: Expr) : Stmt
data class StartActivity(val activity: String) : Stmt
data object Finish : Stmt
data class CallProcedure(val name: String) : Stmt
data class RawStmt(val code: String) : Stmt
data class TimerStart(val timer: String, val delayMs: Expr, val repeating: Boolean) : Stmt
data class TimerStop(val timer: String) : Stmt
data class StorageSet(val store: String, val key: Expr, val value: Expr) : Stmt
data class StorageRemove(val store: String, val key: Expr) : Stmt
data class DialogShow(val dialog: String, val title: Expr, val message: Expr) : Stmt
data class SoundPlay(val sound: String, val file: String) : Stmt
data class SoundStop(val sound: String) : Stmt
data class OpenLink(val url: Expr) : Stmt

enum class Lifecycle(val method: String) { Resume("onResume"), Pause("onPause"), Destroy("onDestroy") }
enum class Helper { FormatNumber, RandomInt, ParseNumber }

data class ViewField(val id: String, val className: String)
data class Handler(val view: String, val body: List<Stmt>)
data class Procedure(val name: String, val body: List<Stmt>)

data class Program(
    val packageName: String,
    val className: String,
    val layout: String,
    val variables: List<Variable>,
    val views: List<ViewField>,
    val onCreate: List<Stmt>,
    val lifecycle: Map<Lifecycle, List<Stmt>>,
    val clicks: List<Handler>,
    val checkedChanges: List<Handler>,
    val procedures: List<Procedure>,
    val helpers: Set<Helper>,
    val components: List<Component> = emptyList(),
    /** Component events: timer ticks, dialog OK, sound finished (the handler's view is the component name). */
    val componentEvents: Map<String, List<Stmt>> = emptyMap(),
)

data class ActivityTarget(val packageName: String, val className: String, val layout: String)

/** Turns a validated [BlockDocument] into a [Program]. Disabled blocks and loose stacks are skipped. */
class Lowering private constructor(private val doc: BlockDocument, private val ctx: BlockContext) {
    private val usedViews = sortedSetOf<String>()
    private val helpers = mutableSetOf<Helper>()
    private var counter = 0

    companion object {
        /** Component event hats and the field naming their component. */
        val COMPONENT_EVENTS = mapOf("event_timer" to "timer", "event_dialog_ok" to "dialog", "event_sound_done" to "sound")

        fun lower(doc: BlockDocument, ctx: BlockContext, target: ActivityTarget): Program {
            require(!BlockValidator.hasErrors(BlockValidator.validate(doc, ctx))) { "Document has errors; validate before lowering" }
            return Lowering(doc, ctx).program(target)
        }
    }

    private fun program(target: ActivityTarget): Program {
        val hats = doc.stacks.mapNotNull { s -> s.blocks.firstOrNull()?.takeIf { BlockRegistry.find(it.type)?.shape == BlockShape.Hat && !it.disabled } }
        fun body(b: Block) = stmts(b.statements["do"].orEmpty())
        val onCreate = hats.filter { it.type == "event_create" }.flatMap(::body)
        val lifecycle = buildMap {
            hats.firstOrNull { it.type == "event_resume" }?.let { put(Lifecycle.Resume, body(it)) }
            hats.firstOrNull { it.type == "event_pause" }?.let { put(Lifecycle.Pause, body(it)) }
            hats.firstOrNull { it.type == "event_destroy" }?.let { put(Lifecycle.Destroy, body(it)) }
        }
        val clicks = hats.filter { it.type == "event_click" }.map { Handler(view(it.field("view")), body(it)) }.sortedBy { it.view }
        val checks = hats.filter { it.type == "event_checked" }.map { Handler(view(it.field("view")), body(it)) }.sortedBy { it.view }
        val procs = hats.filter { it.type == "proc_define" }.map { Procedure(it.field("name"), body(it)) }.sortedBy { it.name }
        val componentEvents = hats.filter { it.type in COMPONENT_EVENTS }.associate { h ->
            h.field(COMPONENT_EVENTS.getValue(h.type)) to body(h)
        }
        val tags = ctx.views.associate { it.id to it.tag }
        return Program(
            packageName = target.packageName,
            className = target.className,
            layout = target.layout,
            variables = doc.variables,
            views = usedViews.map { ViewField(it, ViewFamilies.className(tags.getValue(it))) },
            onCreate = onCreate,
            lifecycle = lifecycle,
            clicks = clicks,
            checkedChanges = checks,
            procedures = procs,
            helpers = helpers,
            components = doc.components,
            componentEvents = componentEvents,
        )
    }

    private fun view(id: String): String { usedViews += id; return id }

    private fun stmts(list: List<Block>): List<Stmt> = list.filterNot { it.disabled }.map(::stmt)

    private fun stmt(b: Block): Stmt = when (b.type) {
        "ctrl_if" -> If(expr(b, "cond"), stmts(b.statements["then"].orEmpty()), null)
        "ctrl_if_else" -> If(expr(b, "cond"), stmts(b.statements["then"].orEmpty()), stmts(b.statements["else"].orEmpty()))
        "ctrl_repeat" -> Repeat(expr(b, "times"), stmts(b.statements["do"].orEmpty()), "i${++counter}")
        "ctrl_while" -> While(expr(b, "cond"), stmts(b.statements["do"].orEmpty()))
        "ctrl_for" -> ForRange(b.field("var"), expr(b, "from"), expr(b, "to"), stmts(b.statements["do"].orEmpty()))
        "proc_call" -> CallProcedure(b.field("name"))
        "var_set" -> {
            val v = doc.variables.first { it.name == b.field("var") }
            SetVar(v.name, if (v.type == ValueType.Text) text(b, "value") else expr(b, "value"))
        }
        "var_change" -> ChangeVar(b.field("var"), expr(b, "by"))
        "list_add" -> ListAdd(b.field("list"), text(b, "item"))
        "list_clear" -> ListClear(b.field("list"))
        "view_set_text" -> SetViewText(view(b.field("view")), text(b, "text"))
        "view_set_visibility" -> SetVisibility(view(b.field("view")), b.field("visibility"))
        "view_set_enabled" -> SetEnabled(view(b.field("view")), expr(b, "enabled"))
        "view_set_checked" -> SetChecked(view(b.field("view")), expr(b, "checked"))
        "view_set_text_color" -> SetTextColor(view(b.field("view")), b.field("color"))
        "app_toast" -> Toast(text(b, "text"))
        "app_log" -> Log(text(b, "text"))
        "app_start" -> StartActivity(b.field("activity"))
        "app_finish" -> Finish
        "code_stmt" -> RawStmt(b.field("code"))
        "timer_start" -> TimerStart(b.field("timer"), expr(b, "ms"), b.field("mode") == "repeating")
        "timer_stop" -> TimerStop(b.field("timer"))
        "storage_set" -> StorageSet(b.field("store"), text(b, "key"), text(b, "value"))
        "storage_remove" -> StorageRemove(b.field("store"), text(b, "key"))
        "dialog_show" -> DialogShow(b.field("dialog"), text(b, "title"), text(b, "message"))
        "sound_play" -> SoundPlay(b.field("sound"), b.field("file"))
        "sound_stop" -> SoundStop(b.field("sound"))
        "app_open_link" -> OpenLink(text(b, "url"))
        else -> error("Not a statement block: ${b.type}")
    }

    private fun expr(parent: Block, input: String): Expr = value(parent.inputs.getValue(input))

    /** Expression for a text input: any value is accepted and converted. */
    private fun text(parent: Block, input: String): Expr = toText(expr(parent, input))

    private fun toText(e: Expr): Expr = when (e.type) {
        ValueType.Text -> e
        ValueType.Number -> { helpers += Helper.FormatNumber; ToText(e) }
        else -> ToText(e)
    }

    private fun value(b: Block): Expr = when (b.type) {
        "logic_bool" -> BoolLit(b.field("value") == "true")
        "logic_compare" -> Binary(
            when (b.field("op")) { "=" -> BinOp.Eq; "≠" -> BinOp.Ne; "<" -> BinOp.Lt; "≤" -> BinOp.Le; ">" -> BinOp.Gt; else -> BinOp.Ge },
            expr(b, "a"), expr(b, "b"),
        )
        "logic_text_equals" -> TextEquals(text(b, "a"), text(b, "b"))
        "logic_op" -> Binary(if (b.field("op") == "and") BinOp.And else BinOp.Or, expr(b, "a"), expr(b, "b"))
        "logic_not" -> Not(expr(b, "a"))
        "math_number" -> NumLit(b.field("num").toDouble())
        "math_arith" -> Binary(
            when (b.field("op")) { "+" -> BinOp.Add; "−" -> BinOp.Sub; "×" -> BinOp.Mul; "÷" -> BinOp.Div; else -> BinOp.Mod },
            expr(b, "a"), expr(b, "b"),
        )
        "math_random" -> { helpers += Helper.RandomInt; RandomInt(expr(b, "from"), expr(b, "to")) }
        "math_round" -> Round(expr(b, "a"))
        "math_parse" -> { helpers += Helper.ParseNumber; ParseNumber(text(b, "text")) }
        "text_literal" -> TextLit(b.field("text"))
        "text_join" -> Concat(text(b, "a"), text(b, "b"))
        "text_length" -> TextLength(text(b, "text"))
        "var_get" -> doc.variables.first { it.name == b.field("var") }.let { VarRef(it.name, it.type) }
        "list_get" -> ListGet(b.field("list"), expr(b, "index"))
        "list_size" -> ListSize(b.field("list"))
        "view_get_text" -> ViewText(view(b.field("view")))
        "view_is_checked" -> ViewChecked(view(b.field("view")))
        "code_expr" -> RawExpr(b.field("code"))
        "storage_get" -> StorageGet(b.field("store"), text(b, "key"))
        else -> error("Not a value block: ${b.type}")
    }
}

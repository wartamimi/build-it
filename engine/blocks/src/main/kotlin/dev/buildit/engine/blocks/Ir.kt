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

/** A field value substituted into a [CodeTemplate]: code as is, or text printed as a string literal. */
sealed interface TField {
    data class Raw(val code: String) : TField
    data class Str(val text: String) : TField
}

/** A value block lowered from its [CodeTemplate]. */
data class TemplateExpr(val code: CodeTemplate, val inputs: Map<String, Expr>, val fields: Map<String, TField>, override val type: ValueType) : Expr

sealed interface Stmt
/** A statement block lowered from its [CodeTemplate]. */
data class TemplateStmt(val code: CodeTemplate, val inputs: Map<String, Expr>, val fields: Map<String, TField>) : Stmt
/** Runs [body] for each item of a copy of [list] (so the body may change the list), storing it in [variable]. */
data class ForEach(val variable: String, val list: String, val elementType: ValueType, val body: List<Stmt>, val item: String) : Stmt
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
data class CallProcedure(val name: String, val args: List<Expr> = emptyList()) : Stmt
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
data class Procedure(val name: String, val body: List<Stmt>, val params: List<ProcParam> = emptyList())

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
    /** Bodies of component event hats, by "hat type|component name". */
    val componentEvents: Map<String, List<Stmt>> = emptyMap(),
    /** Classes needed by template blocks. */
    val imports: Set<String> = emptySet(),
    /** Helper methods needed by template blocks, by name. */
    val templateHelpers: List<TemplateHelper> = emptyList(),
)

data class ActivityTarget(val packageName: String, val className: String, val layout: String)

/** Turns a validated [BlockDocument] into a [Program]. Disabled blocks and loose stacks are skipped. */
class Lowering private constructor(private val doc: BlockDocument, private val ctx: BlockContext) {
    private val usedViews = sortedSetOf<String>()
    private val helpers = mutableSetOf<Helper>()
    private val imports = sortedSetOf<String>()
    private val templateHelpers = sortedMapOf<String, TemplateHelper>()
    private var counter = 0

    companion object {

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
        val procs = hats.filter { it.type == "proc_define" }.map { Procedure(it.field("name"), body(it), Procedures.parse(it.field("params")).orEmpty()) }.sortedBy { it.name }
        val componentEvents = hats.mapNotNull { h -> Components.event(h.type)?.let { (spec, _) -> "${h.type}|${h.field(spec.field)}" to body(h) } }.toMap()
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
            imports = imports,
            templateHelpers = templateHelpers.values.toList(),
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
        "proc_call" -> CallProcedure(b.field("name"), Procedures.params(doc, b.field("name")).map { p ->
            val input = Procedures.INPUT_PREFIX + p.name
            if (p.type == ValueType.Text) text(b, input) else expr(b, input)
        })
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
        "ctrl_foreach", "ctrl_foreach_number" -> ForEach(
            b.field("var"), b.field("list"), if (b.type == "ctrl_foreach") ValueType.Text else ValueType.Number,
            stmts(b.statements["do"].orEmpty()), "item${++counter}",
        )
        else -> template(b)?.let { (code, inputs, fields) -> TemplateStmt(code, inputs, fields) } ?: error("Not a statement block: ${b.type}")
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
        "proc_param" -> VarRef(b.field("param"), BlockOps.outputType(doc, b)!!)
        "list_get" -> ListGet(b.field("list"), expr(b, "index"))
        "list_size" -> ListSize(b.field("list"))
        "view_get_text" -> ViewText(view(b.field("view")))
        "view_is_checked" -> ViewChecked(view(b.field("view")))
        "code_expr" -> RawExpr(b.field("code"))
        "storage_get" -> StorageGet(b.field("store"), text(b, "key"))
        else -> template(b)?.let { (code, inputs, fields) -> TemplateExpr(code, inputs, fields, BlockRegistry[b.type].output!!) }
            ?: error("Not a value block: ${b.type}")
    }

    /** Inputs (text inputs converted to text) and field values of a template block, registering what it uses. */
    private fun template(b: Block): Triple<CodeTemplate, Map<String, Expr>, Map<String, TField>>? {
        val def = BlockRegistry.find(b.type) ?: return null
        val code = def.code ?: return null
        imports += def.imports
        def.helper?.let { templateHelpers[it.name] = it }
        val inputs = def.inputs.associate { i -> i.name to if (i.type == ValueType.Text) text(b, i.name) else expr(b, i.name) }
        val fields = def.fields.associate { f ->
            val v = b.field(f.name)
            f.name to when (f.kind) {
                is FieldKind.View -> TField.Raw(view(v))
                is FieldKind.Text, is FieldKind.Color -> TField.Str(v)
                is FieldKind.Number -> TField.Raw(JavaBackend.num(v.toDouble()))
                else -> TField.Raw(v)
            }
        }
        return Triple(code, inputs, fields)
    }
}

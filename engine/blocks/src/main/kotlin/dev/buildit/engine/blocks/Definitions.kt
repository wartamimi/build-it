package dev.buildit.engine.blocks

enum class BlockCategory { Events, Control, Logic, Math, Text, Variables, Lists, Components, App, Code }

/** How a block connects: event hats start a stack, statements chain, values plug into inputs. */
enum class BlockShape { Hat, Statement, Value }

/** Which views a view field may reference, by widget family. */
enum class ViewKind { Any, Text, Compound }

sealed interface FieldKind {
    data class Text(val default: String = "") : FieldKind
    data class Number(val default: String = "0") : FieldKind
    data class Dropdown(val options: List<String>, val default: String = options.first()) : FieldKind
    data class View(val kind: ViewKind = ViewKind.Any) : FieldKind
    data class VariableRef(val type: ValueType? = null) : FieldKind
    /** Name of a procedure: a free identifier in `define`, a reference in `call`. */
    data class Procedure(val defines: Boolean) : FieldKind
    data object Activity : FieldKind
    data object Code : FieldKind
}

data class FieldDef(val name: String, val kind: FieldKind)
data class InputDef(val name: String, val type: ValueType)
data class StatementDef(val name: String, val label: String)

/**
 * Declarative description of a block type. [message] is the block's label with `{name}`
 * placeholders for fields and value inputs, in display order; statement inputs follow as C-shaped
 * sections labelled by [StatementDef.label].
 */
data class BlockDef(
    val type: String,
    val category: BlockCategory,
    val shape: BlockShape,
    val message: String,
    val fields: List<FieldDef> = emptyList(),
    val inputs: List<InputDef> = emptyList(),
    val statements: List<StatementDef> = emptyList(),
    /** Output type for value blocks; null when it depends on the referenced variable (`var_get`). */
    val output: ValueType? = null,
) {
    fun fieldDef(name: String) = fields.firstOrNull { it.name == name }
    fun inputDef(name: String) = inputs.firstOrNull { it.name == name }

    /** [message] split into literal text and `{placeholder}` parts. */
    val parts: List<MessagePart> by lazy { parse(message) }

    /**
     * Splits a message template (this block's own, or a translation of it) into parts. Unknown
     * placeholders are kept as labels so a bad translation can't hide inputs silently.
     */
    fun parse(template: String): List<MessagePart> {
        val out = mutableListOf<MessagePart>()
        val re = Regex("\\{(\\w+)}")
        var last = 0
        re.findAll(template).forEach { m ->
            if (m.range.first > last) out += MessagePart.Label(template.substring(last, m.range.first).trim())
            val n = m.groupValues[1]
            out += when {
                inputDef(n) != null -> MessagePart.Input(n)
                fieldDef(n) != null -> MessagePart.Field(n)
                else -> MessagePart.Label(m.value)
            }
            last = m.range.last + 1
        }
        if (last < template.length) out += MessagePart.Label(template.substring(last).trim())
        return out.filterNot { it is MessagePart.Label && it.text.isEmpty() }
    }

    /** True when [template] uses exactly this block's placeholders (for validating translations). */
    fun matches(template: String): Boolean {
        val names = Regex("\\{(\\w+)}").findAll(template).map { it.groupValues[1] }.toList()
        return names.sorted() == (fields.map { it.name } + inputs.map { it.name }).sorted()
    }
}

sealed interface MessagePart {
    data class Label(val text: String) : MessagePart
    data class Field(val name: String) : MessagePart
    data class Input(val name: String) : MessagePart
}

object BlockRegistry {
    private val compareOps = listOf("=", "≠", "<", "≤", ">", "≥")
    private val arithOps = listOf("+", "−", "×", "÷", "mod")

    val all: List<BlockDef> = listOf(
        // Events
        BlockDef("event_create", BlockCategory.Events, BlockShape.Hat, "when screen is created", statements = listOf(StatementDef("do", ""))),
        BlockDef("event_resume", BlockCategory.Events, BlockShape.Hat, "when screen resumes", statements = listOf(StatementDef("do", ""))),
        BlockDef("event_pause", BlockCategory.Events, BlockShape.Hat, "when screen pauses", statements = listOf(StatementDef("do", ""))),
        BlockDef("event_destroy", BlockCategory.Events, BlockShape.Hat, "when screen is destroyed", statements = listOf(StatementDef("do", ""))),
        BlockDef("event_click", BlockCategory.Events, BlockShape.Hat, "when {view} is clicked", fields = listOf(FieldDef("view", FieldKind.View())), statements = listOf(StatementDef("do", ""))),
        BlockDef("event_checked", BlockCategory.Events, BlockShape.Hat, "when {view} is checked or unchecked", fields = listOf(FieldDef("view", FieldKind.View(ViewKind.Compound))), statements = listOf(StatementDef("do", ""))),
        BlockDef("proc_define", BlockCategory.Events, BlockShape.Hat, "define procedure {name}", fields = listOf(FieldDef("name", FieldKind.Procedure(defines = true))), statements = listOf(StatementDef("do", ""))),

        // Control
        BlockDef("ctrl_if", BlockCategory.Control, BlockShape.Statement, "if {cond}", inputs = listOf(InputDef("cond", ValueType.Boolean)), statements = listOf(StatementDef("then", "then"))),
        BlockDef("ctrl_if_else", BlockCategory.Control, BlockShape.Statement, "if {cond}", inputs = listOf(InputDef("cond", ValueType.Boolean)), statements = listOf(StatementDef("then", "then"), StatementDef("else", "else"))),
        BlockDef("ctrl_repeat", BlockCategory.Control, BlockShape.Statement, "repeat {times} times", inputs = listOf(InputDef("times", ValueType.Number)), statements = listOf(StatementDef("do", "do"))),
        BlockDef("ctrl_while", BlockCategory.Control, BlockShape.Statement, "repeat while {cond}", inputs = listOf(InputDef("cond", ValueType.Boolean)), statements = listOf(StatementDef("do", "do"))),
        BlockDef("ctrl_for", BlockCategory.Control, BlockShape.Statement, "count with {var} from {from} to {to}",
            fields = listOf(FieldDef("var", FieldKind.VariableRef(ValueType.Number))),
            inputs = listOf(InputDef("from", ValueType.Number), InputDef("to", ValueType.Number)), statements = listOf(StatementDef("do", "do"))),
        BlockDef("proc_call", BlockCategory.Control, BlockShape.Statement, "call {name}", fields = listOf(FieldDef("name", FieldKind.Procedure(defines = false)))),

        // Logic
        BlockDef("logic_bool", BlockCategory.Logic, BlockShape.Value, "{value}", fields = listOf(FieldDef("value", FieldKind.Dropdown(listOf("true", "false")))), output = ValueType.Boolean),
        BlockDef("logic_compare", BlockCategory.Logic, BlockShape.Value, "{a} {op} {b}", fields = listOf(FieldDef("op", FieldKind.Dropdown(compareOps))),
            inputs = listOf(InputDef("a", ValueType.Number), InputDef("b", ValueType.Number)), output = ValueType.Boolean),
        BlockDef("logic_text_equals", BlockCategory.Logic, BlockShape.Value, "{a} is same text as {b}", inputs = listOf(InputDef("a", ValueType.Text), InputDef("b", ValueType.Text)), output = ValueType.Boolean),
        BlockDef("logic_op", BlockCategory.Logic, BlockShape.Value, "{a} {op} {b}", fields = listOf(FieldDef("op", FieldKind.Dropdown(listOf("and", "or")))),
            inputs = listOf(InputDef("a", ValueType.Boolean), InputDef("b", ValueType.Boolean)), output = ValueType.Boolean),
        BlockDef("logic_not", BlockCategory.Logic, BlockShape.Value, "not {a}", inputs = listOf(InputDef("a", ValueType.Boolean)), output = ValueType.Boolean),

        // Math
        BlockDef("math_number", BlockCategory.Math, BlockShape.Value, "{num}", fields = listOf(FieldDef("num", FieldKind.Number("0"))), output = ValueType.Number),
        BlockDef("math_arith", BlockCategory.Math, BlockShape.Value, "{a} {op} {b}", fields = listOf(FieldDef("op", FieldKind.Dropdown(arithOps))),
            inputs = listOf(InputDef("a", ValueType.Number), InputDef("b", ValueType.Number)), output = ValueType.Number),
        BlockDef("math_random", BlockCategory.Math, BlockShape.Value, "random integer from {from} to {to}", inputs = listOf(InputDef("from", ValueType.Number), InputDef("to", ValueType.Number)), output = ValueType.Number),
        BlockDef("math_round", BlockCategory.Math, BlockShape.Value, "round {a}", inputs = listOf(InputDef("a", ValueType.Number)), output = ValueType.Number),
        BlockDef("math_parse", BlockCategory.Math, BlockShape.Value, "number from text {text}", inputs = listOf(InputDef("text", ValueType.Text)), output = ValueType.Number),

        // Text
        BlockDef("text_literal", BlockCategory.Text, BlockShape.Value, "\"{text}\"", fields = listOf(FieldDef("text", FieldKind.Text(""))), output = ValueType.Text),
        BlockDef("text_join", BlockCategory.Text, BlockShape.Value, "join {a} {b}", inputs = listOf(InputDef("a", ValueType.Text), InputDef("b", ValueType.Text)), output = ValueType.Text),
        BlockDef("text_length", BlockCategory.Text, BlockShape.Value, "length of {text}", inputs = listOf(InputDef("text", ValueType.Text)), output = ValueType.Number),

        // Variables
        BlockDef("var_get", BlockCategory.Variables, BlockShape.Value, "{var}", fields = listOf(FieldDef("var", FieldKind.VariableRef()))),
        BlockDef("var_set", BlockCategory.Variables, BlockShape.Statement, "set {var} to {value}", fields = listOf(FieldDef("var", FieldKind.VariableRef())), inputs = listOf(InputDef("value", ValueType.Text))),
        BlockDef("var_change", BlockCategory.Variables, BlockShape.Statement, "change {var} by {by}", fields = listOf(FieldDef("var", FieldKind.VariableRef(ValueType.Number))), inputs = listOf(InputDef("by", ValueType.Number))),

        // Lists (of text)
        BlockDef("list_add", BlockCategory.Lists, BlockShape.Statement, "add {item} to {list}", fields = listOf(FieldDef("list", FieldKind.VariableRef(ValueType.TextList))), inputs = listOf(InputDef("item", ValueType.Text))),
        BlockDef("list_get", BlockCategory.Lists, BlockShape.Value, "item # {index} of {list}", fields = listOf(FieldDef("list", FieldKind.VariableRef(ValueType.TextList))), inputs = listOf(InputDef("index", ValueType.Number)), output = ValueType.Text),
        BlockDef("list_size", BlockCategory.Lists, BlockShape.Value, "size of {list}", fields = listOf(FieldDef("list", FieldKind.VariableRef(ValueType.TextList))), output = ValueType.Number),
        BlockDef("list_clear", BlockCategory.Lists, BlockShape.Statement, "clear {list}", fields = listOf(FieldDef("list", FieldKind.VariableRef(ValueType.TextList)))),

        // Components
        BlockDef("view_set_text", BlockCategory.Components, BlockShape.Statement, "set text of {view} to {text}", fields = listOf(FieldDef("view", FieldKind.View(ViewKind.Text))), inputs = listOf(InputDef("text", ValueType.Text))),
        BlockDef("view_get_text", BlockCategory.Components, BlockShape.Value, "text of {view}", fields = listOf(FieldDef("view", FieldKind.View(ViewKind.Text))), output = ValueType.Text),
        BlockDef("view_set_visibility", BlockCategory.Components, BlockShape.Statement, "make {view} {visibility}",
            fields = listOf(FieldDef("view", FieldKind.View()), FieldDef("visibility", FieldKind.Dropdown(listOf("visible", "invisible", "gone"))))),
        BlockDef("view_set_enabled", BlockCategory.Components, BlockShape.Statement, "set {view} enabled to {enabled}", fields = listOf(FieldDef("view", FieldKind.View())), inputs = listOf(InputDef("enabled", ValueType.Boolean))),
        BlockDef("view_is_checked", BlockCategory.Components, BlockShape.Value, "{view} is checked", fields = listOf(FieldDef("view", FieldKind.View(ViewKind.Compound))), output = ValueType.Boolean),
        BlockDef("view_set_checked", BlockCategory.Components, BlockShape.Statement, "set {view} checked to {checked}", fields = listOf(FieldDef("view", FieldKind.View(ViewKind.Compound))), inputs = listOf(InputDef("checked", ValueType.Boolean))),
        BlockDef("view_set_text_color", BlockCategory.Components, BlockShape.Statement, "set text color of {view} to {color}", fields = listOf(FieldDef("view", FieldKind.View(ViewKind.Text)), FieldDef("color", FieldKind.Text("#FF000000")))),

        // App
        BlockDef("app_toast", BlockCategory.App, BlockShape.Statement, "show message {text}", inputs = listOf(InputDef("text", ValueType.Text))),
        BlockDef("app_log", BlockCategory.App, BlockShape.Statement, "write to log {text}", inputs = listOf(InputDef("text", ValueType.Text))),
        BlockDef("app_start", BlockCategory.App, BlockShape.Statement, "open screen {activity}", fields = listOf(FieldDef("activity", FieldKind.Activity))),
        BlockDef("app_finish", BlockCategory.App, BlockShape.Statement, "close this screen"),

        // Custom code
        BlockDef("code_stmt", BlockCategory.Code, BlockShape.Statement, "code {code}", fields = listOf(FieldDef("code", FieldKind.Code))),
        BlockDef("code_expr", BlockCategory.Code, BlockShape.Value, "text from code {code}", fields = listOf(FieldDef("code", FieldKind.Code)), output = ValueType.Text),
    )

    private val byType = all.associateBy { it.type }

    fun find(type: String): BlockDef? = byType[type]
    operator fun get(type: String): BlockDef = byType[type] ?: error("Unknown block type $type")

    /**
     * A new block of [type] with default field values and default literal blocks in its value
     * inputs, so freshly added blocks are valid wherever possible.
     */
    fun create(type: String, ids: () -> String, variables: List<Variable> = emptyList(), views: List<ViewInfo> = emptyList()): Block {
        val def = get(type)
        val fields = def.fields.associate { f ->
            f.name to when (val k = f.kind) {
                is FieldKind.Text -> k.default
                is FieldKind.Number -> k.default
                is FieldKind.Dropdown -> k.default
                is FieldKind.View -> views.firstOrNull { ViewFamilies.matches(k.kind, it.tag) }?.id.orEmpty()
                is FieldKind.VariableRef -> variables.firstOrNull { k.type == null || it.type == k.type }?.name.orEmpty()
                is FieldKind.Procedure -> if (k.defines) "doSomething" else ""
                FieldKind.Activity -> ""
                FieldKind.Code -> ""
            }
        }
        val inputs = def.inputs.associate { i -> i.name to defaultLiteral(i.type, ids) }
        return Block(ids(), type, fields, inputs.filterValues { it != null }.mapValues { it.value!! })
    }

    private fun defaultLiteral(t: ValueType, ids: () -> String): Block? = when (t) {
        ValueType.Boolean -> Block(ids(), "logic_bool", mapOf("value" to "true"))
        ValueType.Number -> Block(ids(), "math_number", mapOf("num" to "0"))
        ValueType.Text -> Block(ids(), "text_literal", mapOf("text" to ""))
        ValueType.TextList -> null
    }
}

/** A view in the activity's layout that blocks can reference. */
data class ViewInfo(val id: String, val tag: String)

object ViewFamilies {
    private val text = setOf(
        "TextView", "Button", "EditText", "CheckBox", "RadioButton", "Switch", "ToggleButton", "CheckedTextView", "AutoCompleteTextView",
        "com.google.android.material.button.MaterialButton", "com.google.android.material.textfield.TextInputEditText",
        "com.google.android.material.checkbox.MaterialCheckBox", "com.google.android.material.materialswitch.MaterialSwitch",
        "androidx.appcompat.widget.AppCompatTextView", "androidx.appcompat.widget.AppCompatButton", "androidx.appcompat.widget.AppCompatEditText",
    )
    private val compound = setOf(
        "CheckBox", "RadioButton", "Switch", "ToggleButton",
        "com.google.android.material.checkbox.MaterialCheckBox", "com.google.android.material.materialswitch.MaterialSwitch",
    )

    fun matches(kind: ViewKind, tag: String): Boolean = when (kind) {
        ViewKind.Any -> true
        ViewKind.Text -> tag in text
        ViewKind.Compound -> tag in compound
    }

    /** Fully qualified class for a layout tag (framework short names live in android.widget/android.view/android.webkit). */
    fun className(tag: String): String = when {
        '.' in tag -> tag
        tag == "View" || tag == "ViewStub" -> "android.view.$tag"
        tag == "WebView" -> "android.webkit.WebView"
        else -> "android.widget.$tag"
    }
}

package dev.buildit.engine.blocks

data class BlockDiagnostic(
    /** Block the problem is anchored to; null for document-level problems (e.g. a variable). */
    val blockId: String?,
    val severity: Severity,
    val code: String,
    val detail: String = "",
) {
    enum class Severity { Error, Warning }
}

/** What the blocks of one activity can refer to. */
data class BlockContext(
    val views: List<ViewInfo>,
    val activities: List<String>,
    /** Sound files in res/raw, by resource name. */
    val sounds: List<String> = emptyList(),
)

/**
 * Checks a document before code generation. Errors block generation; warnings don't. Every
 * diagnostic is anchored to a block id so the editor can badge the block.
 */
object BlockValidator {
    private val colorPattern = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

    fun validate(doc: BlockDocument, ctx: BlockContext): List<BlockDiagnostic> = buildList {
        fun err(id: String?, code: String, detail: String = "") = add(BlockDiagnostic(id, BlockDiagnostic.Severity.Error, code, detail))
        fun warn(id: String?, code: String, detail: String = "") = add(BlockDiagnostic(id, BlockDiagnostic.Severity.Warning, code, detail))

        // Variables
        val seenVars = mutableSetOf<String>()
        val viewIds = ctx.views.map { it.id }.toSet()
        doc.variables.forEach { v ->
            if (!Names.isIdentifier(v.name)) err(null, "invalid_variable", v.name)
            if (!seenVars.add(v.name)) err(null, "duplicate_variable", v.name)
            if (v.name in viewIds) err(null, "variable_clashes_view", v.name)
        }
        val seenComponents = mutableSetOf<String>()
        doc.components.forEach { c ->
            if (!Names.isIdentifier(c.name)) err(null, "invalid_component", c.name)
            if (!seenComponents.add(c.name) || c.name in seenVars) err(null, "duplicate_component", c.name)
            if (c.name in viewIds) err(null, "component_clashes_view", c.name)
        }
        val procedures = doc.stacks.mapNotNull { s -> s.blocks.firstOrNull()?.takeIf { it.type == "proc_define" } }
        val procNames = procedures.map { it.field("name") }.toSet()

        // Top-level structure and duplicate events
        val eventKeys = mutableMapOf<String, String>()
        doc.stacks.forEach { stack ->
            val first = stack.blocks.firstOrNull() ?: return@forEach
            val def = BlockRegistry.find(first.type)
            if (def?.shape != BlockShape.Hat) {
                warn(first.id, "not_in_event")
            } else {
                if (stack.blocks.size > 1) err(stack.blocks[1].id, "after_event")
                val key = when (first.type) {
                    "event_click", "event_checked" -> first.type + ":" + first.field("view")
                    "event_timer" -> first.type + ":" + first.field("timer")
                    "event_dialog_ok" -> first.type + ":" + first.field("dialog")
                    "event_sound_done" -> first.type + ":" + first.field("sound")
                    "proc_define" -> "proc:" + first.field("name")
                    else -> first.type
                }
                if (!first.disabled && eventKeys.put(key, first.id) != null) {
                    err(first.id, if (first.type == "proc_define") "duplicate_procedure" else "duplicate_event", first.field(if (first.type == "proc_define") "name" else "view"))
                }
            }
            stack.blocks.forEachIndexed { i, b ->
                if (i > 0 && BlockRegistry.find(b.type)?.shape == BlockShape.Hat) err(b.id, "event_not_on_top")
            }
        }

        // Every block (disabled blocks and everything inside them are not compiled, so not checked)
        val inactive = doc.allBlocks().filter { it.disabled }.flatMap { it.walk() }.map { it.id }.toSet()
        doc.allBlocks().filter { it.id !in inactive }.forEach { b ->
            val def = BlockRegistry.find(b.type)
            if (def == null) { err(b.id, "unknown_block", b.type); return@forEach }

            def.inputs.forEach { input ->
                val child = b.inputs[input.name]
                if (child == null) {
                    err(b.id, "missing_input", input.name)
                } else {
                    val childDef = BlockRegistry.find(child.type)
                    if (childDef != null && childDef.shape != BlockShape.Value) err(child.id, "wrong_shape")
                    val out = BlockOps.outputType(doc, child)
                    val expected = if (b.type == "var_set") doc.variables.firstOrNull { it.name == b.field("var") }?.type ?: input.type else input.type
                    if (out != null && !ValueType.accepts(expected, out)) err(child.id, "type_mismatch", "${expected.name}:${out.name}")
                }
            }
            def.statements.forEach { st ->
                b.statements[st.name].orEmpty().forEach { s ->
                    val sd = BlockRegistry.find(s.type)
                    if (sd != null && sd.shape != BlockShape.Statement) err(s.id, "wrong_shape")
                }
            }

            def.fields.forEach { f ->
                val value = b.field(f.name)
                when (val k = f.kind) {
                    is FieldKind.View -> {
                        val view = ctx.views.firstOrNull { it.id == value }
                        when {
                            value.isEmpty() -> err(b.id, "missing_view")
                            view == null -> err(b.id, "unknown_view", value)
                            !ViewFamilies.matches(k.kind, view.tag) -> err(b.id, "view_kind", "$value:${k.kind.name}")
                        }
                    }
                    is FieldKind.VariableRef -> {
                        val v = doc.variables.firstOrNull { it.name == value }
                        when {
                            value.isEmpty() -> err(b.id, "missing_variable")
                            v == null -> err(b.id, "unknown_variable", value)
                            k.type != null && v.type != k.type -> err(b.id, "variable_type", "$value:${k.type.name}")
                        }
                    }
                    is FieldKind.Number -> if (value.toDoubleOrNull()?.isFinite() != true) err(b.id, "invalid_number", value)
                    is FieldKind.Dropdown -> if (value !in k.options) err(b.id, "invalid_option", value)
                    is FieldKind.Procedure -> when {
                        !Names.isIdentifier(value) -> err(b.id, "invalid_procedure", value)
                        !k.defines && value !in procNames -> err(b.id, "unknown_procedure", value)
                    }
                    FieldKind.Activity -> if (value !in ctx.activities) err(b.id, "unknown_activity", value)
                    FieldKind.Code -> if (value.isBlank()) warn(b.id, "empty_code")
                    is FieldKind.ComponentRef -> when {
                        value.isEmpty() -> err(b.id, "missing_component", k.type.name)
                        doc.components.none { it.name == value && it.type == k.type } -> err(b.id, "unknown_component", value)
                    }
                    FieldKind.RawResource -> when {
                        value.isEmpty() -> err(b.id, "missing_sound")
                        value !in ctx.sounds -> err(b.id, "unknown_sound", value)
                    }
                    is FieldKind.Text -> if (b.type == "view_set_text_color" && !colorPattern.matches(value)) err(b.id, "invalid_color", value)
                }
            }
        }
    }

    fun hasErrors(diagnostics: List<BlockDiagnostic>) = diagnostics.any { it.severity == BlockDiagnostic.Severity.Error }
}

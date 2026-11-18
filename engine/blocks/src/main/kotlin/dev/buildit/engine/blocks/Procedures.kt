package dev.buildit.engine.blocks

data class ProcParam(val name: String, val type: ValueType)

/**
 * Procedures with parameters ("more blocks"). A `proc_define` hat keeps its parameters in the
 * `params` field as `name:type,name:type`; each `proc_call` then has one value input per parameter,
 * named [INPUT_PREFIX] + parameter name, and `proc_param` blocks read a parameter inside the body.
 */
object Procedures {
    const val INPUT_PREFIX = "arg_"
    private val generatedLocal = Regex("(i|item)\\d+")

    /** Parameters from a `params` field, or null when the text is malformed. Empty text is no parameters. */
    fun parse(spec: String): List<ProcParam>? {
        if (spec.isBlank()) return emptyList()
        return spec.split(',').map { part ->
            val name = part.substringBefore(':').trim()
            val type = ValueType.fromKey(part.substringAfter(':', "").trim()) ?: return null
            ProcParam(name, type)
        }
    }

    fun format(params: List<ProcParam>): String = params.joinToString(",") { "${it.name}:${it.type.key}" }

    /** Why a parameter name can't be used, or null: invalid, duplicate or clashing with a variable, view or component. */
    fun problem(name: String, others: List<String>, doc: BlockDocument, viewIds: Set<String>): String? = when {
        !Names.isIdentifier(name) || generatedLocal.matches(name) -> "invalid_name"
        name in others -> "duplicate_name"
        doc.variables.any { it.name == name } || doc.components.any { it.name == name } || name in viewIds -> "name_clash"
        else -> null
    }

    fun define(doc: BlockDocument, name: String): Block? =
        doc.stacks.firstNotNullOfOrNull { s -> s.blocks.firstOrNull()?.takeIf { it.type == "proc_define" && it.field("name") == name } }

    fun params(doc: BlockDocument, name: String): List<ProcParam> = define(doc, name)?.let { parse(it.field("params")) }.orEmpty()

    /** The `proc_define` whose stack contains [blockId], if any. */
    fun enclosingDefine(doc: BlockDocument, blockId: String): Block? = doc.stacks.firstNotNullOfOrNull { s ->
        val head = s.blocks.firstOrNull() ?: return@firstNotNullOfOrNull null
        head.takeIf { it.type == "proc_define" && s.blocks.any { b -> b.walk().any { it.id == blockId } } }
    }

    /**
     * Parameters available to [blockId]: those of the procedure it is in, or the values its
     * component event provides (e.g. the response of a network request). Null outside both.
     */
    fun enclosingParams(doc: BlockDocument, blockId: String): List<ProcParam>? {
        val head = doc.stacks.firstOrNull { s -> s.blocks.any { b -> b.walk().any { it.id == blockId } } }?.blocks?.firstOrNull() ?: return null
        if (head.type == "proc_define") return parse(head.field("params")).orEmpty()
        ViewEvents.spec(head.type)?.let { return it.params.takeIf { p -> p.isNotEmpty() } }
        return Components.event(head.type)?.second?.params?.takeIf { it.isNotEmpty() }
    }

    /** Definition of a call block: the static `proc_call` plus one input per parameter of the called procedure. */
    fun callDef(doc: BlockDocument, call: Block): BlockDef {
        val base = BlockRegistry["proc_call"]
        val params = params(doc, call.field("name"))
        if (params.isEmpty()) return base
        return base.copy(
            message = base.message + params.joinToString("") { " ${it.name}: {$INPUT_PREFIX${it.name}}" },
            inputs = params.map { InputDef(INPUT_PREFIX + it.name, it.type) },
        )
    }

    /**
     * Brings every call of [name] in line with its parameters: missing inputs get a default value,
     * inputs of removed parameters are moved out to loose stacks (never deleted).
     */
    fun syncCalls(doc: BlockDocument, name: String, ids: () -> String): BlockDocument {
        val params = params(doc, name)
        val wanted = params.map { INPUT_PREFIX + it.name }.toSet()
        val orphans = mutableListOf<Block>()
        var next = doc
        doc.allBlocks().filter { it.type == "proc_call" && it.field("name") == name }.toList().forEach { call ->
            next = BlockOps.update(next, call.id) { b ->
                val kept = b.inputs.filterKeys { !it.startsWith(INPUT_PREFIX) || it in wanted }
                orphans += b.inputs.filterKeys { it.startsWith(INPUT_PREFIX) && it !in wanted }.values
                val added = params.filter { INPUT_PREFIX + it.name !in kept }
                    .mapNotNull { p -> BlockRegistry.defaultLiteral(p.type, ids)?.let { INPUT_PREFIX + p.name to it } }
                b.copy(inputs = kept + added)
            }
        }
        val maxY = next.stacks.maxOfOrNull { it.y } ?: 0f
        orphans.forEachIndexed { i, o -> next = BlockOps.addStack(next, listOf(o), 40f, maxY + 200f + i * 80f) }
        return next
    }
}

/** The definition of [block] in [doc]: like [BlockRegistry.find], but calls get their parameters as inputs. */
fun BlockDocument.defOf(block: Block): BlockDef? =
    if (block.type == "proc_call") Procedures.callDef(this, block) else BlockRegistry.find(block.type)

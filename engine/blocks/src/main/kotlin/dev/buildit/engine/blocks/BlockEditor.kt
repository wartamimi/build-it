package dev.buildit.engine.blocks

/** Where a block (or chain of statement blocks) sits, or can be inserted. */
sealed interface Slot {
    /** Position [index] in the block list of top-level stack [stackIndex]. */
    data class TopLevel(val stackIndex: Int, val index: Int) : Slot
    /** Position [index] in statement input [input] of block [parentId]. */
    data class Statement(val parentId: String, val input: String, val index: Int) : Slot
    /** Value input [input] of block [parentId]. */
    data class Value(val parentId: String, val input: String) : Slot
}

class BlockEditException(val code: String) : Exception(code)

/**
 * Pure editing operations on [BlockDocument]. Every operation returns a new document; the
 * [BlockEditor] keeps the history for undo/redo. Shared by touch editing and (later) AI-applied
 * changes so both go through the same rules.
 */
object BlockOps {

    fun locate(doc: BlockDocument, id: String): Slot? {
        doc.stacks.forEachIndexed { si, s ->
            s.blocks.forEachIndexed { i, b -> if (b.id == id) return Slot.TopLevel(si, i) }
        }
        doc.allBlocks().forEach { p ->
            p.inputs.forEach { (n, b) -> if (b.id == id) return Slot.Value(p.id, n) }
            p.statements.forEach { (n, list) -> list.forEachIndexed { i, b -> if (b.id == id) return Slot.Statement(p.id, n, i) } }
        }
        return null
    }

    /** Output type of a value block; `var_get` takes its variable's type. */
    fun outputType(doc: BlockDocument, block: Block): ValueType? {
        val def = BlockRegistry.find(block.type) ?: return null
        if (block.type == "var_get") return doc.variables.firstOrNull { it.name == block.field("var") }?.type
        if (block.type == "proc_param") return Procedures.enclosingDefine(doc, block.id)
            ?.let { d -> Procedures.parse(d.field("params"))?.firstOrNull { it.name == block.field("param") }?.type }
        return def.output
    }

    /** Removes [id] and, for statements, every block after it in the same list. Returns the removed chain. */
    fun detach(doc: BlockDocument, id: String): Pair<BlockDocument, List<Block>> {
        return when (val slot = locate(doc, id) ?: throw BlockEditException("not_found")) {
            is Slot.TopLevel -> {
                val stack = doc.stacks[slot.stackIndex]
                val chain = stack.blocks.drop(slot.index)
                val stacks = if (slot.index == 0) doc.stacks.filterIndexed { i, _ -> i != slot.stackIndex }
                else doc.stacks.mapIndexed { i, s -> if (i == slot.stackIndex) s.copy(blocks = s.blocks.take(slot.index)) else s }
                doc.copy(stacks = stacks) to chain
            }
            is Slot.Statement -> {
                var chain = emptyList<Block>()
                val next = update(doc, slot.parentId) { p ->
                    val list = p.statements[slot.input].orEmpty()
                    chain = list.drop(slot.index)
                    p.copy(statements = p.statements + (slot.input to list.take(slot.index)))
                }
                next to chain
            }
            is Slot.Value -> {
                var chain = emptyList<Block>()
                val next = update(doc, slot.parentId) { p ->
                    chain = listOfNotNull(p.inputs[slot.input])
                    p.copy(inputs = p.inputs - slot.input)
                }
                next to chain
            }
        }
    }

    /** Whether [chain] may be inserted at [slot] (shape and type rules). */
    fun canInsert(doc: BlockDocument, chain: List<Block>, slot: Slot): Boolean {
        val first = chain.firstOrNull() ?: return false
        val shape = BlockRegistry.find(first.type)?.shape ?: return false
        if (chain.any { BlockRegistry.find(it.type)?.shape == BlockShape.Hat }) return false
        return when (slot) {
            is Slot.Value -> {
                if (shape != BlockShape.Value || chain.size != 1) return false
                val parent = doc.find(slot.parentId) ?: return false
                val input = doc.defOf(parent)?.inputDef(slot.input) ?: return false
                val out = outputType(doc, first) ?: return true // unresolved variable: allowed, reported by validation
                ValueType.accepts(input.type, out) && parent.walk().none { it.id == first.id }
            }
            is Slot.Statement -> shape == BlockShape.Statement &&
                doc.find(slot.parentId)?.let { p -> BlockRegistry.find(p.type)?.statements?.any { it.name == slot.input } } == true
            is Slot.TopLevel -> shape == BlockShape.Statement &&
                doc.stacks.getOrNull(slot.stackIndex)?.blocks?.firstOrNull()?.let { BlockRegistry.find(it.type)?.shape != BlockShape.Hat } == true
        }
    }

    /**
     * Inserts [chain] at [slot]. A value slot that is already filled has its old block moved out
     * to a new stack next to the parent (nothing is deleted implicitly).
     */
    fun insert(doc: BlockDocument, chain: List<Block>, slot: Slot, displacedAt: Pair<Float, Float> = 0f to 0f): BlockDocument {
        if (!canInsert(doc, chain, slot)) throw BlockEditException("incompatible")
        return when (slot) {
            is Slot.TopLevel -> doc.copy(stacks = doc.stacks.mapIndexed { i, s ->
                if (i == slot.stackIndex) s.copy(blocks = s.blocks.toMutableList().apply { addAll(slot.index.coerceIn(0, size), chain) }) else s
            })
            is Slot.Statement -> update(doc, slot.parentId) { p ->
                val list = p.statements[slot.input].orEmpty().toMutableList()
                list.addAll(slot.index.coerceIn(0, list.size), chain)
                p.copy(statements = p.statements + (slot.input to list))
            }
            is Slot.Value -> {
                var displaced: Block? = null
                val next = update(doc, slot.parentId) { p ->
                    displaced = p.inputs[slot.input]
                    p.copy(inputs = p.inputs + (slot.input to chain.single()))
                }
                displaced?.let { addStack(next, listOf(it), displacedAt.first, displacedAt.second) } ?: next
            }
        }
    }

    fun addStack(doc: BlockDocument, chain: List<Block>, x: Float, y: Float): BlockDocument =
        doc.copy(stacks = doc.stacks + BlockStack(x, y, chain))

    fun moveStack(doc: BlockDocument, stackIndex: Int, x: Float, y: Float): BlockDocument =
        doc.copy(stacks = doc.stacks.mapIndexed { i, s -> if (i == stackIndex) s.copy(x = x, y = y) else s })

    fun setField(doc: BlockDocument, id: String, name: String, value: String): BlockDocument =
        update(doc, id) { it.copy(fields = it.fields + (name to value)) }

    fun setDisabled(doc: BlockDocument, id: String, disabled: Boolean): BlockDocument =
        update(doc, id) { it.copy(disabled = disabled) }

    /** Deletes one block with everything nested in it; following statements close the gap. */
    fun delete(doc: BlockDocument, id: String): BlockDocument {
        return when (val slot = locate(doc, id) ?: throw BlockEditException("not_found")) {
            is Slot.TopLevel -> {
                val stacks = doc.stacks.mapIndexed { i, s -> if (i == slot.stackIndex) s.copy(blocks = s.blocks.filterNot { it.id == id }) else s }
                doc.copy(stacks = stacks.filter { it.blocks.isNotEmpty() })
            }
            is Slot.Statement -> update(doc, slot.parentId) { p ->
                p.copy(statements = p.statements + (slot.input to p.statements[slot.input].orEmpty().filterNot { it.id == id }))
            }
            is Slot.Value -> update(doc, slot.parentId) { p -> p.copy(inputs = p.inputs - slot.input) }
        }
    }

    /** Copies a block (with nested blocks, without following statements) to a new stack. */
    fun duplicate(doc: BlockDocument, id: String, ids: () -> String, x: Float, y: Float): BlockDocument {
        val b = doc.find(id) ?: throw BlockEditException("not_found")
        return addStack(doc, listOf(copyWithNewIds(b, ids)), x, y)
    }

    fun copyWithNewIds(b: Block, ids: () -> String): Block = b.copy(
        id = ids(),
        inputs = b.inputs.mapValues { copyWithNewIds(it.value, ids) },
        statements = b.statements.mapValues { (_, l) -> l.map { copyWithNewIds(it, ids) } },
    )

    // Variables

    fun addVariable(doc: BlockDocument, v: Variable): BlockDocument {
        if (!Names.isIdentifier(v.name)) throw BlockEditException("invalid_name")
        if (doc.variables.any { it.name == v.name } || doc.components.any { it.name == v.name }) throw BlockEditException("duplicate_name")
        return doc.copy(variables = doc.variables + v)
    }

    /** Renames a variable and every block that refers to it. */
    fun renameVariable(doc: BlockDocument, old: String, new: String): BlockDocument {
        if (!Names.isIdentifier(new)) throw BlockEditException("invalid_name")
        if (old != new && doc.variables.any { it.name == new }) throw BlockEditException("duplicate_name")
        val renamed = doc.copy(variables = doc.variables.map { if (it.name == old) it.copy(name = new) else it })
        return mapAll(renamed) { b ->
            val def = BlockRegistry.find(b.type) ?: return@mapAll b
            val refs = def.fields.filter { it.kind is FieldKind.VariableRef && b.fields[it.name] == old }
            if (refs.isEmpty()) b else b.copy(fields = b.fields + refs.associate { it.name to new })
        }
    }

    /** Removes a variable that no block uses. */
    fun removeVariable(doc: BlockDocument, name: String): BlockDocument {
        if (usesVariable(doc, name)) throw BlockEditException("in_use")
        return doc.copy(variables = doc.variables.filterNot { it.name == name })
    }

    fun usesVariable(doc: BlockDocument, name: String): Boolean = doc.allBlocks().any { b ->
        BlockRegistry.find(b.type)?.fields?.any { it.kind is FieldKind.VariableRef && b.fields[it.name] == name } == true
    }

    // Components

    fun addComponent(doc: BlockDocument, c: Component): BlockDocument {
        if (!Names.isIdentifier(c.name)) throw BlockEditException("invalid_name")
        if (doc.components.any { it.name == c.name } || doc.variables.any { it.name == c.name }) throw BlockEditException("duplicate_name")
        return doc.copy(components = doc.components + c)
    }

    /** Renames a component and every block that refers to it. */
    fun renameComponent(doc: BlockDocument, old: String, new: String): BlockDocument {
        if (!Names.isIdentifier(new)) throw BlockEditException("invalid_name")
        if (old != new && (doc.components.any { it.name == new } || doc.variables.any { it.name == new })) throw BlockEditException("duplicate_name")
        val renamed = doc.copy(components = doc.components.map { if (it.name == old) it.copy(name = new) else it })
        return mapAll(renamed) { b ->
            val def = BlockRegistry.find(b.type) ?: return@mapAll b
            val refs = def.fields.filter { it.kind is FieldKind.ComponentRef && b.fields[it.name] == old }
            if (refs.isEmpty()) b else b.copy(fields = b.fields + refs.associate { it.name to new })
        }
    }

    /** Removes a component that no block uses. */
    fun removeComponent(doc: BlockDocument, name: String): BlockDocument {
        if (usesComponent(doc, name)) throw BlockEditException("in_use")
        return doc.copy(components = doc.components.filterNot { it.name == name })
    }

    fun usesComponent(doc: BlockDocument, name: String): Boolean = doc.allBlocks().any { b ->
        BlockRegistry.find(b.type)?.fields?.any { it.kind is FieldKind.ComponentRef && b.fields[it.name] == name } == true
    }

    // Tree helpers

    fun update(doc: BlockDocument, id: String, transform: (Block) -> Block): BlockDocument {
        var found = false
        val next = mapAll(doc) { if (it.id == id) { found = true; transform(it) } else it }
        if (!found) throw BlockEditException("not_found")
        return next
    }

    private fun mapAll(doc: BlockDocument, f: (Block) -> Block): BlockDocument =
        doc.copy(stacks = doc.stacks.map { s -> s.copy(blocks = s.blocks.map { mapDeep(it, f) }) })

    private fun mapDeep(b: Block, f: (Block) -> Block): Block = f(
        b.copy(
            inputs = b.inputs.mapValues { mapDeep(it.value, f) },
            statements = b.statements.mapValues { (_, l) -> l.map { mapDeep(it, f) } },
        ),
    )
}

/** Holds the current document with an undo/redo history of whole-document snapshots. */
class BlockEditor(initial: BlockDocument, private val limit: Int = 200) {
    var document: BlockDocument = initial
        private set
    private val undo = ArrayDeque<BlockDocument>()
    private val redo = ArrayDeque<BlockDocument>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** Applies [change]; a change that throws leaves the document and history untouched. */
    fun apply(change: (BlockDocument) -> BlockDocument): BlockDocument {
        val next = change(document)
        if (next == document) return document
        undo.addLast(document)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
        document = next
        return next
    }

    fun undo(): Boolean {
        val prev = undo.removeLastOrNull() ?: return false
        redo.addLast(document)
        document = prev
        return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        undo.addLast(document)
        document = next
        return true
    }
}

object Names {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Words that can't be identifiers in Java or Kotlin. */
    val reserved = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
        "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
        "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
        "as", "fun", "in", "is", "object", "typealias", "typeof", "val", "var", "when", "_",
    )

    fun isIdentifier(s: String) = identifier.matches(s) && s !in reserved
}

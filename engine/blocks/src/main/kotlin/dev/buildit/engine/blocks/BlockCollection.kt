package dev.buildit.engine.blocks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Blocks saved to the collection: one block (with what is plugged into it) and the variables it uses. */
@Serializable
data class CollectedBlocks(
    val formatVersion: Int = 1,
    val blocks: List<Block>,
    val variables: List<Variable> = emptyList(),
)

/** Saving blocks to, and placing them from, the collection (see CollectionStore in engine:project). */
object BlockCollection {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    /** The block [id] and everything inside it, with the variables it reads or changes. */
    fun collect(doc: BlockDocument, id: String): CollectedBlocks {
        val b = doc.find(id) ?: throw BlockEditException("not_found")
        val used = b.walk().flatMap { it.fields.values }.toSet()
        val vars = doc.variables.filter { it.name in used }
        return CollectedBlocks(blocks = listOf(b), variables = vars)
    }

    fun encode(c: CollectedBlocks): String = json.encodeToString(CollectedBlocks.serializer(), c)
    fun decode(text: String): CollectedBlocks = json.decodeFromString(CollectedBlocks.serializer(), text)

    /**
     * Places collected blocks as a new stack at ([x], [y]) with fresh ids. Variables the screen
     * does not have yet are added; a variable or component with the same name is kept as it is
     * (the validator reports a type mismatch).
     */
    fun place(doc: BlockDocument, c: CollectedBlocks, ids: () -> String, x: Float, y: Float): BlockDocument {
        val taken = doc.variables.map { it.name }.toSet() + doc.components.map { it.name }
        val withVars = doc.copy(variables = doc.variables + c.variables.filter { it.name !in taken && Names.isIdentifier(it.name) })
        return BlockOps.addStack(withVars, c.blocks.map { BlockOps.copyWithNewIds(it, ids) }, x, y)
    }
}

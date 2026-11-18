package dev.buildit.engine.blocks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Static type of a value produced by a block or held by a variable. */
@Serializable
enum class ValueType {
    @SerialName("boolean") Boolean,
    @SerialName("number") Number,
    @SerialName("text") Text,
    @SerialName("textList") TextList,
    ;

    companion object {
        /** Whether a value of type [actual] may be plugged into an input of type [expected]. */
        fun accepts(expected: ValueType, actual: ValueType): kotlin.Boolean =
            expected == actual || expected == Text // any value is converted to text where text is expected
    }
}

/**
 * The block program of one activity, stored in `.buildit/blocks/<Activity>.json`.
 *
 * Blocks form trees: value inputs hold one block, statement inputs hold an ordered list. Top-level
 * [stacks] are positioned on the canvas; a stack whose first block is an event (hat) block is
 * compiled, any other stack is kept but ignored (reported as a warning).
 */
@Serializable
data class BlockDocument(
    val formatVersion: Int = FORMAT_VERSION,
    val activity: String,
    val variables: List<Variable> = emptyList(),
    val stacks: List<BlockStack> = emptyList(),
) {
    fun allBlocks(): Sequence<Block> = stacks.asSequence().flatMap { s -> s.blocks.asSequence().flatMap { it.walk() } }
    fun find(id: String): Block? = allBlocks().firstOrNull { it.id == id }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class Variable(val name: String, val type: ValueType)

@Serializable
data class BlockStack(val x: Float, val y: Float, val blocks: List<Block>)

@Serializable
data class Block(
    val id: String,
    val type: String,
    val fields: Map<String, String> = emptyMap(),
    val inputs: Map<String, Block> = emptyMap(),
    val statements: Map<String, List<Block>> = emptyMap(),
    val disabled: Boolean = false,
) {
    /** This block and every block nested in its inputs and statements (not following siblings). */
    fun walk(): Sequence<Block> = sequence {
        yield(this@Block)
        inputs.values.forEach { yieldAll(it.walk()) }
        statements.values.forEach { list -> list.forEach { yieldAll(it.walk()) } }
    }

    fun field(name: String): String = fields[name].orEmpty()
}

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
    @SerialName("numberList") NumberList,
    /** Text keys to text values (HashMap). */
    @SerialName("map") TextMap,
    ;

    val isCollection get() = this == TextList || this == NumberList || this == TextMap

    /** The name used in stored documents (same as the serial name). */
    val key: String get() = when (this) {
        Boolean -> "boolean"; Number -> "number"; Text -> "text"; TextList -> "textList"; NumberList -> "numberList"; TextMap -> "map"
    }

    companion object {
        /** Whether a value of type [actual] may be plugged into an input of type [expected]. */
        fun accepts(expected: ValueType, actual: ValueType): kotlin.Boolean =
            expected == actual || expected == Text // any value is converted to text where text is expected

        fun fromKey(key: String): ValueType? = entries.firstOrNull { it.key == key }
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
    /** Non-visual parts of the screen (timers, stored values, dialogs, sound players). */
    val components: List<Component> = emptyList(),
) {
    fun allBlocks(): Sequence<Block> = stacks.asSequence().flatMap { s -> s.blocks.asSequence().flatMap { it.walk() } }
    fun find(id: String): Block? = allBlocks().firstOrNull { it.id == id }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class Variable(val name: String, val type: ValueType)

/** Kinds of non-visual components a screen can have; each comes with its own blocks. */
@Serializable
enum class ComponentType {
    /** Runs blocks after a delay, once or repeatedly (android.os.Handler). */
    @SerialName("timer") Timer,
    /** Keeps text values between app launches (SharedPreferences). */
    @SerialName("storage") Storage,
    /** A message box with an OK button (android.app.AlertDialog). */
    @SerialName("dialog") Dialog,
    /** Plays a sound from res/raw (android.media.MediaPlayer). */
    @SerialName("sound") Sound,
    /** Opens another screen or app, with extras (android.content.Intent). */
    @SerialName("intent") Intent,
    @SerialName("vibrator") Vibrator,
    /** A date and time that blocks can change and read (java.util.Calendar). */
    @SerialName("calendar") Calendar,
    /** Animates a property of a view (android.animation.ObjectAnimator). */
    @SerialName("animator") Animator,
    /** Accelerometer, gyroscope, light, proximity or magnetic field readings. */
    @SerialName("sensor") Sensor,
    @SerialName("textToSpeech") TextToSpeech,
    /** Speech input through the system recogniser. */
    @SerialName("speechToText") SpeechToText,
    /** Lets the user pick a file with the system picker. */
    @SerialName("filePicker") FilePicker,
    /** HTTP requests on a background thread, results on the main thread. */
    @SerialName("network") Network,
    @SerialName("notification") Notification,
    @SerialName("datePicker") DatePicker,
    @SerialName("timePicker") TimePicker,
}

@Serializable
data class Component(val name: String, val type: ComponentType)

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

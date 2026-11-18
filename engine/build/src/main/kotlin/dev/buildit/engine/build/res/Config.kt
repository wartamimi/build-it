package dev.buildit.engine.build.res

/**
 * A resource configuration (ResTable_config, 64 bytes) parsed from directory qualifiers such as
 * `values-in-night-v26`. [mask] holds the CONFIG_* bits the qualifiers vary on.
 */
class ResConfig private constructor(val qualifiers: String, val bytes: ByteArray, val mask: Int) {
    val isDefault: Boolean get() = qualifiers.isEmpty()

    override fun equals(other: Any?) = other is ResConfig && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = qualifiers.ifEmpty { "default" }

    companion object {
        const val SIZE = 64
        const val CONFIG_LOCALE = 0x0004
        const val CONFIG_ORIENTATION = 0x0080
        const val CONFIG_DENSITY = 0x0100
        const val CONFIG_SCREEN_SIZE = 0x0200
        const val CONFIG_VERSION = 0x0400
        const val CONFIG_UI_MODE = 0x1000
        const val CONFIG_SMALLEST_SCREEN_SIZE = 0x2000
        const val CONFIG_LAYOUTDIR = 0x4000

        val DEFAULT = ResConfig("", ByteArray(SIZE).also { it[0] = SIZE.toByte() }, 0)

        private val densities = mapOf(
            "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
            "anydpi" to 0xFFFE, "nodpi" to 0xFFFF,
        )

        /**
         * Parses qualifiers (without the type, e.g. `in-rID-night-v26`). Supported: language and
         * region, layout direction, smallest width, available width/height, orientation, night
         * mode, density and platform version. Returns null with a reason for anything else.
         */
        fun parse(qualifiers: String): Result<ResConfig> {
            if (qualifiers.isEmpty()) return Result.success(DEFAULT)
            val b = ByteArray(SIZE)
            b[0] = SIZE.toByte()
            var mask = 0
            fun u16(at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
            val parts = qualifiers.split('-')
            var i = 0
            // Order follows the platform's qualifier precedence; each kind may appear once.
            if (i < parts.size && parts[i].matches(Regex("[a-z]{2,3}")) && parts[i] !in densities && parts[i] !in setOf("land", "port", "night", "ldrtl", "ldltr")) {
                val lang = parts[i++]
                if (lang.length == 2) { b[8] = lang[0].code.toByte(); b[9] = lang[1].code.toByte() } else {
                    // Three-letter codes are packed into two bytes (ResTable_config::packLanguage).
                    val first = lang[0].code - 'a'.code
                    val second = lang[1].code - 'a'.code
                    val third = lang[2].code - 'a'.code
                    b[8] = (0x80 or (third shl 2) or (second shr 3)).toByte()
                    b[9] = ((second shl 5) or first).toByte()
                }
                mask = mask or CONFIG_LOCALE
                if (i < parts.size && parts[i].matches(Regex("r[A-Z]{2}"))) {
                    b[10] = parts[i][1].code.toByte(); b[11] = parts[i][2].code.toByte(); i++
                }
            }
            while (i < parts.size) {
                val q = parts[i++]
                when {
                    q == "ldrtl" || q == "ldltr" -> { b[28] = (b[28].toInt() or if (q == "ldrtl") 0x80 else 0x40).toByte(); mask = mask or CONFIG_LAYOUTDIR }
                    q.matches(Regex("sw\\d+dp")) -> { u16(30, q.drop(2).dropLast(2).toInt()); mask = mask or CONFIG_SMALLEST_SCREEN_SIZE }
                    q.matches(Regex("w\\d+dp")) -> { u16(32, q.drop(1).dropLast(2).toInt()); mask = mask or CONFIG_SCREEN_SIZE }
                    q.matches(Regex("h\\d+dp")) -> { u16(34, q.drop(1).dropLast(2).toInt()); mask = mask or CONFIG_SCREEN_SIZE }
                    q == "port" || q == "land" -> { b[12] = (if (q == "port") 1 else 2).toByte(); mask = mask or CONFIG_ORIENTATION }
                    q == "night" || q == "notnight" -> { b[29] = (b[29].toInt() or if (q == "night") 0x20 else 0x10).toByte(); mask = mask or CONFIG_UI_MODE }
                    q in densities -> { u16(14, densities.getValue(q)); mask = mask or CONFIG_DENSITY }
                    q.matches(Regex("v\\d+")) -> { u16(24, q.drop(1).toInt()); mask = mask or CONFIG_VERSION }
                    else -> return Result.failure(IllegalArgumentException("Unsupported qualifier '$q'"))
                }
            }
            return Result.success(ResConfig(qualifiers, b, mask))
        }
    }
}

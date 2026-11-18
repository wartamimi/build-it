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
        const val CONFIG_SCREEN_LAYOUT = 0x0800
        const val CONFIG_SCREEN_ROUND = 0x8000
        const val CONFIG_COLOR_MODE = 0x10000

        val DEFAULT = ResConfig("", ByteArray(SIZE).also { it[0] = SIZE.toByte() }, 0)

        private val densities = mapOf(
            "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
            "anydpi" to 0xFFFE, "nodpi" to 0xFFFF,
        )

        private val uiModeTypes = mapOf("desk" to 2, "car" to 3, "television" to 4, "appliance" to 5, "watch" to 6, "vrheadset" to 7)
        private val screenSizes = mapOf("small" to 1, "normal" to 2, "large" to 3, "xlarge" to 4)
        private val notLanguage = setOf("land", "port", "night", "car", "any", "mcc", "mnc")

        /**
         * Parses qualifiers (without the type, e.g. `in-rID-night-v26` or `b+sr+Latn`). Supported:
         * MCC/MNC, language with region or BCP-47 (`b+lang+Script+REGION`), layout direction,
         * smallest/available width and height, screen size and aspect, round screens, wide colour
         * gamut and HDR, orientation, UI mode type and night mode, density, touchscreen, keyboard,
         * navigation and platform version. Anything else fails with a reason.
         */
        fun parse(qualifiers: String): Result<ResConfig> {
            if (qualifiers.isEmpty()) return Result.success(DEFAULT)
            val b = ByteArray(SIZE)
            b[0] = SIZE.toByte()
            var mask = 0
            fun u16(at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
            fun or(at: Int, v: Int) { b[at] = (b[at].toInt() or v).toByte() }
            /** Two letters/digits as they are; three are packed (ResTable_config::packLanguageOrRegion). */
            fun pack(at: Int, code: String, base: Char) {
                if (code.length == 2) { b[at] = code[0].code.toByte(); b[at + 1] = code[1].code.toByte(); return }
                val first = code[0] - base
                val second = code[1] - base
                val third = code[2] - base
                b[at] = (0x80 or (third shl 2) or (second shr 3)).toByte()
                b[at + 1] = ((second shl 5) or first).toByte()
            }
            val parts = qualifiers.split('-')
            var i = 0
            if (i < parts.size && parts[i].matches(Regex("mcc\\d+"))) { u16(4, parts[i++].drop(3).toInt()); mask = mask or 0x0001 }
            if (i < parts.size && parts[i].matches(Regex("mnc\\d+"))) { val v = parts[i++].drop(3).toInt(); u16(6, if (v == 0) 0xFFFF else v); mask = mask or 0x0002 }
            if (i < parts.size && parts[i].startsWith("b+")) {
                // BCP-47: b+language[+Script][+REGION][+variant]
                val sub = parts[i++].split('+').drop(1)
                val lang = sub.firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: return Result.failure(IllegalArgumentException("Invalid locale qualifier '${parts[i - 1]}'"))
                pack(8, lang, 'a')
                sub.drop(1).forEach { s ->
                    when {
                        s.length == 4 && s.all { it.isLetter() } -> s.forEachIndexed { k, c -> b[36 + k] = (if (k == 0) c.uppercaseChar() else c.lowercaseChar()).code.toByte() }
                        s.length == 2 && s.all { it.isLetter() } -> pack(10, s.uppercase(), 'A')
                        s.length == 3 && s.all { it.isDigit() } -> pack(10, s, '0')
                        s.length in 5..8 -> s.lowercase().forEachIndexed { k, c -> b[40 + k] = c.code.toByte() }
                        else -> return Result.failure(IllegalArgumentException("Invalid locale part '$s'"))
                    }
                }
                mask = mask or CONFIG_LOCALE
            } else if (i < parts.size && parts[i].matches(Regex("[a-z]{2,3}")) && parts[i] !in densities && parts[i] !in notLanguage) {
                pack(8, parts[i++], 'a')
                mask = mask or CONFIG_LOCALE
                if (i < parts.size && parts[i].matches(Regex("r[A-Z]{2}"))) { pack(10, parts[i].drop(1), 'A'); i++ }
            }
            while (i < parts.size) {
                val q = parts[i++]
                when {
                    q == "ldrtl" || q == "ldltr" -> { or(28, if (q == "ldrtl") 0x80 else 0x40); mask = mask or CONFIG_LAYOUTDIR }
                    q.matches(Regex("sw\\d+dp")) -> { u16(30, q.drop(2).dropLast(2).toInt()); mask = mask or CONFIG_SMALLEST_SCREEN_SIZE }
                    q.matches(Regex("w\\d+dp")) -> { u16(32, q.drop(1).dropLast(2).toInt()); mask = mask or CONFIG_SCREEN_SIZE }
                    q.matches(Regex("h\\d+dp")) -> { u16(34, q.drop(1).dropLast(2).toInt()); mask = mask or CONFIG_SCREEN_SIZE }
                    q in screenSizes -> { or(28, screenSizes.getValue(q)); mask = mask or CONFIG_SCREEN_LAYOUT }
                    q == "long" || q == "notlong" -> { or(28, if (q == "long") 0x20 else 0x10); mask = mask or CONFIG_SCREEN_LAYOUT }
                    q == "round" || q == "notround" -> { or(48, if (q == "round") 2 else 1); mask = mask or CONFIG_SCREEN_ROUND }
                    q == "widecg" || q == "nowidecg" -> { or(49, if (q == "widecg") 2 else 1); mask = mask or CONFIG_COLOR_MODE }
                    q == "highdr" || q == "lowdr" -> { or(49, if (q == "highdr") 8 else 4); mask = mask or CONFIG_COLOR_MODE }
                    q == "port" || q == "land" || q == "square" -> { b[12] = (when (q) { "port" -> 1; "land" -> 2; else -> 3 }).toByte(); mask = mask or CONFIG_ORIENTATION }
                    q in uiModeTypes -> { or(29, uiModeTypes.getValue(q)); mask = mask or CONFIG_UI_MODE }
                    q == "night" || q == "notnight" -> { or(29, if (q == "night") 0x20 else 0x10); mask = mask or CONFIG_UI_MODE }
                    q in densities -> { u16(14, densities.getValue(q)); mask = mask or CONFIG_DENSITY }
                    q.matches(Regex("\\d+dpi")) -> { u16(14, q.dropLast(3).toInt()); mask = mask or CONFIG_DENSITY }
                    q == "notouch" || q == "stylus" || q == "finger" -> { b[13] = (when (q) { "notouch" -> 1; "stylus" -> 2; else -> 3 }).toByte(); mask = mask or 0x0008 }
                    q == "keysexposed" || q == "keyshidden" || q == "keyssoft" -> { or(18, when (q) { "keysexposed" -> 1; "keyshidden" -> 2; else -> 3 }); mask = mask or 0x0020 }
                    q == "nokeys" || q == "qwerty" || q == "12key" -> { b[16] = (when (q) { "nokeys" -> 1; "qwerty" -> 2; else -> 3 }).toByte(); mask = mask or 0x0010 }
                    q == "navexposed" || q == "navhidden" -> { or(18, if (q == "navexposed") 4 else 8); mask = mask or 0x0020 }
                    q == "nonav" || q == "dpad" || q == "trackball" || q == "wheel" -> { b[17] = (when (q) { "nonav" -> 1; "dpad" -> 2; "trackball" -> 3; else -> 4 }).toByte(); mask = mask or 0x0040 }
                    q.matches(Regex("v\\d+")) -> { u16(24, q.drop(1).toInt()); mask = mask or CONFIG_VERSION }
                    else -> return Result.failure(IllegalArgumentException("Unsupported qualifier '$q'"))
                }
            }
            return Result.success(ResConfig(qualifiers, b, mask))
        }
    }
}

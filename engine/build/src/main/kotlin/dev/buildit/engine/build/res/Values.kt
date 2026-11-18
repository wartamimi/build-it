package dev.buildit.engine.build.res

/**
 * A compiled item. Strings are kept as text until the table or XML string pool assigns their index,
 * so [data] is ignored when [string] is set.
 */
data class Item(val type: Int, val data: Int = 0, val string: String? = null) {
    companion object {
        fun text(s: String) = Item(Res.TYPE_STRING, string = s)
        fun of(v: ResValue) = Item(v.type, v.data)
    }
}

/** A parsed `@[+][pkg:]type/name` or `?[pkg:][type/]name` reference. */
data class Reference(val pkg: String?, val type: String, val name: String, val create: Boolean = false, val attribute: Boolean = false) {
    val isFramework: Boolean get() = pkg == "android"

    companion object {
        private val ref = Regex("@(\\+)?(\\*)?(?:([a-zA-Z0-9_.]+):)?([a-z-]+)/(.+)")
        private val attr = Regex("\\?(?:([a-zA-Z0-9_.]+):)?(?:(attr)/)?([A-Za-z0-9_.]+)")

        fun parse(text: String): Reference? {
            val t = text.trim()
            ref.matchEntire(t)?.let { m ->
                val (plus, _, pkg, type, name) = m.destructured
                return Reference(pkg.ifEmpty { null }, type, name, create = plus == "+")
            }
            attr.matchEntire(t)?.let { m ->
                val (pkg, _, name) = m.destructured
                return Reference(pkg.ifEmpty { null }, "attr", name, attribute = true)
            }
            return null
        }
    }
}

/** Parsers for literal values (ResourceUtils semantics). */
object Literals {
    fun color(text: String): ResValue? {
        val t = text.trim()
        if (!t.startsWith("#")) return null
        val hex = t.drop(1)
        if (hex.isEmpty() || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        fun dup(c: Char) = "$c$c"
        return when (hex.length) {
            3 -> ResValue(Res.TYPE_INT_COLOR_RGB4, ("ff" + hex.map(::dup).joinToString("")).toLong(16).toInt())
            4 -> ResValue(Res.TYPE_INT_COLOR_ARGB4, hex.map(::dup).joinToString("").toLong(16).toInt())
            6 -> ResValue(Res.TYPE_INT_COLOR_RGB8, ("ff$hex").toLong(16).toInt())
            8 -> ResValue(Res.TYPE_INT_COLOR_ARGB8, hex.toLong(16).toInt())
            else -> null
        }
    }

    fun bool(text: String): ResValue? = when (text.trim().lowercase()) {
        "true" -> ResValue.bool(true)
        "false" -> ResValue.bool(false)
        else -> null
    }

    fun integer(text: String): ResValue? {
        val t = text.trim()
        if (t.startsWith("0x") || t.startsWith("0X")) return t.drop(2).toLongOrNull(16)?.takeIf { it <= 0xFFFFFFFFL }?.let { ResValue(Res.TYPE_INT_HEX, it.toInt()) }
        return t.toIntOrNull()?.let { ResValue(Res.TYPE_INT_DEC, it) }
    }

    private val units = mapOf("px" to 0, "dp" to 1, "dip" to 1, "sp" to 2, "pt" to 3, "in" to 4, "mm" to 5)
    private val number = Regex("([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*([a-z%]*)")

    /** A float, dimension (`16dp`) or fraction (`50%`, `50%p`), filtered by [formats]. */
    fun floatLike(text: String, formats: Int): ResValue? {
        val m = number.matchEntire(text.trim()) ?: return null
        val f = m.groupValues[1].toFloatOrNull() ?: return null
        val unit = m.groupValues[2]
        return when {
            unit.isEmpty() -> if (formats and Res.FORMAT_FLOAT != 0) ResValue(Res.TYPE_FLOAT, java.lang.Float.floatToIntBits(f)) else null
            unit == "%" || unit == "%p" -> if (formats and Res.FORMAT_FRACTION != 0) ResValue(Res.TYPE_FRACTION, complex(f / 100f) or (if (unit == "%p") 1 else 0)) else null
            unit in units -> if (formats and Res.FORMAT_DIMENSION != 0) ResValue(Res.TYPE_DIMENSION, complex(f) or units.getValue(unit)) else null
            else -> null
        }
    }

    /** Encodes a float as a complex value (mantissa + radix), without the unit bits. */
    fun complex(value: Float): Int {
        var f = value.toDouble()
        val neg = f < 0
        if (neg) f = -f
        val bits = (f * (1 shl 23) + 0.5).toLong()
        val (radix, shift) = when {
            bits and 0x7fffffL == 0L -> 0 to 23
            bits and -0x800000L == 0L -> 3 to 0
            bits and -0x80000000L == 0L -> 2 to 8
            bits and -0x8000000000L == 0L -> 1 to 16
            else -> 0 to 23
        }
        var mantissa = ((bits shr shift) and 0xffffff).toInt()
        if (neg) mantissa = (-mantissa) and 0xffffff
        return (mantissa shl 8) or (radix shl 4)
    }

    /**
     * Applies Android string rules: escapes (`\n`, `\t`, `\'`, `\"`, `\\`, `\@`, `\?`, `\uXXXX`),
     * double quotes preserve whitespace, otherwise runs of whitespace collapse to one space and the
     * ends are trimmed (when [collapse] is set).
     */
    fun string(raw: String, collapse: Boolean = true): String {
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        var pendingSpace = false
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    if (pendingSpace) { sb.append(' '); pendingSpace = false }
                    val n = raw[i + 1]
                    i += 2
                    when (n) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t')
                        'u' -> {
                            val hex = raw.substring(i, minOf(i + 4, raw.length))
                            val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); i += 4 } else sb.append('u')
                        }
                        else -> sb.append(n)
                    }
                    continue
                }
                c == '"' -> { quoted = !quoted }
                collapse && !quoted && c.isWhitespace() -> pendingSpace = sb.isNotEmpty()
                else -> {
                    if (pendingSpace) { sb.append(' '); pendingSpace = false }
                    sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    fun formatName(formats: Int): String {
        if (formats == Res.FORMAT_ANY) return "any"
        val names = listOf(
            Res.FORMAT_REFERENCE to "reference", Res.FORMAT_STRING to "string", Res.FORMAT_INTEGER to "integer",
            Res.FORMAT_BOOLEAN to "boolean", Res.FORMAT_COLOR to "color", Res.FORMAT_FLOAT to "float",
            Res.FORMAT_DIMENSION to "dimension", Res.FORMAT_FRACTION to "fraction", Res.FORMAT_ENUM to "enum", Res.FORMAT_FLAGS to "flags",
        )
        return names.filter { formats and it.first != 0 }.joinToString("|") { it.second }
    }
}

package dev.buildit.engine.build.res

import java.io.ByteArrayOutputStream

/** Chunk types and value encodings of Android's binary resource formats (ResourceTypes.h). */
object Res {
    const val STRING_POOL = 0x0001
    const val TABLE = 0x0002
    const val XML = 0x0003
    const val XML_START_NAMESPACE = 0x0100
    const val XML_END_NAMESPACE = 0x0101
    const val XML_START_ELEMENT = 0x0102
    const val XML_END_ELEMENT = 0x0103
    const val XML_CDATA = 0x0104
    const val XML_RESOURCE_MAP = 0x0180
    const val TABLE_PACKAGE = 0x0200
    const val TABLE_TYPE = 0x0201
    const val TABLE_TYPE_SPEC = 0x0202
    const val TABLE_LIBRARY = 0x0203

    // Res_value data types
    const val TYPE_NULL = 0x00
    const val TYPE_REFERENCE = 0x01
    const val TYPE_ATTRIBUTE = 0x02
    const val TYPE_STRING = 0x03
    const val TYPE_FLOAT = 0x04
    const val TYPE_DIMENSION = 0x05
    const val TYPE_FRACTION = 0x06
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
    const val TYPE_INT_BOOLEAN = 0x12
    const val TYPE_INT_COLOR_ARGB8 = 0x1c
    const val TYPE_INT_COLOR_RGB8 = 0x1d
    const val TYPE_INT_COLOR_ARGB4 = 0x1e
    const val TYPE_INT_COLOR_RGB4 = 0x1f

    const val DATA_NULL_EMPTY = 1

    // Attribute formats (ResTable_map::ATTR_TYPE values)
    const val FORMAT_ANY = 0x0000FFFF
    const val FORMAT_REFERENCE = 1
    const val FORMAT_STRING = 1 shl 1
    const val FORMAT_INTEGER = 1 shl 2
    const val FORMAT_BOOLEAN = 1 shl 3
    const val FORMAT_COLOR = 1 shl 4
    const val FORMAT_FLOAT = 1 shl 5
    const val FORMAT_DIMENSION = 1 shl 6
    const val FORMAT_FRACTION = 1 shl 7
    const val FORMAT_ENUM = 1 shl 16
    const val FORMAT_FLAGS = 1 shl 17

    // Internal bag keys
    fun internal(e: Int) = 0x01000000 or (e and 0xFFFF)
    val ATTR_TYPE = internal(0)
    val ATTR_MIN = internal(1)
    val ATTR_MAX = internal(2)
    val ATTR_OTHER = internal(4)
    val ATTR_ZERO = internal(5)
    val ATTR_ONE = internal(6)
    val ATTR_TWO = internal(7)
    val ATTR_FEW = internal(8)
    val ATTR_MANY = internal(9)

    // Entry flags
    const val ENTRY_FLAG_COMPLEX = 0x0001
    const val ENTRY_FLAG_PUBLIC = 0x0002
    const val ENTRY_FLAG_COMPACT = 0x0008
    const val SPEC_PUBLIC = 0x40000000
    const val NO_ENTRY = -1

    // ResTable_type flags
    const val TYPE_FLAG_SPARSE = 0x01
    const val TYPE_FLAG_OFFSET16 = 0x02
}

/** A typed value (Res_value). */
data class ResValue(val type: Int, val data: Int) {
    companion object {
        fun reference(id: Int) = ResValue(Res.TYPE_REFERENCE, id)
        fun string(poolIndex: Int) = ResValue(Res.TYPE_STRING, poolIndex)
        fun int(v: Int) = ResValue(Res.TYPE_INT_DEC, v)
        fun bool(v: Boolean) = ResValue(Res.TYPE_INT_BOOLEAN, if (v) -1 else 0)
    }
}

/** Little-endian growable buffer with patching, used to assemble chunks. */
class ByteBuf {
    private val out = ByteArrayOutputStream()
    private var patches = ArrayList<Pair<Int, Int>>() // (offset, u32 value) applied on toByteArray

    val size: Int get() = out.size()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
    fun u32(v: Int) = apply { u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun buf(b: ByteBuf) = apply { out.write(b.toByteArray()) }
    fun zeros(n: Int) = apply { repeat(n) { out.write(0) } }
    fun align4() = apply { while (out.size() % 4 != 0) out.write(0) }
    fun value(v: ResValue) = apply { u16(8); u8(0); u8(v.type); u32(v.data) }

    /** Reserves a u32 at the current position and returns its offset for [patch]. */
    fun placeholder(): Int { val at = size; u32(0); return at }
    fun patch(at: Int, v: Int) { patches += at to v }

    fun toByteArray(): ByteArray {
        val b = out.toByteArray()
        for ((at, v) in patches) {
            b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte(); b[at + 2] = (v ushr 16).toByte(); b[at + 3] = (v ushr 24).toByte()
        }
        return b
    }

    companion object {
        /** A chunk: header (type, header size, total size) + extra header fields + body. */
        fun chunk(type: Int, header: ByteBuf, body: ByteBuf): ByteBuf {
            val headerSize = 8 + header.size
            val c = ByteBuf().u16(type).u16(headerSize).u32(headerSize + body.size)
            return c.buf(header).buf(body)
        }
    }
}

/**
 * Builds a string pool (ResStringPool) in UTF-8. Strings are de-duplicated; [index] returns the
 * position of a string, adding it if needed. Strings added with [reserve] keep their order at the
 * start (needed for XML attribute names that map to resource ids).
 */
class StringPool {
    private val strings = ArrayList<String>()
    private val indexOf = HashMap<String, Int>()

    val count: Int get() = strings.size

    fun index(s: String): Int = indexOf.getOrPut(s) { strings += s; strings.size - 1 }

    fun indexOrNull(s: String): Int? = indexOf[s]

    fun get(i: Int): String = strings[i]

    fun encode(): ByteBuf {
        val data = ByteBuf()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { i, s ->
            offsets[i] = data.size
            val utf8 = s.toByteArray(Charsets.UTF_8)
            encodeLength8(data, s.length)
            encodeLength8(data, utf8.size)
            data.bytes(utf8).u8(0)
        }
        data.align4()
        val headerFields = 20 // stringCount, styleCount, flags, stringsStart, stylesStart
        val headerSize = 8 + headerFields
        val stringsStart = headerSize + 4 * strings.size
        val header = ByteBuf().u32(strings.size).u32(0).u32(UTF8_FLAG).u32(if (strings.isEmpty()) 0 else stringsStart).u32(0)
        val body = ByteBuf()
        offsets.forEach { body.u32(it) }
        body.buf(data)
        return ByteBuf.chunk(Res.STRING_POOL, header, body)
    }

    private fun encodeLength8(b: ByteBuf, len: Int) {
        require(len <= 0x7FFF) { "String too long for the resource string pool ($len)" }
        if (len > 0x7F) { b.u8(0x80 or (len shr 8)); b.u8(len and 0xFF) } else b.u8(len)
    }

    companion object {
        const val UTF8_FLAG = 0x100
    }
}

package dev.buildit.engine.build.res

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** One bag item (style item, attr symbol, array element...). */
data class BagItem(val key: Int, val value: ResValue)

/** An entry of a resource table for one configuration. */
data class TableEntry(
    val id: Int,
    val type: String,
    val name: String,
    val config: ByteArray,
    val value: ResValue? = null,
    val parent: Int = 0,
    val bag: List<BagItem>? = null,
) {
    override fun equals(other: Any?) = other is TableEntry && other.id == id && other.config.contentEquals(config)
    override fun hashCode() = id * 31 + config.contentHashCode()
}

/** Parsed resources.arsc: global value strings, and per package its entries and public flags. */
class ParsedTable(
    val strings: List<String>,
    val packages: List<ParsedPackage>,
)

class ParsedPackage(
    val id: Int,
    val name: String,
    val entries: List<TableEntry>,
    /** Resource id -> typeSpec flags (config mask, SPEC_PUBLIC). */
    val specFlags: Map<Int, Int>,
)

/**
 * Reader for compiled resource tables (resources.arsc). Handles the encodings used by current
 * platforms: dense, sparse and 16-bit offset type chunks and compact entries. Used to load the
 * framework's attributes from android.jar and to check our own output in tests.
 */
object ArscReader {
    fun read(bytes: ByteArray): ParsedTable {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getShort(0).toInt() and 0xFFFF == Res.TABLE) { "Not a resource table" }
        val headerSize = b.getShort(2).toInt() and 0xFFFF
        val total = b.getInt(4)
        var pos = headerSize
        var strings: List<String> = emptyList()
        val packages = mutableListOf<ParsedPackage>()
        while (pos < total) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val size = b.getInt(pos + 4)
            when (type) {
                Res.STRING_POOL -> strings = readPool(b, pos)
                Res.TABLE_PACKAGE -> packages += readPackage(b, pos)
            }
            pos += size
        }
        return ParsedTable(strings, packages)
    }

    private fun readPackage(b: ByteBuffer, start: Int): ParsedPackage {
        val headerSize = b.getShort(start + 2).toInt() and 0xFFFF
        val size = b.getInt(start + 4)
        val id = b.getInt(start + 8)
        val nameChars = CharArray(128) { b.getShort(start + 12 + it * 2).toInt().toChar() }
        val name = String(nameChars).substringBefore('\u0000')
        val typeStringsOffset = b.getInt(start + 268)
        val keyStringsOffset = b.getInt(start + 276)
        var typeNames: List<String> = emptyList()
        var keys: List<String> = emptyList()
        val entries = mutableListOf<TableEntry>()
        val specFlags = HashMap<Int, Int>()
        var pos = start + headerSize
        while (pos < start + size) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val chunkSize = b.getInt(pos + 4)
            when (type) {
                Res.STRING_POOL -> when (pos - start) {
                    typeStringsOffset -> typeNames = readPool(b, pos)
                    keyStringsOffset -> keys = readPool(b, pos)
                }
                Res.TABLE_TYPE_SPEC -> {
                    val typeId = b.get(pos + 8).toInt() and 0xFF
                    val count = b.getInt(pos + 12)
                    val hs = b.getShort(pos + 2).toInt() and 0xFFFF
                    for (i in 0 until count) specFlags[(id shl 24) or (typeId shl 16) or i] = b.getInt(pos + hs + i * 4)
                }
                Res.TABLE_TYPE -> readType(b, pos, id, typeNames, keys, entries)
            }
            pos += chunkSize
        }
        return ParsedPackage(id, name, entries, specFlags)
    }

    private fun readType(b: ByteBuffer, start: Int, pkg: Int, typeNames: List<String>, keys: List<String>, out: MutableList<TableEntry>) {
        val headerSize = b.getShort(start + 2).toInt() and 0xFFFF
        val typeId = b.get(start + 8).toInt() and 0xFF
        val flags = b.get(start + 9).toInt() and 0xFF
        val entryCount = b.getInt(start + 12)
        val entriesStart = b.getInt(start + 16)
        val configSize = b.getInt(start + 20)
        val config = ByteArray(configSize).also { arr -> for (i in arr.indices) arr[i] = b.get(start + 20 + i) }
        val typeName = typeNames.getOrElse(typeId - 1) { "type$typeId" }
        val offsets = start + headerSize
        fun entryAt(index: Int, offset: Int) {
            val e = start + entriesStart + offset
            val esize = b.getShort(e).toInt() and 0xFFFF
            val eflags = b.getShort(e + 2).toInt() and 0xFFFF
            val resId = (pkg shl 24) or (typeId shl 16) or index
            if (eflags and Res.ENTRY_FLAG_COMPACT != 0) {
                out += TableEntry(resId, typeName, keys.getOrElse(esize) { "?" }, config, ResValue(eflags ushr 8, b.getInt(e + 4)))
                return
            }
            val key = keys.getOrElse(b.getInt(e + 4)) { "?" }
            if (eflags and Res.ENTRY_FLAG_COMPLEX != 0) {
                val parent = b.getInt(e + 8)
                val count = b.getInt(e + 12)
                val items = (0 until count).map { i ->
                    val m = e + esize + i * 12
                    BagItem(b.getInt(m), ResValue(b.get(m + 7).toInt() and 0xFF, b.getInt(m + 8)))
                }
                out += TableEntry(resId, typeName, key, config, parent = parent, bag = items)
            } else {
                val v = e + esize
                out += TableEntry(resId, typeName, key, config, ResValue(b.get(v + 3).toInt() and 0xFF, b.getInt(v + 4)))
            }
        }
        when {
            flags and Res.TYPE_FLAG_SPARSE != 0 -> for (i in 0 until entryCount) {
                val idx = b.getShort(offsets + i * 4).toInt() and 0xFFFF
                val off = (b.getShort(offsets + i * 4 + 2).toInt() and 0xFFFF) * 4
                entryAt(idx, off)
            }
            flags and Res.TYPE_FLAG_OFFSET16 != 0 -> for (i in 0 until entryCount) {
                val off = b.getShort(offsets + i * 2).toInt() and 0xFFFF
                if (off != 0xFFFF) entryAt(i, off * 4)
            }
            else -> for (i in 0 until entryCount) {
                val off = b.getInt(offsets + i * 4)
                if (off != Res.NO_ENTRY) entryAt(i, off)
            }
        }
    }

    fun readPool(b: ByteBuffer, start: Int): List<String> {
        val count = b.getInt(start + 8)
        val flags = b.getInt(start + 16)
        val stringsStart = b.getInt(start + 20)
        val headerSize = b.getShort(start + 2).toInt() and 0xFFFF
        val utf8 = flags and StringPool.UTF8_FLAG != 0
        return (0 until count).map { i ->
            var p = start + stringsStart + b.getInt(start + headerSize + i * 4)
            if (utf8) {
                p += if (b.get(p).toInt() and 0x80 != 0) 2 else 1 // utf-16 length
                var len = b.get(p).toInt() and 0xFF
                if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (b.get(p + 1).toInt() and 0xFF); p += 2 } else p += 1
                val bytes = ByteArray(len) { b.get(p + it) }
                String(bytes, Charsets.UTF_8)
            } else {
                var len = b.getShort(p).toInt() and 0xFFFF
                if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or (b.getShort(p + 2).toInt() and 0xFFFF); p += 4 } else p += 2
                String(CharArray(len) { b.getShort(p + it * 2).toInt().toChar() })
            }
        }
    }
}

/** An attribute's definition: id, accepted formats and enum/flag symbols. */
data class AttrDef(val id: Int, val format: Int, val symbols: Map<String, Int> = emptyMap())

/**
 * The platform's resources (package `android`, id 0x01) from android.jar: name -> id for every
 * type, which ids are public, and attribute formats with their enum/flag symbols.
 */
class FrameworkResources private constructor(
    private val ids: Map<String, Int>,
    private val specFlags: Map<Int, Int>,
    private val attrs: Map<String, AttrDef>,
) {
    /** Resource id for `type/name` (e.g. `style/Theme.Material`), or null. */
    fun id(type: String, name: String): Int? = ids["$type/$name"]

    fun isPublic(id: Int): Boolean = (specFlags[id] ?: 0) and Res.SPEC_PUBLIC != 0

    fun attr(name: String): AttrDef? = attrs[name]

    companion object {
        fun load(androidJar: File): FrameworkResources {
            val bytes = ZipFile(androidJar).use { z ->
                val e = z.getEntry("resources.arsc") ?: error("${androidJar.name} has no resources.arsc")
                z.getInputStream(e).use { it.readBytes() }
            }
            return from(ArscReader.read(bytes))
        }

        fun from(table: ParsedTable): FrameworkResources {
            val pkg = table.packages.first { it.id == 0x01 }
            val ids = HashMap<String, Int>()
            val namesById = HashMap<Int, String>()
            pkg.entries.forEach { e -> ids.putIfAbsent("${e.type}/${e.name}", e.id); namesById.putIfAbsent(e.id, e.name) }
            val attrs = HashMap<String, AttrDef>()
            pkg.entries.filter { it.type == "attr" && it.bag != null }.forEach { e ->
                if (attrs.containsKey(e.name)) return@forEach
                var format = Res.FORMAT_ANY
                val symbols = LinkedHashMap<String, Int>()
                e.bag!!.forEach { item ->
                    when {
                        item.key == Res.ATTR_TYPE -> format = item.value.data
                        item.key ushr 24 == 0x01 && (item.key ushr 16) and 0xFF != 0 -> namesById[item.key]?.let { symbols[it] = item.value.data }
                    }
                }
                attrs[e.name] = AttrDef(e.id, format, symbols)
            }
            return FrameworkResources(ids, pkg.specFlags, attrs)
        }
    }
}

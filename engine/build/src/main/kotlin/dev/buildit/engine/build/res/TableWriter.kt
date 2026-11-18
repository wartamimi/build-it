package dev.buildit.engine.build.res

/** A compiled resource value: a single item or a bag (style, array, plurals). */
sealed interface Value {
    data class Simple(val item: Item) : Value
    data class Bag(val parent: Int, val items: List<Pair<Int, Item>>) : Value
}

/** One resource type of the app package: entry names by index and values per configuration. */
class TypeData(val id: Int, val name: String, val entries: List<String>) {
    val values = LinkedHashMap<ResConfig, MutableMap<Int, Value>>()
}

/** Writes resources.arsc for a single package (dense type chunks, UTF-8 pools). */
object TableWriter {
    fun write(packageId: Int, packageName: String, types: List<TypeData>): ByteArray {
        val values = StringPool()
        fun intern(item: Item) { item.string?.let(values::index) }
        types.forEach { t -> t.values.values.forEach { m -> m.values.forEach { v -> when (v) { is Value.Simple -> intern(v.item); is Value.Bag -> v.items.forEach { intern(it.second) } } } } }
        fun resolved(item: Item) = if (item.string != null) ResValue(item.type, values.index(item.string)) else ResValue(item.type, item.data)

        val typeNames = StringPool().apply { types.sortedBy { it.id }.forEach { index(it.name) } }
        val keys = StringPool().apply { types.forEach { t -> t.entries.forEach { index(it) } } }
        val typePool = typeNames.encode()
        val keyPool = keys.encode()

        val body = ByteBuf().buf(typePool).buf(keyPool)
        types.sortedBy { it.id }.forEach { t ->
            // typeSpec: which configuration kinds each entry varies on.
            val spec = ByteBuf()
            t.entries.indices.forEach { i ->
                var mask = 0
                t.values.forEach { (config, entries) -> if (entries.containsKey(i)) mask = mask or config.mask }
                spec.u32(mask)
            }
            body.buf(ByteBuf.chunk(Res.TABLE_TYPE_SPEC, ByteBuf().u8(t.id).u8(0).u16(t.values.size).u32(t.entries.size), spec))

            t.values.forEach { (config, entries) ->
                val offsets = ByteBuf()
                val data = ByteBuf()
                t.entries.indices.forEach { i ->
                    val v = entries[i]
                    if (v == null) { offsets.u32(Res.NO_ENTRY); return@forEach }
                    offsets.u32(data.size)
                    val key = keys.index(t.entries[i])
                    when (v) {
                        is Value.Simple -> data.u16(8).u16(0).u32(key).value(resolved(v.item))
                        is Value.Bag -> {
                            data.u16(16).u16(Res.ENTRY_FLAG_COMPLEX).u32(key).u32(v.parent).u32(v.items.size)
                            v.items.forEach { (name, item) -> data.u32(name).value(resolved(item)) }
                        }
                    }
                }
                val headerSize = 8 + 12 + ResConfig.SIZE
                val header = ByteBuf().u8(t.id).u8(0).u16(0).u32(t.entries.size).u32(headerSize + offsets.size).bytes(config.bytes)
                body.buf(ByteBuf.chunk(Res.TABLE_TYPE, header, ByteBuf().buf(offsets).buf(data)))
            }
        }

        val pkgHeader = ByteBuf().u32(packageId)
        val name = packageName.take(127)
        name.forEach { pkgHeader.u16(it.code) }
        pkgHeader.zeros((128 - name.length) * 2)
        val pkgHeaderSize = 8 + 4 + 256 + 20
        pkgHeader.u32(pkgHeaderSize).u32(types.size).u32(pkgHeaderSize + typePool.size).u32(keys.count).u32(0)
        val pkg = ByteBuf.chunk(Res.TABLE_PACKAGE, pkgHeader, body)

        // The value pool must be complete before encoding: resolved() above only reads it now.
        val table = ByteBuf().buf(values.encode()).buf(pkg)
        return ByteBuf.chunk(Res.TABLE, ByteBuf().u32(1), table).toByteArray()
    }
}

package dev.buildit.engine.build.res

import dev.buildit.engine.layout.DetailedXml
import dev.buildit.engine.layout.XmlElement
import dev.buildit.engine.layout.XmlText

/** An attribute resolved for binary XML: namespace, local name, resource id (0 = none) and value. */
internal data class CompiledAttr(val ns: String?, val name: String, val resId: Int, val raw: String?, val item: Item)

/**
 * Writes Android binary XML (ResXMLTree) from a parsed document. Attribute values are compiled by
 * [compileAttr]; returning null drops the attribute (used for the tools namespace). An undeclared
 * prefix is passed as `?prefix` so the caller can report it.
 */
internal class XmlCompiler(
    private val doc: DetailedXml,
    private val compileAttr: (element: XmlElement, ns: String?, local: String, value: String, line: Int) -> CompiledAttr?,
) {
    private val strings = ArrayList<String>()
    private val idStrings = LinkedHashMap<Int, Int>() // resource id -> pool index (these come first)
    private val plain = HashMap<String, Int>()

    fun compile(): ByteArray {
        // Pass 1: compile every element's attributes so the pool can start with id-bearing names.
        val compiled = HashMap<Long, List<CompiledAttr>>()
        val namespaces = HashMap<Long, List<Pair<String, String>>>()
        fun visit(e: XmlElement, scope: Map<String, String>) {
            val declared = e.attributes.filterKeys { it.startsWith("xmlns:") }.map { it.key.removePrefix("xmlns:") to it.value }
            namespaces[e.uid] = declared
            val inner = scope + declared
            val attrs = e.attributes.filterKeys { it != "xmlns" && !it.startsWith("xmlns:") }.mapNotNull { (qname, value) ->
                val prefix = qname.substringBefore(':', "")
                val local = qname.substringAfter(':')
                val ns = if (prefix.isEmpty()) null else inner[prefix] ?: run { compileAttr(e, "?$prefix", local, value, doc.line(e)); null } ?: return@mapNotNull null
                compileAttr(e, ns, local, value, doc.line(e))
            }
            compiled[e.uid] = attrs.sortedWith(attrOrder)
            e.elements.forEach { visit(it, inner) }
        }
        visit(doc.root, emptyMap())
        val ids = compiled.values.flatten().filter { it.resId != 0 }.map { it.resId to it.name }.distinctBy { it.first }.sortedBy { it.first }
        ids.forEach { (id, name) -> strings += name; idStrings[id] = strings.size - 1 }

        // Pass 2: emit chunks.
        val body = ByteBuf()
        fun emit(e: XmlElement) {
            val line = doc.line(e)
            val nsDecls = namespaces.getValue(e.uid)
            nsDecls.forEach { (prefix, uri) -> body.buf(ByteBuf.chunk(Res.XML_START_NAMESPACE, ByteBuf().u32(line).u32(-1), ByteBuf().u32(str(prefix)).u32(str(uri)))) }
            val attrs = compiled.getValue(e.uid)
            val ext = ByteBuf()
            ext.u32(-1).u32(str(e.name)).u16(20).u16(20).u16(attrs.size)
            ext.u16(attrs.indexOfFirst { it.ns == ANDROID_NS && it.name == "id" } + 1)
            ext.u16(attrs.indexOfFirst { it.ns == null && it.name == "class" } + 1)
            ext.u16(attrs.indexOfFirst { it.ns == null && it.name == "style" } + 1)
            attrs.forEach { a ->
                val name = if (a.resId != 0) idStrings.getValue(a.resId) else str(a.name)
                val value = if (a.item.string != null) ResValue(a.item.type, str(a.item.string)) else ResValue(a.item.type, a.item.data)
                val raw = when {
                    a.item.string != null -> value.data
                    a.raw != null -> str(a.raw)
                    else -> -1
                }
                ext.u32(a.ns?.let(::str) ?: -1).u32(name).u32(raw).value(value)
            }
            body.buf(ByteBuf.chunk(Res.XML_START_ELEMENT, ByteBuf().u32(line).u32(-1), ext))
            e.children.forEach { c ->
                when (c) {
                    is XmlElement -> emit(c)
                    is XmlText -> if (c.text.isNotBlank()) {
                        body.buf(ByteBuf.chunk(Res.XML_CDATA, ByteBuf().u32(line).u32(-1), ByteBuf().u32(str(c.text)).value(ResValue(Res.TYPE_NULL, 0))))
                    }
                    else -> Unit
                }
            }
            body.buf(ByteBuf.chunk(Res.XML_END_ELEMENT, ByteBuf().u32(line).u32(-1), ByteBuf().u32(-1).u32(str(e.name))))
            nsDecls.asReversed().forEach { (prefix, uri) -> body.buf(ByteBuf.chunk(Res.XML_END_NAMESPACE, ByteBuf().u32(line).u32(-1), ByteBuf().u32(str(prefix)).u32(str(uri)))) }
        }
        emit(doc.root)

        // Id-bearing names keep their leading slots even if the same text appears again later.
        val poolBytes = encodePool()
        val resMap = ByteBuf().apply { ids.forEach { u32(it.first) } }
        val content = ByteBuf().buf(poolBytes).buf(ByteBuf.chunk(Res.XML_RESOURCE_MAP, ByteBuf(), resMap)).buf(body)
        return ByteBuf.chunk(Res.XML, ByteBuf(), content).toByteArray()
    }

    /** Index of a string that is not an id-bearing attribute name. */
    private fun str(s: String): Int = plain.getOrPut(s) { strings += s; strings.size - 1 }

    /** Encodes [strings] verbatim (duplicates allowed, as id-bearing names must keep their slots). */
    private fun encodePool(): ByteBuf {
        val data = ByteBuf()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { i, s ->
            offsets[i] = data.size
            val utf8 = s.toByteArray(Charsets.UTF_8)
            len8(data, s.length); len8(data, utf8.size)
            data.bytes(utf8).u8(0)
        }
        data.align4()
        val header = ByteBuf().u32(strings.size).u32(0).u32(StringPool.UTF8_FLAG).u32(8 + 20 + 4 * strings.size).u32(0)
        val body = ByteBuf().apply { offsets.forEach { u32(it) } }.buf(data)
        return ByteBuf.chunk(Res.STRING_POOL, header, body)
    }

    private fun len8(b: ByteBuf, len: Int) {
        require(len <= 0x7FFF) { "String too long ($len)" }
        if (len > 0x7F) { b.u8(0x80 or (len shr 8)); b.u8(len and 0xFF) } else b.u8(len)
    }

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NS = "http://schemas.android.com/tools"
        const val AUTO_NS = "http://schemas.android.com/apk/res-auto"

        /** Attributes with resource ids first (ascending), then the rest by namespace and name. */
        private val attrOrder = Comparator<CompiledAttr> { a, b ->
            when {
                a.resId != 0 && b.resId != 0 -> Integer.compareUnsigned(a.resId, b.resId)
                a.resId != 0 -> -1
                b.resId != 0 -> 1
                else -> compareValuesBy(a, b, { it.ns ?: "" }, { it.name })
            }
        }
    }
}

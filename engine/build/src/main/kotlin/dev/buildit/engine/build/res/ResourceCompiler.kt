package dev.buildit.engine.build.res

import dev.buildit.engine.build.Diagnostics
import dev.buildit.engine.layout.ComponentCatalog
import dev.buildit.engine.layout.DetailedXml
import dev.buildit.engine.layout.XmlComment
import dev.buildit.engine.layout.XmlElement
import dev.buildit.engine.layout.XmlParseException
import dev.buildit.engine.layout.XmlParser
import dev.buildit.engine.layout.XmlText
import java.io.File

/** Settings that the build writes into the manifest (they come from the project, like AGP does). */
data class ManifestOptions(
    val applicationId: String,
    val namespace: String,
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val debuggable: Boolean,
)

class CompiledResources(
    val table: ByteArray,
    val manifest: ByteArray,
    /** APK path -> contents (compiled XML or raw files). */
    val files: Map<String, ByteArray>,
    val rJava: String,
    /** `type/name` -> id, for tooling and tests. */
    val ids: Map<String, Int>,
)

/**
 * Compiles `res/` and the manifest into resources.arsc, binary XML files and R.java, entirely in
 * Kotlin (no aapt2 binary). Supported: values (string, color, dimen, bool, integer, fraction, item,
 * id, style, string-array, integer-array, array, plurals), XML files (layout, menu, drawable, color,
 * anim, animator, xml, ...), raw/bitmap files copied as they are, and common qualifiers. Anything
 * else is reported as an error with its file and line rather than being skipped.
 */
class ResourceCompiler(
    private val framework: FrameworkResources,
    private val diagnostics: Diagnostics,
    /** Turns a file into the project-relative path used in diagnostics. */
    private val relative: (File) -> String = { it.path },
) {
    private class Pending(val type: String, val name: String, val config: ResConfig, val element: XmlElement?, val file: File, val line: Int, val apkPath: String? = null, val doc: DetailedXml? = null)

    private val pending = mutableListOf<Pending>()
    private val declaredIds = LinkedHashSet<String>()
    private val ids = HashMap<String, Int>()
    private val types = LinkedHashMap<String, TypeData>()
    private val files = LinkedHashMap<String, ByteArray>()

    fun compile(resDir: File?, manifestFile: File, options: ManifestOptions): CompiledResources? {
        if (resDir != null && resDir.isDirectory) collect(resDir)
        assignIds()
        pending.forEach { p -> if (p.apkPath == null) compileValue(p) else compileFile(p) }
        val manifest = compileManifest(manifestFile, options)
        if (diagnostics.hasErrors || manifest == null) return null
        val table = TableWriter.write(PACKAGE_ID, options.applicationId, types.values.toList())
        return CompiledResources(table, manifest, files, rJava(options.namespace), ids.toMap())
    }

    // ------------------------------------------------------------------ collect

    private fun collect(resDir: File) {
        resDir.listFiles().orEmpty().sortedBy { it.name }.forEach { dir ->
            if (!dir.isDirectory) { diagnostics.warning("Ignored: files directly in res/ are not resources", relative(dir)); return@forEach }
            val type = dir.name.substringBefore('-')
            val qualifiers = dir.name.substringAfter('-', "")
            if (type !in FILE_TYPES && type != "values") { diagnostics.error("Unknown resource directory '${dir.name}'", relative(dir)); return@forEach }
            val config = ResConfig.parse(qualifiers).getOrElse { diagnostics.error("${it.message} in '${dir.name}'", relative(dir)); return@forEach }
            dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name }.forEach { f ->
                if (type == "values") collectValues(f, config) else collectFile(f, type, config, dir.name)
            }
        }
    }

    private fun parse(f: File): DetailedXml? = try {
        XmlParser.parseDetailed(f.readText())
    } catch (e: XmlParseException) {
        diagnostics.error(e.message?.substringBefore(" (line") ?: "Invalid XML", relative(f), e.line); null
    }

    private fun collectValues(f: File, config: ResConfig) {
        if (!f.name.endsWith(".xml")) { diagnostics.warning("Ignored: not an XML file", relative(f)); return }
        val doc = parse(f) ?: return
        if (doc.root.name != "resources") { diagnostics.error("Values files must have <resources> as the root element", relative(f), doc.line(doc.root)); return }
        doc.root.elements.forEach { e ->
            val line = doc.line(e)
            when (e.name) {
                "eat-comment", "skip" -> return@forEach
                "declare-styleable", "attr", "public", "public-group", "overlayable", "staging-public-group", "macro" -> {
                    diagnostics.error("<${e.name}> is not supported by the on-device builder yet", relative(f), line); return@forEach
                }
            }
            val type = when (e.name) {
                "string", "color", "dimen", "bool", "integer", "fraction", "style", "plurals" -> e.name
                "string-array", "integer-array", "array" -> "array"
                "item" -> e["type"] ?: run { diagnostics.error("<item> needs a type attribute", relative(f), line); return@forEach }
                else -> { diagnostics.error("Unknown resource element <${e.name}>", relative(f), line); return@forEach }
            }
            val name = e["name"] ?: run { diagnostics.error("<${e.name}> needs a name attribute", relative(f), line); return@forEach }
            if (!validName(name, allowDots = type == "style")) { diagnostics.error("Invalid resource name '$name'", relative(f), line); return@forEach }
            if (type == "id") { declaredIds += name; return@forEach }
            if (pending.any { it.type == type && it.name == name && it.config == config }) {
                diagnostics.error("Duplicate resource $type/$name${if (config.isDefault) "" else " ($config)"}", relative(f), line); return@forEach
            }
            pending += Pending(type, name, config, e, f, line, doc = doc)
        }
    }

    private fun collectFile(f: File, type: String, config: ResConfig, dirName: String) {
        val name = f.name.substringBefore('.')
        val ext = f.name.substringAfter('.', "").lowercase()
        if (!validName(name, allowDots = false)) { diagnostics.error("Invalid file name '${f.name}': use lowercase letters, digits and _", relative(f)); return }
        if (ext == "9.png") { diagnostics.error("Nine-patch images are not supported by the on-device builder yet", relative(f)); return }
        if (pending.any { it.type == type && it.name == name && it.config == config }) { diagnostics.error("Duplicate resource $type/$name", relative(f)); return }
        val apkPath = "res/$dirName/${f.name}"
        val doc = if (ext == "xml" && type != "raw") parse(f) ?: return else null
        doc?.root?.walk()?.forEach { e ->
            e.attributes.values.forEach { v -> Reference.parse(v)?.takeIf { it.create && it.type == "id" && it.pkg == null }?.let { declaredIds += it.name } }
            if (type == "layout" || type == "menu") ComponentCatalog.all.firstOrNull { it.tag == e.name }?.requiresDependency?.let { dep ->
                diagnostics.error("<${e.name.substringAfterLast('.')}> needs the library $dep; the on-device builder does not support dependencies yet", relative(f), doc.line(e))
            }
        }
        pending += Pending(type, name, config, null, f, 0, apkPath, doc)
    }

    private fun validName(n: String, allowDots: Boolean) =
        n.isNotEmpty() && (n[0].isLetter() || n[0] == '_') && n.all { it.isLetterOrDigit() || it == '_' || (allowDots && it == '.') }

    // ------------------------------------------------------------------ ids

    private fun assignIds() {
        val names = LinkedHashMap<String, MutableSet<String>>()
        pending.forEach { names.getOrPut(it.type) { sortedSetOf() } += it.name }
        if (declaredIds.isNotEmpty()) names.getOrPut("id") { sortedSetOf() } += declaredIds
        var typeId = 1
        TYPE_ORDER.filter { names.containsKey(it) }.forEach { type ->
            val entries = names.getValue(type).sorted()
            val td = TypeData(typeId, type, entries)
            types[type] = td
            entries.forEachIndexed { i, n -> ids["$type/$n"] = (PACKAGE_ID shl 24) or (typeId shl 16) or i }
            typeId++
        }
        names.keys.filter { it !in TYPE_ORDER }.forEach { diagnostics.error("Unsupported resource type '$it'") }
        types["id"]?.let { td -> td.entries.indices.forEach { i -> put("id", i, ResConfig.DEFAULT, Value.Simple(Item(Res.TYPE_INT_BOOLEAN, 0))) } }
    }

    private fun put(type: String, index: Int, config: ResConfig, value: Value) {
        types.getValue(type).values.getOrPut(config) { sortedMapOf() }[index] = value
    }

    private fun put(p: Pending, value: Value) {
        val td = types.getValue(p.type)
        put(p.type, td.entries.indexOf(p.name), p.config, value)
    }

    // ------------------------------------------------------------------ references and values

    /** Resolves a reference to a resource id (0 for @null), reporting problems at [file]:[line]. */
    private fun resolve(ref: Reference, file: File, line: Int): Int? {
        if (ref.isFramework) {
            val id = framework.id(ref.type, ref.name)
            if (id == null) { diagnostics.error("Resource android:${ref.type}/${ref.name} does not exist", relative(file), line); return null }
            if (!framework.isPublic(id)) { diagnostics.error("Resource android:${ref.type}/${ref.name} is private", relative(file), line); return null }
            return id
        }
        if (ref.pkg != null) { diagnostics.error("Unknown package '${ref.pkg}' in reference", relative(file), line); return null }
        ids["${ref.type}/${ref.name}"]?.let { return it }
        if (ref.type == "attr") diagnostics.error("Attribute '${ref.name}' is not defined (only android: attributes are supported yet)", relative(file), line)
        else diagnostics.error("Resource ${ref.type}/${ref.name} does not exist", relative(file), line)
        return null
    }

    /** Compiles `@...`/`?...`; null when [text] is not a reference, [FAILED] when it could not be resolved. */
    private fun reference(text: String, file: File, line: Int): Item? {
        val t = text.trim()
        if (t == "@null") return Item(Res.TYPE_REFERENCE, 0)
        if (t == "@empty") return Item(Res.TYPE_NULL, Res.DATA_NULL_EMPTY)
        if (!t.startsWith("@") && !t.startsWith("?")) return null
        val ref = Reference.parse(t) ?: run { diagnostics.error("Invalid reference '$t'", relative(file), line); return FAILED }
        val id = resolve(ref, file, line) ?: return FAILED
        return Item(if (ref.attribute) Res.TYPE_ATTRIBUTE else Res.TYPE_REFERENCE, id)
    }

    /** Compiles [text] for an attribute with [formats] and [symbols]; null with an error when incompatible. */
    private fun forAttribute(text: String, formats: Int, symbols: Map<String, Int>, what: String, file: File, line: Int): Item? {
        reference(text, file, line)?.let { return it.takeUnless { it === FAILED } }
        val t = text.trim()
        if (formats and Res.FORMAT_ENUM != 0) symbols[t]?.let { return Item(Res.TYPE_INT_DEC, it) }
        if (formats and Res.FORMAT_FLAGS != 0) {
            val parts = t.split('|').map { it.trim() }
            if (parts.all { it in symbols }) return Item(Res.TYPE_INT_HEX, parts.fold(0) { acc, p -> acc or symbols.getValue(p) })
        }
        if (formats and Res.FORMAT_COLOR != 0) Literals.color(t)?.let { return Item.of(it) }
        if (formats and Res.FORMAT_BOOLEAN != 0) Literals.bool(t)?.let { return Item.of(it) }
        if (formats and Res.FORMAT_INTEGER != 0) Literals.integer(t)?.let { return Item.of(it) }
        if (formats and (Res.FORMAT_FLOAT or Res.FORMAT_DIMENSION or Res.FORMAT_FRACTION) != 0) Literals.floatLike(t, formats)?.let { return Item.of(it) }
        if (formats and Res.FORMAT_STRING != 0) return Item.text(Literals.string(text, collapse = false))
        val hint = if (symbols.isNotEmpty()) " (one of: ${symbols.keys.take(12).joinToString()})" else ""
        diagnostics.error("'$t' is not a valid value for $what, which expects ${Literals.formatName(formats)}$hint", relative(file), line)
        return null
    }

    private fun textOf(e: XmlElement, file: File, line: Int): String {
        val sb = StringBuilder()
        var styled = false
        fun walk(n: XmlElement) {
            n.children.forEach { c ->
                when (c) {
                    is XmlText -> sb.append(c.text)
                    is XmlElement -> { if (!c.name.startsWith("xliff:")) styled = true; walk(c) }
                    is XmlComment -> Unit
                }
            }
        }
        walk(e)
        if (styled) diagnostics.warning("Styled text (<b>, <i>, ...) is not supported yet; it is compiled as plain text", relative(file), line)
        return sb.toString()
    }

    private fun stringItem(e: XmlElement, file: File, line: Int): Item? {
        val raw = textOf(e, file, line)
        reference(raw, file, line)?.let { return it.takeUnless { it === FAILED } }
        return Item.text(Literals.string(raw))
    }

    private fun typed(text: String, formats: Int, what: String, file: File, line: Int): Item? = forAttribute(text, formats, emptyMap(), what, file, line)

    private fun compileValue(p: Pending) {
        val e = p.element!!
        val what = "${p.type} '${p.name}'"
        val value: Value? = when (p.type) {
            "string" -> stringItem(e, p.file, p.line)?.let(Value::Simple)
            "color" -> typed(textOf(e, p.file, p.line), Res.FORMAT_COLOR, what, p.file, p.line)?.let(Value::Simple)
            "dimen" -> typed(textOf(e, p.file, p.line), Res.FORMAT_DIMENSION or Res.FORMAT_FLOAT, what, p.file, p.line)?.let(Value::Simple)
            "bool" -> typed(textOf(e, p.file, p.line), Res.FORMAT_BOOLEAN, what, p.file, p.line)?.let(Value::Simple)
            "integer" -> typed(textOf(e, p.file, p.line), Res.FORMAT_INTEGER, what, p.file, p.line)?.let(Value::Simple)
            "fraction" -> typed(textOf(e, p.file, p.line), Res.FORMAT_FRACTION, what, p.file, p.line)?.let(Value::Simple)
            "style" -> style(p)
            "array" -> array(p)
            "plurals" -> plurals(p)
            else -> {
                // <item type="..." format="...">: format decides, else the type's natural format.
                val formats = e["format"]?.let(::formatOf) ?: when (p.type) {
                    "string" -> Res.FORMAT_STRING
                    "color" -> Res.FORMAT_COLOR
                    "dimen" -> Res.FORMAT_DIMENSION or Res.FORMAT_FLOAT
                    "bool" -> Res.FORMAT_BOOLEAN
                    "integer" -> Res.FORMAT_INTEGER
                    else -> Res.FORMAT_REFERENCE
                }
                typed(textOf(e, p.file, p.line), formats, what, p.file, p.line)?.let(Value::Simple)
            }
        }
        if (value != null) put(p, value)
    }

    private fun formatOf(s: String): Int = s.split('|').fold(0) { acc, f ->
        acc or when (f.trim()) {
            "reference" -> Res.FORMAT_REFERENCE; "string" -> Res.FORMAT_STRING; "integer" -> Res.FORMAT_INTEGER
            "boolean" -> Res.FORMAT_BOOLEAN; "color" -> Res.FORMAT_COLOR; "float" -> Res.FORMAT_FLOAT
            "dimension" -> Res.FORMAT_DIMENSION; "fraction" -> Res.FORMAT_FRACTION
            else -> Res.FORMAT_ANY
        }
    }

    private fun style(p: Pending): Value? {
        val e = p.element!!
        val parentText = e["parent"]
        val parent = when {
            parentText == null -> p.name.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }?.let { ids["style/$it"] } ?: 0
            parentText.isBlank() -> 0
            else -> {
                var t = parentText.trim().removePrefix("@")
                val pkg = if (t.contains(':')) t.substringBefore(':') else null
                t = t.substringAfter(':').removePrefix("style/")
                resolve(Reference(pkg, "style", t), p.file, p.line) ?: return null
            }
        }
        val items = mutableListOf<Pair<Int, Item>>()
        var ok = true
        e.elements.forEach { item ->
            val line = p.doc?.line(item) ?: p.line
            if (item.name != "item") { diagnostics.error("Unexpected <${item.name}> in <style>", relative(p.file), line); ok = false; return@forEach }
            val attrName = item["name"] ?: run { diagnostics.error("<item> needs a name", relative(p.file), line); ok = false; return@forEach }
            val local = attrName.removePrefix("android:")
            val def = if (attrName.startsWith("android:")) framework.attr(local) else null
            if (def == null) {
                diagnostics.error(
                    if (attrName.startsWith("android:")) "Unknown attribute $attrName" else "Attribute '$attrName' is not defined; only android: attributes are supported yet (AppCompat/Material themes need dependencies)",
                    relative(p.file), line,
                )
                ok = false; return@forEach
            }
            if (!framework.isPublic(def.id)) { diagnostics.error("Attribute $attrName is private", relative(p.file), line); ok = false; return@forEach }
            val v = forAttribute(textOf(item, p.file, line), def.format, def.symbols, attrName, p.file, line) ?: run { ok = false; return@forEach }
            items += def.id to v
        }
        if (!ok) return null
        return Value.Bag(parent, items.sortedWith { a, b -> Integer.compareUnsigned(a.first, b.first) })
    }

    private fun array(p: Pending): Value? {
        val e = p.element!!
        var ok = true
        val items = e.elements.filter { it.name == "item" }.mapIndexedNotNull { i, item ->
            val line = p.doc?.line(item) ?: p.line
            val v = when (e.name) {
                "string-array" -> stringItem(item, p.file, line)
                "integer-array" -> typed(textOf(item, p.file, line), Res.FORMAT_INTEGER, "an integer-array item", p.file, line)
                else -> {
                    // <array>: references and literals keep their type; anything else is text.
                    val t = textOf(item, p.file, line)
                    reference(t, p.file, line)?.takeUnless { it === FAILED }
                        ?: (Literals.color(t) ?: Literals.bool(t) ?: Literals.integer(t) ?: Literals.floatLike(t, Res.FORMAT_FLOAT or Res.FORMAT_DIMENSION))?.let(Item::of)
                        ?: Item.text(Literals.string(t))
                }
            }
            if (v == null) { ok = false; null } else (Res.ATTR_MIN + i) to v
        }
        return if (ok) Value.Bag(0, items) else null
    }

    private fun plurals(p: Pending): Value? {
        val keys = mapOf("other" to Res.ATTR_OTHER, "zero" to Res.ATTR_ZERO, "one" to Res.ATTR_ONE, "two" to Res.ATTR_TWO, "few" to Res.ATTR_FEW, "many" to Res.ATTR_MANY)
        var ok = true
        val items = p.element!!.elements.mapNotNull { item ->
            val line = p.doc?.line(item) ?: p.line
            val key = keys[item["quantity"]] ?: run { diagnostics.error("<item> in <plurals> needs quantity (zero, one, two, few, many, other)", relative(p.file), line); ok = false; return@mapNotNull null }
            val v = stringItem(item, p.file, line) ?: run { ok = false; return@mapNotNull null }
            key to v
        }
        return if (ok) Value.Bag(0, items.sortedWith { a, b -> Integer.compareUnsigned(a.first, b.first) }) else null
    }

    // ------------------------------------------------------------------ XML files

    private fun compileFile(p: Pending) {
        put(p, Value.Simple(Item.text(p.apkPath!!)))
        val doc = p.doc
        if (doc == null) { files[p.apkPath] = p.file.readBytes(); return }
        files[p.apkPath] = compileXml(doc, p.file) ?: return
    }

    private fun compileXml(doc: DetailedXml, file: File): ByteArray? {
        val before = diagnostics.all.size
        val bytes = XmlCompiler(doc) { element, ns, local, value, line -> attribute(element, ns, local, value, file, line) }.compile()
        return bytes.takeIf { diagnostics.all.drop(before).none { it.severity == dev.buildit.engine.build.BuildDiagnostic.Severity.Error } }
    }

    private fun attribute(e: XmlElement, ns: String?, local: String, value: String, file: File, line: Int): CompiledAttr? {
        when {
            ns == null -> {
                val item = reference(value, file, line)?.takeUnless { it === FAILED } ?: Item.text(value)
                return CompiledAttr(null, local, 0, value.takeIf { item.string == null }, item)
            }
            ns.startsWith("?") -> { diagnostics.error("Undeclared namespace prefix '${ns.drop(1)}' on <${e.name}>", relative(file), line); return null }
            ns == XmlCompiler.TOOLS_NS -> return null
            ns == XmlCompiler.ANDROID_NS -> {
                val def = framework.attr(local)
                if (def == null) { diagnostics.error("Unknown attribute android:$local on <${e.name}>", relative(file), line); return null }
                if (!framework.isPublic(def.id)) { diagnostics.error("Attribute android:$local is private", relative(file), line); return null }
                val item = forAttribute(value, def.format, def.symbols, "android:$local", file, line) ?: return null
                return CompiledAttr(ns, local, def.id, null, item)
            }
            else -> {
                diagnostics.error("Attribute '$local' in namespace $ns needs a library; the on-device builder does not support dependencies yet", relative(file), line)
                return null
            }
        }
    }

    // ------------------------------------------------------------------ manifest

    private fun compileManifest(file: File, o: ManifestOptions): ByteArray? {
        if (!file.isFile) { diagnostics.error("AndroidManifest.xml is missing", relative(file)); return null }
        val doc = parse(file) ?: return null
        if (doc.root.name != "manifest") { diagnostics.error("The manifest's root element must be <manifest>", relative(file), doc.line(doc.root)); return null }
        val prefix = doc.root.attributes.entries.firstOrNull { it.key.startsWith("xmlns:") && it.value == XmlCompiler.ANDROID_NS }?.key?.removePrefix("xmlns:")
        if (prefix == null) { diagnostics.error("The manifest must declare xmlns:android", relative(file), doc.line(doc.root)); return null }
        fun a(n: String) = "$prefix:$n"
        fun qualify(name: String) = when {
            name.startsWith(".") -> o.namespace + name
            !name.contains('.') -> "${o.namespace}.$name"
            else -> name
        }
        val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider", "application")

        fun transform(e: XmlElement): XmlElement {
            var el = e
            if (el.name in componentTags) {
                el[a("name")]?.let { el = el.withAttribute(a("name"), qualify(it)) }
                el[a("targetActivity")]?.let { el = el.withAttribute(a("targetActivity"), qualify(it)) }
            }
            if (el.name == "application" && o.debuggable && el[a("debuggable")] == null) el = el.withAttribute(a("debuggable"), "true")
            if (el.name == "uses-sdk") {
                el = el.withAttribute(a("minSdkVersion"), o.minSdk.toString()).withAttribute(a("targetSdkVersion"), o.targetSdk.toString())
            }
            return el.copy(children = el.children.map { if (it is XmlElement) transform(it) else it })
        }

        var root = transform(doc.root)
            .withAttribute("package", o.applicationId)
            .withAttribute(a("versionCode"), o.versionCode.toString())
            .withAttribute(a("versionName"), o.versionName)
        if (root.elements.none { it.name == "uses-sdk" }) {
            val usesSdk = XmlElement(-1, "uses-sdk", linkedMapOf(a("minSdkVersion") to o.minSdk.toString(), a("targetSdkVersion") to o.targetSdk.toString()))
            root = root.copy(children = listOf(usesSdk) + root.children)
        }
        // Copies keep their uids, so the original line numbers still apply.
        val lines = doc.root.walk().associate { it.uid to doc.line(it) }
        return compileXml(DetailedXml(root, lines), file)
    }

    // ------------------------------------------------------------------ R.java

    private fun rJava(pkg: String): String = buildString {
        appendLine("/* Generated by Build It from res/. Do not edit: it is rewritten on every build. */")
        appendLine("package $pkg;")
        appendLine()
        appendLine("public final class R {")
        appendLine("    private R() {}")
        types.values.forEach { t ->
            appendLine()
            appendLine("    public static final class ${t.name} {")
            appendLine("        private ${t.name}() {}")
            t.entries.forEach { n ->
                appendLine("        public static final int ${n.replace('.', '_')} = 0x${Integer.toHexString(ids.getValue("${t.name}/$n"))};")
            }
            appendLine("    }")
        }
        appendLine("}")
    }

    companion object {
        const val PACKAGE_ID = 0x7f
        private val FAILED = Item(-1)
        private val FILE_TYPES = setOf("anim", "animator", "color", "drawable", "font", "interpolator", "layout", "menu", "mipmap", "raw", "transition", "xml")
        private val TYPE_ORDER = listOf(
            "anim", "animator", "array", "bool", "color", "dimen", "drawable", "font", "fraction", "id", "integer",
            "interpolator", "layout", "menu", "mipmap", "plurals", "raw", "string", "style", "transition", "xml",
        )
    }
}

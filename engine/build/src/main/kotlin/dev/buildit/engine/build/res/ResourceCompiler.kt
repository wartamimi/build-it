package dev.buildit.engine.build.res

import dev.buildit.engine.build.BuildDiagnostic
import dev.buildit.engine.build.Diagnostics
import dev.buildit.engine.layout.ComponentCatalog
import dev.buildit.engine.layout.DetailedXml
import dev.buildit.engine.layout.XmlComment
import dev.buildit.engine.layout.XmlElement
import dev.buildit.engine.layout.XmlNode
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
    /** Adds the console bridge provider and the package query it needs (debug builds only). */
    val debugBridge: dev.buildit.engine.build.DebugBridge.Host? = null,
)

/**
 * A directory of resources. Sources are given lowest priority first: a resource defined again by a
 * later source (same type, name and configuration) replaces the earlier one, as with AGP's
 * library-then-app merge. [label] names a library in messages; null is the app itself.
 */
data class ResSource(val dir: File, val label: String? = null)

/** A library's manifest and the package its relative class names are relative to. */
data class LibraryManifest(val file: File, val label: String)

class CompiledResources(
    val table: ByteArray,
    val manifest: ByteArray,
    /** APK path -> contents (compiled XML or raw files). */
    val files: Map<String, ByteArray>,
    val rJava: String,
    /** `type/name` -> id, for tooling and tests. */
    val ids: Map<String, Int>,
    /** Java source of R for each library package (package -> source); see [ResourceCompiler.libraryR]. */
    val libraryRJava: Map<String, String> = emptyMap(),
)

/**
 * Compiles `res/` (the app's and its libraries') and the merged manifest into resources.arsc,
 * binary XML files and R classes, entirely in Kotlin (no aapt2 binary). Supported: values
 * (string, color, dimen, bool, integer, fraction, item, id, style, arrays, plurals, attr,
 * declare-styleable, macro), XML files (layout, menu, drawable, ...) including inline
 * `<aapt:attr>` resources, nine-patch images, raw/bitmap files, app (`res-auto`) attributes and
 * the platform's configuration qualifiers. Anything else is reported as an error with its file and
 * line rather than being skipped.
 */
class ResourceCompiler(
    private val framework: FrameworkResources,
    private val diagnostics: Diagnostics,
    /** Turns a file into the path used in diagnostics. */
    private val relative: (File) -> String = { it.path },
    /** Library modules (`group:artifact`) present, for widgets that need one. */
    private val availableModules: Set<String> = emptySet(),
) {
    private class Pending(
        val type: String, val name: String, val config: ResConfig, val element: XmlElement?, val file: File, val line: Int,
        val apkPath: String? = null, val doc: DetailedXml? = null, val source: Int = 0, val bytes: ByteArray? = null,
    )

    /** An attribute defined by the app or a library. [strong] when it states a format or symbols. */
    private class AttrSpec(val name: String, var format: Int, val symbols: LinkedHashMap<String, Int>, var flags: Boolean, var strong: Boolean)

    private val pending = LinkedHashMap<String, Pending>()
    private val declaredIds = LinkedHashSet<String>()
    private val attrs = LinkedHashMap<String, AttrSpec>()
    private val styleables = LinkedHashMap<String, LinkedHashSet<String>>()
    private val macros = HashMap<String, String>()
    private val ids = HashMap<String, Int>()
    private val types = LinkedHashMap<String, TypeData>()
    private val files = LinkedHashMap<String, ByteArray>()
    private var sources: List<ResSource> = emptyList()

    fun compile(resDir: File?, manifestFile: File, options: ManifestOptions): CompiledResources? =
        compile(listOfNotNull(resDir?.let { ResSource(it) }), manifestFile, emptyList(), options, emptyList())

    /**
     * [resSources] lowest priority first (libraries, then generated values, then the app).
     * [libraryPackages] get R classes that see every resource, like AGP's non-namespaced R.
     */
    fun compile(
        resSources: List<ResSource>,
        manifestFile: File,
        libraryManifests: List<LibraryManifest>,
        options: ManifestOptions,
        libraryPackages: List<String>,
    ): CompiledResources? {
        sources = resSources
        resSources.forEachIndexed { i, s -> if (s.dir.isDirectory) collect(s.dir, i) }
        assignIds()
        pending.values.forEach { p -> if (p.apkPath == null) compileValue(p) else compileFile(p) }
        val manifest = compileManifest(manifestFile, libraryManifests, options)
        if (diagnostics.hasErrors || manifest == null) return null
        val table = TableWriter.write(PACKAGE_ID, options.applicationId, types.values.toList())
        val styleableIds = styleableArrays()
        val libR = libraryPackages.filter { it != options.namespace }.distinct().associateWith { libraryR(it, options.namespace, styleableIds.isNotEmpty()) }
        return CompiledResources(table, manifest, files, rJava(options.namespace, styleableIds), ids.toMap(), libR)
    }

    private fun isLibrary(source: Int) = sources.getOrNull(source)?.label != null

    private fun warn(source: Int, message: String, file: File, line: Int = 0) {
        // Libraries are not the user's to fix; only their errors are shown.
        if (!isLibrary(source)) diagnostics.warning(message, relative(file), line)
    }

    // ------------------------------------------------------------------ collect

    private fun collect(resDir: File, source: Int) {
        resDir.listFiles().orEmpty().sortedBy { it.name }.forEach { dir ->
            if (!dir.isDirectory) { warn(source, "Ignored: files directly in res/ are not resources", dir); return@forEach }
            val type = dir.name.substringBefore('-')
            val qualifiers = dir.name.substringAfter('-', "")
            if (type !in FILE_TYPES && type != "values") { diagnostics.error("Unknown resource directory '${dir.name}'", relative(dir)); return@forEach }
            val config = ResConfig.parse(qualifiers).getOrElse { diagnostics.error("${it.message} in '${dir.name}'", relative(dir)); return@forEach }
            dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name }.forEach { f ->
                if (type == "values") collectValues(f, config, source) else collectFile(f, type, config, dir.name, source)
            }
        }
    }

    private fun parse(f: File): DetailedXml? = try {
        XmlParser.parseDetailed(f.readText())
    } catch (e: XmlParseException) {
        diagnostics.error(e.message?.substringBefore(" (line") ?: "Invalid XML", relative(f), e.line); null
    }

    private fun add(p: Pending): Boolean {
        val key = "${p.type}/${p.name}/${p.config.qualifiers}"
        val existing = pending[key]
        if (existing != null && existing.source == p.source && !isLibrary(p.source)) {
            diagnostics.error("Duplicate resource ${p.type}/${p.name}${if (p.config.isDefault) "" else " (${p.config})"}", relative(p.file), p.line)
            return false
        }
        pending[key] = p
        return true
    }

    private fun collectValues(f: File, config: ResConfig, source: Int) {
        if (!f.name.endsWith(".xml")) { warn(source, "Ignored: not an XML file", f); return }
        val doc = parse(f) ?: return
        if (doc.root.name != "resources") { diagnostics.error("Values files must have <resources> as the root element", relative(f), doc.line(doc.root)); return }
        doc.root.elements.forEach { e ->
            val line = doc.line(e)
            when (e.name) {
                "eat-comment", "skip", "public", "public-group", "staging-public-group", "staging-public-group-final", "overlayable", "java-symbol" -> return@forEach
                "attr" -> { attr(e, f, line, source, inStyleable = false); return@forEach }
                "declare-styleable" -> { styleable(e, doc, f, source); return@forEach }
                "macro" -> { e["name"]?.let { macros[it] = textOf(e, f, line, source) }; return@forEach }
                "id" -> { e["name"]?.let { declaredIds += it }; return@forEach }
            }
            // Product variants: only the default one is built (like aapt2 without --product).
            e["product"]?.let { if (it != "default") return@forEach }
            val type = when (e.name) {
                "string", "color", "dimen", "bool", "integer", "fraction", "style", "plurals", "drawable" -> e.name
                "string-array", "integer-array", "array" -> "array"
                "item" -> e["type"] ?: run { diagnostics.error("<item> needs a type attribute", relative(f), line); return@forEach }
                else -> { diagnostics.error("Unknown resource element <${e.name}>", relative(f), line); return@forEach }
            }
            val name = e["name"] ?: run { diagnostics.error("<${e.name}> needs a name attribute", relative(f), line); return@forEach }
            if (!validName(name, allowDots = true)) { diagnostics.error("Invalid resource name '$name'", relative(f), line); return@forEach }
            if (type == "id") { declaredIds += name; return@forEach }
            if (type !in TYPE_ORDER) { diagnostics.error("Unsupported resource type '$type'", relative(f), line); return@forEach }
            add(Pending(type, name, config, e, f, line, doc = doc, source = source))
        }
    }

    private fun parseInt(text: String): Int? = text.trim().let { t ->
        runCatching { java.lang.Long.decode(t).toInt() }.getOrNull()
    }

    private fun attr(e: XmlElement, f: File, line: Int, source: Int, inStyleable: Boolean): String? {
        val name = e["name"] ?: run { diagnostics.error("<attr> needs a name attribute", relative(f), line); return null }
        if (name.contains(':')) {
            if (!inStyleable) diagnostics.error("<attr name=\"$name\"> cannot define an attribute of another package", relative(f), line)
            return name
        }
        if (!validName(name, allowDots = false)) { diagnostics.error("Invalid attribute name '$name'", relative(f), line); return null }
        var format = e["format"]?.let(::formatOf) ?: 0
        val symbols = LinkedHashMap<String, Int>()
        var flags = false
        e.elements.forEach { s ->
            if (s.name != "enum" && s.name != "flag") return@forEach
            val sym = s["name"] ?: return@forEach
            val v = s["value"]?.let(::parseInt) ?: run { diagnostics.error("<${s.name} name=\"$sym\"> needs a numeric value", relative(f), line); return@forEach }
            symbols[sym] = v
            declaredIds += sym
            if (s.name == "flag") flags = true
            format = format or if (s.name == "flag") Res.FORMAT_FLAGS else Res.FORMAT_ENUM
        }
        val strong = format != 0
        val existing = attrs[name]
        if (existing == null || (strong && (!existing.strong || source >= 0))) {
            if (existing == null || strong) attrs[name] = AttrSpec(name, if (strong) format else Res.FORMAT_ANY, symbols, flags, strong || !inStyleable)
        }
        return name
    }

    private fun styleable(e: XmlElement, doc: DetailedXml, f: File, source: Int) {
        val name = e["name"] ?: run { diagnostics.error("<declare-styleable> needs a name attribute", relative(f), doc.line(e)); return }
        val members = styleables.getOrPut(name) { LinkedHashSet() }
        e.elements.forEach { a ->
            if (a.name != "attr") return@forEach
            attr(a, f, doc.line(a), source, inStyleable = true)?.let { members += it }
        }
    }

    private fun collectFile(f: File, type: String, config: ResConfig, dirName: String, source: Int) {
        val name = f.name.substringBefore('.')
        val ext = f.name.substringAfter('.', "").lowercase()
        if (!validName(name, allowDots = false)) { diagnostics.error("Invalid file name '${f.name}': use lowercase letters, digits and _", relative(f)); return }
        val apkPath = "res/$dirName/${f.name}"
        if (ext == "9.png") {
            val bytes = try { NinePatch.compile(f.readBytes()) } catch (e: NinePatch.NinePatchException) {
                diagnostics.error("Nine-patch image: ${e.message}", relative(f)); return
            }
            add(Pending(type, name, config, null, f, 0, apkPath, null, source, bytes)); return
        }
        var doc = if (ext == "xml" && type != "raw") parse(f) ?: return else null
        if (doc != null) doc = extractInline(doc, type, name, dirName, config, f, source)
        doc?.root?.walk()?.forEach { e ->
            e.attributes.values.forEach { v -> Reference.parse(v)?.takeIf { it.create && it.type == "id" && (it.pkg == null || it.pkg != "android") }?.let { declaredIds += it.name } }
            if (type == "layout" || type == "menu") ComponentCatalog.all.firstOrNull { it.tag == e.name }?.requiresDependency?.let { dep ->
                val module = dep.substringBeforeLast(':').takeIf { it.count { c -> c == ':' } == 1 } ?: dep
                if (module !in availableModules && dep !in availableModules) {
                    diagnostics.error("<${e.name.substringAfterLast('.')}> needs the library $module: add it in the Libraries tab", relative(f), doc.line(e))
                }
            }
        }
        add(Pending(type, name, config, null, f, 0, apkPath, doc, source))
    }

    /**
     * Moves `<aapt:attr name="x">child</aapt:attr>` into separate resources named
     * `$<file>__<n>` and sets attribute x on the parent to reference them, like aapt2.
     */
    private fun extractInline(doc: DetailedXml, type: String, name: String, dirName: String, config: ResConfig, f: File, source: Int): DetailedXml {
        if (doc.root.walk().none { it.name.endsWith(":attr") }) return doc
        var counter = 0
        fun process(e: XmlElement, scope: Map<String, String>): XmlElement {
            val inner = scope + e.attributes.filterKeys { it.startsWith("xmlns:") }.mapKeys { it.key.removePrefix("xmlns:") }
            var el = e
            val children = mutableListOf<XmlNode>()
            e.children.forEach { c ->
                if (c is XmlElement && c.name.contains(':') && inner[c.name.substringBefore(':')] == AAPT_NS && c.name.substringAfter(':') == "attr") {
                    val attrName = c["name"]
                    val child = c.elements.singleOrNull()
                    if (attrName == null || child == null) { diagnostics.error("<aapt:attr> needs a name and exactly one element", relative(f), doc.line(c)); return@forEach }
                    val resName = "\$${name}__${counter++}"
                    // The extracted file declares the namespaces it inherited.
                    var root = process(child, inner)
                    inner.forEach { (p, uri) -> if (root["xmlns:$p"] == null && uri != AAPT_NS) root = root.withAttribute("xmlns:$p", uri) }
                    add(Pending(type, resName, config, null, f, doc.line(c), "res/$dirName/$resName.xml", DetailedXml(root, linesOf(doc, root)), source))
                    el = el.withAttribute(attrName, "@$type/$resName")
                } else children += if (c is XmlElement) process(c, inner) else c
            }
            return el.copy(children = children)
        }
        val root = process(doc.root, emptyMap())
        return DetailedXml(root, linesOf(doc, root))
    }

    private fun linesOf(doc: DetailedXml, root: XmlElement) = root.walk().associate { it.uid to doc.line(it) }

    private fun validName(n: String, allowDots: Boolean) =
        n.isNotEmpty() && (n[0].isLetter() || n[0] == '_') && n.all { it.isLetterOrDigit() || it == '_' || (allowDots && it == '.') }

    // ------------------------------------------------------------------ ids

    private fun assignIds() {
        val names = LinkedHashMap<String, MutableSet<String>>()
        pending.values.forEach { names.getOrPut(it.type) { sortedSetOf() } += it.name }
        if (attrs.isNotEmpty()) names.getOrPut("attr") { sortedSetOf() } += attrs.keys
        // Styleable members without any definition become "any" attributes (aapt2 does the same).
        styleables.values.flatten().filter { !it.contains(':') && it !in attrs }.forEach { n ->
            attrs[n] = AttrSpec(n, Res.FORMAT_ANY, LinkedHashMap(), false, false)
            names.getOrPut("attr") { sortedSetOf() } += n
        }
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
        types["attr"]?.let { td ->
            td.entries.forEachIndexed { i, n ->
                val a = attrs.getValue(n)
                val items = mutableListOf(Res.ATTR_TYPE to Item(Res.TYPE_INT_DEC, a.format))
                a.symbols.forEach { (sym, v) -> items += ids.getValue("id/$sym") to Item(if (a.flags) Res.TYPE_INT_HEX else Res.TYPE_INT_DEC, v) }
                put("attr", i, ResConfig.DEFAULT, Value.Bag(0, items.sortedWith { x, y -> Integer.compareUnsigned(x.first, y.first) }))
            }
        }
    }

    /** An app or library attribute as the compiler uses it. */
    private fun appAttr(name: String): AttrDef? {
        val a = attrs[name] ?: return null
        return AttrDef(ids.getValue("attr/$name"), a.format, a.symbols)
    }

    private fun put(type: String, index: Int, config: ResConfig, value: Value) {
        types.getValue(type).values.getOrPut(config) { sortedMapOf() }[index] = value
    }

    private fun put(p: Pending, value: Value) {
        val td = types.getValue(p.type)
        put(p.type, td.entries.indexOf(p.name), p.config, value)
    }

    // ------------------------------------------------------------------ references and values

    /** Replaces `@macro/name` with the macro's text (macros may refer to macros). */
    private fun expand(text: String): String {
        var t = text
        repeat(8) {
            val trimmed = t.trim()
            if (!trimmed.startsWith("@macro/")) return t
            t = macros[trimmed.removePrefix("@macro/")] ?: return t
        }
        return t
    }

    /** Resolves a reference to a resource id (0 for @null), reporting problems at [file]:[line]. */
    private fun resolve(ref: Reference, file: File, line: Int): Int? {
        if (ref.isFramework) {
            val id = framework.id(ref.type, ref.name)
            if (id == null) { diagnostics.error("Resource android:${ref.type}/${ref.name} does not exist", relative(file), line); return null }
            if (!framework.isPublic(id)) { diagnostics.error("Resource android:${ref.type}/${ref.name} is private", relative(file), line); return null }
            return id
        }
        // Without namespacing, every package's resources live in the app (like AGP's default).
        ids["${ref.type}/${ref.name}"]?.let { return it }
        if (ref.type == "attr") diagnostics.error("Attribute '${ref.name}' is not defined (add the library that defines it)", relative(file), line)
        else diagnostics.error("Resource ${ref.type}/${ref.name} does not exist", relative(file), line)
        return null
    }

    /** Compiles `@...`/`?...`; null when [text] is not a reference, [FAILED] when it could not be resolved. */
    private fun reference(text: String, file: File, line: Int): Item? {
        val t = expand(text).trim()
        if (t == "@null") return Item(Res.TYPE_REFERENCE, 0)
        if (t == "@empty") return Item(Res.TYPE_NULL, Res.DATA_NULL_EMPTY)
        if (!t.startsWith("@") && !t.startsWith("?")) return null
        val ref = Reference.parse(t) ?: run { diagnostics.error("Invalid reference '$t'", relative(file), line); return FAILED }
        val id = resolve(ref, file, line) ?: return FAILED
        return Item(if (ref.attribute) Res.TYPE_ATTRIBUTE else Res.TYPE_REFERENCE, id)
    }

    /** Compiles [text] for an attribute with [formats] and [symbols]; null with an error when incompatible. */
    private fun forAttribute(source: String, formats: Int, symbols: Map<String, Int>, what: String, file: File, line: Int): Item? {
        val text = expand(source)
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

    private fun textOf(e: XmlElement, file: File, line: Int, source: Int): String {
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
        if (styled) warn(source, "Styled text (<b>, <i>, ...) is not supported yet; it is compiled as plain text", file, line)
        return sb.toString()
    }

    private fun stringItem(e: XmlElement, p: Pending, line: Int): Item? {
        val raw = expand(textOf(e, p.file, line, p.source))
        reference(raw, p.file, line)?.let { return it.takeUnless { it === FAILED } }
        return Item.text(Literals.string(raw))
    }

    private fun typed(p: Pending, text: String, formats: Int, what: String, line: Int = p.line): Item? = forAttribute(text, formats, emptyMap(), what, p.file, line)

    private fun compileValue(p: Pending) {
        val e = p.element!!
        val what = "${p.type} '${p.name}'"
        fun text() = textOf(e, p.file, p.line, p.source)
        val value: Value? = when {
            e.name == "style" -> style(p)
            e.name == "plurals" -> plurals(p)
            e.name.endsWith("array") -> array(p)
            p.type == "string" && e["format"] == null -> stringItem(e, p, p.line)?.let(Value::Simple)
            else -> {
                // <item type="..." format="...">: format decides, else the type's natural format.
                val formats = e["format"]?.let(::formatOf) ?: when (p.type) {
                    "string" -> Res.FORMAT_STRING
                    "color" -> Res.FORMAT_COLOR
                    "dimen" -> Res.FORMAT_DIMENSION or Res.FORMAT_FLOAT or Res.FORMAT_FRACTION
                    "bool" -> Res.FORMAT_BOOLEAN
                    "integer" -> Res.FORMAT_INTEGER
                    "fraction" -> Res.FORMAT_FRACTION
                    else -> Res.FORMAT_REFERENCE or Res.FORMAT_COLOR
                }
                typed(p, text(), formats, what)?.let(Value::Simple)
            }
        }
        if (value != null) put(p, value)
    }

    private fun formatOf(s: String): Int = s.split('|').fold(0) { acc, f ->
        acc or when (f.trim()) {
            "reference" -> Res.FORMAT_REFERENCE; "string" -> Res.FORMAT_STRING; "integer" -> Res.FORMAT_INTEGER
            "boolean" -> Res.FORMAT_BOOLEAN; "color" -> Res.FORMAT_COLOR; "float" -> Res.FORMAT_FLOAT
            "dimension" -> Res.FORMAT_DIMENSION; "fraction" -> Res.FORMAT_FRACTION
            "enum" -> Res.FORMAT_ENUM; "flags" -> Res.FORMAT_FLAGS
            else -> Res.FORMAT_ANY
        }
    }

    /** An attribute named in a style item or XML file: `android:x` (platform) or `x` (app/library). */
    private fun attrNamed(qualified: String): AttrDef? =
        if (qualified.startsWith("android:")) framework.attr(qualified.removePrefix("android:")) else appAttr(qualified.substringAfter(':'))

    private fun style(p: Pending): Value? {
        val e = p.element!!
        val parentText = e["parent"]
        val parent = when {
            parentText == null -> p.name.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }?.let { ids["style/$it"] } ?: 0
            parentText.isBlank() -> 0
            else -> {
                var t = parentText.trim().removePrefix("@").removePrefix("*")
                val pkg = if (t.contains(':')) t.substringBefore(':') else null
                t = t.substringAfter(':').removePrefix("style/")
                resolve(Reference(pkg, "style", t), p.file, p.line) ?: return null
            }
        }
        val items = LinkedHashMap<Int, Item>()
        var ok = true
        e.elements.forEach { item ->
            val line = p.doc?.line(item) ?: p.line
            if (item.name != "item") { diagnostics.error("Unexpected <${item.name}> in <style>", relative(p.file), line); ok = false; return@forEach }
            val attrName = item["name"] ?: run { diagnostics.error("<item> needs a name", relative(p.file), line); ok = false; return@forEach }
            val def = attrNamed(attrName)
            if (def == null) {
                diagnostics.error(
                    if (attrName.startsWith("android:")) "Unknown attribute $attrName" else "Attribute '$attrName' is not defined (add the library that defines it, e.g. AppCompat or Material)",
                    relative(p.file), line,
                )
                ok = false; return@forEach
            }
            if (attrName.startsWith("android:") && !framework.isPublic(def.id)) { diagnostics.error("Attribute $attrName is private", relative(p.file), line); ok = false; return@forEach }
            val v = forAttribute(textOf(item, p.file, line, p.source), def.format, def.symbols, attrName, p.file, line) ?: run { ok = false; return@forEach }
            items[def.id] = v
        }
        if (!ok) return null
        return Value.Bag(parent, items.toList().sortedWith { a, b -> Integer.compareUnsigned(a.first, b.first) })
    }

    private fun array(p: Pending): Value? {
        val e = p.element!!
        var ok = true
        val items = e.elements.filter { it.name == "item" }.mapIndexedNotNull { i, item ->
            val line = p.doc?.line(item) ?: p.line
            val v = when (e.name) {
                "string-array" -> stringItem(item, p, line)
                "integer-array" -> typed(p, textOf(item, p.file, line, p.source), Res.FORMAT_INTEGER, "an integer-array item", line)
                else -> {
                    // <array>: references and literals keep their type; anything else is text.
                    val t = expand(textOf(item, p.file, line, p.source))
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
            val v = stringItem(item, p, line) ?: run { ok = false; return@mapNotNull null }
            key to v
        }
        return if (ok) Value.Bag(0, items.sortedWith { a, b -> Integer.compareUnsigned(a.first, b.first) }) else null
    }

    // ------------------------------------------------------------------ XML files

    private fun compileFile(p: Pending) {
        put(p, Value.Simple(Item.text(p.apkPath!!)))
        val doc = p.doc
        if (doc == null) { files[p.apkPath] = p.bytes ?: p.file.readBytes(); return }
        files[p.apkPath] = compileXml(doc, p.file) ?: return
    }

    private fun compileXml(doc: DetailedXml, file: File): ByteArray? {
        val before = diagnostics.all.size
        val bytes = XmlCompiler(doc) { element, ns, local, value, line -> attribute(element, ns, local, value, file, line) }.compile()
        return bytes.takeIf { diagnostics.all.drop(before).none { it.severity == BuildDiagnostic.Severity.Error } }
    }

    private fun attribute(e: XmlElement, ns: String?, local: String, value: String, file: File, line: Int): CompiledAttr? {
        when {
            ns == null -> {
                val item = reference(value, file, line)?.takeUnless { it === FAILED } ?: Item.text(value)
                return CompiledAttr(null, local, 0, value.takeIf { item.string == null }, item)
            }
            ns.startsWith("?") -> { diagnostics.error("Undeclared namespace prefix '${ns.drop(1)}' on <${e.name}>", relative(file), line); return null }
            ns == XmlCompiler.TOOLS_NS || ns == AAPT_NS -> return null
            ns == XmlCompiler.ANDROID_NS -> {
                val def = framework.attr(local)
                if (def == null) { diagnostics.error("Unknown attribute android:$local on <${e.name}>", relative(file), line); return null }
                if (!framework.isPublic(def.id)) { diagnostics.error("Attribute android:$local is private", relative(file), line); return null }
                val item = forAttribute(value, def.format, def.symbols, "android:$local", file, line) ?: return null
                return CompiledAttr(ns, local, def.id, null, item)
            }
            ns == XmlCompiler.AUTO_NS || ns.startsWith("http://schemas.android.com/apk/res/") -> {
                val def = appAttr(local)
                if (def == null) { diagnostics.error("Attribute '$local' on <${e.name}> is not defined (add the library that defines it)", relative(file), line); return null }
                val item = forAttribute(value, def.format, def.symbols, local, file, line) ?: return null
                return CompiledAttr(XmlCompiler.AUTO_NS, local, def.id, value.takeIf { item.type == Res.TYPE_STRING }, item)
            }
            else -> {
                // Unknown namespaces carry plain strings (like aapt2).
                return CompiledAttr(ns, local, 0, value, Item.text(value))
            }
        }
    }

    // ------------------------------------------------------------------ manifest

    private fun compileManifest(file: File, libraries: List<LibraryManifest>, o: ManifestOptions): ByteArray? {
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
            el = el.copy(attributes = LinkedHashMap(el.attributes.mapValues { (_, v) -> v.replace("\${applicationId}", o.applicationId) }))
            return el.copy(children = el.children.map { if (it is XmlElement) transform(it) else it })
        }

        val lines = HashMap<Long, Int>()
        doc.root.walk().forEach { lines[it.uid] = doc.line(it) }
        var root = transform(doc.root)
        if (libraries.isNotEmpty()) root = ManifestMerger(prefix, o, diagnostics, relative).merge(root, libraries) ?: return null
        root = root
            .withAttribute("package", o.applicationId)
            .withAttribute(a("versionCode"), o.versionCode.toString())
            .withAttribute(a("versionName"), o.versionName)
        o.debugBridge?.let { host ->
            val app = root.elements.firstOrNull { it.name == "application" }
            if (app == null) diagnostics.warning("No <application> element: the debug console is not connected", relative(file))
            else {
                val provider = XmlElement(-2, "provider", linkedMapOf(
                    a("name") to dev.buildit.engine.build.DebugBridge.CLASS,
                    a("authorities") to dev.buildit.engine.build.DebugBridge.ownAuthority(o.applicationId),
                    a("exported") to "false",
                    a("initOrder") to "1000",
                ))
                val queries = XmlElement(-3, "queries", children = listOf(
                    XmlElement(-4, "package", linkedMapOf(a("name") to host.packageName)),
                    XmlElement(-5, "provider", linkedMapOf(a("authorities") to host.authority)),
                ))
                root = root.copy(children = listOf(queries) + root.children.map { c -> if (c === app) app.copy(children = app.children + provider) else c })
            }
        }
        if (root.elements.none { it.name == "uses-sdk" }) {
            val usesSdk = XmlElement(-1, "uses-sdk", linkedMapOf(a("minSdkVersion") to o.minSdk.toString(), a("targetSdkVersion") to o.targetSdk.toString()))
            root = root.copy(children = listOf(usesSdk) + root.children)
        }
        // Copies keep their uids, so the original line numbers still apply.
        return compileXml(DetailedXml(root, lines), file)
    }

    // ------------------------------------------------------------------ R classes

    /** Styleable name -> attribute ids in the order of the R array (ascending, unsigned). */
    private fun styleableArrays(): Map<String, List<Pair<String, Int>>> = styleables.mapValues { (name, members) ->
        members.mapNotNull { m ->
            val id = if (m.startsWith("android:")) framework.id("attr", m.removePrefix("android:")) else ids["attr/${m.substringAfter(':')}"]
            if (id == null) { diagnostics.warning("Styleable $name refers to unknown attribute $m; left out"); null } else m to id
        }.distinctBy { it.second }.sortedWith { a, b -> Integer.compareUnsigned(a.second, b.second) }
    }

    private fun field(n: String) = n.replace('.', '_').replace(':', '_').replace('-', '_')

    private fun rJava(pkg: String, styleableIds: Map<String, List<Pair<String, Int>>>): String = buildString {
        appendLine("/* Generated by Build It from res/. Do not edit: it is rewritten on every build. */")
        appendLine("package $pkg;")
        appendLine()
        appendLine("public final class R {")
        appendLine("    private R() {}")
        // Classes are not final: library R classes extend them (see libraryR).
        types.values.forEach { t ->
            appendLine()
            appendLine("    public static class ${t.name} {")
            appendLine("        protected ${t.name}() {}")
            t.entries.filter { !it.startsWith("$") }.forEach { n ->
                appendLine("        public static final int ${field(n)} = 0x${Integer.toHexString(ids.getValue("${t.name}/$n"))};")
            }
            appendLine("    }")
        }
        if (styleableIds.isNotEmpty()) {
            appendLine()
            appendLine("    public static class styleable {")
            appendLine("        protected styleable() {}")
            styleableIds.forEach { (name, members) ->
                append("        public static final int[] ${field(name)} = {")
                append(members.joinToString(", ") { "0x" + Integer.toHexString(it.second) })
                appendLine("};")
                members.forEachIndexed { i, (m, _) ->
                    appendLine("        public static final int ${field(name)}_${field(m)} = $i;")
                }
            }
            appendLine("    }")
        }
        appendLine("}")
    }

    /**
     * R for a library package. Library classes were compiled against their own non-final R and
     * read its fields at run time, so each nested class simply extends the app's: the fields are
     * resolved through the superclass, with the app's final ids.
     */
    private fun libraryR(pkg: String, appPkg: String, hasStyleables: Boolean): String = buildString {
        appendLine("/* Generated by Build It for a library. Do not edit. */")
        appendLine("package $pkg;")
        appendLine()
        appendLine("public final class R {")
        appendLine("    private R() {}")
        (types.keys + if (hasStyleables) listOf("styleable") else emptyList()).forEach { t ->
            appendLine("    public static final class $t extends $appPkg.R.$t { private $t() {} }")
        }
        appendLine("}")
    }

    companion object {
        const val PACKAGE_ID = 0x7f
        const val AAPT_NS = "http://schemas.android.com/aapt"
        private val FAILED = Item(-1)
        private val FILE_TYPES = setOf("anim", "animator", "color", "drawable", "font", "interpolator", "layout", "menu", "mipmap", "navigation", "raw", "transition", "xml")
        private val TYPE_ORDER = listOf(
            "attr", "anim", "animator", "array", "bool", "color", "dimen", "drawable", "font", "fraction", "id", "integer",
            "interpolator", "layout", "menu", "mipmap", "navigation", "plurals", "raw", "string", "style", "transition", "xml",
        )
    }
}

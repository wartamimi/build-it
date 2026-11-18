package dev.buildit.engine.build.res

import dev.buildit.engine.build.Diagnostics
import dev.buildit.engine.layout.XmlElement
import dev.buildit.engine.layout.XmlParseException
import dev.buildit.engine.layout.XmlParser
import java.io.File

/**
 * Merges library manifests into the app's, following the rules of AGP's manifest merger that
 * libraries rely on:
 * - permissions, features, queries and components of every library are added;
 * - an element with the same key (tag + android:name, or authorities for providers) is merged
 *   once: missing attributes and children are added, the app's values win;
 * - `tools:node="remove"` in the app removes the library element of the same key;
 * - `${applicationId}` is replaced; relative class names are relative to the library's package;
 * - a library whose minSdk is higher than the app's is an error (as in AGP).
 */
internal class ManifestMerger(
    private val androidPrefix: String,
    private val options: ManifestOptions,
    private val diagnostics: Diagnostics,
    private val relative: (File) -> String,
) {
    private var nextUid = -1_000_000L
    private val toolsNode = HashMap<String, String>()

    fun merge(app: XmlElement, libraries: List<LibraryManifest>): XmlElement? {
        val toolsPrefix = app.attributes.entries.firstOrNull { it.key.startsWith("xmlns:") && it.value == XmlCompiler.TOOLS_NS }?.key?.removePrefix("xmlns:")
        // Remember the app's tools:node instructions by key, then drop tools attributes.
        if (toolsPrefix != null) app.walk().forEach { e -> e["$toolsPrefix:node"]?.let { toolsNode[key(e)] = it } }
        var root = app
        var application = root.elements.firstOrNull { it.name == "application" }
        val topLevel = mutableListOf<XmlElement>()
        val appChildren = mutableListOf<XmlElement>()
        libraries.forEach { lib ->
            val parsed = load(lib) ?: return@forEach
            parsed.elements.forEach { e ->
                when (e.name) {
                    "uses-sdk" -> {
                        val min = e["$androidPrefix:minSdkVersion"]?.toIntOrNull() ?: 1
                        if (min > options.minSdk) diagnostics.error("Library ${lib.label} needs minSdk $min, but the app's minSdk is ${options.minSdk}; raise it in Project settings", relative(lib.file))
                    }
                    "application" -> {
                        if (application != null) {
                            // Attributes such as appComponentFactory are taken when the app has none.
                            var a = application!!
                            e.attributes.forEach { (k, v) -> if (a[k] == null && k.startsWith("$androidPrefix:")) a = a.withAttribute(k, v) }
                            application = a
                        }
                        appChildren += e.elements
                    }
                    "instrumentation" -> Unit
                    else -> topLevel += e
                }
            }
        }
        val existingTop = root.elements.filter { it.name != "application" }
        val mergedTop = mergeInto(existingTop, topLevel)
        val appEl = application?.let { it.copy(children = mergeInto(it.elements, appChildren)) }
        root = root.copy(children = root.children.filter { it !is XmlElement } + mergedTop.filter { it.name != "application" } + listOfNotNull(appEl))
        return root.let { r -> r.copy(children = r.children.filter { it is XmlElement && toolsNode[key(it)] !in REMOVE }.let { kept -> kept }.map { strip(it as XmlElement) }) }
    }

    private fun strip(e: XmlElement): XmlElement =
        e.copy(children = e.children.filter { it !is XmlElement || toolsNode[key(it)] !in REMOVE }.map { if (it is XmlElement) strip(it) else it })

    /** Adds [incoming] to [existing], merging elements with the same key. */
    private fun mergeInto(existing: List<XmlElement>, incoming: List<XmlElement>): List<XmlElement> {
        val out = existing.toMutableList()
        incoming.forEach { e ->
            val k = key(e)
            if (toolsNode[k] in REMOVE) return@forEach
            val at = out.indexOfFirst { key(it) == k && (k.contains('|') || it == e) }
            if (at < 0) { out += e; return@forEach }
            if (!k.contains('|')) return@forEach // identical keyless element already present
            val old = out[at]
            var merged = old
            e.attributes.forEach { (n, v) -> if (merged[n] == null) merged = merged.withAttribute(n, v) }
            out[at] = merged.copy(children = mergeInto(old.elements, e.elements))
        }
        return out
    }

    private fun key(e: XmlElement): String {
        val name = e["$androidPrefix:name"] ?: if (e.name == "provider") e["$androidPrefix:authorities"] else null
        return if (name != null) "${e.name}|$name" else e.name
    }

    private fun load(lib: LibraryManifest): XmlElement? {
        val doc = try { XmlParser.parseDetailed(lib.file.readText()) } catch (e: XmlParseException) {
            diagnostics.error("Invalid manifest in library ${lib.label}: ${e.message}", relative(lib.file)); return null
        }
        val root = doc.root
        val pkg = root["package"] ?: ""
        val ns = root.attributes.filterKeys { it.startsWith("xmlns:") }.mapKeys { it.key.removePrefix("xmlns:") }
        val unknown = Regex("\\$\\{([^}]+)\\}")
        fun qualify(n: String) = if (n.startsWith(".")) pkg + n else if (!n.contains('.') && pkg.isNotEmpty()) "$pkg.$n" else n
        val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider", "application")
        fun convert(e: XmlElement): XmlElement {
            val attrs = LinkedHashMap<String, String>()
            e.attributes.forEach { (k, raw) ->
                if (k == "xmlns" || k.startsWith("xmlns:")) return@forEach
                val p = k.substringBefore(':', "")
                val local = k.substringAfter(':')
                val uri = ns[p]
                if (p.isNotEmpty() && uri != XmlCompiler.ANDROID_NS) return@forEach // tools: and others are build-time only
                var v = raw.replace("\${applicationId}", options.applicationId)
                unknown.find(v)?.let { m -> diagnostics.error("Library ${lib.label} needs the manifest placeholder \${${m.groupValues[1]}}, which Build It does not set", relative(lib.file)) }
                if (p.isNotEmpty() && e.name in componentTags && (local == "name" || local == "targetActivity")) v = qualify(v)
                attrs[if (p.isEmpty()) local else "$androidPrefix:$local"] = v
            }
            return XmlElement(nextUid--, e.name, attrs, e.elements.map(::convert))
        }
        return convert(root)
    }

    private companion object {
        val REMOVE = setOf("remove", "removeAll")
    }
}

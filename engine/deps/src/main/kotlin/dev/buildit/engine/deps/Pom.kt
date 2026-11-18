package dev.buildit.engine.deps

import dev.buildit.engine.layout.XmlElement
import dev.buildit.engine.layout.XmlParser
import dev.buildit.engine.layout.XmlText

/** A dependency as declared in a POM or module file, before version selection. */
data class Declared(val group: String, val artifact: String, val version: String, val optional: Boolean = false)

/** The parts of a Maven POM the resolver needs, with parents and imported BOMs applied. */
class Pom(
    val coordinate: Coordinate,
    val packaging: String,
    val dependencies: List<Declared>,
) {
    companion object {
        private fun XmlElement.child(n: String) = elements.firstOrNull { it.name == n }
        private fun XmlElement.text(n: String) = child(n)?.children?.filterIsInstance<XmlText>()?.joinToString("") { it.text }?.trim()

        /** Parses [xml]; [load] returns another POM (parent or BOM) by coordinate, or null. */
        fun parse(xml: String, load: (Coordinate) -> Pom.Raw?): Pom? {
            val raw = Raw.parse(xml) ?: return null
            return raw.effective(load, 0)
        }
    }

    /** One POM file as written, before inheritance. */
    class Raw(
        val group: String?, val artifact: String, val version: String?, val packaging: String?,
        val parent: Coordinate?, val properties: Map<String, String>,
        val managed: List<Triple<Declared, String?, String?>>, // dependency, scope, type
        val deps: List<Triple<Declared, String?, String?>>,
    ) {
        fun effective(load: (Coordinate) -> Raw?, depth: Int): Pom? {
            val parentRaw = if (depth < 10) parent?.let(load) else null
            val parentPom = parentRaw?.effective(load, depth + 1)
            val g = group ?: parent?.group ?: return null
            val v = version ?: parent?.version ?: return null
            // Properties: the parent's, then ours; plus the project.* built-ins.
            val props = HashMap<String, String>()
            parentRaw?.let { props += it.collectProperties(load, depth + 1) }
            props += properties
            props["project.groupId"] = g; props["pom.groupId"] = g; props["groupId"] = g
            props["project.version"] = v; props["pom.version"] = v; props["version"] = v
            props["project.artifactId"] = artifact
            parent?.let { props["project.parent.version"] = it.version; props["parent.version"] = it.version; props["project.parent.groupId"] = it.group }
            fun interp(s: String): String {
                var r = s
                repeat(5) { r = Regex("\\$\\{([^}]+)\\}").replace(r) { m -> props[m.groupValues[1]] ?: m.value } }
                return r
            }
            // Managed versions: the parent's, imported BOMs, then ours (ours win).
            val managed = HashMap<String, String>()
            parentPom?.let { managed += parentManaged(load, depth + 1) }
            this.managed.forEach { (d, scope, type) ->
                val dg = interp(d.group); val da = interp(d.artifact); val dv = interp(d.version)
                if (scope == "import" && type == "pom") {
                    val bom = load(Coordinate(dg, da, dv))?.let { it.effectiveManaged(load, depth + 1) }
                    bom?.forEach { (k, ver) -> managed.putIfAbsent(k, ver) }
                } else managed["$dg:$da"] = dv
            }
            val deps = (parentPom?.dependencies.orEmpty() + this.deps.mapNotNull { (d, scope, _) ->
                if (scope != null && scope !in setOf("compile", "runtime")) return@mapNotNull null
                val dg = interp(d.group); val da = interp(d.artifact)
                val dv = interp(d.version).ifEmpty { managed["$dg:$da"].orEmpty() }
                Declared(dg, da, dv, d.optional)
            }).distinctBy { it.group + ":" + it.artifact }
            return Pom(Coordinate(g, artifact, v), interp(packaging ?: "jar"), deps)
        }

        private fun collectProperties(load: (Coordinate) -> Raw?, depth: Int): Map<String, String> {
            val m = HashMap<String, String>()
            if (depth < 10) parent?.let(load)?.let { m += it.collectProperties(load, depth + 1) }
            m += properties
            return m
        }

        private fun parentManaged(load: (Coordinate) -> Raw?, depth: Int): Map<String, String> = effectiveManaged(load, depth)

        /** dependencyManagement after inheritance and BOM imports, as module -> version. */
        fun effectiveManaged(load: (Coordinate) -> Raw?, depth: Int): Map<String, String> {
            if (depth > 10) return emptyMap()
            val m = HashMap<String, String>()
            parent?.let(load)?.let { m += it.effectiveManaged(load, depth + 1) }
            val props = collectProperties(load, depth) + mapOfNotNull("project.version" to (version ?: parent?.version), "project.groupId" to (group ?: parent?.group))
            fun interp(s: String): String { var r = s; repeat(5) { r = Regex("\\$\\{([^}]+)\\}").replace(r) { x -> props[x.groupValues[1]] ?: x.value } }; return r }
            managed.forEach { (d, scope, type) ->
                if (scope == "import" && type == "pom") load(Coordinate(interp(d.group), interp(d.artifact), interp(d.version)))?.effectiveManaged(load, depth + 1)?.forEach { (k, v) -> m.putIfAbsent(k, v) }
                else m["${interp(d.group)}:${interp(d.artifact)}"] = interp(d.version)
            }
            return m
        }

        private fun mapOfNotNull(vararg p: Pair<String, String?>) = p.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

        companion object {
            fun parse(xml: String): Raw? {
                val root = runCatching { XmlParser.parse(xml) }.getOrNull() ?: return null
                if (root.name != "project") return null
                fun deps(e: XmlElement?) = e?.elements?.filter { it.name == "dependency" }?.mapNotNull { d ->
                    val g = d.text("groupId") ?: return@mapNotNull null
                    val a = d.text("artifactId") ?: return@mapNotNull null
                    Triple(Declared(g, a, d.text("version").orEmpty(), d.text("optional") == "true"), d.text("scope"), d.text("type"))
                }.orEmpty()
                val parent = root.child("parent")?.let { p ->
                    val g = p.text("groupId"); val a = p.text("artifactId"); val v = p.text("version")
                    if (g != null && a != null && v != null) Coordinate(g, a, v) else null
                }
                return Raw(
                    group = root.text("groupId"), artifact = root.text("artifactId") ?: return null, version = root.text("version"),
                    packaging = root.text("packaging"), parent = parent,
                    properties = root.child("properties")?.elements?.associate { it.name to it.children.filterIsInstance<XmlText>().joinToString("") { t -> t.text }.trim() }.orEmpty(),
                    managed = deps(root.child("dependencyManagement")?.child("dependencies")),
                    deps = deps(root.child("dependencies")),
                )
            }
        }
    }
}

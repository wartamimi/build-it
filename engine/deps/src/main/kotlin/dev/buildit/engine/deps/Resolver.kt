package dev.buildit.engine.deps

import java.io.File
import java.io.IOException

/** A library chosen for the build, with its downloaded file (an AAR or a JAR). */
data class ResolvedArtifact(val coordinate: Coordinate, val file: File, val requestedBy: Set<String>) {
    val isAar get() = file.name.endsWith(".aar")
}

data class Resolution(val artifacts: List<ResolvedArtifact>, val problems: List<String>) {
    val ok get() = problems.isEmpty()
}

class ResolveCancelledException : Exception("Cancelled")

/**
 * Resolves libraries and everything they depend on, like Gradle does for an Android app: Gradle
 * module metadata when published (Android variant, Kotlin Multiplatform redirects), the POM
 * otherwise (parents, properties, BOM imports); when two libraries ask for different versions of
 * the same module, the highest wins. Files are downloaded into the [MavenCache].
 */
class Resolver(private val cache: MavenCache) {
    private class Node(val coordinate: Coordinate, val file: Pair<String, String>?, val deps: List<Declared>, val redirect: Coordinate?)

    private val nodes = HashMap<String, Node?>()
    private val raws = HashMap<String, Pom.Raw?>()

    private fun rawPom(c: Coordinate): Pom.Raw? = raws.getOrPut(c.toString()) {
        cache.fetch("${c.path}/${c.artifact}-${c.version}.pom")?.readText()?.let { Pom.Raw.parse(it) }
    }

    /** What one module version needs; null if no repository has it. */
    private fun node(c: Coordinate): Node? = nodes.getOrPut(c.toString()) {
        val module = runCatching { cache.fetch("${c.path}/${c.artifact}-${c.version}.module") }.getOrNull()
        module?.let { ModuleMetadata.androidRuntime(it.readText()) }?.let { v ->
            return@getOrPut when (v) {
                is ModuleVariant.Redirect -> Node(c, null, emptyList(), v.to)
                is ModuleVariant.Local -> {
                    val main = v.files.firstOrNull { it.first.endsWith(".aar") } ?: v.files.firstOrNull { it.first.endsWith(".jar") }
                    Node(c, main, v.dependencies, null)
                }
            }
        }
        val raw = rawPom(c) ?: return@getOrPut null
        val pom = raw.effective(::rawPom, 0) ?: return@getOrPut null
        val ext = when (pom.packaging) { "aar" -> "aar"; "pom" -> null; else -> "jar" }
        val file = ext?.let { "${c.artifact}-${c.version}.$it".let { n -> n to n } }
        Node(c, file, pom.dependencies.filterNot { it.optional }, null)
    }

    /**
     * Resolves [roots]. [progress] gets a short status line; [isCancelled] is checked between
     * downloads. Problems (missing libraries, failed downloads) are reported, not thrown.
     */
    fun resolve(roots: List<Coordinate>, progress: (String) -> Unit = {}, isCancelled: () -> Boolean = { false }): Resolution {
        val problems = linkedSetOf<String>()
        val selected = HashMap<String, String>()   // module -> chosen version
        roots.forEach { selected.merge(it.module, it.version) { a, b -> maxOf(a, b, Versions) } }
        var requesters = HashMap<String, MutableSet<String>>()
        var order = listOf<String>()
        repeat(20) {
            val requested = HashMap<String, String>(selected)
            val seen = LinkedHashSet<String>()
            requesters = HashMap()
            val queue = ArrayDeque(roots.map { it.module })
            roots.forEach { requesters.getOrPut(it.module) { mutableSetOf() } += "project" }
            while (queue.isNotEmpty()) {
                if (isCancelled()) throw ResolveCancelledException()
                val m = queue.removeFirst()
                if (!seen.add(m)) continue
                val version = requested[m] ?: continue
                val (g, a) = m.split(':')
                progress("$g:$a:$version")
                val n = try { node(Coordinate(g, a, version)) } catch (e: IOException) {
                    problems += "$m:$version: ${e.message}"; continue
                }
                if (n == null) { problems += "$m:$version was not found in ${Repository.defaults.joinToString(" or ") { it.name }}"; continue }
                val deps = if (n.redirect != null) listOf(Declared(n.redirect.group, n.redirect.artifact, n.redirect.version)) else n.deps
                for (d in deps) {
                    val v = Versions.select(d.version) { cache.versions(d.group, d.artifact) }
                    if (v == null) { problems += "${d.group}:${d.artifact} has no version (needed by $m)"; continue }
                    val dm = "${d.group}:${d.artifact}"
                    requested.merge(dm, v) { x, y -> maxOf(x, y, Versions) }
                    requesters.getOrPut(dm) { mutableSetOf() } += m
                    queue += dm
                }
            }
            order = seen.toList()
            if (requested == selected) return finish(order, selected, requesters, problems, isCancelled)
            selected.clear(); selected += requested
            problems.clear()
        }
        problems += "Versions did not settle; check the libraries for conflicting requirements"
        return finish(order, selected, requesters, problems, isCancelled)
    }

    private fun finish(order: List<String>, selected: Map<String, String>, requesters: Map<String, Set<String>>, problems: MutableSet<String>, isCancelled: () -> Boolean): Resolution {
        val out = mutableListOf<ResolvedArtifact>()
        for (m in order) {
            if (isCancelled()) throw ResolveCancelledException()
            val (g, a) = m.split(':')
            val c = Coordinate(g, a, selected[m] ?: continue)
            val n = nodes[c.toString()] ?: continue
            val (name, url) = n.file ?: continue
            val relative = "${c.path}/$url".takeIf { !url.contains("/") || url.startsWith("../") }?.let { normalize(it) } ?: "${c.path}/$name"
            val origin = cache.origin("${c.path}/${c.artifact}-${c.version}.pom") ?: cache.origin("${c.path}/${c.artifact}-${c.version}.module")
            val f = try { cache.fetch(relative, origin) } catch (e: IOException) { problems += "$c: ${e.message}"; null }
            if (f == null) { if (problems.none { it.startsWith(c.toString()) }) problems += "$c: file $name is missing from the repository"; continue }
            out += ResolvedArtifact(c, f, requesters[m].orEmpty())
        }
        return Resolution(out, problems.toList())
    }

    private fun normalize(p: String): String {
        val parts = ArrayList<String>()
        p.split('/').forEach { if (it == "..") parts.removeLastOrNull() else if (it.isNotEmpty() && it != ".") parts += it }
        return parts.joinToString("/")
    }
}

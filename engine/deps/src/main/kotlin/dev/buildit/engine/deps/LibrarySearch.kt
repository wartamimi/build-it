package dev.buildit.engine.deps

import dev.buildit.engine.layout.XmlParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/** A library found by [LibrarySearch]. [latest] is the newest stable version when known. */
data class LibraryHit(val group: String, val artifact: String, val latest: String?, val source: String, val note: String? = null) {
    val module get() = "$group:$artifact"
}

/**
 * Finds libraries in Google's Maven repository (its group index) and Maven Central (its search
 * service), plus a short list of commonly used libraries shown before searching.
 */
class LibrarySearch(private val http: Http = Http.Default) {
    private var googleGroups: List<String>? = null
    private val groupIndex = HashMap<String, Map<String, List<String>>>()

    /** Google Maven group names (cached for the session). */
    private fun groups(): List<String> = googleGroups ?: run {
        val xml = http.get(Repository.Google.url + "master-index.xml")?.decodeToString() ?: return emptyList()
        XmlParser.parse(xml).elements.map { it.name }.also { googleGroups = it }
    }

    /** Artifact -> versions (oldest first) for a Google group. */
    fun googleGroup(group: String): Map<String, List<String>> = groupIndex.getOrPut(group) {
        val xml = http.get(Repository.Google.url + group.replace('.', '/') + "/group-index.xml")?.decodeToString() ?: return@getOrPut emptyMap()
        XmlParser.parse(xml).elements.associate { e -> e.name to (e["versions"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.sortedWith(Versions) }
    }

    /**
     * Searches both repositories for [query] (words matched against group and artifact names).
     * A failing repository is reported in [errors] while the other's results are still returned.
     */
    fun search(query: String, limit: Int = 40, errors: MutableList<String> = mutableListOf()): List<LibraryHit> {
        val words = query.lowercase().split(Regex("[\\s:]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val hits = LinkedHashMap<String, LibraryHit>()
        // An exact coordinate is taken as it is.
        Coordinate.parse(query.trim())?.let { c -> hits[c.module] = LibraryHit(c.group, c.artifact, c.version, "") }
        try {
            val all = groups()
            val matching = all.filter { g -> words.any { w -> g.contains(w) } }.sortedBy { it.length }.take(8)
            val searched = (matching + all.filter { it.startsWith("androidx.") && it.substringAfter("androidx.").let { s -> words.any { w -> w.startsWith(s) || s.startsWith(w) } } }).distinct().take(10)
            searched.forEach { g ->
                googleGroup(g).forEach { (a, versions) ->
                    val text = "$g:$a".lowercase()
                    if (words.all { text.contains(it) } || (words.any { g.contains(it) } && words.any { a.contains(it) }) || words.all { g.contains(it) }) {
                        hits.putIfAbsent("$g:$a", LibraryHit(g, a, versions.lastOrNull { Versions.isStable(it) } ?: versions.lastOrNull(), Repository.Google.name))
                    }
                }
            }
        } catch (e: Exception) { errors += "${Repository.Google.name}: ${e.message ?: e.javaClass.simpleName}" }
        try {
            val q = URLEncoder.encode(words.joinToString(" "), "UTF-8")
            val text = http.get("https://search.maven.org/solrsearch/select?q=$q&rows=25&wt=json")?.decodeToString()
            val docs = ((Json.parseToJsonElement(text ?: "{}") as? JsonObject)?.get("response") as? JsonObject)?.get("docs") as? JsonArray
            docs?.forEach { d ->
                val o = d as? JsonObject ?: return@forEach
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val g = s("g") ?: return@forEach
                val a = s("a") ?: return@forEach
                hits.putIfAbsent("$g:$a", LibraryHit(g, a, s("latestVersion"), Repository.MavenCentral.name))
            }
        } catch (e: Exception) { errors += "${Repository.MavenCentral.name}: ${e.message ?: e.javaClass.simpleName}" }
        // Closest names first: artifact equal to a word, then shorter coordinates.
        return hits.values.sortedWith(compareBy({ h -> if (words.any { it == h.artifact.lowercase() }) 0 else 1 }, { it.group.length + it.artifact.length })).take(limit)
    }

    companion object {
        /** Commonly used libraries, shown before searching. Versions are looked up when added. */
        val popular = listOf(
            LibraryHit("androidx.appcompat", "appcompat", null, "Google", "AppCompatActivity, Toolbar, themes"),
            LibraryHit("com.google.android.material", "material", null, "Google", "Material Design 3 components"),
            LibraryHit("androidx.constraintlayout", "constraintlayout", null, "Google", "ConstraintLayout"),
            LibraryHit("androidx.recyclerview", "recyclerview", null, "Google", "Efficient scrolling lists"),
            LibraryHit("androidx.cardview", "cardview", null, "Google", "CardView"),
            LibraryHit("androidx.swiperefreshlayout", "swiperefreshlayout", null, "Google", "Pull to refresh"),
            LibraryHit("androidx.viewpager2", "viewpager2", null, "Google", "Swipeable pages"),
            LibraryHit("androidx.webkit", "webkit", null, "Google", "Modern WebView features"),
            LibraryHit("androidx.preference", "preference", null, "Google", "Settings screens"),
            LibraryHit("com.google.android.gms", "play-services-location", null, "Google", "Location (Google Play services)"),
            LibraryHit("com.google.android.gms", "play-services-maps", null, "Google", "Google Maps (needs an API key in the manifest)"),
            LibraryHit("com.google.android.gms", "play-services-ads", null, "Google", "AdMob ads"),
            LibraryHit("com.google.firebase", "firebase-auth", null, "Google", "Firebase sign-in (needs google-services.json)"),
            LibraryHit("com.google.firebase", "firebase-firestore", null, "Google", "Cloud Firestore (needs google-services.json)"),
            LibraryHit("com.google.firebase", "firebase-database", null, "Google", "Realtime Database (needs google-services.json)"),
            LibraryHit("com.google.firebase", "firebase-messaging", null, "Google", "Push messages (needs google-services.json)"),
            LibraryHit("com.google.code.gson", "gson", null, "Maven Central", "JSON to Java objects"),
            LibraryHit("com.squareup.okhttp3", "okhttp", null, "Maven Central", "HTTP client"),
            LibraryHit("com.github.bumptech.glide", "glide", null, "Maven Central", "Image loading (without its annotation processor)"),
        )
    }
}

package dev.buildit.engine.deps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Gradle module metadata (`.module`), which AndroidX and Kotlin libraries publish. It is more exact
 * than the POM: it says which variant (Android, JVM) to use, and may redirect to another module
 * (`available-at`) for Kotlin Multiplatform libraries.
 */
sealed interface ModuleVariant {
    data class Local(val files: List<Pair<String, String>>, val dependencies: List<Declared>) : ModuleVariant // (name, url)
    data class Redirect(val to: Coordinate) : ModuleVariant
}

object ModuleMetadata {
    private val json = Json { ignoreUnknownKeys = true }

    /** The runtime variant for an Android app, or null if the file has none. */
    fun androidRuntime(text: String): ModuleVariant? {
        val root = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return null
        val variants = (root["variants"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        fun attrs(v: JsonObject) = (v["attributes"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull } ?: emptyMap()
        val candidates = variants.filter { v ->
            val a = attrs(v)
            a["org.gradle.usage"] == "java-runtime" && (a["org.gradle.category"] ?: "library") == "library"
        }
        fun score(v: JsonObject): Int {
            val a = attrs(v)
            var s = 0
            when (a["org.jetbrains.kotlin.platform.type"]) { "androidJvm" -> s += 40; "jvm" -> s += 20; null -> s += 10; else -> s -= 100 }
            when (a["org.gradle.jvm.environment"]) { "android" -> s += 5; "standard-jvm" -> s += 2 }
            if (a["com.android.build.api.attributes.BuildTypeAttr"] == "debug") s -= 3
            if (a["org.gradle.libraryelements"] == "aar") s += 1
            return s
        }
        val v = candidates.maxByOrNull(::score)?.takeIf { score(it) > 0 } ?: return null
        (v["available-at"] as? JsonObject)?.let { at ->
            val g = at.str("group") ?: return null
            val m = at.str("module") ?: return null
            val ver = at.str("version") ?: return null
            return ModuleVariant.Redirect(Coordinate(g, m, ver))
        }
        val files = (v["files"] as? JsonArray)?.mapNotNull { f -> (f as? JsonObject)?.let { o -> (o.str("name") ?: return@mapNotNull null) to (o.str("url") ?: return@mapNotNull null) } }.orEmpty()
        val deps = (v["dependencies"] as? JsonArray)?.mapNotNull { d ->
            val o = d as? JsonObject ?: return@mapNotNull null
            val ver = o["version"] as? JsonObject
            val version = ver?.str("strictly") ?: ver?.str("requires") ?: ver?.str("prefers") ?: ""
            Declared(o.str("group") ?: return@mapNotNull null, o.str("module") ?: return@mapNotNull null, version)
        }.orEmpty()
        return ModuleVariant.Local(files, deps)
    }
}

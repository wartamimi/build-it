package dev.buildit.engine.project

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Upgrades a raw manifest JSON document from [fromVersion] to `fromVersion + 1`.
 * Migrations operate on JSON, not on the current data classes, so old shapes stay readable
 * after the Kotlin model evolves.
 */
interface FormatMigration {
    val fromVersion: Int
    fun migrate(document: JsonObject): JsonObject
}

class UnsupportedFormatException(message: String) : Exception(message)

class ProjectMigrator(
    migrations: List<FormatMigration>,
    private val currentVersion: Int = ProjectFormat.CURRENT_VERSION,
) {
    private val byVersion = migrations.associateBy { it.fromVersion }

    init {
        require(byVersion.size == migrations.size) { "Duplicate migration for the same version" }
    }

    fun versionOf(document: JsonObject): Int =
        document["formatVersion"]?.jsonPrimitive?.intOrNull
            ?: throw UnsupportedFormatException("Manifest has no formatVersion")

    /** Returns the document at [currentVersion], applying each migration step in order. */
    fun migrate(document: JsonObject): JsonObject {
        var doc = document
        var version = versionOf(doc)
        if (version > currentVersion) {
            throw UnsupportedFormatException(
                "Project format $version is newer than this app supports ($currentVersion). Update Build It.",
            )
        }
        while (version < currentVersion) {
            val step = byVersion[version]
                ?: throw UnsupportedFormatException("No migration from format version $version")
            doc = step.migrate(doc)
            val next = doc["formatVersion"]?.jsonPrimitive?.int
            check(next == version + 1) { "Migration from $version must set formatVersion to ${version + 1}" }
            version = next
        }
        return doc
    }

    companion object {
        /** All migrations shipped with the app. Format 1 is the first public format, so none yet. */
        val Default = ProjectMigrator(emptyList())

        fun JsonObject.withVersion(version: Int): JsonObject =
            JsonObject(this + ("formatVersion" to JsonPrimitive(version)))
    }
}

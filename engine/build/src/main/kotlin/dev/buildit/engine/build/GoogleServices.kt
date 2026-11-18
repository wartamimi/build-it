package dev.buildit.engine.build

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Does what the `com.google.gms.google-services` Gradle plugin does: turns `app/google-services.json`
 * into string resources (google_app_id, gcm_defaultSenderId, default_web_client_id, ...) for the
 * client whose package name is the app's application ID. Firebase reads these at start-up.
 */
object GoogleServices {
    /** values XML, or null with an error when the file does not fit the app. */
    fun valuesXml(json: File, applicationId: String, diagnostics: Diagnostics, path: String): String? {
        val root = try { Json.parseToJsonElement(json.readText()) as? JsonObject } catch (e: Exception) { null }
        if (root == null) { diagnostics.error("google-services.json is not valid JSON", path); return null }
        fun JsonElement?.obj() = this as? JsonObject
        fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
        val project = root["project_info"].obj()
        val clients = (root["client"] as? JsonArray).orEmpty().mapNotNull { it.obj() }
        val client = clients.firstOrNull { it["client_info"].obj()?.get("android_client_info").obj()?.get("package_name").str() == applicationId }
        if (client == null) {
            val names = clients.mapNotNull { it["client_info"].obj()?.get("android_client_info").obj()?.get("package_name").str() }
            diagnostics.error("google-services.json has no client for $applicationId (it has: ${names.joinToString().ifEmpty { "none" }}). Download it again from the Firebase console for this application ID", path)
            return null
        }
        val values = LinkedHashMap<String, String>()
        client["client_info"].obj()?.get("mobilesdk_app_id").str()?.let { values["google_app_id"] = it }
        project?.get("project_number").str()?.let { values["gcm_defaultSenderId"] = it }
        project?.get("firebase_url").str()?.let { values["firebase_database_url"] = it }
        project?.get("project_id").str()?.let { values["project_id"] = it }
        project?.get("storage_bucket").str()?.let { values["google_storage_bucket"] = it }
        (client["api_key"] as? JsonArray)?.firstOrNull().obj()?.get("current_key").str()?.let {
            values["google_api_key"] = it; values["google_crash_reporting_api_key"] = it
        }
        val oauth = (client["oauth_client"] as? JsonArray).orEmpty().mapNotNull { it.obj() }
        oauth.firstOrNull { (it["client_type"] as? JsonPrimitive)?.intOrNull == 3 }?.get("client_id").str()?.let { values["default_web_client_id"] = it }
        if ("google_app_id" !in values) { diagnostics.error("google-services.json has no mobilesdk_app_id for $applicationId", path); return null }
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<resources>")
            values.forEach { (k, v) -> appendLine("    <string name=\"$k\" translatable=\"false\">${v.replace("&", "&amp;").replace("<", "&lt;")}</string>") }
            appendLine("</resources>")
        }
    }
}

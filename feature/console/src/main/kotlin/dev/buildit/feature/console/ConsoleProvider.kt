package dev.buildit.feature.console

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder

/**
 * Exported, insert-only endpoint for debug builds (see the engine's DebugBridge). The caller is
 * identified by its UID, never by what it claims, and must pass [ConsoleLog.verifier].
 */
class ConsoleProvider : ContentProvider() {
    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values != null) bulkInsert(uri, arrayOf(values))
        return null
    }

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
        if (uri.lastPathSegment != "entries") return 0
        val pkg = caller() ?: return 0
        val raw = values.take(MAX_BATCH).mapNotNull { v ->
            val text = v.getAsString("text") ?: return@mapNotNull null
            RawEntry(
                packageName = pkg,
                session = v.getAsLong("session") ?: 0,
                pid = v.getAsInteger("pid") ?: 0,
                time = v.getAsLong("time") ?: System.currentTimeMillis(),
                kind = v.getAsString("kind") ?: "log",
                text = text.take(MAX_TEXT),
                thread = v.getAsString("thread"),
            )
        }
        ConsoleLog.shared.add(raw)
        return raw.size
    }

    private fun caller(): String? {
        val pm = context?.packageManager ?: return null
        val packages = pm.getPackagesForUid(Binder.getCallingUid()) ?: return null
        // A shared UID could hold several packages; accept the first one that verifies.
        return packages.firstOrNull { ConsoleLog.verifier(it) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val MAX_BATCH = 500
        const val MAX_TEXT = 64 * 1024
    }
}

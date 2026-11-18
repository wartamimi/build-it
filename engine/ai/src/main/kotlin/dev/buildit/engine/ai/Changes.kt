package dev.buildit.engine.ai

import dev.buildit.engine.project.GeneratedFiles
import dev.buildit.engine.project.ProjectFiles
import java.io.File
import java.io.IOException

/**
 * A reviewable change to one file. [before] is the file on disk when the proposal arrived (null if
 * it does not exist); applying writes [after] only if the file still has exactly that content.
 */
data class FileChange(
    val path: String,
    val before: String?,
    val after: String,
    val hunks: List<Hunk>,
    /** The file changed on disk after it was sent to the model, so the proposal may undo that edit. */
    val changedSinceSent: Boolean,
    /** Build It generates this file (from the designer, blocks or project settings). */
    val generated: Boolean,
    /** Why this change cannot be applied, or null. */
    val blocked: Blocked?,
) {
    val isNew get() = before == null
    val unchanged get() = before == after

    enum class Blocked { Incomplete, InvalidPath, Protected, BuildOutput, NotText, TooLarge }
}

sealed interface ApplyResult {
    data object Applied : ApplyResult
    /** The file changed since the diff was made; nothing was written. */
    data object Stale : ApplyResult
    data class Failed(val message: String) : ApplyResult
}

/** Turns proposed files into diffs against the project and applies the ones the user accepts. */
class ChangeSet(private val projectDir: File) {
    private val files = ProjectFiles(projectDir)
    private val generated = GeneratedFiles(projectDir)

    fun prepare(proposed: List<ProposedFile>, sent: List<ContextFile>): List<FileChange> = proposed.map { p ->
        val path = normalize(p.path)
        val blocked = when {
            !p.complete -> FileChange.Blocked.Incomplete
            path == null -> FileChange.Blocked.InvalidPath
            files.isProtected(path) -> FileChange.Blocked.Protected
            path == "build" || path.startsWith("build/") || path.contains("/build/") -> FileChange.Blocked.BuildOutput
            p.text.length > ProjectFiles.MAX_TEXT_BYTES -> FileChange.Blocked.TooLarge
            else -> null
        }
        var before: String? = null
        var notText = false
        if (path != null && blocked == null) {
            val f = runCatching { files.resolve(path) }.getOrNull()
            if (f == null) notText = true
            else if (f.isFile) {
                before = try { files.readText(path) } catch (_: IOException) { notText = true; null }
                if (before != null && before.contains('\u0000')) notText = true
            } else if (f.isDirectory) notText = true
        }
        val after = normalizeEnding(p.text, before)
        val sentText = sent.firstOrNull { it.path == path }?.text
        FileChange(
            path = path ?: p.path,
            before = before,
            after = after,
            hunks = if (blocked == null && !notText) LineDiff.hunks(LineDiff.diff(before.orEmpty(), after)) else emptyList(),
            changedSinceSent = sentText != null && before != null && sentText != before,
            generated = path != null && runCatching { generated.isTracked(path) }.getOrDefault(false),
            blocked = blocked ?: if (notText) FileChange.Blocked.NotText else null,
        )
    }

    /** Writes [change] if the file on disk is still what the diff was made against. */
    fun apply(change: FileChange): ApplyResult {
        if (change.blocked != null) return ApplyResult.Failed(change.blocked.name)
        return try {
            val f = files.resolve(change.path)
            val current = if (f.isFile) files.readText(change.path) else null
            if (current != change.before) return ApplyResult.Stale
            f.parentFile?.mkdirs()
            files.writeText(change.path, change.after)
            ApplyResult.Applied
        } catch (e: IOException) {
            ApplyResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        /** A clean project-relative path, or null if it points outside the project or is malformed. */
        fun normalize(raw: String): String? {
            val p = raw.trim().replace('\\', '/').removePrefix("./")
            if (p.isEmpty() || p.startsWith("/") || p.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(p)) return null
            val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." }) return null
            return parts.joinToString("/")
        }

        /** Keeps the file's existing final-newline convention; new files end with a newline. */
        private fun normalizeEnding(text: String, before: String?): String {
            val body = text.removeSuffix("\n")
            val newline = before?.endsWith("\n") ?: true
            return if (newline) body + "\n" else body
        }
    }
}

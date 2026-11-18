package dev.buildit.engine.blocks

import dev.buildit.engine.project.AndroidSources
import dev.buildit.engine.project.AtomicFiles
import dev.buildit.engine.project.GeneratedFiles
import dev.buildit.engine.project.ProjectFormat
import dev.buildit.engine.project.ProjectManifest
import dev.buildit.engine.project.SourceLanguage
import kotlinx.serialization.json.Json
import java.io.File

/** Reads and writes `.buildit/blocks/<Activity>.json`. */
class BlockStore(private val projectDir: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun file(activity: String) = File(projectDir, "${ProjectFormat.INTERNAL_DIR}/blocks/$activity.json")

    /** The saved document, or an empty one with an `onCreate` event when none exists yet. */
    fun load(activity: String, ids: () -> String): BlockDocument {
        val f = file(activity)
        if (!f.isFile) return starter(activity, ids)
        val doc = json.decodeFromString(BlockDocument.serializer(), f.readText())
        if (doc.formatVersion > BlockDocument.FORMAT_VERSION) throw IllegalStateException("blocks_too_new")
        return doc
    }

    fun exists(activity: String) = file(activity).isFile

    fun save(doc: BlockDocument) = AtomicFiles.writeText(file(doc.activity), json.encodeToString(BlockDocument.serializer(), doc))

    fun encode(doc: BlockDocument): String = json.encodeToString(BlockDocument.serializer(), doc)
    fun decode(text: String): BlockDocument = json.decodeFromString(BlockDocument.serializer(), text)

    companion object {
        fun starter(activity: String, ids: () -> String) = BlockDocument(
            activity = activity,
            stacks = listOf(BlockStack(40f, 40f, listOf(Block(ids(), "event_create")))),
        )
    }
}

/** Generates an activity's source file from its blocks and writes it without clobbering hand edits. */
object BlockSync {
    sealed interface Result {
        /** Generated source and where it was (or would be) written. */
        data class Generated(val path: String, val code: String, val written: Boolean) : Result
        /** Validation errors: nothing generated. */
        data class Invalid(val diagnostics: List<BlockDiagnostic>) : Result
        /** The source file was edited by hand since Build It last wrote it; nothing written. */
        data class UserModified(val path: String, val code: String) : Result
    }

    fun sourcePath(manifest: ProjectManifest, activity: String): String {
        val kotlin = manifest.app.language == SourceLanguage.Kotlin
        val ns = manifest.app.effectiveNamespace
        return "app/src/main/${if (kotlin) "kotlin" else "java"}/${ns.replace('.', '/')}/$activity.${if (kotlin) "kt" else "java"}"
    }

    fun generateCode(manifest: ProjectManifest, doc: BlockDocument, ctx: BlockContext): Result {
        val diagnostics = BlockValidator.validate(doc, ctx)
        if (BlockValidator.hasErrors(diagnostics)) return Result.Invalid(diagnostics)
        val activity = manifest.modules.flatMap { it.activities }.firstOrNull { it.name == doc.activity }
            ?: return Result.Invalid(listOf(BlockDiagnostic(null, BlockDiagnostic.Severity.Error, "unknown_activity", doc.activity)))
        val program = Lowering.lower(doc, ctx, ActivityTarget(manifest.app.effectiveNamespace, activity.name, activity.layout))
        val code = Backend.of(manifest.app.language).generate(program)
        return Result.Generated(sourcePath(manifest, doc.activity), code, written = false)
    }

    /**
     * Generates and writes the source. The untouched file created by a template or "new activity"
     * counts as Build It's own, so the first generation replaces it; any other hand edit is
     * reported as [Result.UserModified] unless [force] is set.
     */
    fun write(projectDir: File, manifest: ProjectManifest, doc: BlockDocument, ctx: BlockContext, force: Boolean = false): Result {
        val generated = generateCode(manifest, doc, ctx) as? Result.Generated ?: return generateCode(manifest, doc, ctx)
        val tracker = GeneratedFiles(projectDir)
        val file = File(projectDir, generated.path)
        val pristine = file.isFile && file.readText() == pristineSource(manifest, doc.activity)
        return when (val r = tracker.write(generated.path, generated.code, force = force || pristine)) {
            GeneratedFiles.WriteResult.Written, GeneratedFiles.WriteResult.Unchanged -> generated.copy(written = r == GeneratedFiles.WriteResult.Written)
            is GeneratedFiles.WriteResult.UserModified -> Result.UserModified(generated.path, generated.code)
        }
    }

    /** What Build It wrote for this activity before it had blocks. */
    fun pristineSource(manifest: ProjectManifest, activity: String): String? {
        val a = manifest.modules.flatMap { it.activities }.firstOrNull { it.name == activity } ?: return null
        val ns = manifest.app.effectiveNamespace
        val base = if (manifest.app.language == SourceLanguage.Kotlin) AndroidSources.kotlinActivity(ns) else AndroidSources.javaActivity(ns)
        return base.replace("MainActivity", a.name).replace("activity_main", a.layout)
    }
}

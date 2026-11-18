package dev.buildit.engine.project

import java.io.File

/** Kinds of resource files the asset managers handle, with their res/ folder and accepted extensions. */
enum class AssetKind(val folder: String, val extensions: Set<String>) {
    Image("drawable", setOf("png", "jpg", "jpeg", "webp", "gif", "xml")),
    Sound("raw", setOf("mp3", "ogg", "wav", "m4a", "aac", "flac", "mid", "midi", "opus")),
    Font("font", setOf("ttf", "otf")),
}

/** A resource file of an [AssetKind]: [name] is the resource name (R.drawable.name), [file] the file. */
data class ProjectAsset(val name: String, val file: File, val kind: AssetKind) {
    val extension get() = file.extension.lowercase()
}

class AssetException(val code: String, message: String) : Exception(message)

/**
 * Images, sounds and fonts of the app module: files directly in res/drawable, res/raw and
 * res/font. Resource names must be valid (lowercase letters, digits and _); importing never
 * replaces an existing resource of the same name.
 */
class ProjectAssets(private val projectDir: File) {
    fun dir(kind: AssetKind) = File(projectDir, "app/src/main/res/${kind.folder}")

    fun list(kind: AssetKind): List<ProjectAsset> =
        dir(kind).listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in kind.extensions && !it.name.startsWith(".") }
            .map { ProjectAsset(resourceName(it), it, kind) }
            .sortedBy { it.name }

    /**
     * Copies [bytes] in as resource [name] with [extension]. Fails with code `invalid_name`,
     * `unsupported` or `exists` (a resource with that name exists, whatever its extension).
     */
    fun import(kind: AssetKind, name: String, extension: String, bytes: ByteArray): ProjectAsset {
        val ext = extension.lowercase().removePrefix(".")
        if (!isValidName(name)) throw AssetException("invalid_name", "Invalid resource name: $name")
        if (ext !in kind.extensions) throw AssetException("unsupported", "Unsupported file type: .$ext")
        if (exists(kind, name)) throw AssetException("exists", "A ${kind.name.lowercase()} named $name already exists")
        if (kind == AssetKind.Image && ext == "xml") {
            val text = bytes.decodeToString()
            if (drawableRoots.none { text.contains("<$it") }) throw AssetException("unsupported", "This XML file is not a drawable")
        }
        val target = File(dir(kind), "$name.$ext")
        target.parentFile.mkdirs()
        AtomicFiles.writeBytes(target, bytes)
        return ProjectAsset(name, target, kind)
    }

    fun rename(asset: ProjectAsset, newName: String): ProjectAsset {
        if (!isValidName(newName)) throw AssetException("invalid_name", "Invalid resource name: $newName")
        if (newName == asset.name) return asset
        if (exists(asset.kind, newName)) throw AssetException("exists", "A ${asset.kind.name.lowercase()} named $newName already exists")
        val target = File(asset.file.parentFile, "$newName.${asset.file.extension}")
        if (!asset.file.renameTo(target)) throw AssetException("io", "Couldn't rename ${asset.file.name}")
        return asset.copy(name = newName, file = target)
    }

    fun delete(asset: ProjectAsset) {
        if (!asset.file.delete() && asset.file.exists()) throw AssetException("io", "Couldn't delete ${asset.file.name}")
    }

    fun exists(kind: AssetKind, name: String) = dir(kind).listFiles().orEmpty().any { it.isFile && resourceName(it) == name }

    companion object {
        private val drawableRoots = listOf("vector", "shape", "selector", "layer-list", "bitmap", "ripple", "inset", "level-list", "animated-vector", "adaptive-icon")
        private val validName = Regex("[a-z][a-z0-9_]*")
        private val reserved = setOf("abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do",
            "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null")

        fun isValidName(name: String) = validName.matches(name) && name !in reserved

        /** Resource name of a file: the name up to the first dot (so `bg.9.png` is `bg`). */
        fun resourceName(f: File) = f.name.substringBefore('.')

        /** A valid resource name derived from a file's display name, e.g. "My Photo (1).PNG" → "my_photo_1". */
        fun suggestName(displayName: String): String {
            val base = displayName.substringBeforeLast('.').lowercase()
                .replace(Regex("[^a-z0-9_]+"), "_").trim('_').replace(Regex("_+"), "_")
            val name = if (base.isEmpty() || !base[0].isLetter()) "res_$base".trimEnd('_') else base
            return if (name in reserved) "${name}_res" else name
        }
    }
}

package dev.buildit.engine.project

import java.io.File
import java.io.IOException

/** What the collection holds; each kind is a folder of the collection directory. */
enum class CollectionKind(val dir: String, val extension: String?) {
    /** A stack of blocks (JSON, see BlockCollection in engine:blocks). */
    Blocks("blocks", "json"),
    /** A view and its children (layout XML). */
    Widgets("widgets", "xml"),
    Images("images", null),
    Sounds("sounds", null),
}

data class CollectionItem(val kind: CollectionKind, val name: String, val file: File, val size: Long, val modified: Long)

/**
 * The collection (like Sketchware's): blocks, widgets, images and sounds saved once and reused in
 * every project. It lives outside the projects, in the app's own storage.
 */
class CollectionStore(private val root: File) {
    private val name = Regex("[\\p{L}\\p{N} _.-]{1,60}")

    fun dir(kind: CollectionKind) = File(root, kind.dir)

    fun list(kind: CollectionKind): List<CollectionItem> =
        dir(kind).listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty()
            .map { CollectionItem(kind, if (kind.extension != null) it.nameWithoutExtension else it.name, it, it.length(), it.lastModified()) }
            .sortedBy { it.name.lowercase() }

    fun isValidName(n: String) = name.matches(n) && n.trim() == n && n != "." && n != ".."

    private fun target(kind: CollectionKind, n: String): File {
        if (!isValidName(n)) throw IllegalArgumentException("invalid_name")
        return File(dir(kind), if (kind.extension != null) "$n.${kind.extension}" else n)
    }

    fun exists(kind: CollectionKind, n: String) = isValidName(n) && target(kind, n).exists()

    /** Saves a text item (blocks or widget); replaces an item with the same name only when [replace]. */
    fun saveText(kind: CollectionKind, n: String, text: String, replace: Boolean = false): CollectionItem {
        require(kind.extension != null) { "Text items are blocks or widgets" }
        val f = target(kind, n)
        if (f.exists() && !replace) throw IOException("exists")
        f.parentFile.mkdirs()
        AtomicFiles.writeText(f, text)
        return list(kind).first { it.file == f }
    }

    /** Copies [source] into the collection under [n] (images and sounds keep their file name extension). */
    fun addFile(kind: CollectionKind, source: File, n: String = source.name, replace: Boolean = false): CollectionItem {
        require(kind.extension == null) { "Files are images or sounds" }
        val f = target(kind, n)
        if (f.exists() && !replace) throw IOException("exists")
        f.parentFile.mkdirs()
        AtomicFiles.writeBytes(f, source.readBytes())
        return list(kind).first { it.file == f }
    }

    fun readText(item: CollectionItem): String = item.file.readText()

    fun delete(item: CollectionItem): Boolean = item.file.parentFile == dir(item.kind) && item.file.delete()

    fun rename(item: CollectionItem, newName: String): CollectionItem {
        val f = target(item.kind, if (item.kind.extension == null && '.' !in newName) newName + item.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" } else newName)
        if (f.exists()) throw IOException("exists")
        if (!item.file.renameTo(f)) throw IOException("rename failed")
        return list(item.kind).first { it.file == f }
    }
}

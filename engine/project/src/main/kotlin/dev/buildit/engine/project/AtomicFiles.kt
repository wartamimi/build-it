package dev.buildit.engine.project

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AtomicFiles {
    /** Writes to a sibling temp file, fsyncs, then renames over [target]. */
    fun writeText(target: File, text: String) = writeBytes(target, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            // Some file systems refuse rename-over; fall back to delete then rename.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Atomic write failed for $target")
            }
        }
    }
}

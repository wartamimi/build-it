plugins {
    alias(libs.plugins.buildit.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// ECJ refers to java.lang.Runtime$Version (Java 9+), which Android does not have, in one place of its
// parser. The jar is copied with that reference renamed to dev.buildit.compat.RtVer1 (same length,
// so the class files are patched byte for byte). TECHNICAL_DECISIONS D14.
val ecjOriginal: Configuration = configurations.create("ecjOriginal") { isTransitive = false }
val patchedEcj = layout.buildDirectory.file("patched/ecj-patched.jar")
val patchEcj = tasks.register("patchEcj") {
    val source: FileCollection = ecjOriginal
    val target = patchedEcj
    inputs.files(source)
    outputs.file(patchedEcj)
    doLast {
        val from = "java/lang/Runtime\$Version".toByteArray()
        val to = "dev/buildit/compat/RtVer1".toByteArray()
        check(from.size == to.size)
        fun indexOf(h: ByteArray, n: ByteArray, start: Int): Int {
            outer@ for (i in start..h.size - n.size) {
                for (j in n.indices) if (h[i + j] != n[j]) continue@outer
                return i
            }
            return -1
        }
        var patched = 0
        val out = target.get().asFile.apply { parentFile.mkdirs() }
        ZipFile(source.singleFile).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                for (e in zip.entries()) {
                    if (e.name.startsWith("META-INF/") && (e.name.endsWith(".SF") || e.name.endsWith(".RSA") || e.name.endsWith(".DSA"))) continue
                    var bytes = zip.getInputStream(e).readBytes()
                    if (e.name.endsWith(".class")) {
                        var i = 0
                        while (true) {
                            i = indexOf(bytes, from, i)
                            if (i < 0) break
                            System.arraycopy(to, 0, bytes, i, to.size); patched++; i += to.size
                        }
                    }
                    z.putNextEntry(ZipEntry(e.name)); z.write(bytes); z.closeEntry()
                }
            }
        }
        check(patched > 0) { "ECJ no longer refers to Runtime.Version; remove this patch" }
    }
}

dependencies {
    ecjOriginal(libs.ecj)
    api(project(":engine:project"))
    api(project(":engine:deps"))
    implementation(project(":engine:layout"))
    implementation(files(patchedEcj).builtBy(patchEcj))
    implementation(libs.r8)
    implementation(libs.apksig)
    implementation(libs.kotlinx.serialization.json)
}

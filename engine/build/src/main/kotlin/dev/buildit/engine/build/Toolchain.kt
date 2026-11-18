package dev.buildit.engine.build

import dev.buildit.engine.layout.XmlElement
import dev.buildit.engine.layout.XmlParser
import dev.buildit.engine.layout.XmlText
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** A downloadable SDK platform from Google's repository index. */
data class PlatformPackage(
    val api: Int,
    val revision: Int,
    val url: String,
    val sha1: String,
    val size: Long,
    val licenseId: String?,
    val displayName: String,
)

class RepositoryIndex(val platforms: List<PlatformPackage>, val licenses: Map<String, String>) {
    /** The stable base platform for [api] (not extensions, minor versions or previews). */
    fun platform(api: Int): PlatformPackage? = platforms.filter { it.api == api }.maxByOrNull { it.revision }

    companion object {
        const val BASE_URL = "https://dl.google.com/android/repository/"
        const val INDEX_URL = BASE_URL + "repository2-3.xml"

        private val basePath = Regex("platforms;android-(\\d+)(?:\\.0)?")

        fun parse(xml: String): RepositoryIndex {
            val root = XmlParser.parse(xml)
            fun XmlElement.child(n: String) = elements.firstOrNull { it.name == n || it.name.endsWith(":$n") }
            fun XmlElement.text() = children.filterIsInstance<XmlText>().joinToString("") { it.text }.trim()
            val stable = root.elements.filter { it.name == "channel" && it.text() == "stable" }.mapNotNull { it["id"] }.toSet()
            val licenses = root.elements.filter { it.name == "license" }.associate { (it["id"] ?: "") to it.text() }
            val platforms = root.elements.filter { it.name == "remotePackage" }.mapNotNull { p ->
                val api = basePath.matchEntire(p["path"] ?: return@mapNotNull null)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
                val channel = p.child("channelRef")?.get("ref")
                if (channel != null && channel !in stable) return@mapNotNull null
                val complete = p.child("archives")?.elements?.firstNotNullOfOrNull { a -> a.child("complete") } ?: return@mapNotNull null
                val checksum = complete.child("checksum")?.takeIf { it["type"] == "sha1" }?.text() ?: return@mapNotNull null
                PlatformPackage(
                    api = api,
                    revision = p.child("revision")?.child("major")?.text()?.toIntOrNull() ?: 0,
                    url = complete.child("url")?.text() ?: return@mapNotNull null,
                    sha1 = checksum.lowercase(),
                    size = complete.child("size")?.text()?.toLongOrNull() ?: 0,
                    licenseId = p.child("uses-license")?.get("ref"),
                    displayName = p.child("display-name")?.text() ?: "Android SDK Platform $api",
                )
            }
            return RepositoryIndex(platforms, licenses)
        }
    }
}

/** Downloads with progress and cancellation; overridable in tests. */
fun interface Downloader {
    fun open(url: String): Pair<InputStream, Long>

    companion object {
        val Http = Downloader { url ->
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            if (c.responseCode !in 200..299) { c.disconnect(); throw IOException("HTTP ${c.responseCode} for $url") }
            c.inputStream to c.contentLengthLong
        }
    }
}

/**
 * Installed build tools on this device: Android platforms (android.jar) downloaded from Google's
 * official SDK repository and verified against the index's SHA-1 before use. Only android.jar is
 * kept from each platform archive; it is a compile-time library, not executable code.
 */
class Toolchain(private val dir: File, private val downloader: Downloader = Downloader.Http) {
    private val platforms = File(dir, "platforms")

    fun androidJar(api: Int): File? = File(platforms, "android-$api/android.jar").takeIf { it.isFile }

    fun installedApis(): List<Int> = platforms.listFiles().orEmpty()
        .mapNotNull { it.name.removePrefix("android-").toIntOrNull()?.takeIf { api -> androidJar(api) != null } }.sorted()

    /** Cache for prepared helper files (compiler stubs). */
    val cacheDir: File get() = File(dir, "cache")

    fun fetchIndex(): RepositoryIndex {
        val (stream, _) = downloader.open(RepositoryIndex.INDEX_URL)
        return RepositoryIndex.parse(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    /**
     * Downloads [pkg], verifies its SHA-1, and installs its android.jar. [progress] gets bytes read
     * and the total; [isCancelled] is checked between reads. Nothing is installed unless every check
     * passes.
     */
    fun install(pkg: PlatformPackage, progress: (Long, Long) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }) {
        dir.mkdirs()
        val tmp = File(dir, "download-${pkg.api}.zip.part")
        try {
            val (input, length) = downloader.open(RepositoryIndex.BASE_URL + pkg.url)
            val total = if (length > 0) length else pkg.size
            val digest = MessageDigest.getInstance("SHA-1")
            input.use { inp ->
                tmp.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        if (isCancelled()) throw BuildCancelledException()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n)
                        read += n
                        progress(read, total)
                    }
                }
            }
            val sha1 = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha1 != pkg.sha1) throw IOException("Checksum mismatch for ${pkg.url}: expected ${pkg.sha1}, got $sha1")

            val target = File(platforms, "android-${pkg.api}").apply { mkdirs() }
            val jarTmp = File(target, "android.jar.part")
            var found = false
            ZipInputStream(tmp.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    // Archives hold one top-level folder (e.g. android-36/android.jar).
                    if (!e.isDirectory && e.name.count { it == '/' } == 1 && e.name.endsWith("/android.jar")) {
                        jarTmp.outputStream().use { z.copyTo(it) }
                        found = true
                        break
                    }
                }
            }
            if (!found) throw IOException("${pkg.url} does not contain android.jar")
            val jar = File(target, "android.jar")
            jar.delete()
            if (!jarTmp.renameTo(jar)) throw IOException("Could not install android.jar")
            File(target, "source.properties").writeText("api=${pkg.api}\nrevision=${pkg.revision}\nsha1=${pkg.sha1}\nurl=${pkg.url}\n")
        } finally {
            tmp.delete()
        }
    }

    fun uninstall(api: Int) { File(platforms, "android-$api").deleteRecursively() }

    /** Licenses are accepted per text (like sdkmanager): a changed license must be accepted again. */
    fun isLicenseAccepted(id: String, text: String): Boolean = File(dir, "licenses/$id").let { it.isFile && it.readText() == hash(text) }

    fun acceptLicense(id: String, text: String) {
        File(dir, "licenses").mkdirs()
        File(dir, "licenses/$id").writeText(hash(text))
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-1").digest(text.trim().toByteArray()).joinToString("") { "%02x".format(it) }
}

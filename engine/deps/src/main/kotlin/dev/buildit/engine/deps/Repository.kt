package dev.buildit.engine.deps

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

data class Repository(val name: String, val url: String) {
    companion object {
        val Google = Repository("Google", "https://dl.google.com/dl/android/maven2/")
        val MavenCentral = Repository("Maven Central", "https://repo1.maven.org/maven2/")
        val defaults = listOf(Google, MavenCentral)
    }
}

/** Fetches a URL; returns null when the server says the file does not exist (404/410). */
fun interface Http {
    fun get(url: String): ByteArray?

    companion object {
        /** Busy answers (429, 503) are retried a few times, waiting as the server asks (at most 20 s). */
        val Default = Http { url ->
            var attempt = 0
            while (true) {
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                val wait: Long = try {
                    c.connectTimeout = 20_000
                    c.readTimeout = 60_000
                    c.instanceFollowRedirects = true
                    c.setRequestProperty("User-Agent", "BuildIt")
                    when (val code = c.responseCode) {
                        in 200..299 -> return@Http c.inputStream.use { it.readBytes() }
                        404, 410 -> return@Http null
                        429, 503 -> if (attempt < 4) retryDelay(c.getHeaderField("Retry-After"), attempt) else throw IOException("HTTP $code for $url (server busy, try again later)")
                        else -> throw IOException("HTTP $code for $url")
                    }
                } finally { c.disconnect() }
                Thread.sleep(wait)
                attempt++
            }
            @Suppress("UNREACHABLE_CODE") null
        }

        internal fun retryDelay(retryAfter: String?, attempt: Int): Long =
            (retryAfter?.trim()?.toLongOrNull()?.times(1000) ?: (1000L shl (attempt * 2))).coerceIn(500, 20_000)
    }
}

/**
 * Local copy of Maven repositories. Released artifacts never change, so a file fetched once is
 * used offline afterwards; "missing" answers are not cached. Every download is written to a
 * temporary file first and moved into place, so a cancelled download never leaves a broken file.
 */
class MavenCache(val dir: File, private val repositories: List<Repository> = Repository.defaults, private val http: Http = Http.Default) {
    /** Where [relative] (e.g. `androidx/core/core/1.0.0/core-1.0.0.pom`) lives locally. */
    fun local(relative: String) = File(dir, relative)

    /** The file, from the cache or the first repository that has it; null if none has it. */
    fun fetch(relative: String, preferred: Repository? = null): File? {
        val f = local(relative)
        if (f.isFile) return f
        val order = listOfNotNull(preferred) + repositories.filter { it != preferred }
        for (repo in order) {
            val bytes = http.get(repo.url + relative) ?: continue
            f.parentFile.mkdirs()
            val tmp = File(f.path + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            File(f.path + ".repo").writeText(repo.name)
            return f
        }
        return null
    }

    /** The repository a cached file came from (dependencies are looked up there first). */
    fun origin(relative: String): Repository? = File(local(relative).path + ".repo").takeIf { it.isFile }?.readText()?.let { n -> repositories.firstOrNull { it.name == n } }

    /** Versions published for a module, newest last (from maven-metadata.xml; not cached). */
    fun versions(group: String, artifact: String): List<String> {
        val path = "${group.replace('.', '/')}/$artifact/maven-metadata.xml"
        val all = repositories.flatMap { repo ->
            val text = runCatching { http.get(repo.url + path) }.getOrNull()?.decodeToString() ?: return@flatMap emptyList()
            Regex("<version>([^<]+)</version>").findAll(text).map { it.groupValues[1].trim() }.toList()
        }
        return all.distinct().sortedWith(Versions)
    }
}

package dev.buildit.engine.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ToolchainTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parsesTheOfficialIndexFormat() {
        val xml = javaClass.getResource("/repository-sample.xml")!!.readText()
        val index = RepositoryIndex.parse(xml)
        val p36 = index.platform(36)!!
        assertEquals("platform-36_r02.zip", p36.url)
        assertEquals("2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576", p36.sha1)
        assertEquals(65878410L, p36.size)
        assertEquals("android-sdk-license", p36.licenseId)
        assertTrue(index.licenses.getValue("android-sdk-license").contains("Android Software Development Kit License Agreement"))
        // android-37.0 is the base platform for API 37; extensions and minor versions are skipped.
        assertEquals(37, index.platform(37)!!.api)
        assertEquals(setOf(35, 36, 37), index.platforms.map { it.api }.toSet())
    }

    private fun platformZip(jar: ByteArray): ByteArray = ByteArrayOutputStream().also { bo ->
        ZipOutputStream(bo).use { z ->
            z.putNextEntry(ZipEntry("android-36/source.properties")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("android-36/android.jar")); z.write(jar); z.closeEntry()
        }
    }.toByteArray()

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun installsOnlyAVerifiedArchive() {
        val zip = platformZip("fake jar".toByteArray())
        var requested = ""
        val tc = Toolchain(tmp.newFolder("tc")) { url -> requested = url; zip.inputStream() to zip.size.toLong() }
        val pkg = PlatformPackage(36, 2, "platform-36_r02.zip", sha1(zip), zip.size.toLong(), null, "Platform 36")
        var last = 0L
        tc.install(pkg, progress = { read, _ -> last = read })
        assertEquals(RepositoryIndex.BASE_URL + "platform-36_r02.zip", requested)
        assertEquals(zip.size.toLong(), last)
        assertEquals("fake jar", tc.androidJar(36)!!.readText())
        assertEquals(listOf(36), tc.installedApis())

        val bad = pkg.copy(api = 35, sha1 = "0".repeat(40))
        try { tc.install(bad); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("Checksum mismatch")) }
        assertNull(tc.androidJar(35))
        assertEquals(listOf(36), tc.installedApis())
    }

    @Test fun cancelledDownloadLeavesNothingBehind() {
        val zip = platformZip(ByteArray(300_000) { it.toByte() })
        val dir = tmp.newFolder("tc")
        val tc = Toolchain(dir) { zip.inputStream() to zip.size.toLong() }
        var reads = 0
        try {
            tc.install(PlatformPackage(36, 2, "p.zip", sha1(zip), 0, null, "p"), progress = { _, _ -> reads++ }, isCancelled = { reads >= 1 })
            fail()
        } catch (_: BuildCancelledException) { }
        assertNull(tc.androidJar(36))
        assertTrue(dir.walkTopDown().none { it.isFile })
    }
}

package dev.buildit.engine.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReleaseKeyringTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Memory : PasswordStore {
        val map = HashMap<String, String>()
        override fun read(name: String) = map[name]
        override fun write(name: String, value: String) { map[name] = value }
        override fun delete(name: String) { map.remove(name) }
    }

    @Test fun createdKeySignsAndPasswordsStayOutOfFiles() {
        val pw = Memory()
        val dir = tmp.newFolder("keys")
        val ring = ReleaseKeyring(dir, pw)
        val info = ring.create(NewKey("My key", "upload", "s3cret-pass".toCharArray(), "Jane Dev", "Example", "id"))
        assertEquals("upload", info.alias)
        assertTrue(info.subject, info.subject.contains("CN=Jane Dev") && info.subject.contains("C=ID"))
        assertEquals(32, info.sha256.split(':').size)
        val key = ring.key(info.id)
        assertEquals(info.subject, key.certificates.first().subjectX500Principal.name)
        assertTrue(dir.walk().filter { it.isFile }.none { f -> f.readBytes().toString(Charsets.ISO_8859_1).contains("s3cret-pass") })
        assertEquals(listOf(info), ring.list())

        ring.assign("p1", info.id)
        assertEquals(info.id, ring.assigned("p1"))
        ring.delete(info.id)
        assertNull(ring.assigned("p1"))
        assertTrue(ring.list().isEmpty())
        assertTrue(pw.map.isEmpty())
    }

    @Test fun importChecksPasswordAndAlias() {
        val src = ReleaseKeyring(tmp.newFolder("a"), Memory())
        val made = src.create(NewKey("k", "first", "password1".toCharArray(), "A"))
        val bytes = src.keystore(made.id).readBytes()
        val ring = ReleaseKeyring(tmp.newFolder("b"), Memory())
        try {
            ring.import(bytes, "x", "wrong-pass".toCharArray(), null, null); fail()
        } catch (e: ReleaseKeyException) {
            assertEquals(ReleaseKeyException.Reason.WrongPassword, e.reason)
        }
        try {
            ring.import(bytes, "x", "password1".toCharArray(), "other", null); fail()
        } catch (e: ReleaseKeyException) {
            assertEquals(ReleaseKeyException.Reason.NoKey, e.reason)
        }
        try {
            ring.import(byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte(), 0, 0, 0, 2), "x", "a".toCharArray(), null, null); fail()
        } catch (e: ReleaseKeyException) {
            assertEquals(ReleaseKeyException.Reason.NotPkcs12, e.reason)
        }
        assertTrue(ring.list().isEmpty())
        val imported = ring.import(bytes, "", "password1".toCharArray(), null, null)
        assertEquals("first", imported.name)
        assertEquals(made.sha256, imported.sha256)
        assertEquals(made.sha256.replace(":", "").lowercase(), sha(ring.key(imported.id).certificates.first().encoded))
    }

    @Test fun releaseBuildIsSignedWithTheChosenKey() {
        val jar = TestSdk.androidJar
        TestSdk.require(jar != null, "android.jar")
        val ring = ReleaseKeyring(tmp.newFolder("keys"), Memory())
        val info = ring.create(NewKey("k", "upload", "password1".toCharArray(), "Release"))
        val p = dev.buildit.engine.project.ProjectStore(tmp.newFolder("ws")).create("Hello", "com.example.hello")
        val result = BuildPipeline().run(BuildRequest(p.directory, p.manifest.app, jar!!, BuildType.Release, key = { ring.key(info.id) }))
        assertTrue(result.diagnostics.joinToString(), result.success)
        val apk = result.apk!!
        assertTrue(apk.name.endsWith("-release.apk"))
        val v = com.android.apksig.ApkVerifier.Builder(apk).build().verify()
        assertTrue(v.isVerified)
        assertEquals(info.sha256.replace(":", "").lowercase(), sha(v.signerCertificates.first().encoded))
        assertFalse(File(p.directory, "build").walk().any { it.name.endsWith(".p12") })
    }

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

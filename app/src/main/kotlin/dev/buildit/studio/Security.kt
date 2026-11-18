package dev.buildit.studio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.buildit.engine.ai.SecretStore
import dev.buildit.engine.build.Keys
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets (AI provider keys) encrypted with AES-GCM under a key that never leaves the Android
 * Keystore. Only the ciphertext is stored, in app-private preferences excluded from backup.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    @Synchronized override fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(c.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull() // A key that can no longer be decrypted (e.g. after a device reset) reads as missing.
    }

    @Synchronized override fun write(name: String, value: String) {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        check(iv.size == IV_SIZE)
        prefs.edit().putString(name, Base64.encodeToString(iv + c.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).apply()
    }

    @Synchronized override fun delete(name: String) { prefs.edit().remove(name).apply() }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "buildit-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/**
 * Lets an app write to the console only if it is signed with this device's Build It debug key,
 * i.e. it is a debug build made here. The caller's identity comes from the system, by UID.
 */
class DebugBuildVerifier(context: Context, private val debugKeystore: File) : (String) -> Boolean {
    private val pm = context.applicationContext.packageManager
    @Volatile private var expected: Pair<Long, String>? = null

    private fun expectedHash(): String? {
        if (!debugKeystore.isFile) return null
        val stamp = debugKeystore.lastModified()
        expected?.takeIf { it.first == stamp }?.let { return it.second }
        val hash = runCatching { sha256(Keys.debugKey(debugKeystore).certificates.first().encoded) }.getOrNull() ?: return null
        expected = stamp to hash
        return hash
    }

    override fun invoke(packageName: String): Boolean {
        val want = expectedHash() ?: return false
        val signers = runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            }
        }.getOrNull() ?: return false
        return signers.size == 1 && sha256(signers[0].toByteArray()) == want
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

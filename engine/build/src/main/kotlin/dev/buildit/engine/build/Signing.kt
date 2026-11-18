package dev.buildit.engine.build

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A private key and its certificate chain. */
class SigningKey(val privateKey: PrivateKey, val certificates: List<X509Certificate>)

/**
 * Keys for signing. The debug key is created once per device (PKCS#12 keystore, standard debug
 * passwords) so reinstalling a debug build keeps working. Release keys come from the user's own
 * PKCS#12 keystore.
 */
object Keys {
    const val DEBUG_ALIAS = "buildit-debug"
    const val DEBUG_PASSWORD = "android"

    fun debugKey(keystore: File): SigningKey {
        if (keystore.isFile) return load(keystore, DEBUG_PASSWORD.toCharArray(), DEBUG_ALIAS, DEBUG_PASSWORD.toCharArray())
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
        val pair = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val cert = SelfSignedCertificate.create(pair.private, pair.public.encoded, "CN=Build It Debug, O=Build It", Date(now - 86_400_000L), Date(now + 30L * 365 * 86_400_000L))
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(DEBUG_ALIAS, pair.private, DEBUG_PASSWORD.toCharArray(), arrayOf(cert))
        keystore.parentFile?.mkdirs()
        val tmp = File(keystore.path + ".tmp")
        tmp.outputStream().use { ks.store(it, DEBUG_PASSWORD.toCharArray()) }
        if (!tmp.renameTo(keystore)) { tmp.copyTo(keystore, overwrite = true); tmp.delete() }
        return SigningKey(pair.private, listOf(cert))
    }

    fun load(keystore: File, storePassword: CharArray, alias: String, keyPassword: CharArray): SigningKey {
        val ks = KeyStore.getInstance("PKCS12")
        keystore.inputStream().use { ks.load(it, storePassword) }
        val key = ks.getKey(alias, keyPassword) as? PrivateKey ?: error("No private key named '$alias' in ${keystore.name}")
        val chain = ks.getCertificateChain(alias)?.map { it as X509Certificate } ?: error("No certificate for '$alias'")
        return SigningKey(key, chain)
    }
}

object ApkSigning {
    fun sign(unsigned: File, out: File, key: SigningKey, minSdk: Int) {
        val config = ApkSigner.SignerConfig.Builder("BUILDIT", key.privateKey, key.certificates).build()
        out.delete()
        ApkSigner.Builder(listOf(config))
            .setInputApk(unsigned)
            .setOutputApk(out)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(minSdk < 24)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setCreatedBy("Build It")
            .build()
            .sign()
    }

    /** Verifies the signature like the platform does; returns the problems (empty when valid). */
    fun verify(apk: File, minSdk: Int): List<String> {
        val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(minSdk).build().verify()
        return if (result.isVerified) emptyList() else result.errors.map { it.toString() } + result.allSchemeErrors()
    }

    private fun ApkVerifier.Result.allSchemeErrors(): List<String> =
        v2SchemeSigners.flatMap { s -> s.errors.map { it.toString() } } + v3SchemeSigners.flatMap { s -> s.errors.map { it.toString() } }
}

/** Minimal DER encoder for a self-signed X.509 certificate (SHA256withRSA). */
internal object SelfSignedCertificate {
    private val SHA256_RSA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val OIDS = mapOf("CN" to byteArrayOf(0x55, 0x04, 0x03), "O" to byteArrayOf(0x55, 0x04, 0x0a), "C" to byteArrayOf(0x55, 0x04, 0x06))

    fun create(key: PrivateKey, publicKeyInfo: ByteArray, dn: String, notBefore: Date, notAfter: Date): X509Certificate {
        val algorithm = seq(tlv(0x06, SHA256_RSA), tlv(0x05, ByteArray(0)))
        val name = seq(*dn.split(',').map { it.trim() }.map { part ->
            val (k, v) = part.split('=', limit = 2)
            set(seq(tlv(0x06, OIDS.getValue(k)), tlv(0x0c, v.toByteArray(Charsets.UTF_8))))
        }.toTypedArray())
        val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))), // v3
            tlv(0x02, serial.toByteArray()),
            algorithm,
            name,
            seq(time(notBefore), time(notAfter)),
            name,
            publicKeyInfo,
        )
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(tbs) }.sign()
        val cert = seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as X509Certificate
    }

    private fun time(d: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { time = d }.get(java.util.Calendar.YEAR)
        return if (year < 2050) tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = utc }.format(d).toByteArray())
        else tlv(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = utc }.format(d).toByteArray())
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = value.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(value)
        return out.toByteArray()
    }
}

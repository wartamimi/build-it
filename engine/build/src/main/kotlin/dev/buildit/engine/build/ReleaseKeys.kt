package dev.buildit.engine.build

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID

/** Where keystore passwords are kept. On Android they are encrypted with the Android Keystore. */
interface PasswordStore {
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun delete(name: String)
}

/** A release key as the UI lists it. Never holds a password. */
data class ReleaseKeyInfo(
    val id: String,
    val name: String,
    val alias: String,
    val subject: String,
    /** SHA-256 of the certificate, as stores and Google show it (AA:BB:...). */
    val sha256: String,
    val expires: Long,
)

/** Values for a new release key. */
data class NewKey(
    val name: String,
    val alias: String,
    val password: CharArray,
    val commonName: String,
    val organization: String = "",
    val country: String = "",
    val years: Int = 25,
)

/** Why a keystore could not be used, for the UI to explain. */
class ReleaseKeyException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NotPkcs12, WrongPassword, NoKey, Invalid }
}

/**
 * The user's release signing keys (TECHNICAL_DECISIONS D22). Each key is a PKCS#12 keystore in
 * [dir], which is app-private and never inside a project, so keys are not exported with a project
 * ZIP or shared by accident. Passwords live only in the [PasswordStore]. The user can export the
 * keystore file to back it up: losing it means losing the ability to update the app.
 */
class ReleaseKeyring(private val dir: File, private val passwords: PasswordStore) {
    @Serializable
    private data class Meta(val name: String, val alias: String)

    @Serializable
    private data class Assignments(val projects: Map<String, String> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val assignmentsFile get() = File(dir, "projects.json")

    @Synchronized
    fun list(): List<ReleaseKeyInfo> =
        dir.listFiles().orEmpty().filter { it.name.endsWith(".p12") }.mapNotNull { f -> runCatching { info(f.nameWithoutExtension) }.getOrNull() }.sortedBy { it.name.lowercase() }

    /** Creates a new RSA 2048 key with a self-signed certificate. */
    @Synchronized
    fun create(k: NewKey): ReleaseKeyInfo {
        require(k.password.size >= 6) { "The password needs at least 6 characters" }
        require(k.alias.isNotBlank() && k.commonName.isNotBlank()) { "Alias and name are required" }
        require(k.years in 1..100) { "Validity must be 1 to 100 years" }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val dn = buildList {
            add("CN=${k.commonName.clean()}")
            if (k.organization.isNotBlank()) add("O=${k.organization.clean()}")
            if (k.country.isNotBlank()) add("C=${k.country.clean().take(2).uppercase()}")
        }.joinToString(", ")
        val cert = SelfSignedCertificate.create(pair.private, pair.public.encoded, dn, Date(now - 86_400_000L), Date(now + k.years * 365L * 86_400_000L))
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(k.alias.trim(), pair.private, k.password, arrayOf(cert))
        val id = UUID.randomUUID().toString()
        dir.mkdirs()
        val file = keystore(id)
        file.outputStream().use { ks.store(it, k.password) }
        return save(id, Meta(k.name.trim().ifEmpty { k.alias.trim() }, k.alias.trim()), String(k.password), String(k.password))
    }

    /**
     * Adds an existing PKCS#12 keystore (.p12/.pfx, or a .jks converted with keytool). [alias] null
     * takes the only key in it. The file is checked before anything is kept.
     */
    @Synchronized
    fun import(bytes: ByteArray, name: String, storePassword: CharArray, alias: String?, keyPassword: CharArray?): ReleaseKeyInfo {
        if (looksLikeJks(bytes)) throw ReleaseKeyException(ReleaseKeyException.Reason.NotPkcs12, "This is a JKS keystore. Android can only read PKCS#12: convert it with keytool -importkeystore -deststoretype pkcs12")
        val ks = KeyStore.getInstance("PKCS12")
        try {
            ks.load(bytes.inputStream(), storePassword)
        } catch (e: java.io.IOException) {
            val wrong = generateSequence(e as Throwable) { it.cause }.any { it is java.security.UnrecoverableKeyException || it is javax.crypto.BadPaddingException } ||
                e.message.orEmpty().contains("password", ignoreCase = true)
            throw ReleaseKeyException(if (wrong) ReleaseKeyException.Reason.WrongPassword else ReleaseKeyException.Reason.Invalid, e.message ?: "Not a PKCS#12 keystore")
        }
        val keys = ks.aliases().toList().filter { ks.isKeyEntry(it) }
        val chosen = alias?.trim()?.takeIf { it.isNotEmpty() } ?: keys.singleOrNull()
            ?: throw ReleaseKeyException(ReleaseKeyException.Reason.NoKey, if (keys.isEmpty()) "The keystore has no private key" else "The keystore has several keys; enter the alias (${keys.joinToString()})")
        if (chosen !in keys) throw ReleaseKeyException(ReleaseKeyException.Reason.NoKey, "No key named '$chosen' (${keys.joinToString()})")
        val kp = keyPassword?.takeIf { it.isNotEmpty() } ?: storePassword
        try {
            ks.getKey(chosen, kp) as? PrivateKey ?: throw ReleaseKeyException(ReleaseKeyException.Reason.NoKey, "'$chosen' is not a private key")
        } catch (e: java.security.UnrecoverableKeyException) {
            throw ReleaseKeyException(ReleaseKeyException.Reason.WrongPassword, "Wrong key password")
        }
        val id = UUID.randomUUID().toString()
        dir.mkdirs()
        keystore(id).writeBytes(bytes)
        return save(id, Meta(name.trim().ifEmpty { chosen }, chosen), String(storePassword), String(kp))
    }

    /** The key for signing. */
    @Synchronized
    fun key(id: String): SigningKey {
        val meta = meta(id)
        val store = passwords.read(storeName(id)) ?: throw ReleaseKeyException(ReleaseKeyException.Reason.WrongPassword, "The password for this key is no longer available on this device; import the keystore again")
        val key = passwords.read(keyName(id)) ?: store
        return Keys.load(keystore(id), store.toCharArray(), meta.alias, key.toCharArray())
    }

    /** The keystore file, for the user to back up. */
    fun keystore(id: String) = File(dir, "${safe(id)}.p12")

    @Synchronized
    fun delete(id: String) {
        keystore(id).delete(); File(dir, "${safe(id)}.json").delete()
        passwords.delete(storeName(id)); passwords.delete(keyName(id))
        val a = assignments()
        writeAssignments(Assignments(a.projects.filterValues { it != id }))
    }

    @Synchronized
    fun assigned(projectId: String): String? = assignments().projects[projectId]?.takeIf { keystore(it).isFile }

    @Synchronized
    fun assign(projectId: String, id: String?) {
        val a = assignments().projects.toMutableMap()
        if (id == null) a.remove(projectId) else a[projectId] = id
        writeAssignments(Assignments(a))
    }

    private fun save(id: String, meta: Meta, store: String, key: String): ReleaseKeyInfo {
        passwords.write(storeName(id), store)
        if (key != store) passwords.write(keyName(id), key) else passwords.delete(keyName(id))
        File(dir, "$id.json").writeText(json.encodeToString(Meta.serializer(), meta))
        return info(id)
    }

    private fun meta(id: String): Meta = json.decodeFromString(Meta.serializer(), File(dir, "${safe(id)}.json").readText())

    /** Reads the certificate without a password (PKCS#12 certificates may still be encrypted; then the stored password is used). */
    private fun info(id: String): ReleaseKeyInfo {
        val meta = meta(id)
        val cert = certificate(id, meta.alias)
        val sha = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        return ReleaseKeyInfo(id, meta.name, meta.alias, cert.subjectX500Principal.name, sha, cert.notAfter.time)
    }

    private fun certificate(id: String, alias: String): X509Certificate {
        val pw = passwords.read(storeName(id))?.toCharArray() ?: error("missing password")
        val ks = KeyStore.getInstance("PKCS12").apply { keystore(id).inputStream().use { load(it, pw) } }
        return ks.getCertificate(alias) as X509Certificate
    }

    private fun assignments(): Assignments =
        runCatching { json.decodeFromString(Assignments.serializer(), assignmentsFile.readText()) }.getOrDefault(Assignments())

    private fun writeAssignments(a: Assignments) { dir.mkdirs(); assignmentsFile.writeText(json.encodeToString(Assignments.serializer(), a)) }

    private fun storeName(id: String) = "release-key:$id:store"
    private fun keyName(id: String) = "release-key:$id:key"
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }
    private fun String.clean() = replace(",", " ").replace("=", " ").trim()

    private fun looksLikeJks(b: ByteArray) = b.size >= 4 && (b[0] == 0xFE.toByte() && b[1] == 0xED.toByte() && b[2] == 0xFE.toByte() && b[3] == 0xED.toByte())
}

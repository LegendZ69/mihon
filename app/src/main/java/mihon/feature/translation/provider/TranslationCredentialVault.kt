package mihon.feature.translation.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.VertexAuthMode
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User-owned credentials live outside Android backup and never enter application preferences. */
@Inject
@SingleIn(AppScope::class)
class TranslationCredentialVault(context: Context) {
    private val storage = EncryptedCredentialStorage(
        File(context.noBackupFilesDir, "translation/credentials"),
        ::encryptionKey,
    )

    suspend fun import(
        id: String,
        jsonOrKey: String,
        kind: TranslationProviderKind,
        label: String = id,
        vertexAuthMode: VertexAuthMode = VertexAuthMode.SERVICE_ACCOUNT_JSON,
    ): CredentialInfo = withContext(Dispatchers.IO) {
        val credential = CredentialParser.forProvider(jsonOrKey, label, id, kind, vertexAuthMode)
        storage.save(credential)
        credential.info()
    }

    suspend fun info(id: String): CredentialInfo? = withContext(Dispatchers.IO) {
        runCatching { storage.load(id).info() }.getOrNull()
    }

    suspend fun delete(id: String) = remove(id)

    suspend fun importServiceAccount(document: String, label: String = "Service account"): CredentialInfo =
        withContext(Dispatchers.IO) {
            val credential = CredentialParser.serviceAccount(document, label)
            storage.save(credential)
            credential.info()
        }

    suspend fun importApiKey(key: String, label: String = "API key"): CredentialInfo = withContext(Dispatchers.IO) {
        val credential = CredentialParser.apiKey(key, label)
        storage.save(credential)
        credential.info()
    }

    suspend fun list(): List<CredentialInfo> = withContext(Dispatchers.IO) { storage.list() }

    suspend fun remove(reference: String) = withContext(Dispatchers.IO) { storage.remove(reference) }

    internal suspend fun load(reference: String): StoredTranslationCredential =
        withContext(Dispatchers.IO) { storage.load(reference) }

    @Synchronized
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "mihon.translation.credentials.v1"
    }
}

@Serializable
data class CredentialInfo(
    val reference: String,
    val label: String,
    val kind: CredentialKind,
    val projectId: String? = null,
    val accountEmail: String? = null,
    val createdAt: Long,
)

@Serializable
enum class CredentialKind { SERVICE_ACCOUNT, API_KEY, SERVICE_ACCOUNT_BOUND_KEY }

/** Intentionally not a data class: toString must never print a key. */
@Serializable
internal class StoredTranslationCredential(
    val reference: String,
    val label: String,
    val kind: CredentialKind,
    val createdAt: Long,
    val apiKey: String? = null,
    val projectId: String? = null,
    val accountEmail: String? = null,
    val privateKey: String? = null,
    val privateKeyId: String? = null,
) {
    fun info() = CredentialInfo(reference, label, kind, projectId, accountEmail, createdAt)
    override fun toString() = "TranslationCredential(reference=$reference, kind=$kind)"
}

internal object CredentialParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun forProvider(
        value: String,
        label: String,
        reference: String,
        kind: TranslationProviderKind,
        vertexAuthMode: VertexAuthMode = VertexAuthMode.SERVICE_ACCOUNT_JSON,
    ): StoredTranslationCredential = when (kind) {
        TranslationProviderKind.VERTEX_SERVICE_ACCOUNT -> if (vertexAuthMode ==
            VertexAuthMode.SERVICE_ACCOUNT_BOUND_KEY
        ) {
            val parsed = apiKey(value, label, reference)
            StoredTranslationCredential(
                parsed.reference,
                parsed.label,
                CredentialKind.SERVICE_ACCOUNT_BOUND_KEY,
                parsed.createdAt,
                apiKey = parsed.apiKey,
            )
        } else {
            serviceAccount(value, label, reference)
        }
        else -> apiKey(value, label, reference)
    }

    fun apiKey(
        value: String,
        label: String,
        reference: String = UUID.randomUUID().toString(),
    ): StoredTranslationCredential {
        val key = value.trim()
        require(key.isNotEmpty() && key.length <= 16_384 && key.none { it.isWhitespace() }) {
            "Enter a valid API key without whitespace."
        }
        return StoredTranslationCredential(
            reference = reference,
            label = label.trim().take(100).ifBlank { "API key" },
            kind = CredentialKind.API_KEY,
            createdAt = System.currentTimeMillis(),
            apiKey = key,
        )
    }

    fun serviceAccount(
        document: String,
        label: String,
        reference: String = UUID.randomUUID().toString(),
    ): StoredTranslationCredential {
        require(document.length <= 128 * 1024) { "Service account document is too large." }
        val value = try {
            json.parseToJsonElement(document).jsonObject
        } catch (_: Exception) {
            throw IllegalArgumentException("The service account document is not valid JSON.")
        }
        fun field(name: String): String? = runCatching { value[name]?.jsonPrimitive?.content }.getOrNull()
        require(field("type") == "service_account") { "Choose a Google service account JSON key." }
        val project = field("project_id").orEmpty()
        require(project.matches(Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]"))) { "Invalid project ID." }
        val email = field("client_email").orEmpty()
        require(email.endsWith(".iam.gserviceaccount.com") && email.contains('@')) { "Invalid service account email." }
        val tokenUri = field("token_uri")
        require(tokenUri == null || tokenUri == "https://oauth2.googleapis.com/token") {
            "The credential must use Google's OAuth token endpoint."
        }
        val key = field("private_key").orEmpty()
        try {
            rsaPrivateKey(key)
        } catch (_: Exception) {
            throw IllegalArgumentException("The document does not contain a valid RSA private key.")
        }
        return StoredTranslationCredential(
            reference = reference,
            label = label.trim().take(100).ifBlank { "Service account" },
            kind = CredentialKind.SERVICE_ACCOUNT,
            createdAt = System.currentTimeMillis(),
            projectId = project,
            accountEmail = email,
            privateKey = key,
            privateKeyId = field("private_key_id"),
        )
    }

    fun rsaPrivateKey(pem: String): PrivateKey {
        require(pem.startsWith("-----BEGIN PRIVATE KEY-----"))
        val bytes = Base64.getDecoder().decode(
            pem.removePrefix("-----BEGIN PRIVATE KEY-----")
                .substringBefore("-----END PRIVATE KEY-----")
                .filterNot(Char::isWhitespace),
        )
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }
}

internal class EncryptedCredentialStorage(
    private val directory: File,
    private val key: () -> SecretKey,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun save(value: StoredTranslationCredential) {
        val destination = file(value.reference)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create credential storage." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.encodeToString(value).toByteArray(Charsets.UTF_8))
        val temporary = File(directory, "${value.reference}.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(FORMAT_VERSION)
                output.write(cipher.iv.size)
                output.write(cipher.iv)
                output.write(encrypted)
                output.fd.sync()
            }
            check(temporary.renameTo(destination)) { "Cannot save credential." }
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun load(reference: String): StoredTranslationCredential {
        val source = file(reference)
        check(source.isFile) { "Credential is missing. Import it again in translation settings." }
        require(source.length() <= 256 * 1024) { "Invalid credential file." }
        val bytes = source.readBytes()
        require(bytes.size > 30 && bytes[0].toInt() == FORMAT_VERSION) { "Invalid credential file." }
        val ivSize = bytes[1].toInt()
        require(ivSize == 12 && bytes.size > 2 + ivSize + 16) { "Invalid credential file." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(2, 2 + ivSize)))
        }
        val plaintext = cipher.doFinal(bytes, 2 + ivSize, bytes.size - 2 - ivSize)
        return try {
            json.decodeFromString<StoredTranslationCredential>(plaintext.toString(Charsets.UTF_8)).also {
                require(it.reference == reference) { "Invalid credential reference." }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun list(): List<CredentialInfo> = directory.listFiles().orEmpty()
        .filter { it.extension == "credential" }
        .mapNotNull { runCatching { load(it.nameWithoutExtension).info() }.getOrNull() }
        .sortedByDescending { it.createdAt }

    @Synchronized
    fun remove(reference: String) {
        val source = file(reference)
        check(!source.exists() || source.delete()) { "Cannot remove credential." }
    }

    private fun file(reference: String): File {
        require(reference.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid credential reference." }
        return File(directory, "$reference.credential")
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

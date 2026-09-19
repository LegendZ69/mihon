package mihon.feature.translation.provider

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** OAuth assertions and token responses are deliberately never passed to the diagnostics recorder. */
internal class GoogleServiceAccountTokens(private val client: OkHttpClient) {
    private class AccessToken(val value: String, val expiresAt: Long, val fingerprint: String)
    private val tokens = ConcurrentHashMap<String, AccessToken>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun accessToken(credential: StoredTranslationCredential): String =
        locks.getOrPut(credential.reference) { Mutex() }.withLock {
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest((credential.privateKey.orEmpty() + credential.accountEmail).toByteArray())
                .joinToString("") { "%02x".format(it) }
            tokens[credential.reference]?.takeIf {
                it.fingerprint == fingerprint && it.expiresAt > System.currentTimeMillis() + 60_000
            }?.let { return@withLock it.value }
            if (credential.kind != CredentialKind.SERVICE_ACCOUNT) {
                throw TranslationException(
                    TranslationFailureKind.AUTHENTICATION,
                    "Import a service account for this provider.",
                )
            }
            val response = client.newCall(
                Request.Builder()
                    .url(TOKEN_URL)
                    .post(
                        FormBody.Builder()
                            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                            .add("assertion", assertion(credential, System.currentTimeMillis() / 1000))
                            .build(),
                    )
                    .build(),
            ).await()
            response.use {
                if (!it.isSuccessful) {
                    throw TranslationException(
                        if (it.code == 429) {
                            TranslationFailureKind.RATE_LIMIT
                        } else if (it.code >= 500) {
                            TranslationFailureKind.TRANSIENT
                        } else {
                            TranslationFailureKind.AUTHENTICATION
                        },
                        "Google OAuth rejected the credential (HTTP ${it.code}). Check the key, permissions " +
                            "and device clock.",
                        httpStatus = it.code,
                    )
                }
                val source = it.body.source()
                if (source.request(65_537)) {
                    throw TranslationException(
                        TranslationFailureKind.AUTHENTICATION,
                        "Google OAuth returned an oversized token response.",
                    )
                }
                val body = source.readUtf8()
                val payload = try {
                    TranslationWireFormat.json.parseToJsonElement(body).jsonObject
                } catch (_: Exception) {
                    throw TranslationException(
                        TranslationFailureKind.AUTHENTICATION,
                        "Google OAuth returned an invalid token response.",
                    )
                }
                val token = payload["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: throw TranslationException(
                        TranslationFailureKind.AUTHENTICATION,
                        "Google OAuth did not return an access token.",
                    )
                val seconds = payload["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
                tokens[credential.reference] =
                    AccessToken(token, System.currentTimeMillis() + seconds * 1000, fingerprint)
                token
            }
        }

    fun invalidate(reference: String) {
        tokens.remove(reference)
    }

    internal fun assertion(credential: StoredTranslationCredential, issuedAt: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun encoded(text: String) = encoder.encodeToString(text.toByteArray(Charsets.UTF_8))
        val header = buildJsonObject {
            put("alg", "RS256")
            put("typ", "JWT")
            credential.privateKeyId?.let { put("kid", it) }
        }
        val claims = buildJsonObject {
            put("iss", credential.accountEmail)
            put("scope", "https://www.googleapis.com/auth/cloud-platform")
            put("aud", TOKEN_URL)
            put("iat", issuedAt)
            put("exp", issuedAt + 3600)
        }
        val unsigned = "${encoded(header.toString())}.${encoded(claims.toString())}"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(CredentialParser.rsaPrivateKey(credential.privateKey.orEmpty()))
            update(unsigned.toByteArray(Charsets.US_ASCII))
        }.sign()
        return "$unsigned.${encoder.encodeToString(signature)}"
    }

    private companion object {
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    }
}

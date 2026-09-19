package mihon.feature.translation.provider

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tachiyomi.domain.translation.model.TranslationPromptStage
import java.security.MessageDigest

/** Out-of-band request tag: never becomes an undocumented provider parameter. */
internal data class TranslationPromptDiagnostics(
    val stage: TranslationPromptStage,
    val system: String,
    val user: String,
) {
    val sha256: String get() = MessageDigest.getInstance("SHA-256").digest(
        buildJsonObject {
            put("system", system)
            put("user", user)
        }.toString().toByteArray(Charsets.UTF_8),
    ).joinToString("") { "%02x".format(it) }

    fun details(secrets: Collection<String>): Map<String, String> = buildMap {
        put("promptStage", stage.name)
        put("promptSha256", sha256)
        put("promptSystem", DiagnosticRedactor.bodyText(system, secrets).take(LIMIT))
        put("promptUser", DiagnosticRedactor.bodyText(user, secrets).take(LIMIT))
        put("promptSystemCharacters", system.length.toString())
        put("promptUserCharacters", user.length.toString())
        if (system.length > LIMIT || user.length > LIMIT) {
            put(
                "promptSnapshotIncomplete",
                "Long text omitted from event; SHA-256 covers the full effective prompts. " +
                    "See sanitized API capture when enabled.",
            )
        }
    }

    private companion object {
        const val LIMIT = 65_536
    }
}

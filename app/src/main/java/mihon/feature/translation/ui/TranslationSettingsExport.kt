package mihon.feature.translation.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mihon.feature.translation.provider.SanitizedCaptureOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.ByteArrayOutputStream

internal fun serializeTranslationSettingsExport(settings: TranslationSettings, json: Json): String {
    // Structured advanced settings receive the same nested omission policy as diagnostic exports.
    json.parseToJsonElement(settings.provider.advancedJson).jsonObject
    val portable = settings.copy(
        provider = settings.provider.copy(
            credentialId = "",
            extraHeaders = emptyMap(),
            baseUrl = settings.provider.baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.username("")?.password("")?.query(null)?.fragment(null)?.build()?.toString()?.removeSuffix("/")
                ?: "",
        ),
    )
    val output = ByteArrayOutputStream()
    var complete = false
    SanitizedCaptureOutputStream(output, completed = { complete = it.complete }).use { sanitizer ->
        sanitizer.write(json.encodeToString(portable).toByteArray())
        sanitizer.finish(true)
    }
    check(complete) { "Settings could not be safely exported within the sanitized payload limits." }
    return output.toString(Charsets.UTF_8.name())
}

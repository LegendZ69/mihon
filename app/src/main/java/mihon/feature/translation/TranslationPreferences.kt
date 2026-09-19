package mihon.feature.translation

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.translation.model.TranslationSettings

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(context: Context) {
    // This separate store is not included in Mihon's regular preference backups.
    private val store = context.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val mutableSettings = MutableStateFlow(read("global") ?: TranslationSettings())
    val settings = mutableSettings.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    fun effectiveSettings(mangaId: Long): TranslationSettings = read("series_$mangaId") ?: settings.value

    fun hasSeriesOverride(mangaId: Long) = store.contains("series_$mangaId")

    @Synchronized
    fun update(value: TranslationSettings, mangaId: Long? = null) {
        value.validate()
        val sensitive = value.provider.extraHeaders.keys.firstOrNull {
            it.equals("Authorization", true) || it.contains("key", true) || it.contains("token", true) ||
                it.equals("Cookie", true)
        }
        require(sensitive == null) { "Use the protected credential field for secrets, not extra header $sensitive" }
        val key = mangaId?.let { "series_$it" } ?: "global"
        val previous = store.getString(key, null)
        if (!store.edit().putString(key, json.encodeToString(value)).commit()) {
            store.edit().putString(key, previous).commit()
            error("Settings could not be saved to device storage")
        }
        if (mangaId == null) mutableSettings.value = value
        mutableRevision.value++
    }

    @Synchronized
    fun reset(mangaId: Long? = null) {
        if (mangaId == null) {
            update(TranslationSettings())
        } else {
            check(store.edit().remove("series_$mangaId").commit()) { "Settings could not be reset" }
            mutableRevision.value++
        }
    }

    private fun read(key: String): TranslationSettings? = store.getString(key, null)?.let {
        // Editable preferences adopt new defaults; immutable queued jobs use TranslationJobSnapshot instead.
        runCatching { json.decodeFromString<TranslationSettings>(it) }.getOrNull()
    }
}

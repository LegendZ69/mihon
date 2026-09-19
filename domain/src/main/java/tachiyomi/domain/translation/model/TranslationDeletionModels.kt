package tachiyomi.domain.translation.model

enum class TranslationDeletionScope(val label: String) {
    TRANSLATIONS("Translations, raw OCR, corrections, geometry recovery checkpoints and review / Undo history"),
    LOGS("Operation logs, batch history and sanitized API captures; geometry recovery checkpoints are retained"),
    ACCOUNTING("Application usage and cost / reservation history"),
    RENDERED_EXPORTS("App-owned rendered CBZ / PDF exports"),
}

data class TranslationStoredRecords(val records: Long, val payloadBytes: Long, val fingerprint: String)

/** Logical JSON bytes are distinct from SQLite file space, which may be reused instead of shrinking. */
data class TranslationDeletionSnapshot(
    val jobId: String,
    val job: TranslationJob?,
    val jobFingerprint: String,
    val groups: Map<TranslationDeletionScope, TranslationStoredRecords>,
)

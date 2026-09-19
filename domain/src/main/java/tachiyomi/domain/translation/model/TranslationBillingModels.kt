package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

/** References user-configured accounts only; no credential material or cloud provisioning settings. */
@Serializable
data class TranslationBillingConnection(
    val id: String,
    val label: String,
    val source: TranslationBillingSource,
    val credentialId: String = "",
    val googleProject: String = "",
    val googleTable: String = "",
    val googleLocation: String = "US",
    val projectIds: List<String> = emptyList(),
    val organization: String = "",
    val maximumBytesBilled: Long? = null,
    val refreshMinutes: Long? = null,
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid connection ID" }
        require(label.isNotBlank() && label.length <= 100) { "Enter a connection label" }
        require(refreshMinutes == null || refreshMinutes in 60..43_200) { "Refresh must be manual or at least hourly" }
        require(
            projectIds.size <= 100 && projectIds.all {
                it.matches(Regex("[A-Za-z0-9_-]{1,128}"))
            },
        ) { "Invalid project filter" }
        require(organization.isBlank() || organization.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            "Invalid organization"
        }
        if (source != TranslationBillingSource.IMPORTED_STATEMENT) {
            require(credentialId.matches(Regex("billing-[A-Za-z0-9_-]{1,72}"))) {
                "Import a separate billing credential"
            }
        }
        if (source == TranslationBillingSource.GOOGLE_BIGQUERY) {
            require(googleProject.matches(Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]|[0-9]+"))) {
                "Enter the query project ID"
            }
            require(googleTable.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_]+\\.[A-Za-z0-9_]+"))) {
                "Use project.dataset.table for the existing billing export"
            }
            require(googleLocation.matches(Regex("[A-Za-z0-9-]{2,40}"))) { "Enter the export's BigQuery location" }
            require(maximumBytesBilled != null && maximumBytesBilled > 0) {
                "Set a positive maximum billed-byte cap before querying"
            }
        }
    }
}

data class TranslationBillingRange(val startMillis: Long, val endMillis: Long) {
    fun validate() {
        require(startMillis >= 0 && endMillis > startMillis && endMillis - startMillis <= 31L * 86_400_000) {
            "Select a billing range of at most 31 days"
        }
    }
}

/** Organization totals are never inserted into the app's per-request usage table. */
@Serializable
data class TranslationAccountUsageBucket(
    val startMillis: Long,
    val endMillis: Long,
    val projectId: String? = null,
    val model: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val requests: Long? = null,
)

@Serializable
data class TranslationBillingReport(
    val connectionId: String,
    val snapshots: List<TranslationBillingSnapshot>,
    val usage: List<TranslationAccountUsageBucket> = emptyList(),
    val syncedAt: Long,
    val periodStart: Long,
    val periodEnd: Long,
    val bytesProcessed: Long? = null,
    val bytesBilled: Long? = null,
    val notes: String = "",
)

/** Serialized row bytes exclude SQLite page allocation and shared database overhead. */
data class TranslationBillingStorageStats(val records: Long? = null, val payloadBytes: Long? = null)

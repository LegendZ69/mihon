package mihon.feature.translation.accounting

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import tachiyomi.domain.translation.model.TranslationBillingConnection
import tachiyomi.domain.translation.model.TranslationBillingReport
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File

@Serializable
internal data class BillingLocalState(
    val connections: List<TranslationBillingConnection> = emptyList(),
    val reports: Map<String, TranslationBillingReport> = emptyMap(),
    val failures: Map<String, String> = emptyMap(),
    val paused: Set<String> = emptySet(),
    val pendingReports: Map<String, TranslationBillingReport> = emptyMap(),
    val pendingDeletes: Set<String> = emptySet(),
)

/** Stores account configuration and reported usage in noBackupFilesDir; credentials stay in the vault. */
internal class TranslationBillingStorage(private val file: File) {
    suspend fun commitReport(
        state: BillingLocalState,
        report: TranslationBillingReport,
        repository: TranslationRepository,
    ): BillingLocalState {
        val pending = state.copy(
            pendingReports = state.pendingReports + (report.connectionId to report),
            pendingDeletes = state.pendingDeletes - report.connectionId,
        )
        write(pending)
        report.snapshots.forEach { repository.saveBilling(it) }
        return pending.copy(
            reports = pending.reports + (report.connectionId to report),
            pendingReports =
            pending.pendingReports - report.connectionId,
        ).also(::write)
    }

    suspend fun recover(repository: TranslationRepository): BillingLocalState {
        var state = read()
        for (id in state.pendingDeletes) {
            repository.deleteBilling(id)
            state = state.copy(reports = state.reports - id, pendingDeletes = state.pendingDeletes - id)
            write(state)
        }
        for (report in state.pendingReports.values) state = commitReport(state, report, repository)
        return state
    }

    suspend fun deleteReport(
        state: BillingLocalState,
        connectionId: String,
        repository: TranslationRepository,
    ): BillingLocalState {
        val pending = state.copy(
            pendingDeletes = state.pendingDeletes + connectionId,
            pendingReports = state.pendingReports - connectionId,
        )
        write(pending)
        repository.deleteBilling(connectionId)
        return pending.copy(
            reports = pending.reports - connectionId,
            pendingDeletes =
            pending.pendingDeletes - connectionId,
        ).also(::write)
    }

    @Synchronized
    fun read(): BillingLocalState {
        if (!file.exists()) return BillingLocalState()
        check(file.length() <= 16L * 1024 * 1024) { "Billing cache exceeds its storage bound" }
        return BillingJson.decodeFromString(file.readText())
    }

    @Synchronized
    fun write(state: BillingLocalState) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create private billing storage" }
        val bytes = BillingJson.encodeToString(state).toByteArray()
        check(bytes.size <= 16 * 1024 * 1024) { "Billing cache exceeds its storage bound" }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.outputStream().use {
                it.write(bytes)
                it.fd.sync()
            }
            check(temporary.renameTo(file)) { "Cannot save billing settings" }
        } finally {
            temporary.delete()
        }
    }
}

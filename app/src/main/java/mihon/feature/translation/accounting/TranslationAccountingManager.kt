package mihon.feature.translation.accounting

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.feature.translation.provider.CredentialKind
import mihon.feature.translation.provider.GoogleServiceAccountTokens
import mihon.feature.translation.provider.TranslationCredentialVault
import okhttp3.CookieJar
import tachiyomi.domain.translation.model.TranslationBillingConnection
import tachiyomi.domain.translation.model.TranslationBillingRange
import tachiyomi.domain.translation.model.TranslationBillingReport
import tachiyomi.domain.translation.model.TranslationBillingSource
import tachiyomi.domain.translation.model.TranslationBillingStorageStats
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class TranslationAccountingManager(
    private val context: Context,
    private val repository: TranslationRepository,
    private val vault: TranslationCredentialVault,
    network: NetworkHelper,
) {
    private val storage = TranslationBillingStorage(File(context.noBackupFilesDir, "translation/accounting/state.json"))
    private val initialized = CompletableDeferred<Unit>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableLoading = MutableStateFlow(true)
    val loading = mutableLoading.asStateFlow()
    private val mutableStorageFailure = MutableStateFlow<String?>(null)
    val storageFailures = mutableStorageFailure.asStateFlow()
    val storageError: String? get() = mutableStorageFailure.value
    private var local = BillingLocalState()
    private val lock = Mutex()
    private val syncLock = Mutex()
    private val mutableConnections = MutableStateFlow(local.connections)
    val connections = mutableConnections.asStateFlow()
    private val mutableReports = MutableStateFlow(local.reports)
    val reports = mutableReports.asStateFlow()
    private val mutableFailures = MutableStateFlow(local.failures)
    val failures = mutableFailures.asStateFlow()
    private val mutableActive = MutableStateFlow<String?>(null)
    val active = mutableActive.asStateFlow()
    private val client = network.client.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
        cookieJar(CookieJar.NO_COOKIES)
        followRedirects(false)
        followSslRedirects(false)
        retryOnConnectionFailure(false)
        callTimeout(90, TimeUnit.SECONDS)
    }.build()
    private val tokens = GoogleServiceAccountTokens(client)
    private val connector = TranslationBillingConnector(client, authorize = { request, connection ->
        val credential = try {
            vault.load(connection.credentialId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw TranslationException(
                TranslationFailureKind.AUTHENTICATION,
                "The separate billing credential is unavailable",
            )
        }
        val bearer = if (connection.source == TranslationBillingSource.GOOGLE_BIGQUERY) {
            if (credential.kind != CredentialKind.SERVICE_ACCOUNT) {
                throw TranslationException(
                    TranslationFailureKind.AUTHENTICATION,
                    "Google billing requires a separate service-account JSON credential",
                )
            }
            tokens.accessToken(credential)
        } else {
            if (credential.kind != CredentialKind.API_KEY ||
                credential.apiKey.isNullOrBlank()
            ) {
                throw TranslationException(
                    TranslationFailureKind.AUTHENTICATION,
                    "OpenAI organization billing requires a separate administration API key",
                )
            }
            credential.apiKey
        }
        request.header("Authorization", "Bearer $bearer")
    })

    init {
        ioScope.launch {
            try {
                recoverLocalState()
            } finally {
                initialized.complete(Unit)
            }
        }
    }

    suspend fun recoverLocalState() = withContext(Dispatchers.IO) {
        mutableLoading.value = true
        lock.withLock {
            try {
                adopt(storage.recover(repository))
                mutableStorageFailure.value = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableStorageFailure.value = "Billing storage could not be recovered; " +
                    "existing files and fetched reports are preserved. " +
                    "Retry local recovery after resolving the storage error."
            } finally {
                mutableLoading.value = false
            }
        }
    }

    private suspend fun reconcilePending() {
        if (local.pendingReports.isNotEmpty() || local.pendingDeletes.isNotEmpty()) adopt(storage.recover(repository))
    }

    private suspend fun commitReport(state: BillingLocalState, report: TranslationBillingReport) {
        try {
            adopt(storage.commitReport(state, report, repository))
        } catch (error: Exception) {
            // Keep the journal in memory too, so later settings saves cannot overwrite pending recovery.
            withContext(NonCancellable) { adopt(storage.read()) }
            throw error
        }
    }

    suspend fun saveConnection(connection: TranslationBillingConnection) =
        withContext(Dispatchers.IO + NonCancellable) {
            initialized.await()
            connection.validate()
            lock.withLock {
                reconcilePending()
                persist(local.copy(connections = local.connections.filterNot { it.id == connection.id } + connection))
            }
            schedule(connection)
        }

    /** Navigation flush owns only a previously validated non-secret draft. */
    suspend fun flushConnection(connection: TranslationBillingConnection) {
        try {
            saveConnection(connection)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            lock.withLock {
                val message = "Billing settings could not be saved; the previous configuration is retained"
                mutableFailures.value = mutableFailures.value + (connection.id to message)
            }
        }
    }

    suspend fun importCredential(
        connection: TranslationBillingConnection,
        value: String,
    ): TranslationBillingConnection = withContext(Dispatchers.IO) {
        initialized.await()
        check(storageError == null) { storageError.orEmpty() }
        require(connection.id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(connection.source != TranslationBillingSource.IMPORTED_STATEMENT)
        val updated = connection.copy(credentialId = "billing-${connection.id}")
        // Validate the complete non-secret configuration before replacing its explicitly imported credential.
        updated.validate()
        vault.import(
            updated.credentialId,
            value,
            if (updated.source ==
                TranslationBillingSource.GOOGLE_BIGQUERY
            ) {
                TranslationProviderKind.VERTEX_SERVICE_ACCOUNT
            } else {
                TranslationProviderKind.OPENAI
            },
            "Billing: ${updated.label}",
        )
        saveConnection(updated)
        lock.withLock {
            persist(local.copy(paused = local.paused - updated.id, failures = local.failures - updated.id))
        }
        updated
    }

    suspend fun removeConnection(id: String) = withContext(Dispatchers.IO) {
        initialized.await()
        val removed = lock.withLock {
            reconcilePending()
            val value = local.connections.firstOrNull { it.id == id } ?: return@withContext
            check(mutableActive.value != id) { "Wait for or cancel the active synchronization first" }
            persist(local.copy(connections = local.connections.filterNot { it.id == id }, paused = local.paused - id))
            value
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        if (removed.credentialId.startsWith("billing-") &&
            local.connections.none { it.credentialId == removed.credentialId }
        ) {
            vault.remove(removed.credentialId)
        }
        // Reported costs remain available as accounting history until explicitly deleted.
    }

    suspend fun importStatement(document: String): String = withContext(Dispatchers.IO) {
        initialized.await()
        val imported = TranslationBillingStatementImporter.parse(document, System.currentTimeMillis())
        syncLock.withLock {
            lock.withLock {
                reconcilePending()
                check(storageError == null) { storageError.orEmpty() }
                val id = imported.connection.id
                val old = local.reports[id]
                if (old != null) {
                    val matching = old.copy(
                        syncedAt = imported.report.syncedAt,
                        snapshots = old.snapshots.map { it.copy(syncedAt = imported.report.syncedAt) },
                    ) ==
                        imported.report
                    check(matching) {
                        "This statement ID already has different accounting. Existing history is preserved; " +
                            "inspect it before explicitly deleting or replacing it."
                    }
                    return@withContext "Identical statement already imported; no duplicate accounting was added"
                }
                val operation =
                    TranslationOperation(
                        UUID.randomUUID().toString(),
                        "billing-$id",
                        TranslationStage.ACCOUNTING_IMPORT,
                        state = TranslationOperationState.ACTIVE,
                        startedAt = System.currentTimeMillis(),
                        total = 1,
                        message = "Import a user-supplied Groq statement; no network request",
                    )
                repository.saveOperation(operation)
                try {
                    commitReport(
                        local.copy(
                            connections =
                            local.connections.filterNot { it.id == id } + imported.connection,
                        ),
                        imported.report,
                    )
                    repository.saveOperation(
                        operation.copy(
                            state = TranslationOperationState.COMPLETED,
                            completed = 1,
                            updatedAt = System.currentTimeMillis(),
                            endedAt = System.currentTimeMillis(),
                        ),
                    )
                    repository.addEvent(
                        TranslationEvent(
                            UUID.randomUUID().toString(),
                            "billing-$id",
                            operationId = operation.id,
                            stage = "ACCOUNTING_IMPORT",
                            operationState = TranslationOperationState.COMPLETED,
                            message = "Imported statement is separate from provider-reported costs " +
                                "and application estimates",
                        ),
                    )
                    "Statement imported; no provider request was sent"
                } catch (error: Exception) {
                    withContext(NonCancellable) {
                        repository.saveOperation(
                            operation.copy(
                                state = TranslationOperationState.FAILED,
                                updatedAt = System.currentTimeMillis(),
                                endedAt = System.currentTimeMillis(),
                                message = "Statement could not be saved",
                            ),
                        )
                    }
                    throw error
                }
            }
        }
    }

    suspend fun deleteHistory(
        connectionId: String,
        expectedReport: TranslationBillingReport,
        expectedStats: TranslationBillingStorageStats,
    ) = withContext(Dispatchers.IO + NonCancellable) {
        initialized.await()
        syncLock.withLock {
            lock.withLock {
                reconcilePending()
                check(storageError == null) { storageError.orEmpty() }
                check(
                    local.reports[connectionId] == expectedReport &&
                        repository.billingStats(connectionId) == expectedStats,
                ) {
                    "Accounting changed after this preview; inspect the updated history before deletion"
                }
                try {
                    adopt(storage.deleteReport(local, connectionId, repository))
                } catch (
                    error: Exception,
                ) {
                    adopt(storage.read())
                    throw error
                }
            }
        }
    }

    suspend fun storageStats(connectionId: String) = withContext(Dispatchers.IO) {
        initialized.await()
        repository.billingStats(connectionId)
    }

    suspend fun synchronize(
        id: String,
        range: TranslationBillingRange = recentRange(),
        scheduled: Boolean = false,
    ): TranslationBillingReport? =
        withContext(Dispatchers.IO) {
            initialized.await()
            syncLock.withLock {
                val connection = lock.withLock {
                    reconcilePending()
                    check(storageError == null) { storageError.orEmpty() }
                    if (scheduled && id in local.paused) return@withContext null
                    local.connections.firstOrNull { it.id == id } ?: return@withContext null
                }
                mutableActive.value = id
                val operation =
                    TranslationOperation(
                        UUID.randomUUID().toString(),
                        "billing-$id",
                        TranslationStage.BILLING_SYNC,
                        state = TranslationOperationState.ACTIVE,
                        startedAt = System.currentTimeMillis(),
                        total = 1,
                        message = "Read reported ${connection.source.name.lowercase()} accounting",
                    )
                try {
                    repository.saveOperation(operation)
                    val report = connector.fetch(connection, range)
                    lock.withLock {
                        check(
                            local.connections.firstOrNull {
                                it.id == id
                            } == connection,
                        ) { "Billing configuration changed; sync result was not applied" }
                        commitReport(local.copy(failures = local.failures - id, paused = local.paused - id), report)
                    }
                    repository.saveOperation(
                        operation.copy(
                            state = TranslationOperationState.COMPLETED,
                            completed = 1,
                            endedAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            message = report.notes,
                        ),
                    )
                    repository.addEvent(
                        TranslationEvent(
                            UUID.randomUUID().toString(),
                            "billing-$id",
                            operationId = operation.id,
                            stage = "BILLING_SYNC",
                            operationState = TranslationOperationState.COMPLETED,
                            message = "Reported accounting synchronized; account totals are separate " +
                                "from app estimates",
                            details = mapOf(
                                "source" to connection.source.name,
                                "scope" to report.snapshots.map { it.scope }.distinct().joinToString(),
                                "periodStart" to range.startMillis.toString(),
                                "periodEnd" to range.endMillis.toString(),
                                "costRows" to report.snapshots.size.toString(),
                                "usageRows" to report.usage.size.toString(),
                            ),
                        ),
                    )
                    report
                } catch (error: Exception) {
                    val cancelled = error is CancellationException
                    val auth = error is TranslationException && error.kind == TranslationFailureKind.AUTHENTICATION
                    val message = when {
                        cancelled -> "Synchronization interrupted; earlier reported accounting is preserved"
                        auth ->
                            "Billing authentication failed; scheduled sync paused. " +
                                "Check the separate billing credential and access."
                        error is IllegalStateException || error is IllegalArgumentException ->
                            error.message
                                ?: "Billing configuration is invalid"
                        else ->
                            "Billing synchronization failed; earlier reports are preserved. " +
                                "Check connection and account access."
                    }
                    withContext(NonCancellable) {
                        lock.withLock {
                            persist(
                                local.copy(
                                    failures = local.failures + (id to message),
                                    paused = if (auth) {
                                        local.paused +
                                            id
                                    } else {
                                        local.paused
                                    },
                                ),
                            )
                        }
                        repository.saveOperation(
                            operation.copy(
                                state = if (cancelled) {
                                    TranslationOperationState.INTERRUPTED
                                } else {
                                    TranslationOperationState.FAILED
                                },
                                endedAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                                message = message,
                            ),
                        )
                        repository.addEvent(
                            TranslationEvent(
                                UUID.randomUUID().toString(),
                                "billing-$id",
                                operationId = operation.id,
                                level = if (cancelled) "WARN" else "ERROR",
                                stage = "BILLING_SYNC",
                                operationState = if (cancelled) {
                                    TranslationOperationState.INTERRUPTED
                                } else {
                                    TranslationOperationState.FAILED
                                },
                                message = message,
                            ),
                        )
                    }
                    if (cancelled) throw error
                    throw IllegalStateException(message)
                } finally {
                    mutableActive.value = null
                }
            }
        }

    private fun persist(value: BillingLocalState) {
        check(storageError == null) { storageError.orEmpty() }
        storage.write(value)
        adopt(value)
    }

    private fun adopt(value: BillingLocalState) {
        local = value
        mutableConnections.value = value.connections
        mutableReports.value = value.reports
        mutableFailures.value = value.failures
    }

    private fun schedule(connection: TranslationBillingConnection) {
        val work = WorkManager.getInstance(context)
        val minutes = connection.refreshMinutes
        if (minutes == null || connection.source == TranslationBillingSource.IMPORTED_STATEMENT) {
            work.cancelUniqueWork(workName(connection.id))
        } else {
            work.enqueueUniquePeriodicWork(
                workName(connection.id),
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<TranslationBillingWorker>(minutes, TimeUnit.MINUTES)
                    .setInitialDelay(minutes, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf("connectionId" to connection.id)).build(),
            )
        }
    }

    companion object {
        private fun workName(id: String) = "translator_billing_$id"
        fun recentRange(): TranslationBillingRange {
            val today = LocalDate.now(ZoneOffset.UTC)
            return TranslationBillingRange(
                today.minusDays(29).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                System.currentTimeMillis(),
            )
        }
    }
}

class TranslationBillingWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("connectionId") ?: return Result.failure()
        return try {
            applicationContext.appGraph.translationAccountingManager.synchronize(id, scheduled = true)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The configured interval owns scheduling; do not multiply billable queries with transport retries.
            Result.success()
        }
    }
}

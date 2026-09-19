package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.release.model.Release
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

@Inject
@SingleIn(AppScope::class)
class AppUpdateManager(private val context: Context, private val network: NetworkHelper) {
    private val store = AppUpdateStore(File(context.filesDir, "app-updates"))
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val admission = Mutex()
    val state = store.state

    init {
        scope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).collect { jobs ->
                val current = state.value ?: return@collect
                val job = jobs.firstOrNull { it.id.toString() == current.id } ?: return@collect
                if (job.state.isFinished && current.stage in activeStages) {
                    store.update(current.id) {
                        if (it.stage in activeStages) {
                            it.copy(
                                stage = AppUpdateStage.FAILED,
                                error = "Download stopped. Tap Retry to download again.",
                            )
                        } else {
                            it
                        }
                    }
                    store.partial(current.id).delete()
                } else if (job.state == WorkInfo.State.ENQUEUED && current.stage in activeStages) {
                    store.update(current.id, onlyWhileActive = true) { it.copy(stage = AppUpdateStage.QUEUED) }
                }
            }
        }
    }

    suspend fun download(release: Release) = withContext(Dispatchers.IO) {
        admission.withLock {
            require(BuildConfig.UPDATER_ENABLED) { "Updates are disabled for this build" }
            if (BuildConfig.TRANSLATOR_RELEASE_NUMBER > 0) {
                requireNotNull(release.apk) { "The translator release is missing verification information" }
            }
            val retained = state.value
            if (retained?.release?.isSameUpdate(release) == true && retained.stage == AppUpdateStage.DOWNLOADED &&
                store.apk(retained.id).isFile
            ) {
                return@withLock
            }
            val active = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().any { !it.state.isFinished }
            if (active) {
                require(state.value?.release?.isSameUpdate(release) == true) {
                    "Another update is downloading. Cancel it before switching."
                }
                return@withLock
            }
            val id = UUID.randomUUID()
            check(store.save(AppUpdateDownload(id.toString(), release, AppUpdateStage.QUEUED))) {
                "Could not save update state. Check free storage and try again."
            }
            store.removeOtherFiles(id.toString())
            val request = OneTimeWorkRequestBuilder<AppUpdateWorker>()
                .setId(id)
                .setInputData(workDataOf(RECORD_ID to id.toString()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            try {
                workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request).result.await()
            } catch (error: Exception) {
                store.update(id.toString()) {
                    it.copy(stage = AppUpdateStage.FAILED, error = "Could not schedule the update download. Tap Retry.")
                }
                throw error
            }
        }
    }

    suspend fun cancelDownload() = withContext(Dispatchers.IO) {
        admission.withLock {
            val current = state.value ?: return@withLock
            if (current.stage !in activeStages) return@withLock
            workManager.cancelWorkById(UUID.fromString(current.id)).result.await()
            store.update(current.id) {
                it.copy(stage = AppUpdateStage.FAILED, error = "Download cancelled. Tap Retry to download again.")
            }
            // The worker owns its partial bytes until cancellation has unwound.
        }
    }

    suspend fun reconcileInstalledVersion() = withContext(Dispatchers.IO) {
        admission.withLock {
            val current = state.value ?: return@withLock
            val expectedVersion = current.release.apk?.versionCode
            val installedVersion = PackageInfoCompat.getLongVersionCode(installedPackage())
            if (expectedVersion != null && installedVersion >= expectedVersion) {
                workManager.cancelWorkById(UUID.fromString(current.id)).result.await()
                check(store.save(null)) { "Could not clear completed update state. Check free storage and try again." }
                store.removeFiles(current.id)
                AppUpdateNotifications(context).dismiss()
            } else if (current.stage in activeStages) {
                val work = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first()
                    .firstOrNull { it.id.toString() == current.id }
                if (work == null || work.state.isFinished) {
                    store.update(current.id) {
                        it.copy(
                            stage = AppUpdateStage.FAILED,
                            error = "Download interrupted. Tap Retry to download again.",
                        )
                    }
                    store.partial(current.id).delete()
                }
            } else if (current.stage == AppUpdateStage.DOWNLOADED && !store.apk(current.id).isFile) {
                store.update(current.id) {
                    it.copy(
                        stage = AppUpdateStage.FAILED,
                        error = "The update file is missing. Tap Retry to download again.",
                    )
                }
            }
        }
    }

    /** Recheck bytes and package identity immediately before granting an installer access. */
    suspend fun verifiedApk(release: Release): File = withContext(Dispatchers.IO) {
        val current = state.value
        require(
            current != null && current.release.isSameUpdate(release) && current.stage == AppUpdateStage.DOWNLOADED,
        ) {
            "Download the update first"
        }
        val file = store.apk(current.id)
        try {
            release.apk?.let { expected ->
                require(file.length() == expected.sizeBytes) { "Update download is incomplete" }
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        digest.update(buffer, 0, count)
                    }
                }
                require(digest.digest().hex().equals(expected.sha256, ignoreCase = true)) {
                    "Update checksum does not match the release"
                }
            }
            verifyArchive(file, release)
            file
        } catch (error: Exception) {
            store.update(current.id) { it.copy(stage = AppUpdateStage.FAILED, error = error.message) }
            store.removeFiles(current.id)
            throw error
        }
    }

    internal suspend fun runDownload(id: String, progress: suspend (AppUpdateDownload) -> Unit) =
        withContext(Dispatchers.IO) {
            val current = state.value?.takeIf { it.id == id } ?: error("Update download was replaced")
            try {
                val uri = java.net.URI(current.release.downloadLink)
                require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null) {
                    "The update download address is invalid"
                }
                if (!store.update(id, onlyWhileActive = true) {
                        it.copy(stage = AppUpdateStage.DOWNLOADING, progress = 0, error = null)
                    }
                ) {
                    throw CancellationException("Update download was cancelled")
                }
                // WorkManager may restart the same operation after process death: discard incomplete bytes.
                store.partial(id).delete()
                store.apk(id).delete()
                val client = network.client.newBuilder().followSslRedirects(false).build()
                val call = client.newCall(GET(current.release.downloadLink))
                coroutineScope {
                    // Keep cancellation bound after headers while the response body is streaming.
                    val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            call.cancel()
                        }
                    }
                    try {
                        call.awaitSuccess().use { response ->
                            val expected = current.release.apk
                            val contentLength = response.body.contentLength()
                            require(expected == null || contentLength < 0 || contentLength == expected.sizeBytes) {
                                "Update response size does not match the release"
                            }
                            response.body.byteStream().use { input ->
                                copyVerifiedApk(
                                    input,
                                    store.partial(id),
                                    store.apk(id),
                                    expected,
                                    verifyIdentity = { apk -> verifyArchive(apk, current.release) },
                                ) { percent ->
                                    val stage = if (percent ==
                                        100
                                    ) {
                                        AppUpdateStage.VERIFYING
                                    } else {
                                        AppUpdateStage.DOWNLOADING
                                    }
                                    if (!store.update(id, persist = false, onlyWhileActive = true) {
                                            it.copy(progress = percent, stage = stage)
                                        }
                                    ) {
                                        throw CancellationException("Update download was cancelled")
                                    }
                                    state.value?.takeIf {
                                        it.id == id && it.stage in activeStages
                                    }?.let { progress(it) }
                                }
                            }
                        }
                    } finally {
                        withContext(NonCancellable) { cancellation.cancelAndJoin() }
                    }
                }
                if (!store.update(id, onlyWhileActive = true) {
                        it.copy(stage = AppUpdateStage.DOWNLOADED, progress = 100, error = null)
                    }
                ) {
                    store.removeFiles(id)
                    throw CancellationException("Update download was cancelled")
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable) { store.partial(id).delete() }
                throw error
            } catch (error: Exception) {
                store.partial(id).delete()
                currentCoroutineContext().ensureActive()
                store.update(id, onlyWhileActive = true) {
                    it.copy(stage = AppUpdateStage.FAILED, error = error.message ?: "Download failed. Tap Retry.")
                }
                throw error
            }
        }

    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo = context.packageManager.getPackageInfo(
        context.packageName,
        signingFlags,
    )

    @Suppress("DEPRECATION")
    private val signingFlags: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo, abis: Set<String> = emptySet()): UpdateApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return UpdateApkIdentity(
            info.packageName,
            PackageInfoCompat.getLongVersionCode(info),
            signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet(),
            abis,
        )
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, release: Release) {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags)
            ?: error("The downloaded file is not a valid APK")
        require(info.splitNames.isNullOrEmpty()) { "Split APK updates are not supported" }
        val abis = ZipFile(file).use { zip ->
            zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.split('/')[1] }.toSet()
        }
        require(abis.isEmpty() || Build.SUPPORTED_ABIS.any { it in abis }) { "Update is incompatible with this device" }
        verifyUpdateIdentity(identity(info, abis), identity(installedPackage()), release.apk)
    }

    companion object {
        internal const val WORK_NAME = "app_update_download"
        internal const val RECORD_ID = "update_record_id"
        const val OPEN_DOWNLOAD = "app.mihon.OPEN_APP_UPDATE"
        val activeStages = setOf(AppUpdateStage.QUEUED, AppUpdateStage.DOWNLOADING, AppUpdateStage.VERIFYING)
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

package eu.kanade.tachiyomi.data.updater

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseApk
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppUpdateNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun durableRecordRetainsVerifiedDownloadAndCancellationAcrossRecreation() {
        val root = File(context.filesDir, "update-store-test-${UUID.randomUUID()}")
        try {
            val store = AppUpdateStore(root)
            val id = UUID.randomUUID().toString()
            val release = Release(
                "translator-v20",
                "Partially tested",
                "https://github.com/LegendZ69/mihon/releases/tag/translator-v20",
                "https://github.com/LegendZ69/mihon/releases/download/translator-v20/update.apk",
                ReleaseApk(100020, "app.mihon", "arm64-v8a", 3, "a".repeat(64), "b".repeat(64)),
                true,
            )
            assertTrue(store.save(AppUpdateDownload(id, release, AppUpdateStage.QUEUED)))
            assertEquals(store.state.value, AppUpdateStore(root).state.value)
            assertTrue(store.update(id) { it.copy(stage = AppUpdateStage.FAILED, error = "Cancelled") })
            val restored = AppUpdateStore(root)
            assertFalse(restored.update(id, onlyWhileActive = true) { it.copy(stage = AppUpdateStage.DOWNLOADING) })
            assertEquals(AppUpdateStage.FAILED, restored.state.value!!.stage)
            assertTrue(restored.save(restored.state.value!!.copy(stage = AppUpdateStage.DOWNLOADED, progress = 100)))
            assertEquals(AppUpdateStage.DOWNLOADED, AppUpdateStore(root).state.value!!.stage)
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    @Test
    fun fileProviderExposesOwnedApkButNotUpdaterMetadata() {
        val id = UUID.randomUUID()
        val apk = File(context.filesDir, "app-updates/apks/native-test-$id.apk")
        val metadata = File(context.filesDir, "app-updates/native-test-$id.json")
        val partial = File(context.filesDir, "app-updates/staging/native-test-$id.part")
        try {
            apk.parentFile!!.mkdirs()
            apk.writeText("owned test bytes")
            metadata.writeText("private metadata")
            partial.parentFile!!.mkdirs()
            partial.writeText("unverified partial bytes")
            val authority = "${context.packageName}.provider"
            val uri = FileProvider.getUriForFile(context, authority, apk)
            assertEquals("content", uri.scheme)
            assertEquals(
                "owned test bytes",
                context.contentResolver.openInputStream(uri)!!.bufferedReader().use {
                    it.readText()
                },
            )
            for (privateFile in listOf(metadata, partial)) {
                var rejected = false
                try {
                    FileProvider.getUriForFile(context, authority, privateFile)
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                assertTrue("Updater metadata and partial bytes must stay outside FileProvider roots", rejected)
            }
        } finally {
            assertTrue(apk.delete())
            assertTrue(metadata.delete())
            assertTrue(partial.delete())
        }
    }
}

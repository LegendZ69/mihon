package eu.kanade.tachiyomi.data.updater

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseApk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class VerifiedApkDownloadTest {
    @Test
    fun `edited notes preserve artifact identity but different bytes do not`() {
        val release = Release("translator-v20", "old notes", "release", "apk", expected(), true)
        assertTrue(release.isSameUpdate(release.copy(info = "new notes")))
        assertFalse(release.isSameUpdate(release.copy(apk = expected().copy(sha256 = "c".repeat(64)))))
        assertFalse(release.isSameUpdate(release.copy(downloadLink = "different")))
    }

    @TempDir lateinit var directory: File
    private val bytes = ByteArray(32_000) { (it % 251).toByte() }
    private val partial get() = File(directory, "update.part")
    private val complete get() = File(directory, "update.apk")
    private fun expected() = ReleaseApk(
        100020,
        "app.mihon",
        "arm64-v8a",
        bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        "a".repeat(64),
    )

    @Test
    fun `publishes only after byte and archive verification`() = runBlocking {
        val progresses = mutableListOf<Int>()
        copyVerifiedApk(ByteArrayInputStream(bytes), partial, complete, expected(), {
            assertTrue(partial.isFile)
            assertFalse(complete.exists())
            assertArrayEquals(bytes, it.readBytes())
        }, { progresses += it })
        assertFalse(partial.exists())
        assertArrayEquals(bytes, complete.readBytes())
        assertEquals(100, progresses.last())
    }

    @Test
    fun `keeps unverified bytes outside the shared apk directory`() = runBlocking {
        val staged = File(directory, "staging/update.part")
        val shared = File(directory, "apks/update.apk")
        copyVerifiedApk(ByteArrayInputStream(bytes), staged, shared, expected(), {
            assertTrue(staged.isFile)
            assertTrue(shared.parentFile!!.listFiles()!!.isEmpty())
        }, {})
        assertFalse(staged.exists())
        assertArrayEquals(bytes, shared.readBytes())
    }

    @Test
    fun `truncated oversized and corrupted bodies never publish`() {
        listOf(bytes.copyOf(bytes.size - 1), bytes + 0.toByte(), bytes.copyOf().apply { this[0] = 7 }).forEach { body ->
            assertThrows(IOException::class.java) {
                runBlocking {
                    copyVerifiedApk(ByteArrayInputStream(body), partial, complete, expected(), {
                        error("Must not inspect corrupt bytes")
                    }, {})
                }
            }
            assertFalse(partial.exists())
            assertFalse(complete.exists())
        }
    }

    @Test
    fun `cancellation and identity failure remove partial bytes`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                copyVerifiedApk(ByteArrayInputStream(bytes), partial, complete, expected(), {
                }, { throw CancellationException("Cancelled") })
            }
        }
        assertFalse(partial.exists())
        assertFalse(complete.exists())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                copyVerifiedApk(ByteArrayInputStream(bytes), partial, complete, expected(), {
                    throw IllegalArgumentException("Wrong certificate")
                }, {})
            }
        }
        assertFalse(partial.exists())
        assertFalse(complete.exists())
    }

    @Test
    fun `restarts stale partial bytes from zero`() = runBlocking {
        partial.writeText("interrupted previous response")
        copyVerifiedApk(ByteArrayInputStream(bytes), partial, complete, expected(), {}, {})
        assertArrayEquals(bytes, complete.readBytes())
    }

    @Test
    fun `existing final file is preserved on duplicate transfer`() {
        complete.writeText("previous verified download")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { copyVerifiedApk(ByteArrayInputStream(bytes), partial, complete, expected(), {}, {}) }
        }
        assertEquals("previous verified download", complete.readText())
    }

    @Test
    fun `rejects wrong package version certificate and architecture`() {
        val expected = expected()
        val installed = UpdateApkIdentity("app.mihon", 100015, setOf(expected.certificateSha256), setOf("arm64-v8a"))
        val valid = installed.copy(versionCode = expected.versionCode)
        verifyUpdateIdentity(valid, installed, expected)
        listOf(
            valid.copy(packageName = "app.mihon.benchmark"),
            valid.copy(versionCode = 100015),
            valid.copy(versionCode = 100021),
            valid.copy(certificates = setOf("b".repeat(64))),
            valid.copy(certificates = valid.certificates + "b".repeat(64)),
            valid.copy(abis = setOf("x86_64")),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { verifyUpdateIdentity(invalid, installed, expected) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyUpdateIdentity(valid, installed, expected.copy(certificateSha256 = "b".repeat(64)))
        }
    }
}

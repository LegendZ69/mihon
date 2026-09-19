package mihon.feature.translation.acceptance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReviewAcceptanceDirectoryTest {
    @Test
    fun concurrentCreationAfterMissingCheckRemainsAValidDirectory() = withRoot { root ->
        val directory = object : File(root, "shared") {
            override fun mkdirs(): Boolean {
                // Another filesystem caller wins after the helper observed a missing directory.
                check(File(path).mkdirs())
                val ownCreation = super.mkdirs()
                assertFalse("The original caller loses mkdirs despite a valid directory", ownCreation)
                return ownCreation
            }
        }
        assertSame(directory, ensureReviewAcceptanceDirectory(directory))
        assertTrue(directory.isDirectory)
    }

    @Test
    fun newAndExistingDirectoriesRemainUsable() = withRoot { root ->
        val directory = File(root, "nested/files")
        assertSame(directory, ensureReviewAcceptanceDirectory(directory))
        File(directory, "retained.txt").writeText("saved evidence")
        assertSame(directory, ensureReviewAcceptanceDirectory(directory))
        assertEquals("saved evidence", File(directory, "retained.txt").readText())
    }

    @Test
    fun fileCollisionIsRejectedWithoutOverwritingTheFile() = withRoot { root ->
        val directory = File(root, "collision").apply { writeText("preserve me") }
        assertThrows(IllegalStateException::class.java) { ensureReviewAcceptanceDirectory(directory) }
        assertEquals("preserve me", directory.readText())
    }

    @Test
    fun failedCreationWithoutADirectoryIsRejected() = withRoot { root ->
        val parent = File(root, "file-parent").apply { writeText("preserve parent") }
        assertThrows(IllegalStateException::class.java) {
            ensureReviewAcceptanceDirectory(File(parent, "files"))
        }
        assertEquals("preserve parent", parent.readText())
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("mihon-review-directory-").toFile()
        try {
            block(root)
        } finally {
            check(root.deleteRecursively())
        }
    }
}

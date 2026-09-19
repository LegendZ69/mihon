package tachiyomi.data.translation

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.TranslationArchiveChapter
import tachiyomi.domain.translation.model.TranslationArchiveConflictPolicy
import tachiyomi.domain.translation.model.TranslationArchiveLink
import tachiyomi.domain.translation.model.TranslationArchivePreview
import tachiyomi.domain.translation.model.TranslationArchivePreviewJob
import tachiyomi.domain.translation.model.TranslationArchiveProvenance
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationRestoreReport
import tachiyomi.domain.translation.service.QualityReviewValidation
import tachiyomi.domain.translation.service.TranslationArchiveRepository
import tachiyomi.domain.translation.service.TranslationJobSnapshot
import java.security.MessageDigest

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SqlDelightTranslationArchiveRepository(private val database: Database) : TranslationArchiveRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val queries get() = database.translationQueries
    private val archiveQueries get() = database.translationArchiveQueries

    private suspend fun job(id: String) = archiveQueries.archiveJob(id).awaitAsList().firstOrNull()?.let {
        TranslationJobSnapshot.decode(json, it)
    }
    private suspend fun images(
        id: String,
    ) = queries.images(id).awaitAsList().map { json.decodeFromString<TranslationImage>(it) }
    private suspend fun results(
        id: String,
    ) = queries.results(id).awaitAsList().map { json.decodeFromString<TranslationPageResult>(it) }
    private suspend fun reviews(
        id: String,
    ) = queries.reviews(id).awaitAsList().map { json.decodeFromString<QualityReviewCheckpoint>(it) }

    override suspend fun snapshot(jobId: String): TranslationArchiveChapter? = database.transactionWithResult {
        val job = job(jobId) ?: return@transactionWithResult null
        TranslationArchiveChapter(
            job,
            images(jobId),
            results(jobId),
            reviews(jobId),
            job.archiveMetadata?.effectiveSettings,
            job.replacesJobId ?: job.archiveMetadata?.sourceReplacesJobId,
        )
    }

    override suspend fun preview(): TranslationArchivePreview = database.transactionWithResult {
        val jobs = linkedMapOf<String, TranslationArchivePreviewJob>()
        var offset = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = archiveQueries.archivePreviewJobs(500, offset).awaitAsList()
            for (job in page) {
                require(jobs.size < 100_000) { "Too many translation records for a safe restore preview" }
                jobs[job.id] = previewJob(job.id, job.manga_id, job.chapter_id)
            }
            if (page.size < 500) break
            offset += page.size
        }
        TranslationArchivePreview(jobs)
    }

    private suspend fun previewJob(id: String, mangaId: Long, chapterId: Long): TranslationArchivePreviewJob {
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        append(mangaId.toString())
        append(chapterId.toString())
        suspend fun rows(label: String, read: suspend (Long) -> List<String>) {
            append(label)
            var offset = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = read(offset)
                page.forEach(::append)
                if (page.size < 500) break
                offset += page.size
            }
        }
        rows("images") {
            archiveQueries.archivePreviewImageIdentities(id, 500, it).awaitAsList()
                .map { row -> requireNotNull(row.json_object) }
        }
        rows("results") {
            archiveQueries.archivePreviewResultRevisions(id, 500, it).awaitAsList()
                .map { row -> requireNotNull(row.json_object) }
        }
        rows("reviews") {
            archiveQueries.archivePreviewReviewRevisions(id, 500, it).awaitAsList()
                .map { row -> requireNotNull(row.json_object) }
        }
        return TranslationArchivePreviewJob(mangaId, chapterId, digest.digest().joinToString("") { "%02x".format(it) })
    }

    override suspend fun restore(
        chapters: Sequence<TranslationArchiveChapter>,
        policy: TranslationArchiveConflictPolicy,
        links: Map<String, TranslationArchiveLink>,
        preview: TranslationArchivePreview?,
    ): TranslationRestoreReport = database.transactionWithResult {
        val verifiedPreviewJobs = mutableSetOf<String>()
        suspend fun verifyPreview(id: String) {
            if (preview == null || !verifiedPreviewJobs.add(id)) return
            val current = job(id)
            val actual = current?.let { previewJob(it.id, it.mangaId, it.chapterId) }
            require(preview.jobs[id] == actual) {
                "Saved translations changed since the import preview. Reopen the file and review conflicts again."
            }
        }
        var imported = 0
        var identical = 0
        var conflicts = 0
        var linked = 0
        var unlinked = 0
        val ids = mutableListOf<String>()
        val sourceIds = mutableSetOf<String>()
        chapters.forEach { chapter ->
            currentCoroutineContext().ensureActive()
            validate(chapter)
            require(sourceIds.add(chapter.job.id)) { "Duplicate archived job" }
            val link = links[chapter.job.id]
            verifyPreview(chapter.job.id)
            var targetId = chapter.job.id
            var current = job(targetId)
            var currentImages = current?.let { images(targetId) }.orEmpty()
            if (link != null) {
                require(link.mangaId >= 0 && link.chapterId >= 0) { "Select a library or local chapter" }
                val candidates = archiveQueries.archiveChapterJobs(link.chapterId).awaitAsList()
                    .map { TranslationJobSnapshot.decode(json, it) }.filter { it.mangaId == link.mangaId }
                if (preview != null) {
                    val expectedIds = preview.jobs.filterValues {
                        it.mangaId == link.mangaId && it.chapterId == link.chapterId
                    }.keys
                    require(expectedIds == candidates.map { it.id }.toSet()) {
                        "Selected chapter translations changed since import preview. Reopen the file."
                    }
                    candidates.forEach { verifyPreview(it.id) }
                }
                var match: TranslationJob? = null
                for (candidate in candidates) {
                    val originals = images(candidate.id)
                    if (chapter.images.all { source ->
                            originals.any { it.index == source.index && sameImage(source, it) }
                        } &&
                        (
                            link.originals.isEmpty() ||
                                (
                                    originals.size == link.originals.size &&
                                        originals.all { original ->
                                            link.originals.any {
                                                it.index == original.index &&
                                                    sameImage(it, original)
                                            }
                                        }
                                    )
                            )
                    ) {
                        match = candidate
                        break
                    }
                }
                targetId =
                    link.targetJobId ?: match?.id
                        ?: stableId("archive-linked", "${chapter.job.id}:${link.mangaId}:${link.chapterId}")
                current = if (match?.id == targetId) match else job(targetId)
                require(
                    current?.let {
                        it.mangaId == link.mangaId && it.chapterId == link.chapterId
                    } != false,
                ) { "Relink target identity changed" }
                currentImages = current?.let { images(targetId) }.orEmpty()
                if (link.originals.isNotEmpty()) {
                    require(
                        link.originals.map { it.id }.distinct().size == link.originals.size &&
                            link.originals.map { it.index }.distinct().size == link.originals.size,
                    ) {
                        "Verified originals must have unique identities and page order"
                    }
                    if (current != null) {
                        fun identities(images: List<TranslationImage>) = images.sortedBy { it.index }.map {
                            it.index to Triple(it.contentHash, it.width, it.height)
                        }
                        val sameOriginals = identities(currentImages) == identities(link.originals)
                        val unacquired = current.imageCount == 0 && currentImages.isEmpty() &&
                            results(targetId).isEmpty()
                        require(sameOriginals || unacquired) {
                            "Chapter originals changed since import preview. Import this source revision " +
                                "as a separate saved chapter to preserve existing translations."
                        }
                    }
                    currentImages = link.originals.map { verified ->
                        currentImages.singleOrNull { it.index == verified.index && sameImage(it, verified) }
                            ?.copy(filePath = verified.filePath) ?: verified
                    }
                }
                require(
                    chapter.images.isNotEmpty() && chapter.images.all { source ->
                        currentImages.count {
                            it.index == source.index && sameImage(source, it) && it.filePath.isNotBlank()
                        } ==
                            1
                    },
                ) { "Selected chapter originals do not match the archived content hashes, dimensions and page order" }
            } else if (current != null &&
                chapter.images.none { source -> currentImages.any { sameImage(source, it) } }
            ) {
                targetId = stableId("archive", chapter.job.id + chapter.images.joinToString { it.contentHash })
                current = job(targetId)
                currentImages = current?.let { images(targetId) }.orEmpty()
            }
            verifyPreview(targetId)
            require(
                current?.state !in
                    setOf(
                        TranslationJobState.QUEUED,
                        TranslationJobState.ACQUIRING,
                        TranslationJobState.OCR,
                        TranslationJobState.TRANSLATING,
                        TranslationJobState.WAITING,
                    ),
            ) {
                "Pause selected active work before restoring translations"
            }
            val existingResults = current?.let { results(targetId) }.orEmpty().associateBy { it.imageId }
            link?.expectedRevisions?.forEach { (imageId, revision) ->
                require(existingResults[imageId]?.revision == revision) {
                    "A saved page changed since import preview; refresh and review conflicts"
                }
            }
            val targetImages = chapter.images.associate { image ->
                val existing = currentImages.singleOrNull { it.id == image.id && sameImage(it, image) }
                    ?: currentImages.filter { sameImage(it, image) && it.index == image.index }.singleOrNull()
                image.id to (existing ?: image.copy(filePath = ""))
            }
            val expectedRevisions = link?.expectedRevisions
            if (expectedRevisions != null) {
                require(expectedRevisions.keys == targetImages.values.map { it.id }.toSet()) {
                    "Import revision checks must cover exactly the selected original pages"
                }
                require(link.replaceImageIds.all { expectedRevisions[it] != null }) {
                    "Explicit replacement requires the previewed saved revision"
                }
            }
            val now = System.currentTimeMillis()
            val provenance = TranslationArchiveProvenance(
                chapter.job.id,
                chapter.results.associate {
                    it.imageId to
                        it.revision
                },
                now,
                chapter.effectiveSettings,
                chapter.replacesSourceJobId,
                structuredFiles = chapter.job.isStructuredFiles,
                structuredPages = chapter.job.archiveMetadata?.structuredPages.orEmpty(),
            )
            val target = (
                current ?: chapter.job.copy(
                    id = targetId,
                    mangaId = link?.mangaId ?: -1,
                    chapterId = link?.chapterId ?: -1,
                    replacesJobId = null,
                    mangaTitle = link?.mangaTitle ?: chapter.job.mangaTitle,
                    chapterTitle =
                    link?.chapterTitle ?: chapter.job.chapterTitle,
                    imageCount = link?.originals?.size?.takeIf { it > 0 } ?: chapter.job.imageCount,
                )
                ).let {
                it.copy(
                    imageCount = link?.originals?.size?.takeIf { count -> count > 0 } ?: it.imageCount,
                    archiveMetadata = if (current != null && provenance.structuredPages.isNotEmpty()) {
                        current?.archiveMetadata
                    } else if (policy == TranslationArchiveConflictPolicy.REPLACE ||
                        it.archiveMetadata == null
                    ) {
                        provenance
                    } else {
                        requireNotNull(it.archiveMetadata).copy(
                            structuredFiles =
                            requireNotNull(it.archiveMetadata).structuredFiles || provenance.structuredFiles,
                        )
                    },
                )
            }
            archiveQueries.upsertArchiveJob(
                target.id,
                target.mangaId,
                target.chapterId,
                target.priority,
                target.updatedAt,
                json.encodeToString(target),
            )
            if (link != null) {
                currentImages.forEach { image ->
                    queries.upsertImage(target.id, image.id, image.index.toLong(), json.encodeToString(image))
                }
            }
            val accepted = mutableSetOf<String>()
            val writtenSources = mutableSetOf<String>()
            val replacementRevisions = mutableMapOf<String, Long>()
            chapter.results.forEach { source ->
                currentCoroutineContext().ensureActive()
                val image = targetImages.getValue(source.imageId)
                var result = mapResult(source, image.id)
                val previous = existingResults[image.id]
                if (previous != null && previous.copy(revision = result.revision) == result) {
                    identical++
                    accepted += source.imageId
                } else if (previous != null && policy == TranslationArchiveConflictPolicy.KEEP_LOCAL &&
                    image.id !in link?.replaceImageIds.orEmpty()
                ) {
                    conflicts++
                } else {
                    if (previous != null) {
                        require(maxOf(previous.revision, result.revision) < Long.MAX_VALUE) {
                            "Translation revision cannot advance"
                        }
                        result = result.copy(revision = maxOf(now, previous.revision + 1, result.revision + 1))
                        replacementRevisions[source.imageId] = result.revision
                        reviews(target.id).filter {
                            it.imageId == image.id &&
                                (
                                    it.state.pending ||
                                        it.state == tachiyomi.domain.translation.model.QualityReviewState.REPAIRED
                                    )
                        }.forEach { review ->
                            val superseded = review.copy(
                                state = tachiyomi.domain.translation.model.QualityReviewState.SUPERSEDED,
                                updatedAt = now,
                                message = "Explicit archive replacement changed the saved result; historical " +
                                    "checkpoint retained",
                            )
                            archiveQueries.upsertArchiveReview(
                                superseded.id,
                                target.id,
                                image.id,
                                now,
                                json.encodeToString(superseded),
                            )
                        }
                    }
                    queries.upsertImage(target.id, image.id, image.index.toLong(), json.encodeToString(image))
                    queries.upsertResult(target.id, result.imageId, now, json.encodeToString(result))
                    imported++
                    accepted += source.imageId
                    writtenSources += source.imageId
                }
            }
            chapter.reviews.filter { it.imageId in accepted }.forEach { source ->
                val imageId = targetImages.getValue(source.imageId).id
                val replacement = replacementRevisions[source.imageId]
                val eligibleUndo =
                    source.repairedRevision == chapter.results.single { it.imageId == source.imageId }.revision
                var review = source.copy(
                    jobId = target.id,
                    imageId = imageId,
                    beforeResult = mapResult(source.beforeResult, imageId),
                    renderEvidence = source.renderEvidence?.let { it.copy(original = it.original.copy(id = imageId)) },
                )
                if (replacement != null) {
                    review = review.copy(
                        id = stableId("archive-review", "${target.id}:${source.id}:$replacement"),
                        state = if (source.state == tachiyomi.domain.translation.model.QualityReviewState.REPAIRED &&
                            !eligibleUndo
                        ) {
                            tachiyomi.domain.translation.model.QualityReviewState.SUPERSEDED
                        } else {
                            source.state
                        },
                        repairedRevision = if (source.state ==
                            tachiyomi.domain.translation.model.QualityReviewState.REPAIRED &&
                            eligibleUndo
                        ) {
                            replacement
                        } else {
                            source.repairedRevision
                        },
                        updatedAt = if (eligibleUndo) now else source.updatedAt,
                    )
                }
                val prior = archiveQueries.archiveReview(review.id).awaitAsList().firstOrNull()?.let {
                    json.decodeFromString<QualityReviewCheckpoint>(it)
                }
                if (prior != null &&
                    prior.jobId != target.id
                ) {
                    review =
                        review.copy(id = stableId("archive-review", target.id + source.id))
                }
                if (archiveQueries.archiveReview(review.id).awaitAsList().isEmpty()) {
                    archiveQueries.upsertArchiveReview(
                        review.id,
                        target.id,
                        imageId,
                        review.updatedAt,
                        json.encodeToString(review),
                    )
                }
            }
            val persistedImages = images(target.id)
            val saved = results(target.id).count { result ->
                persistedImages.any { image ->
                    image.id == result.imageId &&
                        image.contentHash == result.imageHash &&
                        image.width == result.width &&
                        image.height == result.height
                }
            }
            val updated = target.copy(
                archiveMetadata = if (provenance.structuredPages.isNotEmpty()) {
                    if (writtenSources.isEmpty()) {
                        current?.archiveMetadata ?: target.archiveMetadata
                    } else {
                        val metadata = current?.archiveMetadata ?: provenance.copy(structuredPages = emptyMap())
                        metadata.copy(
                            structuredFiles = true,
                            structuredPages = metadata.structuredPages + provenance.structuredPages
                                .filterKeys { it in writtenSources }.mapKeys { targetImages.getValue(it.key).id },
                        )
                    }
                } else {
                    target.archiveMetadata
                },
                completedImages = saved,
                state = if (target.imageCount > 0 &&
                    saved >= target.imageCount
                ) {
                    TranslationJobState.COMPLETED
                } else {
                    TranslationJobState.PAUSED
                },
                reviewReturnState = null,
                reviewImageIds = null,
                message = if (target.isStructuredFiles || (provenance.structuredFiles && writtenSources.isNotEmpty())) {
                    if (target.imageCount > 0 &&
                        saved >= target.imageCount
                    ) {
                        "Imported translations saved"
                    } else {
                        "Awaiting imported pages"
                    }
                } else {
                    "Saved translations restored. Unfinished pages and review requests were not scheduled."
                },
            )
            archiveQueries.upsertArchiveJob(
                updated.id,
                updated.mangaId,
                updated.chapterId,
                updated.priority,
                updated.updatedAt,
                json.encodeToString(updated),
            )
            ids += target.id
            if (target.chapterId >= 0) linked++ else unlinked++
        }
        TranslationRestoreReport(imported, identical, conflicts, linked, unlinked, ids)
    }

    override suspend fun importHistory(
        events: List<tachiyomi.domain.translation.model.TranslationEvent>,
        operations: List<tachiyomi.domain.translation.model.TranslationOperation>,
    ) = database.transaction {
        val jobIds = (events.map { it.jobId } + operations.map { it.jobId }).toSet()
        jobIds.forEach { require(job(it) != null) { "Historical log target is unavailable" } }
        operations.forEach { supplied ->
            currentCoroutineContext().ensureActive()
            // This repository boundary accepts history facts, never executable provider checkpoints.
            val operation = supplied.copy(geometryCorrection = null)
            operation.validate()
            require(operation.state.terminal) { "Imported operations must be inert historical records" }
            archiveQueries.insertArchiveOperation(
                operation.id, operation.jobId, operation.parentId, operation.batchId,
                operation.imageId, operation.stage.name, operation.state.name, operation.updatedAt,
                json.encodeToString(
                    operation,
                ),
            )
        }
        events.forEach { event ->
            currentCoroutineContext().ensureActive()
            archiveQueries.insertArchiveEvent(
                event.id, event.jobId, event.time, json.encodeToString(event),
                event.operationId, event.batchId, event.imageId, event.stage, event.level,
            )
        }
    }

    private fun sameImage(a: TranslationImage, b: TranslationImage) =
        a.contentHash == b.contentHash && a.width == b.width && a.height == b.height
    private fun mapResult(
        result: TranslationPageResult,
        imageId: String,
    ) = result.copy(imageId = imageId, rawOcr = result.rawOcr?.copy(imageId = imageId))
    private fun stableId(prefix: String, value: String) =
        "$prefix-" +
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun validate(chapter: TranslationArchiveChapter) {
        require(chapter.job.replacesJobId == null) { "Archive contains an operational replacement relationship" }
        require(
            chapter.job.settings.provider.credentialId.isEmpty() &&
                chapter.job.settings.provider.extraHeaders.isEmpty(),
        ) {
            "Archive contains credentials"
        }
        require(
            !chapter.job.settings.autoTranslate && chapter.job.settings.chaptersAhead == 0 &&
                chapter.job.reviewImageIds == null &&
                chapter.job.reviewReturnState == null,
        ) { "Archive contains scheduled work" }
        val images = chapter.images.associateBy { it.id }
        require(
            images.size == chapter.images.size &&
                chapter.results.map { it.imageId }.distinct().size == chapter.results.size &&
                chapter.results.size == images.size,
        ) { "Duplicate or unfinished archive page" }
        chapter.results.forEach { result ->
            val image = requireNotNull(images[result.imageId])
            require(
                image.filePath.isEmpty() && result.imageHash == image.contentHash && result.width == image.width &&
                    result.height == image.height &&
                    result.revision > 0,
            ) { "Archive image identity mismatch" }
            QualityReviewValidation.validatePage(result)
        }
        chapter.reviews.forEach { review ->
            review.executionSettings?.let { settings ->
                require(settings.provider.credentialId.isEmpty() && settings.provider.extraHeaders.isEmpty()) {
                    "Archive review execution settings contain credentials"
                }
                require(!settings.autoTranslate && settings.chaptersAhead == 0) {
                    "Archive review execution settings contain scheduled work"
                }
            }
            val image = requireNotNull(images[review.imageId])
            require(
                review.jobId == chapter.job.id && !review.state.pending && review.beforeResult.imageId == image.id &&
                    review.beforeResult.imageHash == image.contentHash &&
                    review.beforeResult.width == image.width &&
                    review.beforeResult.height == image.height,
            ) { "Archive review original identity mismatch" }
            QualityReviewValidation.validatePage(review.beforeResult)
        }
    }
}

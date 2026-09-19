package mihon.feature.translation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import mihon.app.di.appGraph
import mihon.feature.translation.TranslationManager
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.QualityReviewSummary
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.service.TranslationCoverage
import tachiyomi.domain.translation.service.TranslationCoverageState
import tachiyomi.domain.translation.service.TranslationRepository

/** Saved page identities determine completion. Queue state never stands in for OCR or review completion. */
data class ChapterTranslationControl(
    val chapterId: Long,
    val coverage: TranslationCoverage,
    val job: TranslationJob?,
    val pendingReviews: Int = 0,
    val incompleteReviews: Int = 0,
) {
    val active get() = job?.state in TranslationManager.activeStates ||
        job?.state in setOf(TranslationJobState.QUEUED, TranslationJobState.WAITING)
    val complete get() = coverage.state == TranslationCoverageState.COMPLETED
    val state get() = when {
        active -> checkNotNull(job).state
        complete -> TranslationJobState.COMPLETED
        job?.state == TranslationJobState.FAILED -> TranslationJobState.FAILED
        coverage.saved > 0 -> TranslationJobState.PARTIAL
        else -> job?.state ?: TranslationJobState.QUEUED
    }
    val actionLabel get() = when {
        active -> "Queue"
        complete -> "Results"
        job?.isStructuredFiles == true -> "Import"
        job != null -> "Resume"
        else -> "Translate"
    }
}

data class TranslationControlSummary(
    val chapters: List<ChapterTranslationControl> = emptyList(),
    val expectedChapters: Int? = null,
    val loaded: Boolean = false,
    val error: String? = null,
) {
    val active get() = chapters.any { it.active }
    val complete get() = expectedChapters != null && expectedChapters > 0 &&
        chapters.count { it.complete } == expectedChapters
    val state get() = when {
        error != null -> TranslationJobState.FAILED
        active -> chapters.first { it.active }.state
        complete -> TranslationJobState.COMPLETED
        chapters.any { it.state == TranslationJobState.FAILED } -> TranslationJobState.FAILED
        chapters.any { it.coverage.saved > 0 } -> TranslationJobState.PARTIAL
        else -> chapters.firstOrNull()?.state ?: TranslationJobState.QUEUED
    }
    val progress: Float? get() {
        if (!loaded || chapters.isEmpty()) return null
        if (expectedChapters == 1) {
            val coverage = chapters.singleOrNull()?.coverage ?: return null
            return coverage.total?.takeIf { it > 0 }?.let { coverage.saved.toFloat() / it }
        }
        return expectedChapters?.takeIf { it > 0 }?.let { total -> chapters.count { it.complete }.toFloat() / total }
    }
    val progressLabel get() = when {
        !loaded -> "Loading status…"
        error != null -> "Status unavailable"
        expectedChapters == 1 -> chapters.singleOrNull()?.coverage?.let {
            "${it.saved}/${it.total ?: "?"} saved pages"
        } ?: "0/? saved pages"
        else -> "${chapters.count { it.complete }}/${expectedChapters ?: "?"} saved chapters"
    }
    val actionLabel get() = when {
        active -> "Queue"
        complete -> "Results"
        expectedChapters == 1 -> chapters.singleOrNull()?.actionLabel ?: "Translate"
        else -> "Translate"
    }
    val detail get() = buildString {
        append(progressLabel)
        if (active) {
            val jobs = chapters.filter { it.active }.mapNotNull { it.job }
            append(" · ").append(jobs.size).append(" active / queued jobs")
            jobs.firstOrNull()?.let { job ->
                append(" · ").append(
                    when (job.state) {
                        TranslationJobState.ACQUIRING -> "Acquiring original pages"
                        TranslationJobState.OCR -> "Local OCR"
                        TranslationJobState.TRANSLATING -> "Provider processing"
                        TranslationJobState.WAITING -> "Waiting / retry"
                        else -> "Queued"
                    },
                )
                job.message?.let { append(" · ").append(it) }
            }
        }
        if (chapters.any { !it.complete && it.job?.isStructuredFiles == true }) {
            append(" · Awaiting imported pages")
        }
        val pending = chapters.sumOf { it.pendingReviews }
        val needsReview = chapters.sumOf { it.incompleteReviews }
        if (pending > 0) append(" · AI review pending: ").append(pending).append(" review records")
        if (needsReview > 0) append(" · AI review incomplete: ").append(needsReview).append(" review records")
        error?.let { append(" · ").append(it) }
    }
}

internal suspend fun loadChapterTranslationControl(
    repository: TranslationRepository,
    chapterId: Long,
    history: List<TranslationJob>,
    reviewSnapshot: List<QualityReviewSummary>? = null,
): ChapterTranslationControl {
    val ordered = history.sortedByDescending { it.createdAt }
    val reviews = reviewSnapshot ?: ordered.flatMap { repository.reviewSummaries(it.id) }
    return ChapterTranslationControl(
        chapterId,
        repository.chapterCoverage(chapterId),
        selectChapterTranslationJob(ordered),
        pendingReviews = reviews.count { it.state.pending },
        incompleteReviews = reviews.count {
            it.state in
                setOf(QualityReviewState.NEEDS_REVIEW, QualityReviewState.INCOMPLETE)
        },
    )
}

/** Observe the same persisted facts consumed by visible chapter and series controls. */
internal fun observeTranslationControl(
    repository: TranslationRepository,
    mangaIds: List<Long>,
    chapterIds: List<Long>? = null,
    expectedChapters: Int? = chapterIds?.size,
): kotlinx.coroutines.flow.Flow<TranslationControlSummary> {
    val coverageCache = mutableMapOf<Long, Pair<List<TranslationJob>, ChapterTranslationControl>>()
    val jobs = repository.observeJobs().map { all ->
        all.filter { it.mangaId in mangaIds && (chapterIds == null || it.chapterId in chapterIds) }
    }.distinctUntilChanged()
    val edits = if (chapterIds?.size == 1) {
        repository.observeChapterCoverage(chapterIds.single())
    } else {
        flowOf(null)
    }
    return combine(jobs, edits, repository.observeReviewSummaries()) { activeJobs, coverage, summaries ->
        val jobIds = activeJobs.map { it.id }.toSet()
        Triple(activeJobs, summaries.filter { it.jobId in jobIds }, coverage)
    }.distinctUntilChanged()
        .mapLatest { (activeJobs, reviews, _) ->
            try {
                val ids = chapterIds ?: activeJobs.map { it.chapterId }.distinct()
                coverageCache.keys.retainAll(ids.toSet())
                val controls = ids.map { id ->
                    val history = activeJobs.filter { it.chapterId == id }
                    val jobIds = history.map { it.id }.toSet()
                    val chapterReviews = reviews.filter { it.jobId in jobIds }
                    val cached = coverageCache[id]?.takeIf { it.first == history && chapterIds?.size != 1 }
                    val control = cached?.second ?: loadChapterTranslationControl(
                        repository,
                        id,
                        history,
                        chapterReviews,
                    ).also {
                        coverageCache[id] = history to it
                    }
                    control.copy(
                        pendingReviews = chapterReviews.count { it.state.pending },
                        incompleteReviews = chapterReviews.count {
                            it.state == QualityReviewState.NEEDS_REVIEW || it.state == QualityReviewState.INCOMPLETE
                        },
                    )
                }
                TranslationControlSummary(controls, expectedChapters, loaded = true)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                TranslationControlSummary(
                    expectedChapters = expectedChapters,
                    loaded = true,
                    error = "Cannot load saved translation status",
                )
            }
        }.flowOn(Dispatchers.IO)
}

/** One scope per visible control. Only a visible single chapter observes result edits directly. */
@Composable
fun rememberTranslationControl(
    mangaIds: List<Long>,
    chapterIds: List<Long>? = null,
    expectedChapters: Int? = chapterIds?.size,
): TranslationControlSummary {
    val repository = LocalContext.current.appGraph.translationRepository
    val flow = remember(repository, mangaIds, chapterIds, expectedChapters) {
        observeTranslationControl(repository, mangaIds, chapterIds, expectedChapters)
    }
    return flow.collectAsState(TranslationControlSummary(expectedChapters = expectedChapters)).value
}

@Composable
fun TranslationProgressButton(
    control: TranslationControlSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    label: String = control.actionLabel,
) {
    val settings by LocalContext.current.appGraph.translationPreferences.settings.collectAsState()
    val color = translationStateColor(control.state, settings.queueColors)
    TextButton(
        onClick = onClick,
        enabled = control.loaded,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        modifier = modifier.semantics { contentDescription = "$label · ${control.detail}" },
    ) {
        Column(
            Modifier.widthIn(max = if (compact) 116.dp else 280.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (control.active) CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 1.5.dp)
                Text("${translationStateSymbol(control.state)} $label", style = MaterialTheme.typography.labelLarge)
            }
            Text(if (compact) control.progressLabel else control.detail, style = MaterialTheme.typography.labelSmall)
            control.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = color,
                    modifier = Modifier.height(2.dp),
                )
            }
        }
    }
}

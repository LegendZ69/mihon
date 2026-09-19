package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.TranslationJob

/** Stable UI grouping keys; archive sentinels do not identify a real chapter or series. */
fun TranslationJob.chapterHistoryKey(): String =
    if (mangaId < 0 || chapterId < 0) "archive-chapter:$id" else "chapter:$mangaId:$chapterId"

fun TranslationJob.seriesHistoryKey(): String =
    if (mangaId < 0) "archive-series:$id" else "series:$mangaId"

package mihon.feature.translation.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity

class TranslationActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mangaId = intent.getLongExtra("mangaId", -1).takeIf { it >= 0 }
        val jobId = intent.getStringExtra("jobId")
        val operationId = intent.getStringExtra("operationId")
        val tab = intent.getIntExtra("tab", 0)
        setContent {
            TachiyomiTheme {
                Navigator(
                    if (intent.getBooleanExtra("structuredImport", false)) {
                        TranslationStructuredImportScreen(
                            intent.getLongArrayExtra("importMangaIds")?.toList().orEmpty(),
                            intent.getLongArrayExtra("importChapterIds")?.toList().orEmpty(),
                            intent.getStringArrayListExtra("importUris").orEmpty().take(128),
                        )
                    } else if (tab == 1 &&
                        (jobId != null || operationId != null)
                    ) {
                        TranslationLogsScreen(jobId, operationId)
                    } else {
                        TranslationScreen(mangaId, tab)
                    },
                )
            }
        }
    }

    companion object {
        fun importIntent(
            context: Context,
            mangaIds: List<Long>,
            chapterIds: List<Long> = emptyList(),
            uris: List<android.net.Uri> = emptyList(),
        ): Intent =
            Intent(context, TranslationActivity::class.java)
                .putExtra("structuredImport", true)
                .putExtra("importMangaIds", mangaIds.toLongArray())
                .putExtra("importChapterIds", chapterIds.toLongArray())
                .putStringArrayListExtra("importUris", ArrayList(uris.take(128).map(android.net.Uri::toString)))

        fun intent(context: Context, mangaId: Long? = null, tab: Int = 0): Intent =
            Intent(context, TranslationActivity::class.java).putExtra("mangaId", mangaId ?: -1).putExtra("tab", tab)
    }
}

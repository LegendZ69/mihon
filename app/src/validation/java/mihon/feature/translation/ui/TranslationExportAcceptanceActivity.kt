package mihon.feature.translation.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tachiyomi.domain.translation.model.TranslationPdfPageSize
import tachiyomi.domain.translation.model.TranslationRenderedFormat

/** Runs the production dialog without reading jobs, settings, credentials or starting an export. */
class TranslationExportAcceptanceActivity : ComponentActivity() {
    private var open by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var logs by mutableStateOf(false)
    private var captures by mutableStateOf(false)
    private var format by mutableStateOf(TranslationRenderedFormat.CBZ)
    private var paper by mutableStateOf(TranslationPdfPageSize.A4)
    private var logChanges = 0
    private var captureChanges = 0
    private var backups = 0
    private var rendered = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Text("Isolated export dialog validation")
                if (open) {
                    TranslationExportDialog(
                        count = 1,
                        busy = busy,
                        logs = logs,
                        captures = captures,
                        format = format,
                        paper = paper,
                        onLogsChange = {
                            logs = it
                            logChanges++
                        },
                        onCapturesChange = {
                            captures = it
                            captureChanges++
                        },
                        onFormatChange = { format = it },
                        onPaperChange = { paper = it },
                        onCancel = { open = false },
                        onBackup = { backups++ },
                        onRendered = { rendered++ },
                    )
                }
            }
        }
    }

    fun selections(): Pair<Boolean, Boolean> = logs to captures
    fun changeCounts(): Pair<Int, Int> = logChanges to captureChanges
    fun exportCounts(): Pair<Int, Int> = backups to rendered
    fun formatSelection(): Pair<TranslationRenderedFormat, TranslationPdfPageSize> = format to paper
    fun setOperationBusy(value: Boolean) {
        busy = value
    }
    fun dialogOpen(): Boolean = open
}

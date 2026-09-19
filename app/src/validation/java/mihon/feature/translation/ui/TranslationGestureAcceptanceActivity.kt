package mihon.feature.translation.ui

import android.graphics.RectF
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Refresh

/** Isolated debug/benchmark surface. It never reads or creates translation jobs or credentials. */
class TranslationGestureAcceptanceActivity : ComponentActivity() {
    private var rows by mutableStateOf(listOf("alpha", "beta"))
    private var selected by mutableStateOf(emptySet<String>())
    private var revision by mutableStateOf(0)
    private var starts = 0
    private var ends = 0
    private val bounds = mutableMapOf<String, RectF>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val rtl = intent.getBooleanExtra("rtl", false)
        setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
                            Text("Isolated translator gesture validation", Modifier.padding(16.dp))
                            rows.forEach { id ->
                                androidx.compose.runtime.key(id) {
                                    TranslationActionRow(
                                        selected = id in selected,
                                        selectionActive = selected.isNotEmpty(),
                                        onClick = { if (selected.isNotEmpty()) selected = selected.toggle(id) },
                                        onLongClick = { selected = selected.toggle(id) },
                                        startAction = TranslationRowAction(
                                            "Start action $id",
                                            MaterialSymbols.Rounded.Refresh,
                                            !intent.getBooleanExtra("disabled", false),
                                        ) {
                                            starts++
                                        },
                                        endAction = TranslationRowAction(
                                            "End action $id",
                                            MaterialSymbols.Rounded.Delete,
                                            !intent.getBooleanExtra("disabled", false),
                                        ) {
                                            ends++
                                        },
                                        modifier = Modifier.fillMaxWidth().height(112.dp)
                                            .semantics { contentDescription = "Validation row $id" }
                                            .onGloballyPositioned { coordinates ->
                                                val origin = coordinates.localToScreen(Offset.Zero)
                                                bounds[id] = RectF(
                                                    origin.x,
                                                    origin.y,
                                                    origin.x + coordinates.size.width,
                                                    origin.y + coordinates.size.height,
                                                )
                                            },
                                    ) {
                                        Text("$id · saved result revision $revision", Modifier.padding(16.dp))
                                    }
                                }
                            }
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            TranslationSelectionBar(selected.size, { selected = emptySet() }, emptyList())
                        }
                    }
                }
            }
        }
    }

    fun rowBounds(id: String): RectF = RectF(requireNotNull(bounds[id]))
    fun selectedIds(): Set<String> = selected.toSet()
    fun actionCounts(): Pair<Int, Int> = starts to ends

    /** Simulates repository progress and order updates without invoking any gesture callbacks. */
    fun updateRows() {
        rows = rows.reversed()
        revision++
    }
}

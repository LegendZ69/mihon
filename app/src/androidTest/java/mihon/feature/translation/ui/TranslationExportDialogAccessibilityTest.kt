package mihon.feature.translation.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.TranslationPdfPageSize
import tachiyomi.domain.translation.model.TranslationRenderedFormat

/** Android accessibility and touch input against Management's actual Compose export dialog. */
@RunWith(AndroidJUnit4::class)
class TranslationExportDialogAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val labels = listOf("Include logs", "Include sanitized API payloads")

    @Test
    fun labelledRowsExposeCheckedStateAndOneActionWithoutStartingExports() = withDialog { activity ->
        labels.forEach { label ->
            val node = toggle(label)
            assertTrue("The labelled row must expose a checkable state", node.isCheckable)
            assertTrue(node.isEnabled)
            assertTrue(node.isClickable)
            assertFalse(node.isChecked)
            assertTrue(
                "The labelled checkbox must expose an accessibility click",
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            await("Checked state for $label") { labelledToggles(label).singleOrNull()?.isChecked == true }
        }
        assertEquals(true to true, onMain { activity.selections() })
        assertEquals(
            "Each accessibility click invokes exactly one callback",
            1 to 1,
            onMain {
                activity.changeCounts()
            },
        )
        assertEquals(0 to 0, onMain { activity.exportCounts() })
        assertEquals(
            TranslationRenderedFormat.CBZ to TranslationPdfPageSize.A4,
            onMain { activity.formatSelection() },
        )
        // The row's trailing area lies outside the visual checkbox and must share its toggle action.
        tapTrailingRow(toggle(labels[0]), activity)
        await("Row tap toggles only logs") { onMain { activity.selections() } == (false to true) }
        assertEquals(2 to 1, onMain { activity.changeCounts() })
        onMain { activity.setOperationBusy(true) }
        await("Both options expose the disabled state") {
            labels.all {
                labelledToggles(it).singleOrNull()?.isEnabled ==
                    false
            }
        }
        labels.forEach { label ->
            toggle(label).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            tapTrailingRow(toggle(label), activity)
        }
        settle()
        assertEquals("Disabled options keep the selected flags", false to true, onMain { activity.selections() })
        assertEquals("Disabled options never invoke callbacks", 2 to 1, onMain { activity.changeCounts() })
        assertEquals(0 to 0, onMain { activity.exportCounts() })
        onMain { activity.setOperationBusy(false) }
        await("Options reenable without clearing selections") {
            labels.all {
                labelledToggles(it).singleOrNull()?.isEnabled ==
                    true
            }
        }
        assertTrue(action("Cancel").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Cancel closes the dialog without requesting export") { !onMain { activity.dialogOpen() } }
        assertEquals(0 to 0, onMain { activity.exportCounts() })
    }

    @Test
    fun explicitFormatActionsRemainSeparateFromDiagnosticSelections() = withDialog { activity ->
        assertTrue(toggle(labels[1]).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Capture selection changed") { onMain { activity.selections() } == (false to true) }
        assertEquals(0 to 0, onMain { activity.exportCounts() })
        assertTrue(action("Structured ZIP…").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Explicit structured backup action") { onMain { activity.exportCounts() } == (1 to 0) }
        assertTrue(action("PDF").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("PDF paper choices are shown") {
            onMain { activity.formatSelection().first } ==
                TranslationRenderedFormat.PDF
        }
        assertTrue(action("LETTER").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Explicit Letter selection") {
            onMain { activity.formatSelection().second } ==
                TranslationPdfPageSize.LETTER
        }
        assertEquals("Format selection does not start a rendered export", 1 to 0, onMain { activity.exportCounts() })
        assertTrue(action("Export PDF…").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Explicit rendered action") { onMain { activity.exportCounts() } == (1 to 1) }
        assertEquals(false to true, onMain { activity.selections() })
    }

    private fun withDialog(block: (TranslationExportAcceptanceActivity) -> Unit) {
        val automation = instrumentation.uiAutomation
        val before = automation.serviceInfo
        val originalFlags = before.flags
        before.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = before
        var activity: TranslationExportAcceptanceActivity? = null
        try {
            val surface = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, TranslationExportAcceptanceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as TranslationExportAcceptanceActivity
            activity = surface
            await("The production export dialog owns the focused accessibility window") {
                focusedDialog() && nodes().any { it.text?.toString() == "Export 1 selected histories" }
            }
            await("Exactly one labelled checkbox for each diagnostic option") {
                labels.all { label -> labelledToggles(label).size == 1 }
            }
            report("ready")
            block(surface)
        } finally {
            report("finished")
            activity?.let { onMain { it.finish() } }
            instrumentation.waitForIdleSync()
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private fun focusedDialog(): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        val window = root.window ?: return false
        return root.packageName?.toString() == instrumentation.targetContext.packageName && window.isActive &&
            window.isFocused
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || size >= 128) return
            add(node)
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun hasLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return true
        return (0 until node.childCount).any { index -> node.getChild(index)?.let { hasLabel(it, label) } == true }
    }

    private fun labelledToggles(label: String) = nodes().filter {
        it.isVisibleToUser && it.isCheckable && hasLabel(it, label)
    }

    private fun toggle(label: String): AccessibilityNodeInfo = labelledToggles(label).single()

    private fun action(label: String): AccessibilityNodeInfo {
        await("Enabled action $label") {
            nodes().count { it.isVisibleToUser && it.isEnabled && it.isClickable && hasLabel(it, label) } == 1
        }
        return nodes().single { it.isVisibleToUser && it.isEnabled && it.isClickable && hasLabel(it, label) }
    }

    private fun tapTrailingRow(node: AccessibilityNodeInfo, activity: TranslationExportAcceptanceActivity) {
        assertTrue("Only inject input into the focused export dialog", focusedDialog())
        val bounds = Rect().also(node::getBoundsInScreen)
        val density = activity.resources.displayMetrics.density
        assertTrue("The whole row must meet the native touch target", bounds.height() >= 48 * density - 1)
        val x = bounds.right - 8 * density
        val y = bounds.exactCenterY()
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        settle()
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
        instrumentation.waitForIdleSync()
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5_000
        while (!predicate() && SystemClock.uptimeMillis() < end) SystemClock.sleep(25)
        assertTrue("$label did not settle", predicate())
    }

    private fun report(stage: String) {
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "export_dialog_accessibility",
                    "$stage: " + nodes().filter { it.isCheckable }.joinToString {
                        "class=${it.className}, text=${it.text}, label=${labels.firstOrNull { label ->
                            hasLabel(it, label)
                        }}, " +
                            "enabled=${it.isEnabled}, checked=${it.isChecked}, clickable=${it.isClickable}"
                    },
                )
            },
        )
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
}

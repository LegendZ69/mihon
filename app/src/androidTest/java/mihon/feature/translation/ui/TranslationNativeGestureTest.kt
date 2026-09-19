package mihon.feature.translation.ui

import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose input against the same row used by queues, logs, management and model packs. */
@RunWith(AndroidJUnit4::class)
class TranslationNativeGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun nativeThresholdAndCompletedActionsMirrorInRtl() {
        for (rtl in listOf(false, true)) {
            withSurface(rtl) { activity ->
                val density = activity.resources.displayMetrics.density
                val direction = if (rtl) -1 else 1
                swipe(activity, bounds(activity, "alpha"), 20f * density * direction)
                assertEquals(
                    "A below-threshold gesture must not invoke either action",
                    0 to 0,
                    onMain { activity.actionCounts() },
                )
                assertTrue(
                    "Below-threshold swipes must not become a long press: ${diagnostics(activity)}",
                    onMain { activity.selectedIds().isEmpty() },
                )
                swipe(activity, bounds(activity, "alpha"), 140f * density * direction)
                await("Start action rtl=$rtl", { diagnostics(activity) }) {
                    onMain { activity.actionCounts() } == (1 to 0)
                }
                swipe(activity, bounds(activity, "alpha"), -140f * density * direction)
                await("End action rtl=$rtl", { diagnostics(activity) }) {
                    onMain { activity.actionCounts() } == (1 to 1)
                }
                assertEquals(1 to 1, onMain { activity.actionCounts() })
            }
        }
    }

    @Test
    fun longPressTapReorderAndBackKeepSelectionWithoutSwipingWork() = withSurface { activity ->
        press(activity, bounds(activity, "alpha"), ViewConfiguration.getLongPressTimeout() + 200L)
        await { onMain { activity.selectedIds() } == setOf("alpha") }
        press(activity, bounds(activity, "beta"), 80)
        await { onMain { activity.selectedIds() } == setOf("alpha", "beta") }
        onMain { activity.updateRows() }
        settle()
        assertEquals(setOf("alpha", "beta"), onMain { activity.selectedIds() })
        swipe(activity, bounds(activity, "alpha"), 140f * activity.resources.displayMetrics.density)
        assertEquals("Selection disables completed swipe actions", 0 to 0, onMain { activity.actionCounts() })
        assertEquals(setOf("alpha", "beta"), onMain { activity.selectedIds() })
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await { onMain { activity.selectedIds().isEmpty() } }
        assertFalse("Back clears selection without closing this screen", activity.isFinishing)
    }

    @Test
    fun accessibilityExposesActionsAndSelectedStateWithoutRestoringSwipes() = withSurface { activity ->
        val selectedDescription = activity.getString(androidx.compose.ui.R.string.selected)
        val unselectedDescription = activity.getString(androidx.compose.ui.R.string.not_selected)
        var row = findRow("alpha")
        assertNotNull("The row must be independently accessible", row)
        assertEquals(unselectedDescription, AccessibilityNodeInfoCompat.wrap(requireNotNull(row)).stateDescription)
        val actions = requireNotNull(row).actionList.mapNotNull { it.label?.toString() }
        assertTrue(actions.toString(), "Start action alpha" in actions)
        assertTrue(actions.toString(), "End action alpha" in actions)
        assertTrue(requireNotNull(row).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        await { onMain { activity.selectedIds() } == setOf("alpha") }
        await("Selected accessibility state and actions", { diagnostics(activity) }) {
            findRow("alpha")?.let { selectedRow ->
                AccessibilityNodeInfoCompat.wrap(selectedRow).stateDescription == selectedDescription &&
                    selectedRow.actionList.none {
                        it.label?.toString() in listOf("Start action alpha", "End action alpha")
                    }
            } == true
        }
        row = findRow("alpha")
        // Compose's non-tab roles expose selection via stateDescription, not isSelected.
        assertEquals(
            "TalkBack receives the selected state description",
            selectedDescription,
            AccessibilityNodeInfoCompat.wrap(requireNotNull(row)).stateDescription,
        )
        val selectedActions = requireNotNull(row).actionList.mapNotNull { it.label?.toString() }
        assertFalse("A selected row must not expose disabled swipe actions", "Start action alpha" in selectedActions)
        assertFalse("A selected row must not expose disabled swipe actions", "End action alpha" in selectedActions)
        assertTrue(requireNotNull(row).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await { onMain { activity.selectedIds().isEmpty() } }
        await("Unselected accessibility state and restored actions", { diagnostics(activity) }) {
            findRow("alpha")?.let { restored ->
                AccessibilityNodeInfoCompat.wrap(restored).stateDescription == unselectedDescription &&
                    restored.actionList.mapNotNull { it.label?.toString() }
                        .containsAll(listOf("Start action alpha", "End action alpha"))
            } == true
        }
        assertEquals(0 to 0, onMain { activity.actionCounts() })
    }

    @Test
    fun disabledActionsDoNotDispatchThroughSwipesOrAccessibility() = withSurface(disabled = true) { activity ->
        swipe(activity, bounds(activity, "alpha"), 140f * activity.resources.displayMetrics.density)
        swipe(activity, bounds(activity, "alpha"), -140f * activity.resources.displayMetrics.density)
        assertEquals(0 to 0, onMain { activity.actionCounts() })
        val actions = requireNotNull(findRow("alpha")).actionList.mapNotNull { it.label?.toString() }
        assertFalse("Start action alpha" in actions)
        assertFalse("End action alpha" in actions)
    }

    private fun withSurface(
        rtl: Boolean = false,
        disabled: Boolean = false,
        block: (TranslationGestureAcceptanceActivity) -> Unit,
    ) {
        // Register accessibility before Compose starts; its first tree is asynchronous.
        instrumentation.uiAutomation
        val intent = Intent(instrumentation.targetContext, TranslationGestureAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("rtl", rtl).putExtra("disabled", disabled)
        val activity = instrumentation.startActivitySync(intent) as TranslationGestureAcceptanceActivity
        try {
            await("Validation activity owns the input window", { diagnostics(activity) }) {
                onMain { activity.hasWindowFocus() } &&
                    instrumentation.uiAutomation.rootInActiveWindow?.packageName ==
                    instrumentation.targetContext.packageName
            }
            await("Both rows have visible accessibility and layout bounds", { diagnostics(activity) }) {
                listOf("alpha", "beta").all { id ->
                    runCatching { bounds(activity, id).width() > 0 }.getOrDefault(false) && findRow(id) != null
                }
            }
            settle()
            assertInputWindow(activity)
            report("ready", activity)
            block(activity)
        } finally {
            report("finished", activity)
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun bounds(activity: TranslationGestureAcceptanceActivity, id: String) = onMain { activity.rowBounds(id) }

    private fun swipe(activity: TranslationGestureAcceptanceActivity, rect: RectF, distance: Float) {
        assertInputWindow(activity)
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        val duration = minOf(80L, timeout / 4)
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY())
        for (step in 1..4) {
            SystemClock.sleep((duration / 4).coerceAtLeast(1))
            send(down, MotionEvent.ACTION_MOVE, rect.centerX() + distance * step / 4, rect.centerY())
        }
        send(down, MotionEvent.ACTION_UP, rect.centerX() + distance, rect.centerY())
        val elapsed = SystemClock.uptimeMillis() - down
        assertTrue("Input injection took ${elapsed}ms, exceeding long-press timeout ${timeout}ms", elapsed < timeout)
        settle()
        report("swipe distance=$distance elapsedMs=$elapsed", activity)
    }

    private fun press(activity: TranslationGestureAcceptanceActivity, rect: RectF, durationMs: Long) {
        assertInputWindow(activity)
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY())
        SystemClock.sleep(durationMs)
        send(down, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY())
        settle()
    }

    private fun send(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
        instrumentation.waitForIdleSync()
    }

    private fun await(
        label: String = "Gesture state",
        details: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val end = SystemClock.uptimeMillis() + 5_000
        while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(25)
        assertTrue("$label did not settle within five seconds: ${details()}", condition())
    }

    private fun assertInputWindow(activity: TranslationGestureAcceptanceActivity) {
        assertTrue(
            "Validation activity lost input focus: ${diagnostics(activity)}",
            onMain {
                activity.hasWindowFocus()
            },
        )
        assertEquals(
            "Accessibility must describe the same app window before input: ${diagnostics(activity)}",
            instrumentation.targetContext.packageName,
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString(),
        )
    }

    private fun diagnostics(activity: TranslationGestureAcceptanceActivity): String = buildString {
        append("rtl=${activity.intent.getBooleanExtra("rtl", false)} ")
        append("density=${activity.resources.displayMetrics.density} ")
        append("longPressMs=${ViewConfiguration.getLongPressTimeout()} ")
        append("touchSlop=${ViewConfiguration.get(activity).scaledTouchSlop} ")
        append(
            onMain {
                "focus=${activity.hasWindowFocus()} selected=${activity.selectedIds()} counts=${activity.actionCounts()} "
            },
        )
        var remaining = 32
        fun tree(node: AccessibilityNodeInfo?) {
            if (node == null || remaining-- <= 0) return
            append("[${node.packageName}, ${node.contentDescription}, ${node.text}, selected=${node.isSelected}, ")
            append("state=${AccessibilityNodeInfoCompat.wrap(node).stateDescription}, ")
            append("click=${node.isClickable}, actions=${node.actionList.map { it.label ?: it.id }}]")
            for (index in 0 until node.childCount) tree(node.getChild(index))
        }
        tree(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun report(step: String, activity: TranslationGestureAcceptanceActivity) {
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString("gesture_diagnostic", "$step: ${diagnostics(activity)}")
            },
        )
    }

    private fun findRow(id: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isVisibleToUser && node.contentDescription?.toString() == "Validation row $id") {
                if (node.isClickable && node.isLongClickable) return node
                // Compose may put the exact description on a synthetic child of the merged row.
                // Require its immediate actionable parent to have precisely the same screen bounds.
                val parent = node.parent ?: return null
                val childBounds = Rect().also(node::getBoundsInScreen)
                val parentBounds = Rect().also(parent::getBoundsInScreen)
                return parent.takeIf {
                    it.isVisibleToUser && it.isClickable && it.isLongClickable && childBounds == parentBounds
                }
            }
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
}

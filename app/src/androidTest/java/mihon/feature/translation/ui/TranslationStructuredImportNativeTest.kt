package mihon.feature.translation.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import mihon.feature.translation.transfer.StructuredTranslationDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Production navigation and editable controls, using only an owned file and a nonexistent series preference scope. */
@RunWith(AndroidJUnit4::class)
class TranslationStructuredImportNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun structuredFileRequiresExplicitPageMatchAndCanCancelWithoutCreatingWork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translation.acceptance") == "true")
        val graph = context.appGraph
        val before = graph.translationRepository.jobs()
        val root = File(context.cacheDir, "structured-import-ui-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(root, "native-structured-example.json").apply {
            writeText(StructuredTranslationDocuments.sampleJson)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        try {
            withActivity(TranslationActivity.importIntent(context, emptyList(), uris = listOf(uri))) {
                await("Selected file decoded in Files step") {
                    nodes().any {
                        it.text?.contains("native-structured-example.json") ==
                            true
                    } &&
                        text("1 · Files")
                }
                click("Format instructions")
                await("Native reference instructions dialog") { text("Structured translation format") }
                reveal("Save sample JSON…") { actions("Save sample JSON…").isNotEmpty() }
                reveal("Save JSON schema…") { actions("Save JSON schema…").isNotEmpty() }
                click("Close")
                click("Match pages")
                await("Page matching requires a destination") { text("2 · Page matching") }
                reveal("File image ID: my-page-identifier") { text("File image ID: my-page-identifier") }
                assertFalse(
                    "No target can validate without explicit original acquisition",
                    actionNode("Validate matches").isEnabled,
                )
                assertFalse(
                    "No original acquisition without a chosen chapter",
                    actionNode("Load original pages").isEnabled,
                )
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Back returns to Files without an import") { text("1 · Files") }
                assertEquals(
                    "Opening, matching and Back cannot enqueue paid work",
                    before,
                    graph.translationRepository.jobs(),
                )
                report(
                    "file-flow-passed",
                    "Parsed portable sample; no chosen original; validation disabled; Back preserved jobs",
                )
            }
        } finally {
            assertTrue("Remove only this test's owned cache directory", root.deleteRecursively())
        }
    }

    @Test
    fun nativePromptEditorsSaveValidDraftAndRetainInvalidDraftWithoutProviderWork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translation.acceptance") == "true")
        val graph = context.appGraph
        val preferences = graph.translationPreferences
        val before = graph.translationRepository.jobs()
        val global = preferences.settings.value
        val scopeId = 8_000_000_000_000_000L + (SystemClock.elapsedRealtimeNanos() % 1_000_000_000L)
        assertFalse(preferences.hasSeriesOverride(scopeId))
        assertEquals(
            "Series no longer exists",
            runCatching {
                graph.translationManager.chapterChoices(scopeId)
            }.exceptionOrNull()?.message,
        )
        try {
            withActivity(TranslationActivity.intent(context, scopeId, 2)) {
                // Use the native settings search; do not assume the preference is above the fold.
                setEditable("Search settings", "prompts")
                click("Prompts")
                await("Stage controls are visible") { text("Geometry correction") && text("Quality review") }
                reveal("Custom replacement") { actions("Custom replacement").isNotEmpty() }
                val custom = actions("Custom replacement").first()
                assertTrue(performTargetAction(custom, AccessibilityNodeInfo.ACTION_CLICK))
                // Compose publishes the replacement field after accepting the chip action.
                // Scrolling before that publication can move the field out of the viewport
                // before its editable semantics arrive (observed on K90).
                await("System replacement publishes its editable semantics before scrolling") {
                    nodes().any { it.isEditable && labelled(it, "System task wording") }
                }
                reveal("System task wording") { editors("System task wording").isNotEmpty() }
                val valid = "Native validation: preserve dialogue and use {{target_language}}."
                setPrompt(valid)
                await("Valid edits autosave") {
                    preferences.effectiveSettings(scopeId).prompts.translation.system ==
                        valid
                }
                setPrompt("Keep this editable {{unknown_native_validation}}")
                await("Unknown variable remains a visible validation error") {
                    nodes().any {
                        it.text?.contains("Unknown prompt variable") ==
                            true
                    }
                }
                SystemClock.sleep(400)
                assertEquals(valid, preferences.effectiveSettings(scopeId).prompts.translation.system)
                // A rejected draft is editable, and a corrected replacement can be saved before navigation.
                val corrected = "Native validation final {{source_language}} → {{target_language}}."
                setPrompt(corrected)
                // Navigate before the 300 ms debounce; disposal must flush the last valid edit.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Back returns to parent settings") { text("Search settings") }
                await("Navigation flushes the corrected draft") {
                    preferences.effectiveSettings(scopeId).prompts.translation.system == corrected
                }
                assertEquals(corrected, preferences.effectiveSettings(scopeId).prompts.translation.system)
                assertEquals(global, preferences.settings.value)
                assertEquals(before, graph.translationRepository.jobs())
                report(
                    "prompt-flow-passed",
                    "Series-only system replacement; debounced save; invalid draft retained; " +
                        "navigation saved; no new jobs",
                )
            }
        } finally {
            // The activity has finished; allow its disposal flush to settle before removing only our scope.
            var revision = preferences.revision.value
            var stableSince = SystemClock.uptimeMillis()
            val deadline = stableSince + 10_000
            while (SystemClock.uptimeMillis() - stableSince < 600 && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(40)
                if (preferences.revision.value != revision) {
                    revision = preferences.revision.value
                    stableSince = SystemClock.uptimeMillis()
                }
            }
            preferences.reset(scopeId)
            assertFalse(preferences.hasSeriesOverride(scopeId))
            assertEquals(global, preferences.settings.value)
        }
    }

    private fun setPrompt(value: String) = setEditable("System task wording", value)

    private fun editors(label: String) = nodes().filter { it.isVisibleToUser && it.isEditable && labelled(it, label) }

    @Test
    fun populatedQueueAndGestureSettingsUseAppGraphAndPreserveWork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translation.acceptance") == "true")
        val graph = context.appGraph
        val repository = graph.translationRepository
        val before = repository.jobs()
        val runnableStates = mihon.feature.translation.TranslationManager.activeStates + setOf(
            tachiyomi.domain.translation.model.TranslationJobState.QUEUED,
            tachiyomi.domain.translation.model.TranslationJobState.WAITING,
        )
        assumeTrue(before.none { it.state in runnableStates })
        val start = graph.translationGesturePreferences.assignment(
            TranslationGestureRow.WORK,
            TranslationGestureDirection.START,
        )
        val end = graph.translationGesturePreferences.assignment(
            TranslationGestureRow.WORK,
            TranslationGestureDirection.END,
        )
        val preserved = listOf(start, end).map { Triple(it, it.isSet(), it.get()) }
        val scopeId = 8_100_000_000_000_000L + (SystemClock.elapsedRealtimeNanos() % 1_000_000_000L)
        val fixture = tachiyomi.domain.translation.model.TranslationJob(
            id = "native-gesture-graph-${UUID.randomUUID()}",
            mangaId = scopeId,
            chapterId = scopeId,
            mangaTitle = "Owned gesture graph fixture",
            chapterTitle = "Owned gesture graph chapter",
            settings = tachiyomi.domain.translation.model.TranslationSettings(
                mode = tachiyomi.domain.translation.model.TranslationMode.STRUCTURED_FILES,
            ),
            state = tachiyomi.domain.translation.model.TranslationJobState.PAUSED,
            imageCount = 2,
            message = "Disposable native UI fixture; no credentials or provider work",
        )
        try {
            start.set(TranslationGestureAction.STATE_ACTION)
            end.set(TranslationGestureAction.DISABLED)
            repository.saveJob(fixture)
            withActivity(TranslationActivity.intent(context)) {
                await("Populated queue renders the real configured swipe row") { text(fixture.chapterTitle) }
                assertTrue(
                    "Queue row exposes the current state action",
                    nodes().any { node ->
                        node.isLongClickable && labelled(node, fixture.chapterTitle) &&
                            node.actionList.isNotEmpty()
                    },
                )
                click("Settings")
                setEditable("Search settings", "Translation work")
                val settingsWindowId = instrumentation.uiAutomation.rootInActiveWindow?.windowId
                click("Translation work · swipe start")
                await("Gesture dialog owns a focused accessibility window") {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    root?.windowId != settingsWindowId && root?.window?.isFocused == true &&
                        text(
                            "Start and end mirror with the app language. " +
                                "Swipe actions are disabled during selection.",
                        )
                }
                click("Related logs")
                await("Gesture choice saves to the native app graph preference store") {
                    start.get() == TranslationGestureAction.LOGS
                }
                click("Queue")
                await("Queue reflects the saved gesture through its accessible action") {
                    nodes().any { node ->
                        node.isLongClickable && labelled(node, fixture.chapterTitle) &&
                            node.actionList.any { it.label?.toString() == "Related logs" }
                    }
                }
                assertEquals(fixture, repository.jobs().single { it.id == fixture.id })
                assertEquals(before, repository.jobs().filterNot { it.id == fixture.id })
                assertTrue(repository.batches(fixture.id).isEmpty())
                assertTrue(repository.results(fixture.id).isEmpty())
                assertTrue(repository.operationPage(jobId = fixture.id).isEmpty())
                assertTrue(repository.usagePage(0, Long.MAX_VALUE, jobId = fixture.id).isEmpty())
                report(
                    "gesture-graph-passed",
                    "Populated queue and global gesture preferences opened; saved action reflected in queue; " +
                        "paused file-import fixture remained unchanged without provider work",
                )
            }
        } finally {
            preserved.forEach { (preference, wasSet, value) ->
                if (wasSet) preference.set(value) else preference.delete()
            }
            repository.removeJob(fixture.id)
            assertEquals(before, repository.jobs())
        }
    }

    private fun setEditable(label: String, value: String) {
        reveal(label) { editors(label).isNotEmpty() }
        val editor = editors(label).single()
        assertTrue(
            performTargetAction(
                editor,
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                },
            ),
        )
    }

    private suspend fun withActivity(intent: Intent, block: suspend () -> Unit) {
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var activity: TranslationActivity? = null
        try {
            activity =
                instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TranslationActivity
            await("Translator owns focused window") {
                automation.rootInActiveWindow?.packageName?.toString() ==
                    context.packageName
            }
            block()
        } finally {
            activity?.let { value -> instrumentation.runOnMainSync { value.finish() } }
            instrumentation.waitForIdleSync()
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        }
    }

    /** Null is normal while a dialog attaches/detaches; another app is never a valid input target. */
    private fun focusedTargetRoot(): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if (!root.refresh()) return null
        val packageName = root.packageName?.toString() ?: return null
        assertEquals("Refuse to interact with a different app", context.packageName, packageName)
        return root.takeIf { it.window?.isFocused == true }
    }

    private fun awaitFocusedTargetRoot(label: String, deadline: Long): AccessibilityNodeInfo {
        do {
            focusedTargetRoot()?.let { return it }
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("No focused ${context.packageName} accessibility root while revealing '$label'")
    }

    private fun performTargetAction(node: AccessibilityNodeInfo, action: Int, arguments: Bundle? = null): Boolean {
        val root = focusedTargetRoot() ?: return false
        if (!node.refresh() || node.packageName?.toString() != context.packageName ||
            node.windowId != root.windowId || node.window?.isFocused != true
        ) {
            return false
        }
        return node.performAction(action, arguments)
    }

    private fun nodes(root: AccessibilityNodeInfo? = focusedTargetRoot()): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || size >= 512) return
            add(node)
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(root)
    }

    private fun labelled(node: AccessibilityNodeInfo, label: String): Boolean =
        node.text?.toString() == label || node.contentDescription?.toString() == label ||
            node.hintText?.toString() == label || (0 until node.childCount).any {
                node.getChild(it)?.let { child -> labelled(child, label) } == true
            }

    private fun text(value: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == value }
    private fun actions(label: String) = nodes().filter {
        it.isVisibleToUser && it.isEnabled && it.isClickable &&
            labelled(it, label)
    }
    private fun buttonNodes(label: String) = nodes().filter {
        it.isVisibleToUser && (it.isClickable || it.className?.toString() == "android.widget.Button") &&
            labelled(it, label)
    }
    private fun actionNode(label: String): AccessibilityNodeInfo {
        reveal(label) { buttonNodes(label).isNotEmpty() }
        return buttonNodes(label).first()
    }
    private fun click(label: String) {
        reveal(label) { actions(label).isNotEmpty() }
        val deadline = SystemClock.uptimeMillis() + 5_000
        var attempts = 0
        var observation = "No actionable node"
        do {
            instrumentation.waitForIdleSync()
            // Dialog semantics can be published before their window owns focus. Reacquire after
            // a rejected action; never repeat an accepted click that may already have changed UI.
            val node = actions(label).firstOrNull()
            if (node != null) {
                val fresh = node.refresh()
                val focused = node.window?.isFocused == true
                val bounds = Rect().also(node::getBoundsInScreen)
                observation = "window=${node.windowId} fresh=$fresh focused=$focused bounds=$bounds " +
                    "visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable}"
                if (fresh && focused && node.isVisibleToUser && node.isEnabled && node.isClickable &&
                    labelled(node, label) &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                ) {
                    attempts++
                    if (performTargetAction(node, AccessibilityNodeInfo.ACTION_CLICK)) return
                }
            }
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("Click '$label' rejected after $attempts attempts within five seconds: $observation", false)
    }

    /** Scroll only the focused app's observed vertical surface, with a fixed bound on attempts. */
    private fun reveal(label: String, visible: () -> Boolean) {
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + 10_000
        repeat(16) { attempt ->
            val root = awaitFocusedTargetRoot(label, minOf(deadline, SystemClock.uptimeMillis() + 5_000))
            if (visible()) return
            val observed = nodes(root)
            val named = observed.lastOrNull {
                labelled(it, label) && it.actionList.any { action ->
                    action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                }
            }
            if (named == null ||
                !performTargetAction(named, AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            ) {
                val direction = if (attempt <
                    8
                ) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                val verticalDirection = if (attempt <
                    8
                ) {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                } else {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                }
                val candidates = observed.filter {
                    it.isVisibleToUser && it.isScrollable &&
                        it.actionList.any { action ->
                            action.id == direction
                        }
                }.sortedByDescending { node -> Rect().also(node::getBoundsInScreen).height() }
                val vertical = candidates.firstOrNull { node ->
                    node.actionList.any { action ->
                        action.id == verticalDirection
                    }
                } ?: candidates.firstOrNull()
                if (vertical == null || !performTargetAction(vertical, direction)) {
                    // A newly composed screen may not have published its lazy children yet.
                    SystemClock.sleep(100)
                }
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        assertTrue("Visible native control after bounded accessibility scrolling: $label", visible())
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(40)
        assertTrue(label, predicate())
    }
    private fun report(stage: String, evidence: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("structured_native_flow", "$stage: $evidence") })
    }
}

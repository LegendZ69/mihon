package app.mihon.validation.framework;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.os.PowerManager;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.system.Os;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Self instrumentation: the installed reader remains in its own process and keeps its own ABI. */
public final class ReaderUiInstrumentation extends Instrumentation {
    private static final String HARNESS = "app.mihon.validation.framework";
    private static final String SUBJECT = "app.mihon";
    private static final String TITLE = "Controlled Translation Validation";
    private static final String FIRST = "001 - Five-page modes";
    private static final String SECOND = "002 - Twelve-page corpus";
    private static final long STORAGE_LIMIT = 128L * 1024 * 1024;
    private final JSONArray actions = new JSONArray();
    private final JSONObject report = new JSONObject();
    private Bundle arguments;
    private UiAutomation ui;
    private File output;
    private String originalChapter;
    private float originalPage;
    private int toggles;
    private int screenshotIndex;
    private long storedBytes;
    private boolean authorizedReader;
    private boolean exercisePlan;
    private boolean stylePlan;
    private boolean framePlan;
    private boolean qualityPlan;
    private boolean lifecyclePlan;
    private int lifecyclePid;
    private String lifecycleBaselineHash;
    private final JSONArray lifecycleSamples = new JSONArray();
    private String initialFrameMode;
    private String frameBackend;
    private FrameAnchorPolicy frameAnchor = FrameAnchorPolicy.parse("", null, "0");
    private String originalFrameHash;
    private String translatedFrameHash;
    private String initialFrameHash;
    private String entryFrameHash;
    private final JSONArray frameWindows = new JSONArray();
    private boolean styleScopeBound;
    private final ReaderSettingsPolicy.StyleMutation styleMutation = new ReaderSettingsPolicy.StyleMutation();
    private String originalFont;
    private String originalOpacity;
    private String lastViewportHash;
    private String styleBaselineHash;
    private boolean rotationChanged;
    private int originalRotation;
    private int originalAutoRotation;
    private int originalUserRotation;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : args;
        start();
    }

    @Override public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_CANCELED;
        try {
            require("true".equals(arguments.getString("acceptance")), "Explicit acceptance=true is required");
            require(HARNESS.equals(getTargetContext().getPackageName()), "Harness must instrument its own package");
            PackageInfo subject = getTargetContext().getPackageManager().getPackageInfo(SUBJECT,
                Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            require((subject.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0,
                "The selected subject must be the non-debuggable app.mihon build");
            JSONArray certificates = new JSONArray();
            Signature[] signatures = Build.VERSION.SDK_INT >= 28 ? subject.signingInfo.getApkContentsSigners() : subject.signatures;
            for (Signature signature : signatures) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
                StringBuilder hash = new StringBuilder();
                for (byte value : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                certificates.put(hash.toString());
            }
            report.put("subject_version", subject.versionName).put("subject_version_code",
                Build.VERSION.SDK_INT >= 28 ? subject.getLongVersionCode() : subject.versionCode)
                .put("subject_certificate_sha256", certificates).put("subject_debuggable", false);
            String plan = arguments.getString("plan", "inspect");
            require(plan.equals("inspect") || plan.equals("exercise") || plan.equals("styleCycles") || plan.equals("framePairs") || plan.equals("qualityReview") || plan.equals("readerLifecycle"), "Unknown plan");
            exercisePlan = plan.equals("exercise");
            stylePlan = plan.equals("styleCycles");
            framePlan = plan.equals("framePairs");
            qualityPlan = plan.equals("qualityReview");
            lifecyclePlan = plan.equals("readerLifecycle");
            int cycles = Integer.parseInt(arguments.getString("cycles", "10"));
            require(cycles >= 1 && cycles <= 10, "Cycles must be 1..10");
            if (lifecyclePlan) require(cycles == ReaderLifecyclePolicy.CYCLES, "Reader lifecycle requires exactly ten cycles");
            if (exercisePlan || stylePlan || framePlan || qualityPlan || lifecyclePlan) {
                require("true".equals(arguments.getString("automaticTranslationDisabled")),
                    "Host must first verify automatic translation is disabled for this controlled series");
            }
            if (stylePlan) {
                require("true".equals(arguments.getString("chaptersAheadZero")), "Host must verify chapters ahead is zero");
                require("true".equals(arguments.getString("seriesOverrideExists")), "An existing controlled-series override is required");
            }
            int framePairs = Integer.parseInt(arguments.getString("pairs", "3"));
            if (framePlan || lifecyclePlan) {
                require(framePairs >= 1 && framePairs <= 3, "Frame pairs must be 1..3");
                require("true".equals(arguments.getString("chaptersAheadZero")), "Host must verify chapters ahead is zero");
                require("true".equals(arguments.getString("cachedTranslationVisible")), "Host must verify a visible cached translation");
                initialFrameMode = arguments.getString("initialMode");
                require("translated".equals(initialFrameMode) || "original".equals(initialFrameMode), "Host must attest initialMode=translated|original");
                frameBackend = arguments.getString("backend");
                require("webgpu".equals(frameBackend) || "classic".equals(frameBackend), "Explicit observed backend is required");
            }
            frameAnchor = FrameAnchorPolicy.parse(plan, frameBackend, arguments.getString("frameAnchorPixels"),
                Boolean.parseBoolean(arguments.getString("frameAnchorPrimed", "false")));
            output = new File(getTargetContext().getFilesDir(), "reader-validation/" + UUID.randomUUID());
            require(output.mkdirs(), "Cannot create private evidence directory");
            Os.chmod(output.getParent(), 0700);
            Os.chmod(output.getPath(), 0700);
            ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            require(ui != null, "Cannot connect UiAutomation");
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            ui.setServiceInfo(info);
            if (qualityPlan) {
                runQualityReview();
                resultCode = Activity.RESULT_OK;
                return;
            }
            originalChapter = selectedChapter(foreground());
            require(originalChapter != null, "Open the controlled fixture reader with its toolbar visible");
            reader(originalChapter, false);
            originalPage = framePlan || lifecyclePlan ? FrameViewportReset.pageNumber(pageProgress()) : pageProgress();
            frameAnchor.requireTarget(originalChapter, (int)originalPage);
            authorizedReader = true;
            originalRotation = rotation();
            originalAutoRotation = Settings.System.getInt(getTargetContext().getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0);
            originalUserRotation = Settings.System.getInt(getTargetContext().getContentResolver(),
                Settings.System.USER_ROTATION, originalRotation);
            report.put("schema", 1).put("subject_package", SUBJECT).put("harness_package", HARNESS)
                .put("plan", plan).put("fixture_title", TITLE).put("initial_chapter", originalChapter)
                .put("initial_page", originalPage).put("started_wall_ms", System.currentTimeMillis())
                .put("automatic_translation_off", (exercisePlan || stylePlan || framePlan || lifecyclePlan) ? "host_verified_prerequisite" : "not_required")
                .put("scope", "Framework UI actions against a separate already-running minified app; no app ABI or storage access")
                .put("meaning_review", "not_assessed").put("font_style_changes", "not_exercised")
                .put("comparison_cycles", 0).put("chapter_cycles", 0)
                .put("visual_assessment", "Screenshots and viewport pixel hashes require review; action completion is not rendering or meaning acceptance");
            if (framePlan) {
                report.put("viewport_reset_policy", "adjacent_cached_page_then_selected_page")
                    .put("restoration_target", "canonical_page_start").put("frame_restore_required", true)
                    .put("entry_offset_restoration", "not_claimed: setup normalizes the selected page before the measured baseline")
                    .put("cache_scope", "Host selected the downloaded controlled chapter; UI verifies adjacent/selected page identity, not private cache files");
                if (frameAnchor.enabled()) {
                    report.put("viewport_reset_policy", "adjacent_cached_page_then_" + frameAnchor.protocol())
                        .put("restoration_target", "repeatable_interior_anchor")
                        .put("frame_anchor", frameAnchorDescriptor())
                        .put("anchor_displacement_scope", "100-pixel motion body; optional separately declared priming input. Finger travel is not document displacement. Exact repeated rendered pixels define acceptance.");
                }
                checkpoint("frame_entry_before_canonical_setup", false, true);
                entryFrameHash = lastViewportHash;
                report.put("entry_viewport_sha256", entryFrameHash);
                resetFrameBaseline("setup_first");
                checkpoint("initial", true);
                initialFrameHash = lastViewportHash;
                resetFrameBaseline("setup_repeatability");
                checkpoint("frame_setup_repeatability", false, true);
                require(initialFrameHash.equals(lastViewportHash), "Canonical page-start reset was not repeatable before capture");
                report.put("canonical_setup_verified", true).put("canonical_baseline_sha256", initialFrameHash)
                    .put("entry_pixels_match_canonical", entryFrameHash.equals(initialFrameHash));
                runFramePairs(framePairs);
            } else {
                checkpoint("initial", true);
            }
            if (stylePlan) runStyleCycles(cycles);
            if (lifecyclePlan) runReaderLifecycle(cycles);
            if (plan.equals("exercise")) {
                for (int i = 0; i < cycles; i++) {
                    toggleOriginal();
                    checkpoint("comparison_" + (i + 1) + "_opposite", true);
                    toggleOriginal();
                    checkpoint("comparison_" + (i + 1) + "_restored", true);
                    report.put("comparison_cycles", i + 1);
                }
                if ("true".equals(arguments.getString("chapterCycles", "false"))) {
                    for (int i = 0; i < cycles; i++) {
                        String other = originalChapter.equals(FIRST) ? SECOND : FIRST;
                        chapter(originalChapter, other);
                        checkpoint("chapter_" + (i + 1) + "_other", i == 0 || i == cycles - 1);
                        chapter(other, originalChapter);
                        setPage(originalPage);
                        checkpoint("chapter_" + (i + 1) + "_restored", i == 0 || i == cycles - 1);
                        report.put("chapter_cycles", i + 1);
                    }
                }
                if ("true".equals(arguments.getString("gestures", "true"))) {
                    pinch(0.12f, 0.30f);
                    checkpoint("pinch_out", true);
                    pan(0.48f, 0.50f, 0.54f, 0.53f);
                    checkpoint("pan", true);
                    pan(0.54f, 0.53f, 0.48f, 0.50f);
                    pinch(0.30f, 0.12f);
                    setPage(originalPage);
                    checkpoint("gesture_return", true);
                }
                if ("true".equals(arguments.getString("rotation", "false"))) {
                    rotationChanged = true;
                    require(ui.setRotation((originalRotation + 1) % 4), "Rotation request rejected");
                    settle();
                    reader(originalChapter, true);
                    require(rotation() != originalRotation, "Requested rotation was not observed; check reader orientation policy");
                    checkpoint("rotated", true);
                    restoreRotation();
                    reader(originalChapter, true);
                    checkpoint("rotation_restored", true);
                }
            }
            report.put("status", "passed_action_assertions");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable failure) {
            try {
                if (qualityPlan && (report.optBoolean("review_click_pending", false) || report.optInt("review_control_clicks", 0) > 0)) {
                    report.put("request_outcome", "uncertain after UI failure; retain reservation and verify provider logs before another dispatch");
                }
                report.put("status", "failed").put("failure_class", failure.getClass().getName())
                    .put("failure", String.valueOf(failure.getMessage()));
            } catch (Exception ignored) { }
        } finally {
            try {
                if (authorizedReader && stylePlan && ui != null) {
                    restoreStyleAfterFailure();
                } else if (authorizedReader && lifecyclePlan && ui != null) {
                    restoreReaderLifecycle();
                } else if (authorizedReader && (exercisePlan || framePlan) && ui != null) {
                    if (framePlan) require(!report.optBoolean("comparison_pending_toggle", false), "Interrupted toggle needs explicit UI inspection before restoration");
                    if (framePlan) reader(originalChapter, true);
                    if (toggles % 2 != 0) toggleOriginal();
                    if (rotationChanged) restoreRotation();
                    String current = selectedChapter(foreground());
                    if (current != null && !current.equals(originalChapter)) chapter(current, originalChapter);
                    reader(originalChapter, true);
                    if (framePlan) resetFrameBaseline("finally"); else setPage(originalPage);
                    report.put("restoration", framePlan
                        ? (frameAnchor.enabled()
                            ? "comparison parity and selected chapter/page restored to the declared repeatable interior anchor; arbitrary entry offset not claimed"
                            : "comparison parity and selected chapter/page restored to the declared canonical page-start baseline; arbitrary entry offset not claimed")
                        : "comparison parity, initial chapter/page and requested rotation restored")
                        .put("toggle_count", toggles);
                    if (framePlan) {
                        checkpoint("frame_finally_restored", true);
                        require(initialFrameHash != null && report.optBoolean("canonical_setup_verified", false) &&
                            initialFrameHash.equals(lastViewportHash), "Frame plan did not restore its verified canonical page-start pixels");
                        report.put("frame_restore_required", false);
                    }
                } else if (qualityPlan) {
                    report.put("restoration", "No source, style or chapter changes; selected-page review result retained");
                } else if (!exercisePlan && !stylePlan && !framePlan && !lifecyclePlan) {
                    report.put("restoration", "not_required: inspect dispatched no input");
                }
            } catch (Throwable failure) {
                resultCode = Activity.RESULT_CANCELED;
                try { report.put("status", "failed").put("restoration", "incomplete: " + failure.getMessage()); }
                catch (Exception ignored) { }
            }
            try {
                if (stylePlan) report.put("style_restore_required", styleMutation.requiresRestoration());
                report.put("ended_wall_ms", System.currentTimeMillis()).put("actions", actions);
                persist();
                result.putString("report", output == null ? "unavailable" : output.getPath() + "/report.json");
                result.putString("stream", report.toString(2) + "\n");
            } catch (Exception failure) {
                resultCode = Activity.RESULT_CANCELED;
                result.putString("stream", "Evidence persistence failed: " + failure.getClass().getName());
            }
            finish(resultCode, result);
        }
    }

    /** This separately authorized plan is the only harness path that may dispatch provider work. */
    private void runQualityReview() throws Exception {
        require("true".equals(arguments.getString("allowProviderDispatch")),
            "qualityReview requires explicit allowProviderDispatch=true and a host-reserved reference");
        require("true".equals(arguments.getString("chaptersAheadZero")), "Host must verify chapters ahead is zero");
        String dispatchKind = arguments.getString("dispatchKind", "");
        String reference = arguments.getString("dispatchReference", "");
        if (dispatchKind.equals("reserved_live")) {
            require(reference.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
                "Live review requires the exact UUID of a pre-reserved spend-ledger entry");
        } else {
            require(dispatchKind.equals("local_fixture") && reference.matches("fixture:[A-Za-z0-9_.-]{1,100}"),
                "Local review requires dispatchKind=local_fixture and dispatchReference=fixture:<run>");
        }
        String imageId = arguments.getString("imageId", "");
        String pageLabel = arguments.getString("pageLabel", "");
        String chapterTitle = arguments.getString("chapterTitle", "");
        require(imageId.matches("[A-Za-z0-9_.:-]{1,160}"), "Explicit imageId is required");
        require(pageLabel.matches("Image [1-9][0-9]{0,4}"), "Explicit displayed pageLabel=Image N is required");
        require(chapterTitle.length() > 0 && chapterTitle.length() <= 120 && !chapterTitle.contains("\n"),
            "Explicit displayed chapterTitle is required");
        int timeout = Integer.parseInt(arguments.getString("reviewTimeoutSeconds", "180"));
        require(timeout >= 10 && timeout <= 900, "Review timeout must be 10..900 seconds");
        report.put("schema", 1).put("subject_package", SUBJECT).put("harness_package", HARNESS)
            .put("plan", "qualityReview").put("fixture_title", TITLE).put("image_id", imageId)
            .put("page_label", pageLabel).put("chapter_title", chapterTitle)
            .put("started_wall_ms", System.currentTimeMillis()).put("dispatch_kind", dispatchKind)
            .put("dispatch_reference", reference).put("automatic_translation_off", "host_verified_prerequisite")
            .put("chapters_ahead_zero", "host_verified_prerequisite").put("review_control_clicks", 0)
            .put("chapter_translation_controls_invoked", 0).put("meaning_review", "human_pending")
            .put("spend_guard", "Host reservation attestation only; this harness cannot enforce provider billing or verify the private ledger")
            .put("provider_dispatch_count", JSONObject.NULL)
            .put("findings_capture", "Visible selected-card screenshots only; full findings require the app review log export")
            .put("scope", "One explicit selected-page AI review through the already-open fixture inspector; API attempts require independent logs")
            .put("visual_assessment", "Screenshots and AI findings require review; observed completion is not human meaning approval");
        AccessibilityNodeInfo beforeRoot = qualityInspector(imageId, pageLabel, chapterTitle);
        require(find(beforeRoot, "Save manual edits", false) == null, "Save or discard manual edits before review");
        String beforeState = qualityState(beforeRoot);
        require(!beforeState.equals("queued") && !beforeState.equals("running") && !beforeState.equals("paused"),
            "Start from an unreviewed or terminal page; this plan does not resume existing review work");
        String beforeId = prefixedText(beforeRoot, "Review ID: ");
        require(beforeState.equals("not reviewed") || beforeId != null,
            "Keep the full quality card visible, including its Review ID");
        qualityCheckpoint("quality_before", imageId, pageLabel, chapterTitle, true);
        AccessibilityNodeInfo node = find(qualityInspector(imageId, pageLabel, chapterTitle), "Review selected page", false);
        require(node != null && node.isEnabled(), "Show the enabled Review selected page control");
        AccessibilityNodeInfo clickable = node;
        for (int i = 0; i < 3 && clickable != null && !clickable.isClickable(); i++) clickable = clickable.getParent();
        require(clickable != null && clickable.isClickable() && clickable.isEnabled(), "Selected-page review has no enabled click action");
        report.put("review_click_pending", true);
        action("selected_review_intent", new JSONObject().put("image_id", imageId).put("prior_review_id", beforeId == null ? JSONObject.NULL : beforeId));
        qualityInspector(imageId, pageLabel, chapterTitle);
        require(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Selected-page review click rejected");
        report.put("review_control_clicks", 1).put("review_click_pending", false);
        action("selected_review_clicked", new JSONObject().put("image_id", imageId));
        long deadline = SystemClock.uptimeMillis() + timeout * 1000L;
        long heartbeat = 0;
        String lastObserved = "";
        while (SystemClock.uptimeMillis() <= deadline) {
            AccessibilityNodeInfo root = qualityInspector(imageId, pageLabel, chapterTitle);
            String state = qualityState(root);
            String id = prefixedText(root, "Review ID: ");
            String observation = String.valueOf(id) + ":" + state;
            if (!observation.equals(lastObserved)) {
                action("quality_state_observed", new JSONObject().put("review_id", id == null ? JSONObject.NULL : id).put("state", state));
                lastObserved = observation;
            }
            if (id != null && !id.equals(beforeId) && !state.equals("queued") && !state.equals("running")) {
                require(state.equals("passed") || state.equals("repaired") || state.equals("needs review") ||
                    state.equals("incomplete") || state.equals("superseded") || state.equals("undone") || state.equals("paused"),
                    "Unknown review state; preserve evidence and inspect manually");
                report.put("observed_review_id", id).put("observed_ai_state", state)
                    .put("status", "completed_review_ui_observation")
                    .put("review_needs_attention", !(state.equals("passed") || state.equals("repaired")))
                    .put("human_approval", false);
                qualityCheckpoint("quality_after", imageId, pageLabel, chapterTitle, true);
                return;
            }
            if (SystemClock.uptimeMillis() >= heartbeat) {
                Bundle progress = new Bundle();
                progress.putString("stream", "Selected-page review observation: " + state + " (no additional click)\n");
                sendStatus(0, progress);
                heartbeat = SystemClock.uptimeMillis() + 15000;
            }
            SystemClock.sleep(200);
        }
        report.put("request_outcome", "uncertain; retain reservation and inspect provider logs before another dispatch");
        qualityCheckpoint("quality_timeout", imageId, pageLabel, chapterTitle, true);
        throw new IllegalStateException("Review observation timed out; no retry or chapter action was sent");
    }

    private AccessibilityNodeInfo qualityInspector(String imageId, String pageLabel, String chapterTitle) {
        require(qualityPlan, "Inspector access is limited to the qualityReview plan");
        AccessibilityNodeInfo root = foreground();
        require(find(root, "OCR inspector", false) != null && find(root, TITLE, false) != null &&
            find(root, chapterTitle, false) != null && find(root, "Image ID: " + imageId, false) != null &&
            matchingPageLabels(root, pageLabel) == 1, "Exact fixture inspector, chapter or selected image identity changed");
        require(find(root, "Human passage review pending. An AI pass is not human approval.", false) != null,
            "Keep the selected page's AI quality card visible before and during observation");
        return root;
    }

    private int matchingPageLabels(AccessibilityNodeInfo node, String expected) {
        if (node == null) return 0;
        int matches = 0;
        if (node.isVisibleToUser() && node.getText() != null &&
                ReaderSettingsPolicy.matchesPageLabel(node.getText().toString(), expected)) matches++;
        for (int i = 0; i < node.getChildCount(); i++) matches += matchingPageLabels(node.getChild(i), expected);
        return matches;
    }

    private String qualityState(AccessibilityNodeInfo root) {
        String state = prefixedText(root, "AI quality assessment · ");
        require(state != null, "Quality assessment state is not visible");
        return state;
    }

    private String prefixedText(AccessibilityNodeInfo node, String prefix) {
        if (node == null) return null;
        CharSequence value = node.getText();
        if (node.isVisibleToUser() && value != null && value.toString().startsWith(prefix)) {
            return value.toString().substring(prefix.length());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String match = prefixedText(node.getChild(i), prefix);
            if (match != null) return match;
        }
        return null;
    }

    private void qualityCheckpoint(String label, String imageId, String pageLabel, String chapterTitle, boolean screenshot) throws Exception {
        AccessibilityNodeInfo root = qualityInspector(imageId, pageLabel, chapterTitle);
        JSONObject details = new JSONObject().put("image_id", imageId).put("ai_state", qualityState(root));
        String reviewId = prefixedText(root, "Review ID: ");
        String revision = prefixedText(root, "Source revision: ");
        details.put("review_id", reviewId == null ? JSONObject.NULL : reviewId)
            .put("source_revision", revision == null ? JSONObject.NULL : revision);
        if (screenshot) {
            require(screenshotIndex < 48, "Screenshot count limit reached");
            Bitmap capture = ui.takeScreenshot();
            require(capture != null, "Inspector screenshot unavailable");
            try {
                qualityInspector(imageId, pageLabel, chapterTitle);
                File file = new File(output, String.format(java.util.Locale.ROOT, "%02d-%s.png", screenshotIndex++, label));
                try (FileOutputStream stream = new FileOutputStream(file)) {
                    require(capture.compress(Bitmap.CompressFormat.PNG, 100, stream), "Inspector screenshot compression failed");
                }
                Os.chmod(file.getPath(), 0600);
                storedBytes += file.length();
                require(storedBytes <= STORAGE_LIMIT, "Private evidence storage limit exceeded");
                details.put("screenshot", file.getName());
            } finally { capture.recycle(); }
        }
        action(label, details);
    }

    private void runFramePairs(int pairs) throws Exception {
        report.put("frame_pairs_requested", pairs).put("frame_pairs_completed", 0)
            .put("initial_mode", initialFrameMode).put("mode_identity", "host-attested initial mode plus observed toggle parity")
            .put("backend", frameBackend).put("frame_windows", frameWindows).put("frame_restore_required", true)
            .put("frame_timing_scope", "Device-timed input windows; actual frame deadlines require matching Perfetto slices")
            .put("same_page_limit", "Page identity checked after input; natural pixel drift recorded separately; canonical resets occur outside timed windows; hidden controls cannot prove every intermediate scroll position")
            .put("chapters_ahead_zero", "host_verified_prerequisite");
        action("frame_plan_preflight", new JSONObject().put("pairs", pairs));
        for (int pair = 1; pair <= pairs; pair++) {
            String first = pair % 2 == 1 ? "translated" : "original";
            runFrameWindow(pair, first);
            runFrameWindow(pair, first.equals("translated") ? "original" : "translated");
            report.put("frame_pairs_completed", pair);
            action("frame_pair_completed", new JSONObject().put("pair", pair));
        }
    }

    private String currentFrameMode() {
        return toggles % 2 == 0 ? initialFrameMode : initialFrameMode.equals("translated") ? "original" : "translated";
    }

    private boolean hasClass(AccessibilityNodeInfo node, String name) {
        if (node == null) return false;
        if (node.isVisibleToUser() && name.contentEquals(node.getClassName() == null ? "" : node.getClassName())) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (hasClass(node.getChild(i), name)) return true;
        return false;
    }

    private void runReaderLifecycle(int cycles) throws Exception {
        verifyLifecycleBackend();
        report.put("reader_lifecycle_cycles", 0).put("reader_lifecycle_restore_required", true)
            .put("backend", frameBackend).put("initial_mode", initialFrameMode)
            .put("lifecycle_samples", lifecycleSamples)
            .put("lifecycle_scope", "Exactly ten Back exits to the controlled local series and chapter-row reopens; distinct resumed ActivityRecord tokens in one PID, not process restarts or callback instrumentation")
            .put("memory_scope", "Discrete process meminfo endpoints; not allocation totals, peaks, GPU inventory or a leak-free guarantee")
            .put("overlay_scope", "No comparison toggle or preference write; exact canonical viewport pixels must return")
            .put("entry_offset_restoration", "Page is retained; arbitrary entry offset is normalized to page start");
        resetFrameViewport("lifecycle_setup");
        checkpoint("lifecycle_baseline", true);
        lifecycleBaselineHash = lastViewportHash;
        resetFrameViewport("lifecycle_setup_repeatability");
        checkpoint("lifecycle_baseline_repeatability", false, true);
        require(lifecycleBaselineHash.equals(lastViewportHash), "Lifecycle canonical baseline is not repeatable");
        ReaderLifecyclePolicy.run(cycles, new ReaderLifecyclePolicy.Driver() {
            @Override public ReaderLifecyclePolicy.State observe(String phase, int cycle) throws Exception {
                return lifecycleObservation(phase, cycle);
            }
            @Override public void exitReader() throws Exception {
                reader(originalChapter, false);
                require(lifecycleActivity().getString("component").endsWith(".ui.reader.ReaderActivity"),
                    "Back is restricted to the controlled ReaderActivity");
                action("reader_lifecycle_exit_intent", new JSONObject().put("chapter", originalChapter));
                require(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK),
                    "Reader Back action was rejected");
                settle();
                require(UiStateTransition.afterSingleAction(() -> { },
                    () -> find(foreground(), SUBJECT + ":id/viewer_container", true) == null,
                    SystemClock::elapsedRealtime, SystemClock::sleep, 5000), "Reader viewport remained after Back");
                lifecycleChapterRow();
            }
            @Override public void reopenChapter() throws Exception { reopenLifecycleChapter(); }
            @Override public void verifyRestoredReader(int cycle) throws Exception {
                verifyLifecycleBackend();
                resetFrameViewport("lifecycle_" + cycle);
                checkpoint("lifecycle_" + cycle + "_restored", true);
                require(lifecycleBaselineHash.equals(lastViewportHash), "Reader reopen changed canonical overlay pixels");
                lifecycleObservation("restored", cycle);
            }
            @Override public void completed(int cycle) throws Exception {
                report.put("reader_lifecycle_cycles", cycle);
                action("reader_lifecycle_completed", new JSONObject().put("cycle", cycle));
            }
        });
    }

    private void verifyLifecycleBackend() throws Exception {
        reader(originalChapter, false);
        require(rotation() == originalRotation && contextInteractive(), "Lifecycle requires the original interactive orientation");
        require(hasClass(foreground(), "androidx.recyclerview.widget.RecyclerView") == frameBackend.equals("classic"),
            "Reader structure differs from the declared scrolling backend");
    }

    private AccessibilityNodeInfo lifecycleChapterRow() throws Exception {
        AccessibilityNodeInfo root = foreground();
        require(find(root, SUBJECT + ":id/viewer_container", true) == null &&
            find(root, TITLE, false) != null && find(root, "Local source", false) != null &&
            find(root, FIRST, false) != null && find(root, SECOND, false) != null,
            "Expected the controlled local series with both exact chapter rows; no navigation fallback");
        require(lifecycleActivity().getString("component").endsWith(".ui.main.MainActivity"),
            "Chapter reopening is restricted to the main series screen");
        require(countLabel(root, originalChapter) == 1, "Initial chapter label must be unique");
        AccessibilityNodeInfo row = find(root, originalChapter, false);
        for (int i = 0; i < 3 && row != null && !row.isClickable(); i++) row = row.getParent();
        String other = originalChapter.equals(FIRST) ? SECOND : FIRST;
        require(row != null && row.isVisibleToUser() && row.isEnabled() && row.isClickable() &&
            row.isLongClickable() && !row.isSelected() && countLabel(row, originalChapter) == 1 &&
            countLabel(row, other) == 0, "Exact chapter row has no isolated unselected click action");
        return row;
    }

    private int countLabel(AccessibilityNodeInfo node, String label) {
        if (node == null) return 0;
        int count = node.isVisibleToUser() && (label.contentEquals(node.getText() == null ? "" : node.getText()) ||
            label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) ? 1 : 0;
        for (int i = 0; i < node.getChildCount(); i++) count += countLabel(node.getChild(i), label);
        return count;
    }

    private void reopenLifecycleChapter() throws Exception {
        AccessibilityNodeInfo row = lifecycleChapterRow();
        Rect bounds = new Rect();
        row.getBoundsInScreen(bounds);
        action("reader_lifecycle_reopen_intent", new JSONObject().put("chapter", originalChapter)
            .put("observed_row_bounds", bounds.toShortString()));
        require(row.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Chapter row click was rejected");
        settle();
        require(UiStateTransition.afterSingleAction(() -> { },
            () -> find(foreground(), SUBJECT + ":id/viewer_container", true) != null,
            SystemClock::elapsedRealtime, SystemClock::sleep, 5000), "Chapter click did not open a reader viewport");
        reader(originalChapter, true);
    }

    private void restoreReaderLifecycle() throws Exception {
        if (!report.optBoolean("reader_lifecycle_restore_required", false)) {
            report.put("restoration", "not_required: lifecycle preflight dispatched no input");
            return;
        }
        AccessibilityNodeInfo root = foreground();
        if (find(root, SUBJECT + ":id/viewer_container", true) == null) reopenLifecycleChapter();
        reader(originalChapter, true);
        verifyLifecycleBackend();
        resetFrameViewport("lifecycle_finally");
        checkpoint("lifecycle_finally_restored", true);
        require(lifecycleBaselineHash != null && lifecycleBaselineHash.equals(lastViewportHash),
            "Lifecycle restoration did not match the recorded canonical pixels");
        report.put("reader_lifecycle_restore_required", false)
            .put("restoration", "Initial chapter/page/backend and canonical pixels restored; no overlay preference mutation; arbitrary entry offset not claimed");
    }

    private ReaderLifecyclePolicy.State lifecycleObservation(String phase, int cycle) throws Exception {
        boolean exited = phase.equals("exited");
        if (exited) lifecycleChapterRow(); else verifyLifecycleBackend();
        JSONObject activity = lifecycleActivity();
        String expected = exited ? ".ui.main.MainActivity" : ".ui.reader.ReaderActivity";
        require(activity.getString("component").endsWith(expected), "Unexpected resumed Activity during lifecycle observation");
        String rawPid = lifecycleShell("pidof " + SUBJECT).trim();
        require(rawPid.matches("[1-9][0-9]*"), "A unique subject PID is required for lifecycle attribution");
        int pid = Integer.parseInt(rawPid);
        if (phase.equals("initial")) lifecyclePid = pid;
        require(pid == lifecyclePid, "Subject process changed during a lifecycle endpoint");
        JSONObject sample = new JSONObject().put("phase", phase).put("cycle", cycle)
            .put("elapsed_ns", SystemClock.elapsedRealtimeNanos()).put("pid", pid).put("activity", activity);
        try {
            String raw = lifecycleShell("dumpsys -t 5 meminfo " + pid);
            JSONObject memory = new JSONObject();
            Matcher values = Pattern.compile("(?m)(Java Heap|Native Heap|Graphics|TOTAL PSS|TOTAL RSS):\\s*([0-9]+)").matcher(raw);
            while (values.find()) memory.put(values.group(1).replace(' ', '_').toLowerCase(java.util.Locale.ROOT) + "_kb", Long.parseLong(values.group(2)));
            sample.put("memory", memory).put("memory_available", memory.length() > 0);
        } catch (Exception unavailable) {
            sample.put("memory_available", false).put("memory_error", unavailable.getClass().getSimpleName());
        }
        lifecycleSamples.put(sample);
        action("reader_lifecycle_observed", sample);
        return new ReaderLifecyclePolicy.State(exited ? ReaderLifecyclePolicy.Screen.SERIES : ReaderLifecyclePolicy.Screen.READER,
            activity.getString("token"), pid);
    }

    private JSONObject lifecycleActivity() throws Exception {
        String raw = lifecycleShell("dumpsys -t 5 activity activities");
        Matcher resumed = Pattern.compile("(?m)^\\s*(?:topResumedActivity|mResumedActivity)\\s*[:=]\\s*ActivityRecord\\{(\\S+)\\s+u[0-9]+\\s+app\\.mihon/([^\\s}]+)").matcher(raw);
        require(resumed.find(), "Resumed subject Activity identity is unavailable");
        return new JSONObject().put("token", resumed.group(1)).put("component", resumed.group(2));
    }

    private String lifecycleShell(String command) throws Exception {
        require(lifecyclePlan && (command.equals("pidof " + SUBJECT) || command.equals("dumpsys -t 5 activity activities") ||
            command.matches("dumpsys -t 5 meminfo [1-9][0-9]*")), "Lifecycle shell query is not allowlisted");
        try (ParcelFileDescriptor descriptor = ui.executeShellCommand(command);
             FileInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                require(bytes.size() + read <= 512 * 1024, "Lifecycle query exceeds evidence bound");
                bytes.write(buffer, 0, read);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void runFrameWindow(int pair, String mode) throws Exception {
        reader(originalChapter, true);
        if (!currentFrameMode().equals(mode)) toggleOriginal();
        resetFrameBaseline("window_" + (frameWindows.length() + 1) + "_before");
        require(rotation() == originalRotation, "Rotation changed before frame window");
        boolean recycler = hasClass(foreground(), "androidx.recyclerview.widget.RecyclerView");
        require(recycler == frameBackend.equals("classic"), "Reader structure differs from selected scrolling backend");
        int ordinal = frameWindows.length() + 1;
        String label = "frame_" + ordinal + "_" + mode;
        checkpoint(label + "_before", true);
        String beforeHash = lastViewportHash;
        if (mode.equals(initialFrameMode)) require(initialFrameHash.equals(beforeHash), "The initial mode's canonical baseline changed");
        String previousHash = mode.equals("translated") ? translatedFrameHash : originalFrameHash;
        if (previousHash != null) require(previousHash.equals(beforeHash), "Window did not start from its mode's canonical page-start viewport");
        if (mode.equals("translated")) translatedFrameHash = beforeHash; else originalFrameHash = beforeHash;
        if (translatedFrameHash != null && originalFrameHash != null) {
            require(!translatedFrameHash.equals(originalFrameHash), "Original and translated viewport pixels are identical");
        }
        Rect visible = viewport(foreground());
        long hideStarted = SystemClock.elapsedRealtime();
        boolean hidden = UiStateTransition.afterSingleAction(
            () -> tap(visible.centerX(), visible.centerY()),
            () -> {
                require(contextInteractive(), "Display became noninteractive while hiding reader controls");
                AccessibilityNodeInfo current = foreground();
                viewport(current);
                String chapter = selectedChapter(current);
                require(chapter == null || originalChapter.equals(chapter), "Chapter changed while hiding controls");
                return chapter == null && slider(current) == null;
            }, SystemClock::elapsedRealtime, SystemClock::sleep, 5000);
        action(hidden ? "frame_controls_hidden" : "frame_controls_hide_timeout", new JSONObject()
            .put("ordinal", ordinal).put("tap_count", 1)
            .put("observed_after_ms", SystemClock.elapsedRealtime() - hideStarted));
        require(hidden, "Reader controls did not hide within 5 seconds after one tap");
        Rect bounds = viewport(foreground());
        require(bounds.height() >= 700 && bounds.width() >= 300, "Viewport cannot contain the fixed 500-pixel gesture");
        JSONObject window = new JSONObject().put("ordinal", ordinal).put("pair", pair).put("mode", mode)
            .put("chapter", originalChapter).put("page", originalPage).put("backend", frameBackend)
            .put("viewport", bounds.toShortString()).put("before_viewport_sha256", beforeHash)
            .put("duration_ms", 30000).put("gesture_count_expected", 30).put("cadence_ms", 1000)
            .put("gesture_duration_ms", 500).put("distance_px", 500).put("status", "awaiting_host_trace");
        if (frameAnchor.enabled()) window.put("frame_anchor", frameAnchorDescriptor());
        frameWindows.put(window);
        awaitFrameGate(window);
        long scheduledStart = SystemClock.uptimeMillis() + 1000;
        window.put("scheduled_start_uptime_ms", scheduledStart);
        action("frame_window_scheduled", new JSONObject().put("ordinal", ordinal).put("start_uptime_ms", scheduledStart));
        sleepUntil(scheduledStart);
        long startUptime = SystemClock.uptimeMillis();
        long startElapsed = SystemClock.elapsedRealtimeNanos();
        require(startUptime - scheduledStart < 250, "Frame window start missed its device schedule");
        JSONArray gestures = new JSONArray();
        window.put("start_elapsed_ns", startElapsed).put("start_uptime_ms", startUptime).put("gestures", gestures)
            .put("status", "measuring");
        try {
            for (int index = 0; index < 30; index++) {
                long target = startUptime + index * 1000L;
                sleepUntil(target);
                require(contextInteractive(), "Display became noninteractive inside frame window");
                require(bounds.equals(viewport(foreground())), "Reader viewport changed inside frame window");
                require(slider(foreground()) == null && selectedChapter(foreground()) == null, "Reader controls appeared inside frame window");
                long actual = SystemClock.uptimeMillis();
                require(actual - target < 250, "Gesture cadence missed by at least 250 ms");
                JSONObject gesture = new JSONObject().put("index", index).put("direction", index % 2 == 0 ? "up" : "down")
                    .put("scheduled_uptime_ms", target).put("start_uptime_ms", actual)
                    .put("start_elapsed_ns", SystemClock.elapsedRealtimeNanos()).put("start_lateness_ms", actual - target);
                gestures.put(gesture);
                timedFrameGesture(bounds, index % 2 == 0);
                gesture.put("end_uptime_ms", SystemClock.uptimeMillis()).put("end_elapsed_ns", SystemClock.elapsedRealtimeNanos());
            }
            sleepUntil(startUptime + 30000);
            while (SystemClock.elapsedRealtimeNanos() - startElapsed < 30000000000L) SystemClock.sleep(1);
            window.put("end_uptime_ms", SystemClock.uptimeMillis()).put("end_elapsed_ns", SystemClock.elapsedRealtimeNanos());
            long elapsed = (window.getLong("end_elapsed_ns") - startElapsed) / 1000000;
            require(elapsed >= 30000 && elapsed < 30250, "Measured input window did not retain the bounded 30 seconds");
            window.put("status", "input_window_complete");
        } catch (Throwable failure) {
            window.put("status", "input_window_failed").put("end_elapsed_ns", SystemClock.elapsedRealtimeNanos())
                .put("failure_class", failure.getClass().getName());
            throw failure;
        } finally {
            // No report fsync or screenshot occurs inside the measured interval.
            action("frame_window_input_finished", new JSONObject().put("ordinal", ordinal));
        }
        reader(originalChapter, true);
        BoundaryRead firstBoundary = readFrameBoundary();
        window.put("final_boundary_first", firstBoundary.details);
        action("frame_final_boundary_observed", new JSONObject().put("ordinal", ordinal)
            .put("observation", firstBoundary.details));
        JSONArray boundarySamples = new JSONArray().put(firstBoundary.details);
        window.put("final_boundary_observations", boundarySamples);
        FrameBoundaryDiagnostics.Result boundary = FrameBoundaryDiagnostics.inspect(
            FrameViewportReset.pageNumber(originalPage), firstBoundary.sample,
            () -> {
                BoundaryRead observed = readFrameBoundary();
                boundarySamples.put(observed.details);
                return observed.sample;
            }, () -> captureFrameBoundary(label, window), SystemClock::elapsedRealtime, SystemClock::sleep);
        if (boundary.firstMismatch) {
            window.put("status", "boundary_assertion_failed")
                .put("boundary_first_mismatch_retained", true)
                .put("boundary_diagnostic_elapsed_ms", boundary.elapsedMs)
                .put("boundary_capture_failure_class", boundary.captureFailure == null ? JSONObject.NULL : boundary.captureFailure)
                .put("boundary_observation_failure_class", boundary.observationFailure == null ? JSONObject.NULL : boundary.observationFailure);
            action("frame_final_boundary_failed", new JSONObject().put("ordinal", ordinal)
                .put("samples", boundary.samples).put("first_mismatch_retained", true));
        }
        require(!boundary.firstMismatch,
            "Window crossed its selected page at the final boundary");
        require(rotation() == originalRotation, "Window changed reader orientation");
        checkpoint(label + "_natural_after", false, true);
        window.put("natural_after_viewport_sha256", lastViewportHash)
            .put("natural_after_elapsed_ns", SystemClock.elapsedRealtimeNanos())
            .put("natural_pixel_observation", "hash-only after input and control reveal; no claim that opposite gestures are lossless")
            .put("reset_after_start_elapsed_ns", SystemClock.elapsedRealtimeNanos());
        resetFrameBaseline("window_" + ordinal + "_after");
        checkpoint(label + "_after", true);
        window.put("after_viewport_sha256", lastViewportHash)
            .put("reset_after_end_elapsed_ns", SystemClock.elapsedRealtimeNanos());
        require(beforeHash.equals(lastViewportHash), "Explicit reset did not restore this mode's canonical page-start pixels");
        window.put("canonical_reset_verified", true).put("status", "boundary_assertions_passed");
        action("frame_window_complete", new JSONObject().put("ordinal", ordinal));
        Bundle status = new Bundle();
        status.putString("frame_window_complete_json", window.toString());
        sendStatus(0, status);
    }

    private static final class BoundaryRead {
        final FrameBoundaryDiagnostics.Sample sample;
        final JSONObject details;
        BoundaryRead(FrameBoundaryDiagnostics.Sample sample, JSONObject details) {
            this.sample = sample;
            this.details = details;
        }
    }

    /** Reads scalar state only. It neither reveals controls nor navigates the reader. */
    private BoundaryRead readFrameBoundary() throws Exception {
        JSONObject details = new JSONObject().put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
            .put("expected_page", FrameViewportReset.pageNumber(originalPage)).put("scope_verified", false);
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        boolean controlled = contextInteractive() && !imeVisible() && root != null &&
            SUBJECT.contentEquals(root.getPackageName()) && find(root, TITLE, false) != null &&
            originalChapter.equals(selectedChapter(root)) && find(root, SUBJECT + ":id/viewer_container", true) != null;
        if (!controlled) {
            details.put("unavailable_reason", "Controlled reader, chapter or unobscured foreground unavailable");
            return new BoundaryRead(new FrameBoundaryDiagnostics.Sample(false, null), details);
        }
        Rect bounds = viewport(root);
        details.put("scope_verified", true).put("chapter", originalChapter)
            .put("viewport", bounds.toShortString()).put("rotation", rotation());
        AccessibilityNodeInfo pageSlider = slider(root);
        Integer rounded = null;
        if (pageSlider == null || pageSlider.getRangeInfo() == null) {
            details.put("unavailable_reason", "Page slider unavailable");
        } else {
            float raw = pageSlider.getRangeInfo().getCurrent();
            details.put("raw_slider_value", Float.isFinite(raw) ? raw : Float.toString(raw));
            try { rounded = FrameViewportReset.pageNumber(raw); }
            catch (IllegalArgumentException invalid) { details.put("unavailable_reason", "Page slider is not a bounded integer"); }
        }
        details.put("rounded_page", rounded == null ? JSONObject.NULL : rounded);
        return new BoundaryRead(new FrameBoundaryDiagnostics.Sample(true, rounded), details);
    }

    /** Captures only the controlled reader, checking scope again before and after the platform call. */
    private void captureFrameBoundary(String label, JSONObject window) throws Exception {
        BoundaryRead before = readFrameBoundary();
        require(before.sample.controlledReader, "Boundary capture scope changed before screenshot");
        Bitmap capture = ui.takeScreenshot();
        require(capture != null, "Boundary screenshot unavailable");
        try {
            BoundaryRead after = readFrameBoundary();
            require(after.sample.controlledReader &&
                before.details.getString("viewport").equals(after.details.getString("viewport")),
                "Boundary capture scope changed during screenshot; bitmap discarded");
            require(storedBytes + capture.getByteCount() <= STORAGE_LIMIT, "Boundary evidence storage budget unavailable");
            String fileName = String.format(java.util.Locale.ROOT, "%02d-%s_boundary_failed.png", screenshotIndex++, label);
            File file = new File(output, fileName);
            require(!file.exists(), "Boundary screenshot path must be new");
            try (FileOutputStream stream = new FileOutputStream(file)) {
                require(capture.compress(Bitmap.CompressFormat.PNG, 100, stream), "Boundary screenshot encoding failed");
                stream.getFD().sync();
            }
            storedBytes += file.length();
            JSONObject evidence = new JSONObject().put("screenshot", fileName).put("bytes", file.length())
                .put("before_capture", before.details).put("after_capture", after.details)
                .put("scope", "Failed boundary diagnostic; no page reset or reader input before capture");
            window.put("boundary_diagnostic_capture", evidence);
            action("frame_boundary_diagnostic_capture", evidence);
        } finally { capture.recycle(); }
    }

    private boolean contextInteractive() {
        return ((PowerManager)getTargetContext().getSystemService(Context.POWER_SERVICE)).isInteractive();
    }

    private void awaitFrameGate(JSONObject window) throws Exception {
        String nonce = UUID.randomUUID().toString();
        File gate = new File(output, "frame-" + window.getInt("ordinal") + ".go");
        require(!gate.exists(), "Frame gate must be new");
        window.put("gate_file", gate.getPath()).put("gate_nonce", nonce);
        action("frame_window_ready", new JSONObject().put("ordinal", window.getInt("ordinal")));
        Bundle status = new Bundle();
        status.putString("frame_window_ready_json", window.toString());
        sendStatus(0, status);
        long deadline = SystemClock.elapsedRealtime() + 120000;
        while (!gate.isFile()) {
            require(SystemClock.elapsedRealtime() < deadline, "Host did not arm this window within 120 seconds");
            foreground();
            SystemClock.sleep(100);
        }
        require(SystemClock.elapsedRealtime() < deadline, "Host gate arrived after its deadline");
        require(gate.length() <= 64 && gate.getCanonicalFile().getParentFile().equals(output.getCanonicalFile()), "Invalid frame gate");
        byte[] data = new byte[(int)gate.length()];
        try (FileInputStream stream = new FileInputStream(gate)) {
            int offset = 0;
            while (offset < data.length) {
                int count = stream.read(data, offset, data.length - offset);
                require(count > 0, "Incomplete frame gate");
                offset += count;
            }
        }
        require(nonce.equals(new String(data, StandardCharsets.UTF_8).trim()), "Frame gate nonce mismatch");
        require(gate.delete(), "Cannot remove consumed frame gate");
        action("frame_gate_consumed", new JSONObject().put("ordinal", window.getInt("ordinal")));
    }

    private void sleepUntil(long uptime) {
        long remaining;
        while ((remaining = uptime - SystemClock.uptimeMillis()) > 0) SystemClock.sleep(remaining);
    }

    private void timedFrameGesture(Rect bounds, boolean up) {
        float x = bounds.centerX();
        float startY = bounds.centerY() + (up ? 250f : -250f);
        float endY = bounds.centerY() + (up ? -250f : 250f);
        long down = SystemClock.uptimeMillis();
        boolean released = false;
        touchUnchecked(down, MotionEvent.ACTION_DOWN, new float[]{x}, new float[]{startY});
        try {
            for (int move = 1; move <= 25; move++) {
                sleepUntil(down + move * 420L / 25);
                double fraction = (1 - Math.cos(Math.PI * move / 25.0)) / 2;
                float y = startY + (endY - startY) * (float)fraction;
                touchUnchecked(down, MotionEvent.ACTION_MOVE, new float[]{x}, new float[]{y});
            }
            // Hold the endpoint before UP to avoid adding a fling to the paired displacement.
            sleepUntil(down + 500);
            touchUnchecked(down, MotionEvent.ACTION_UP, new float[]{x}, new float[]{endY});
            released = true;
        } finally {
            if (!released) touchUnchecked(down, MotionEvent.ACTION_CANCEL, new float[]{x}, new float[]{endY});
        }
    }

    private void runStyleCycles(int cycles) throws Exception {
        openSeriesSettings();
        if (styleUsesAutosave()) awaitStyleSaved();
        filterStyle("Imported font path");
        require(editableValue("Imported font path").isEmpty(), "Imported font overrides family; refusing to change it");
        filterStyle("Font family");
        originalFont = fontValue();
        filterStyle("Background opacity");
        originalOpacity = editableValue("Background opacity");
        float opacity = Float.parseFloat(originalOpacity);
        require(Float.isFinite(opacity) && opacity >= 0 && opacity <= 1, "Invalid initial opacity");
        String alternateFont = originalFont.equals("monospace") ? "serif" : "monospace";
        String alternateOpacity = opacity > 0.60f ? "0.35" : "0.85";
        report.put("font_style_changes", "system family and background opacity through existing series override")
            .put("style_original", new JSONObject().put("font_family", originalFont).put("background_opacity", originalOpacity))
            .put("style_alternate", new JSONObject().put("font_family", alternateFont).put("background_opacity", alternateOpacity))
            .put("style_cycles", 0).put("chapters_ahead_zero", "host_verified_prerequisite")
            .put("series_override_existed", "host_verified_prerequisite").put("imported_font_absent", true)
            .put("style_journal_policy", "durable intent before the first possible autosaved mutation")
            .put("style_restore_required", false);
        action("style_values_recorded_before_mutation", new JSONObject());
        leaveSeriesSettings();
        setPage(originalPage);
        checkpoint("style_baseline", true);
        styleBaselineHash = lastViewportHash;
        for (int i = 0; i < cycles; i++) {
            applySeriesStyle(alternateFont, alternateOpacity, "style_" + (i + 1) + "_alternate");
            checkpoint("style_" + (i + 1) + "_changed", true);
            require(!styleBaselineHash.equals(lastViewportHash), "Style change produced no measured viewport change");
            applySeriesStyle(originalFont, originalOpacity, "style_" + (i + 1) + "_restore");
            checkpoint("style_" + (i + 1) + "_restored", true);
            final int completedCycle = i + 1;
            styleMutation.restored(
                () -> require(styleBaselineHash.equals(lastViewportHash), "Restored style did not restore the measured viewport pixels"),
                () -> {
                    report.put("style_restore_required", false).put("style_cycles", completedCycle);
                    action("style_round_trip_completed", new JSONObject().put("cycle", completedCycle));
                });
        }
    }

    private void openSeriesSettings() throws Exception {
        reader(originalChapter, true);
        click("More options");
        click("Translator queue and settings");
        require(waitNode("Translator") != null, "Expected Translator screen did not open");
        styleScopeBound = true;
        styleClick("Settings");
        assertSeriesSettings();
    }

    private void assertSeriesSettings() throws Exception {
        require(stylePlan && styleScopeBound, "Series settings were not entered from the authorized reader");
        waitNode("Settings for this series");
        requireSeriesSettings(foreground());
    }

    private void requireSeriesSettings(AccessibilityNodeInfo root) {
        require(stylePlan && styleScopeBound && find(root, "Settings for this series", false) != null,
            "Series settings scope changed");
        require(find(root, "Translation defaults", false) == null, "Refusing global translator defaults");
        require(find(root, "Translator", false) != null, "Unexpected settings screen");
    }

    private ReaderSettingsPolicy.SaveState styleSaveState(AccessibilityNodeInfo root) {
        requireSeriesSettings(root);
        if (find(root, "Retry save", false) != null) return ReaderSettingsPolicy.SaveState.FAILED;
        if (find(root, "Saving…", false) != null || find(root, "Saving", false) != null ||
                find(root, "Saving...", false) != null) return ReaderSettingsPolicy.SaveState.SAVING;
        if (find(root, "Saved", false) != null) return ReaderSettingsPolicy.SaveState.SAVED;
        return ReaderSettingsPolicy.SaveState.UNKNOWN;
    }

    private boolean styleUsesAutosave() throws Exception { return styleUsesAutosave(false); }

    private boolean styleUsesAutosave(boolean restoring) throws Exception {
        assertSeriesSettings();
        AccessibilityNodeInfo root = foreground();
        ReaderSettingsPolicy.SaveState state = styleSaveState(root);
        if (state == ReaderSettingsPolicy.SaveState.FAILED) {
            require(restoring, "Resolve existing settings storage errors before validation");
            return true;
        }
        if (state == ReaderSettingsPolicy.SaveState.SAVED || state == ReaderSettingsPolicy.SaveState.SAVING) return true;
        require(find(root, "Apply", false) != null, "Neither autosave status nor a legacy Apply control is visible");
        return false;
    }

    private void awaitStyleSaved() throws Exception {
        ReaderSettingsPolicy.awaitSaved(() -> styleSaveState(foreground()), SystemClock::uptimeMillis,
            SystemClock::sleep, 5000);
        action("style_save_observed", new JSONObject().put("status", "Saved").put("stable_for_ms", 400));
    }

    private AccessibilityNodeInfo waitNode(String label) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        AccessibilityNodeInfo node;
        do {
            node = find(foreground(), label, false);
            if (node != null) return node;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException("Required style control unavailable: " + label);
    }

    private AccessibilityNodeInfo editable(String label) throws Exception {
        require(label.equals("Search settings") || label.equals("Background opacity") || label.equals("Imported font path"),
            "Editable label is not allowlisted");
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            AccessibilityNodeInfo node = findEditable(foreground(), label);
            if (node != null) return node;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException("Labeled editable control unavailable: " + label);
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (node.isVisibleToUser() && node.isEditable() && node.isEnabled()) {
            // A search query equals the target label, so its editable text is not the target field.
            boolean search = find(node, "Search settings", false) != null;
            if (search) return label.equals("Search settings") ? node : null;
            if (!label.equals("Search settings") && find(node, label, false) != null) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private String editableValue(String label) throws Exception {
        CharSequence value = editable(label).getText();
        return value == null ? "" : value.toString();
    }

    private void setEditable(String label, String value) throws Exception {
        assertSeriesSettings();
        require(label.equals("Search settings") || label.equals("Background opacity"), "Field is read-only in this harness");
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        require(editable(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "Set-text action rejected");
        settle();
        require(editableValue(label).equals(value), "Field did not retain requested text: " + label);
        action("style_set_text", new JSONObject().put("label", label).put("value", value));
    }

    private void filterStyle(String label) throws Exception {
        require(label.equals("Font family") || label.equals("Background opacity") || label.equals("Imported font path"),
            "Settings search is not allowlisted");
        setEditable("Search settings", label);
        hideIme();
        assertSeriesSettings();
        waitNode(label);
    }

    private boolean imeVisible() {
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        }
        return false;
    }

    private void hideIme() throws Exception {
        if (imeVisible()) {
            require(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK), "IME dismissal rejected");
            settle();
            require(!imeVisible(), "Keyboard remains visible; refusing covered controls");
            action("style_hide_ime", new JSONObject());
        }
    }

    private boolean isFont(String label) {
        return label.equals("system") || label.equals("sans-serif") || label.equals("serif") ||
            label.equals("monospace") || label.equals("cursive");
    }

    private String fontValue() throws Exception {
        assertSeriesSettings();
        require(editableValue("Search settings").equals("Font family"), "Font selector requires exact search");
        String value = null;
        for (String choice : new String[]{"system", "sans-serif", "serif", "monospace", "cursive"}) {
            if (find(foreground(), choice, false) != null) {
                require(value == null, "Ambiguous font value or open dropdown");
                value = choice;
            }
        }
        require(value != null, "Font family value is unavailable");
        return value;
    }

    private void styleClick(String label) throws Exception {
        require(stylePlan && styleScopeBound, "Unbound style action");
        require(label.equals("Settings") || label.equals("Navigate up") || label.equals("Apply") || isFont(label),
            "Style control is not allowlisted");
        hideIme();
        AccessibilityNodeInfo node = waitNode(label);
        for (int depth = 0; depth < 4 && node != null && !node.isClickable(); depth++) {
            require(node.isEnabled(), "Style control disabled: " + label);
            node = node.getParent();
        }
        require(node != null && node.isEnabled() && node.isClickable(), "Style control cannot be clicked: " + label);
        require(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Style click rejected");
        action("style_click", new JSONObject().put("label", label));
        settle();
    }

    private void leaveSeriesSettings() throws Exception {
        assertSeriesSettings();
        hideIme();
        styleClick("Navigate up");
        reader(originalChapter, true);
        styleScopeBound = false;
    }

    private void applySeriesStyle(String font, String opacity, String stage) throws Exception {
        require(originalFont != null && originalOpacity != null && isFont(font), "Original style must be journaled first");
        if (selectedChapter(foreground()) != null) openSeriesSettings();
        assertSeriesSettings();
        filterStyle("Font family");
        String currentFont = fontValue();
        boolean autosave = styleUsesAutosave(font.equals(originalFont) && opacity.equals(originalOpacity));
        styleMutation.mutate(() -> {
            report.put("style_restore_required", true).put("style_pending_apply", new JSONObject()
                .put("font_family", font).put("background_opacity", opacity).put("stage", stage))
                .put("style_save_mode", autosave ? "autosave" : "legacy_apply");
            action("style_mutation_intent", new JSONObject().put("stage", stage));
        }, () -> {
            if (!currentFont.equals(font)) {
                styleClick(currentFont);
                for (String choice : new String[]{"system", "sans-serif", "serif", "monospace", "cursive"}) waitNode(choice);
                styleClick(font);
                require(fontValue().equals(font), "Font draft did not change");
                if (autosave) awaitStyleSaved();
            }
            filterStyle("Background opacity");
            setEditable("Background opacity", opacity);
            hideIme();
            assertSeriesSettings();
            if (autosave) {
                awaitStyleSaved();
            } else {
                AccessibilityNodeInfo button = waitNode("Apply");
                for (int depth = 0; depth < 4 && button != null && button.isEnabled() && !button.isClickable(); depth++) {
                    button = button.getParent();
                }
                if (button != null && button.isEnabled() && button.isClickable()) styleClick("Apply");
                else require(font.equals(originalFont) && opacity.equals(originalOpacity), "Alternate style did not enable Apply");
            }
        });
        leaveSeriesSettings();
        // Reopen a new screen to verify persisted values, rather than just the draft controls.
        openSeriesSettings();
        filterStyle("Font family");
        require(fontValue().equals(font), "Persisted font differs from requested style");
        filterStyle("Background opacity");
        require(Float.compare(Float.parseFloat(editableValue("Background opacity")), Float.parseFloat(opacity)) == 0,
            "Persisted opacity differs from requested style");
        leaveSeriesSettings();
        setPage(originalPage);
        action("style_persisted_values_verified", new JSONObject().put("font_family", font)
            .put("background_opacity", opacity).put("stage", stage));
    }

    private void restoreStyleAfterFailure() throws Exception {
        // A font popup is the only transient window this plan may deliberately open.
        if (styleScopeBound && find(foreground(), "Settings for this series", false) == null &&
            find(foreground(), "cursive", false) != null && find(foreground(), "monospace", false) != null) {
            require(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK), "Font popup dismissal rejected");
            settle();
        }
        if (styleMutation.requiresRestoration()) {
            applySeriesStyle(originalFont, originalOpacity, "finally_restore");
            checkpoint("style_finally_restored", true);
            styleMutation.restored(
                () -> require(styleBaselineHash != null && styleBaselineHash.equals(lastViewportHash), "Final style pixels remain different"),
                () -> {
                    report.put("style_restore_required", false);
                    action("style_final_restoration_verified", new JSONObject());
                });
        } else if (styleScopeBound) {
            leaveSeriesSettings();
        }
        reader(originalChapter, true);
        setPage(originalPage);
        report.put("style_restore_required", false).put("restoration", "Original series font/opacity and reader chapter/page verified");
    }

    private AccessibilityNodeInfo foreground() {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        require(root != null && SUBJECT.contentEquals(root.getPackageName()), "Foreground package changed; refusing input");
        return root;
    }

    private String selectedChapter(AccessibilityNodeInfo root) {
        if (find(root, TITLE, false) == null) return null;
        if (find(root, FIRST, false) != null) return FIRST;
        if (find(root, SECOND, false) != null) return SECOND;
        return null;
    }

    private void reader(String chapter, boolean reveal) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        boolean revealed = false;
        while (SystemClock.uptimeMillis() <= deadline) {
            AccessibilityNodeInfo root = foreground();
            if (chapter.equals(selectedChapter(root))) {
                viewport(root);
                return;
            }
            require(find(root, TITLE, false) == null || selectedChapter(root) != null,
                "Unexpected chapter in the controlled series; refusing further actions");
            if (reveal && authorizedReader && !revealed && selectedChapter(root) == null) {
                Rect bounds = viewport(root);
                tap(bounds.centerX(), bounds.centerY());
                revealed = true;
                settle();
            }
            SystemClock.sleep(50);
        }
        throw new IllegalStateException("Expected controlled chapter/title did not become visible");
    }

    private Rect viewport(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo view = find(root, SUBJECT + ":id/viewer_container", true);
        require(view != null, "Reader viewport is absent from accessibility; no coordinate fallback");
        Rect bounds = new Rect();
        view.getBoundsInScreen(bounds);
        require(bounds.width() > 200 && bounds.height() > 200, "Reader viewport bounds are invalid");
        return bounds;
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String exact, boolean id) {
        if (node == null) return null;
        if (node.isVisibleToUser() && (id ? exact.equals(node.getViewIdResourceName()) :
            exact.contentEquals(node.getText() == null ? "" : node.getText()) ||
            exact.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = find(node.getChild(i), exact, id);
            if (match != null) return match;
        }
        return null;
    }

    private void click(String label) throws Exception {
        require(label.equals("More options") || label.equals("Original / translated") ||
            label.equals("Next chapter") || label.equals("Previous chapter") ||
            (stylePlan && label.equals("Translator queue and settings")), "Control is not allowlisted");
        long deadline = SystemClock.uptimeMillis() + 5000;
        AccessibilityNodeInfo node;
        do {
            node = find(foreground(), label, false);
            if (node != null && node.isEnabled()) break;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        require(node != null && node.isEnabled(), "Required allowlisted control is not available: " + label);
        AccessibilityNodeInfo action = node;
        for (int i = 0; i < 3 && action != null && !action.isClickable(); i++) action = action.getParent();
        require(action != null && action.isEnabled() && action.isClickable(), "Control has no clickable accessibility action");
        if (framePlan && label.equals("Original / translated")) {
            report.put("comparison_pending_toggle", true);
            action("frame_toggle_intent", new JSONObject().put("previous_toggle_count", toggles));
        }
        require(action.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Accessibility click rejected");
        if (label.equals("Original / translated")) {
            toggles++;
            report.put("toggle_count", toggles);
            if (framePlan) report.put("comparison_pending_toggle", false);
        }
        action("click", new JSONObject().put("label", label));
        settle();
    }

    private void toggleOriginal() throws Exception {
        String chapter = selectedChapter(foreground());
        require(chapter != null, "Cannot toggle outside the controlled reader");
        click("More options");
        click("Original / translated");
        reader(chapter, true);
    }

    private void chapter(String from, String to) throws Exception {
        require((from.equals(FIRST) && to.equals(SECOND)) || (from.equals(SECOND) && to.equals(FIRST)),
            "Only the two controlled fixture chapters are allowed");
        reader(from, true);
        click(from.equals(FIRST) ? "Next chapter" : "Previous chapter");
        reader(to, true);
    }

    private AccessibilityNodeInfo slider(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser() && node.getRangeInfo() != null && node.isEnabled() &&
            node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = slider(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private float pageProgress() {
        AccessibilityNodeInfo node = slider(foreground());
        require(node != null, "Visible reader page slider is required for page restoration");
        return node.getRangeInfo().getCurrent();
    }

    private void setPage(float page) throws Exception {
        reader(originalChapter, true);
        AccessibilityNodeInfo node = slider(foreground());
        require(node != null, "Page slider is unavailable");
        if (node.getRangeInfo().getCurrent() == page) return;
        require(page >= node.getRangeInfo().getMin() && page <= node.getRangeInfo().getMax(), "Original page is out of range");
        Bundle args = new Bundle();
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, page);
        require(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args), "Page restore rejected");
        settle();
        require(pageProgress() == page, "Page restoration did not take effect");
        action("restore_page", new JSONObject().put("page", page));
    }

    private JSONObject frameAnchorDescriptor() throws Exception {
        JSONObject descriptor = new JSONObject().put("protocol", frameAnchor.protocol())
            .put("requested_drag_pixels", frameAnchor.requestedPixels).put("direction", "up")
            .put("motion_ms", 420).put("release_hold_ms", 300).put("expected_page", 7);
        if (frameAnchor.primed) descriptor.put("prime_pixels", FrameAnchorMotion.PRIME_PIXELS)
            .put("prime_move_ms", FrameAnchorMotion.PRIME_MOVE_MS).put("prime_hold_ms", FrameAnchorMotion.PRIME_HOLD_MS);
        return descriptor;
    }

    private void resetFrameBaseline(String phase) throws Exception {
        int[] reads = {0};
        frameAnchor.reset(() -> resetFrameViewport(phase), () -> {
            boolean first = reads[0]++ == 0;
            String label = "anchor_" + phase + (first ? "_page_start" : "_interior");
            checkpoint(label, phase.equals("setup_first"), true);
            BoundaryRead observed = readFrameBoundary();
            require(observed.sample.controlledReader && observed.sample.roundedPage != null,
                "Interior anchor lost its controlled reader identity");
            return new FrameAnchorPolicy.State(observed.sample.roundedPage,
                observed.details.getString("viewport"), lastViewportHash);
        }, () -> {
            reader(originalChapter, false);
            Rect bounds = viewport(foreground());
            require(contextInteractive() && rotation() == originalRotation, "Interior anchor requires unchanged interactive viewport");
            long start = SystemClock.elapsedRealtimeNanos();
            float x = bounds.centerX();
            float startY = bounds.centerY() + frameAnchor.requestedPixels / 2f;
            float endY = startY - frameAnchor.requestedPixels;
            long down = SystemClock.uptimeMillis();
            if (frameAnchor.primed) {
                int slop = ViewConfiguration.get(getTargetContext()).getScaledTouchSlop();
                FrameAnchorMotion.requireSlop(slop);
                require(startY + FrameAnchorMotion.PRIME_PIXELS < bounds.bottom && endY > bounds.top,
                    "Primed anchor input must remain inside the observed viewport");
                FrameAnchorMotion.run(down, x, startY, frameAnchor.requestedPixels,
                    (event, eventX, eventY) -> touchUnchecked(down, event, new float[]{eventX}, new float[]{eventY}),
                    this::sleepUntil);
                report.put("anchor_observed_touch_slop_px", slop);
            } else {
                boolean released = false;
                touchUnchecked(down, MotionEvent.ACTION_DOWN, new float[]{x}, new float[]{startY});
                try {
                    for (int move = 1; move <= 25; move++) {
                        sleepUntil(down + move * 420L / 25);
                        double fraction = (1 - Math.cos(Math.PI * move / 25.0)) / 2;
                        float y = startY + (endY - startY) * (float)fraction;
                        touchUnchecked(down, MotionEvent.ACTION_MOVE, new float[]{x}, new float[]{y});
                    }
                    sleepUntil(down + 720);
                    touchUnchecked(down, MotionEvent.ACTION_UP, new float[]{x}, new float[]{endY});
                    released = true;
                } finally {
                    if (!released) touchUnchecked(down, MotionEvent.ACTION_CANCEL, new float[]{x}, new float[]{endY});
                }
            }
            settle();
            reader(originalChapter, true);
            action("frame_interior_anchor_input", new JSONObject().put("phase", phase)
                .put("protocol", frameAnchorDescriptor()).put("started_elapsed_ns", start)
                .put("ended_elapsed_ns", SystemClock.elapsedRealtimeNanos())
                .put("viewport", bounds.toShortString()).put("attempt", 1));
        });
    }

    private void resetFrameViewport(String phase) throws Exception {
        require(framePlan || lifecyclePlan, "Canonical normalization is restricted to the frame or lifecycle plan");
        reader(originalChapter, true);
        AccessibilityNodeInfo node = slider(foreground());
        require(node != null, "Canonical reset requires the controlled chapter page slider");
        long started = SystemClock.elapsedRealtimeNanos();
        int adjacent = FrameViewportReset.reset((int)originalPage,
            node.getRangeInfo().getMin(), node.getRangeInfo().getMax(), new FrameViewportReset.Navigator() {
                @Override public float currentPage() throws Exception {
                    reader(originalChapter, false);
                    return pageProgress();
                }
                @Override public void jumpToPage(int page) throws Exception {
                    reader(originalChapter, false);
                    require(contextInteractive(), "Display became noninteractive during canonical reset");
                    AccessibilityNodeInfo current = slider(foreground());
                    require(current != null, "Controlled page slider disappeared during reset");
                    Bundle args = new Bundle();
                    args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, page);
                    require(current.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args),
                        "Canonical page jump was rejected");
                    settle();
                    boolean reached = UiStateTransition.afterSingleAction(() -> { }, () -> {
                        require(contextInteractive(), "Display became noninteractive while observing page jump");
                        require(originalChapter.equals(selectedChapter(foreground())), "Chapter changed during page reset");
                        return FrameViewportReset.pageNumber(pageProgress()) == page;
                    }, SystemClock::elapsedRealtime, SystemClock::sleep, 5000);
                    require(reached, "Canonical page jump was not observed within five seconds");
                    action("frame_reset_page_observed", new JSONObject().put("phase", phase).put("page", page)
                        .put("raw_slider_value", pageProgress()));
                }
            });
        action("frame_canonical_reset", new JSONObject().put("phase", phase).put("selected_page", (int)originalPage)
            .put("adjacent_page", adjacent).put("chapter", originalChapter)
            .put("started_elapsed_ns", started).put("ended_elapsed_ns", SystemClock.elapsedRealtimeNanos()));
    }

    private void pinch(float start, float end) throws Exception {
        reader(originalChapter, true);
        Rect bounds = viewport(foreground());
        long down = SystemClock.uptimeMillis();
        float x = bounds.centerX(), y = bounds.centerY(), size = Math.min(bounds.width(), bounds.height());
        touch(down, MotionEvent.ACTION_DOWN, new float[]{x - start * size}, new float[]{y});
        touch(down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new float[]{x - start * size, x + start * size}, new float[]{y, y});
        for (int i = 1; i <= 20; i++) {
            float distance = (start + (end - start) * i / 20f) * size;
            touch(down, MotionEvent.ACTION_MOVE, new float[]{x - distance, x + distance}, new float[]{y, y});
            SystemClock.sleep(16);
        }
        touch(down, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new float[]{x - end * size, x + end * size}, new float[]{y, y});
        touch(down, MotionEvent.ACTION_UP, new float[]{x - end * size}, new float[]{y});
        settle();
        action("pinch", new JSONObject().put("start_fraction", start).put("end_fraction", end));
        reader(originalChapter, true);
    }

    private void pan(float x1, float y1, float x2, float y2) throws Exception {
        reader(originalChapter, true);
        Rect b = viewport(foreground());
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, new float[]{b.left + x1 * b.width()}, new float[]{b.top + y1 * b.height()});
        for (int i = 1; i <= 20; i++) {
            float f = i / 20f;
            touch(down, MotionEvent.ACTION_MOVE, new float[]{b.left + (x1 + (x2 - x1) * f) * b.width()},
                new float[]{b.top + (y1 + (y2 - y1) * f) * b.height()});
            SystemClock.sleep(16);
        }
        touch(down, MotionEvent.ACTION_UP, new float[]{b.left + x2 * b.width()}, new float[]{b.top + y2 * b.height()});
        settle();
        action("pan", new JSONObject().put("from_x", x1).put("from_y", y1).put("to_x", x2).put("to_y", y2));
        reader(originalChapter, true);
    }

    private void tap(float x, float y) {
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, new float[]{x}, new float[]{y});
        touch(down, MotionEvent.ACTION_UP, new float[]{x}, new float[]{y});
    }

    private void touch(long down, int action, float[] xs, float[] ys) {
        foreground();
        touchUnchecked(down, action, xs, ys);
    }

    private void touchUnchecked(long down, int action, float[] xs, float[] ys) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[xs.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[xs.length];
        for (int i = 0; i < xs.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = xs[i]; coordinates[i].y = ys[i];
            coordinates[i].pressure = 1f; coordinates[i].size = 1f;
        }
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, xs.length, properties,
            coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { require(ui.injectInputEvent(event, true), "Touch injection rejected"); }
        finally { event.recycle(); }
    }

    private int rotation() {
        return ((WindowManager) getTargetContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
    }

    private void restoreRotation() throws Exception {
        require(ui.setRotation(originalUserRotation), "Cannot restore saved manual rotation");
        if (originalAutoRotation == 1) require(ui.setRotation(UiAutomation.ROTATION_UNFREEZE), "Cannot restore automatic rotation");
        settle();
        require(Settings.System.getInt(getTargetContext().getContentResolver(), Settings.System.USER_ROTATION, -1) ==
            originalUserRotation, "Saved manual rotation was not restored");
        require(Settings.System.getInt(getTargetContext().getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, -1) ==
            originalAutoRotation, "Automatic rotation policy was not restored");
        rotationChanged = false;
    }

    private void settle() throws Exception {
        // A dispatched accessibility click can return before its first UI event arrives.
        // An immediately idle event stream alone does not prove the transition has started.
        SystemClock.sleep(200);
        ui.waitForIdle(150, 5000);
    }

    private void checkpoint(String label, boolean screenshot) throws Exception {
        checkpoint(label, screenshot, false);
    }

    private void checkpoint(String label, boolean screenshot, boolean hashOnly) throws Exception {
        String chapter = selectedChapter(foreground());
        require(chapter != null, "Refusing evidence capture outside the controlled reader");
        float observedPage = pageProgress();
        JSONObject details = new JSONObject().put("chapter", chapter)
            .put("page", framePlan ? FrameViewportReset.pageNumber(observedPage) : observedPage).put("rotation", rotation());
        if (framePlan) details.put("raw_slider_value", observedPage);
        if (screenshot || hashOnly) {
            require(!screenshot || screenshotIndex < 48, "Screenshot count limit reached");
            Bitmap capture = ui.takeScreenshot();
            require(capture != null, "Screenshot unavailable");
            Bitmap bitmap = capture.getConfig() == Bitmap.Config.HARDWARE ? capture.copy(Bitmap.Config.ARGB_8888, false) : capture;
            if (bitmap != capture) capture.recycle();
            require(bitmap != null, "Cannot read screenshot pixels");
            try {
                if (screenshot) {
                    File file = new File(output, String.format(java.util.Locale.ROOT, "%02d-%s.png", screenshotIndex++, label));
                    try (FileOutputStream stream = new FileOutputStream(file)) {
                        require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "Screenshot compression failed");
                    }
                    Os.chmod(file.getPath(), 0600);
                    storedBytes += file.length();
                    require(storedBytes <= STORAGE_LIMIT, "Private evidence storage limit exceeded");
                    details.put("screenshot", file.getName());
                } else {
                    details.put("pixel_evidence", "observed hash only; bitmap not retained");
                }
                Rect b = viewport(foreground());
                b.inset(b.width() / 5, b.height() / 4);
                require(b.left >= 0 && b.top >= 0 && b.right <= bitmap.getWidth() && b.bottom <= bitmap.getHeight(),
                    "Viewport changed during capture");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                int[] row = new int[b.width()];
                for (int y = b.top; y < b.bottom; y++) {
                    bitmap.getPixels(row, 0, row.length, b.left, y, row.length, 1);
                    for (int pixel : row) {
                        digest.update((byte)(pixel >> 24)); digest.update((byte)(pixel >> 16));
                        digest.update((byte)(pixel >> 8)); digest.update((byte)pixel);
                    }
                }
                StringBuilder hash = new StringBuilder();
                for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                lastViewportHash = hash.toString();
                details.put("viewport_pixel_sha256", hash.toString()).put("hashed_rect", b.toShortString());
            } finally { bitmap.recycle(); }
        }
        action(label, details);
    }

    private void action(String name, JSONObject details) throws Exception {
        actions.put(new JSONObject().put("action", name).put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
            .put("details", details));
        report.put("actions", actions);
        persist();
    }

    private void persist() throws Exception {
        if (output == null) return;
        File temporary = new File(output, "report.tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        Os.chmod(temporary.getPath(), 0600);
        Os.rename(temporary.getPath(), new File(output, "report.json").getPath());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

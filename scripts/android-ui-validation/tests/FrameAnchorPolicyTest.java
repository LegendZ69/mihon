package app.mihon.validation.framework;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FrameAnchorPolicyTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static FrameAnchorPolicy.State state(int page, String viewport, String pixels) {
        return new FrameAnchorPolicy.State(page, viewport, pixels);
    }
    public static void main(String[] args) throws Exception {
        List<String> actions = new ArrayList<>();
        FrameAnchorPolicy.parse("framePairs", "webgpu", null).reset(() -> actions.add("reset"),
            () -> { throw new AssertionError("Legacy must not read anchor state"); },
            () -> { throw new AssertionError("Legacy must not drag"); });
        check(actions.equals(Arrays.asList("reset")), "Default protocol must remain exact canonical reset");
        FrameAnchorPolicy enabled = FrameAnchorPolicy.parse("framePairs", "webgpu", "100");
        enabled.requireTarget("002 - Twelve-page corpus", 7);
        for (String phase : new String[]{"setup", "repeatability", "translated", "original", "finally"}) {
            int[] reads = {0};
            enabled.reset(() -> actions.add(phase + ":reset"),
                () -> state(7, "viewport", reads[0]++ == 0 ? "start" : "interior"),
                () -> actions.add(phase + ":fixed100"));
        }
        check(actions.size() == 11 && actions.get(9).equals("finally:reset") && actions.get(10).equals("finally:fixed100"),
            "Every phase including restoration must use the same single fixed anchor");
        for (String value : new String[]{"-100", "50", "101", "0100", "100.0", "", "NaN"}) {
            boolean refused = false;
            try { FrameAnchorPolicy.parse("framePairs", "webgpu", value); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "Unrecorded offset protocol must fail before input");
        }
        for (String[] bad : new String[][]{{"qualityReview", "webgpu"}, {"framePairs", "classic"}}) {
            boolean refused = false;
            try { FrameAnchorPolicy.parse(bad[0], bad[1], "100"); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "Interior anchor must never affect another plan/backend");
        }
        boolean wrongTarget = false;
        try { enabled.requireTarget("001 - Five-page modes", 7); }
        catch (IllegalArgumentException expected) { wrongTarget = true; }
        check(wrongTarget, "Anchor must remain limited to the declared chapter");
        int[] attempts = {0};
        boolean failed = false;
        try {
            int[] reads = {0};
            enabled.reset(() -> { }, () -> state(reads[0]++ == 0 ? 7 : 6, "viewport", "hash"), () -> attempts[0]++);
        } catch (IllegalStateException expected) { failed = true; }
        check(failed && attempts[0] == 1, "A changed page fails once, without adaptive anchor retry");
        failed = false;
        try { enabled.reset(() -> { }, () -> state(7, "viewport", "unchanged"), () -> { }); }
        catch (IllegalStateException expected) { failed = true; }
        check(failed, "A swallowed/no-op gesture cannot establish an interior baseline");
        boolean cancelled = false;
        try { enabled.reset(() -> { }, () -> state(7, "viewport", "hash"), () -> { throw new InterruptedException(); }); }
        catch (InterruptedException expected) { cancelled = true; }
        check(cancelled, "Cancellation must propagate instead of retrying anchor input");
        System.out.println("GREEN8 anchor policy: legacy isolation, identical phases, exact configuration, plan/backend scope, chapter scope, no retries, no-op rejection, cancellation");
    }
}

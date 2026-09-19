package app.mihon.validation.framework;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class ReaderSettingsPolicyTest {
    private interface Case { void run() throws Exception; }
    private static int failures;
    private static int cases;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void test(String name, Case action) {
        cases++;
        try { action.run(); System.out.println("PASS " + name); }
        catch (Throwable failure) { failures++; System.out.println("FAIL " + name + ": " + failure); }
    }
    private static final class Clock {
        long time;
        void sleep(long ms) { time += ms; }
    }
    public static void main(String[] args) {
        test("legacy and native page labels preserve the exact selected page", () -> {
            check(ReaderSettingsPolicy.matchesPageLabel("Image 7", "Image 7"), "Legacy label rejected");
            check(ReaderSettingsPolicy.matchesPageLabel("Image 7 · 12 saved pages", "Image 7"), "Native label rejected");
            for (String actual : Arrays.asList("Image 70 · 12 saved pages", "Image 8 · 12 saved pages",
                    "Image 7 · 0 saved pages", "Image 7 · 12 saved pages extra", "Image 7 anything", "Image 07")) {
                check(!ReaderSettingsPolicy.matchesPageLabel(actual, "Image 7"), "Loose page match: " + actual);
            }
        });
        test("font mutation failure keeps the durable restoration intent", () -> {
            ReaderSettingsPolicy.StyleMutation guard = new ReaderSettingsPolicy.StyleMutation();
            List<String> actions = new ArrayList<>();
            try {
                guard.mutate(() -> actions.add("journal"), () -> {
                    actions.add("font autosaved"); throw new IOException("Font popup or later opacity action failed");
                });
                throw new AssertionError("Mutation should fail");
            } catch (IOException expected) { }
            check(actions.equals(Arrays.asList("journal", "font autosaved")), "Intent must precede the first persisted field");
            check(guard.requiresRestoration(), "Font-only failure lost its restoration requirement");
        });
        test("journal failure prevents every settings mutation", () -> {
            ReaderSettingsPolicy.StyleMutation guard = new ReaderSettingsPolicy.StyleMutation();
            List<String> actions = new ArrayList<>();
            try {
                guard.mutate(() -> { throw new IOException("Storage full"); }, () -> actions.add("unsafe mutation"));
                throw new AssertionError("Journal should fail");
            } catch (IOException expected) { }
            check(actions.isEmpty(), "Mutation ran without a durable intent");
            check(guard.requiresRestoration(), "Uncertain journal must remain conservatively pending");
        });
        test("Saved must settle after delayed Saving rather than pass a stale status", () -> {
            Clock clock = new Clock();
            ReaderSettingsPolicy.awaitSaved(() -> clock.time < 100 || clock.time >= 650
                    ? ReaderSettingsPolicy.SaveState.SAVED : ReaderSettingsPolicy.SaveState.SAVING,
                () -> clock.time, clock::sleep, 5000);
            check(clock.time == 1050, "Save accepted before 400 ms of stable Saved: " + clock.time);
        });
        test("save failure never clears restoration or retries input", () -> {
            ReaderSettingsPolicy.StyleMutation guard = new ReaderSettingsPolicy.StyleMutation();
            Clock clock = new Clock();
            final int[] mutations = {0};
            try {
                guard.mutate(() -> { }, () -> {
                    mutations[0]++;
                    ReaderSettingsPolicy.awaitSaved(() -> clock.time >= 300
                            ? ReaderSettingsPolicy.SaveState.FAILED : ReaderSettingsPolicy.SaveState.SAVING,
                        () -> clock.time, clock::sleep, 5000);
                });
                throw new AssertionError("Storage error should fail");
            } catch (IllegalStateException expected) { }
            check(mutations[0] == 1 && guard.requiresRestoration(), "Failure retried or lost restoration");
        });
        test("cancellation and failed restoration retain the original obligation", () -> {
            ReaderSettingsPolicy.StyleMutation guard = new ReaderSettingsPolicy.StyleMutation();
            try {
                guard.mutate(() -> { }, () -> { throw new CancellationException("Interrupted after font"); });
            } catch (CancellationException expected) { }
            check(guard.requiresRestoration(), "Cancellation lost restore intent");
            try {
                guard.restored(() -> { throw new IllegalStateException("Original pixels differ"); },
                    () -> { throw new AssertionError("Cannot journal restoration before verification"); });
            } catch (IllegalStateException expected) { }
            check(guard.requiresRestoration(), "Failed verification cleared restore intent");
            try { guard.restored(() -> { }, () -> { throw new IOException("Journal interrupted"); }); }
            catch (IOException expected) { }
            check(guard.requiresRestoration(), "Incomplete final journal cleared restore intent");
            guard.restored(() -> { }, () -> { });
            check(!guard.requiresRestoration(), "Verified and persisted restoration remains pending");
        });
        test("changed scope, unknown status and suspended time cannot pass", () -> {
            Clock clock = new Clock();
            try {
                ReaderSettingsPolicy.awaitSaved(() -> { throw new IllegalStateException("Global defaults appeared"); },
                    () -> clock.time, clock::sleep, 5000);
                throw new AssertionError("Scope changes must fail");
            } catch (IllegalStateException expected) { }
            check(clock.time == 0, "Scope failure was retried");
            try {
                ReaderSettingsPolicy.awaitSaved(() -> ReaderSettingsPolicy.SaveState.UNKNOWN,
                    () -> clock.time, clock::sleep, 5000);
                throw new AssertionError("Missing status must fail");
            } catch (IllegalStateException expected) { }
            check(clock.time == 5000, "Timeout was extended: " + clock.time);
            clock.time = 0;
            try {
                ReaderSettingsPolicy.awaitSaved(() -> { clock.time += 6000; return ReaderSettingsPolicy.SaveState.SAVED; },
                    () -> clock.time, clock::sleep, 5000);
                throw new AssertionError("Late Saved must fail");
            } catch (IllegalStateException expected) { }
        });
        System.out.println((failures == 0 ? "GREEN " : "RED ") + cases + " cases, " + failures + " failures");
        if (failures != 0) throw new AssertionError(failures + " policy regressions failed");
    }
}

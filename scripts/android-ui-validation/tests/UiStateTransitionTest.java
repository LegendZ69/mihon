package app.mihon.validation.framework;

public final class UiStateTransitionTest {
    private static final class Scenario {
        long time;
        int actions;
        final long hideAt;
        Scenario(long hideAt) { this.hideAt = hideAt; }
        void tap() { actions++; }
        boolean hidden() { return time >= hideAt; }
        void sleep(long ms) { time += ms; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        // Actual classic callbacks defer single-tap confirmation, then animate the bars out for200ms.
        //350ms is the old settle's minimum; idle events do not guarantee animation completion.
        Scenario old = new Scenario(500);
        old.tap(); old.sleep(200); old.sleep(150);
        check(!old.hidden(), "The old fixed-wait policy unexpectedly met the delayed-state oracle");
        System.out.println("RED old-policy simulation: toolbar still visible after350ms; hidden at500ms");

        Scenario delayed = new Scenario(500);
        check(UiStateTransition.afterSingleAction(delayed::tap, delayed::hidden, () -> delayed.time,
            delayed::sleep, 5000), "Deferred tap plus animated exit must complete");
        check(delayed.actions == 1 && delayed.time == 500, "One action, then observed completion");
        Scenario blocked = new Scenario(Long.MAX_VALUE);
        check(!UiStateTransition.afterSingleAction(blocked::tap, blocked::hidden, () -> blocked.time,
            blocked::sleep, 5000), "Persistently visible controls must time out");
        check(blocked.actions == 1 && blocked.time == 5000, "Timeout must not retoggle or extend");
        Scenario immediate = new Scenario(0);
        check(UiStateTransition.afterSingleAction(immediate::tap, immediate::hidden, () -> immediate.time,
            immediate::sleep, 5000), "Immediately observed transition succeeds");
        check(immediate.actions == 1 && immediate.time == 0, "No arbitrary wait after observed completion");
        Scenario unsafe = new Scenario(500);
        boolean rejected = false;
        try {
            UiStateTransition.afterSingleAction(unsafe::tap, () -> { throw new IllegalStateException("Foreground changed"); },
                () -> unsafe.time, unsafe::sleep, 5000);
        } catch (IllegalStateException expected) { rejected = true; }
        check(rejected && unsafe.actions == 1, "Scope failure must propagate without more input");
        Scenario late = new Scenario(5100);
        check(!UiStateTransition.afterSingleAction(late::tap, late::hidden, () -> late.time,
            ms -> late.time += 6000, 5000), "Suspension cannot make a late state a passing bounded transition");
        System.out.println("GREEN5cases delayed completion, timeout, immediate, changed scope, overlong pause; one action each");
    }
}

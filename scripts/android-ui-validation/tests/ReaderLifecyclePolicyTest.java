package app.mihon.validation.framework;

public final class ReaderLifecyclePolicyTest {
    private static final class Fake implements ReaderLifecyclePolicy.Driver {
        int exits;
        int reopens;
        int verified;
        int completed;
        boolean ignoredBack;
        boolean reusedActivity;
        boolean changedPid;
        boolean failedPixels;
        @Override public ReaderLifecyclePolicy.State observe(String phase, int cycle) {
            boolean closed = phase.equals("exited");
            ReaderLifecyclePolicy.Screen screen = closed && !ignoredBack
                ? ReaderLifecyclePolicy.Screen.SERIES : ReaderLifecyclePolicy.Screen.READER;
            String token = closed && !ignoredBack ? "main" : "reader-" + (reusedActivity ? 0 : cycle);
            return new ReaderLifecyclePolicy.State(screen, token, changedPid && cycle > 0 ? 2 : 1);
        }
        @Override public void exitReader() { exits++; }
        @Override public void reopenChapter() { reopens++; }
        @Override public void verifyRestoredReader(int cycle) {
            if (failedPixels) throw new IllegalStateException("Viewport changed");
            verified++;
        }
        @Override public void completed(int cycle) { completed++; }
    }
    public static void main(String[] args) throws Exception {
        Fake passing = new Fake();
        ReaderLifecyclePolicy.run(10, passing);
        check(passing.exits == 10 && passing.reopens == 10 && passing.verified == 10 && passing.completed == 10,
            "Exactly ten exits, reopens and verified completions are required");
        Fake invalidCount = new Fake();
        fails(() -> ReaderLifecyclePolicy.run(1, invalidCount));
        check(invalidCount.exits == 0, "Invalid cycle count must fail before input");
        Fake comparisonOnly = new Fake();
        comparisonOnly.ignoredBack = true;
        fails(() -> ReaderLifecyclePolicy.run(10, comparisonOnly));
        check(comparisonOnly.exits == 1 && comparisonOnly.reopens == 0 && comparisonOnly.completed == 0,
            "A comparison-only or ignored Back transition must not count as lifecycle");
        Fake reuse = new Fake();
        reuse.reusedActivity = true;
        fails(() -> ReaderLifecyclePolicy.run(10, reuse));
        check(reuse.exits == 1 && reuse.reopens == 1 && reuse.completed == 0, "Activity reuse cannot pass");
        Fake newProcess = new Fake();
        newProcess.changedPid = true;
        fails(() -> ReaderLifecyclePolicy.run(10, newProcess));
        check(newProcess.reopens == 0 && newProcess.completed == 0, "Process restart must remain a separate workload");
        Fake pixels = new Fake();
        pixels.failedPixels = true;
        fails(() -> ReaderLifecyclePolicy.run(10, pixels));
        check(pixels.exits == 1 && pixels.reopens == 1 && pixels.completed == 0, "Changed pixels must stop without another attempt");
        System.out.println("GREEN actual lifecycle policy: ten exits and new Activity identities; failure is nonadaptive");
    }
    interface Action { void run() throws Exception; }
    private static void fails(Action action) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected explicit lifecycle rejection");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

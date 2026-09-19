package app.mihon.validation.framework;

import java.io.IOException;

public final class FrameBoundaryDiagnosticsTest {
    static final class Probe {
        long now;
        int captures;
        int reads;
        int sleeps;
        FrameBoundaryDiagnostics.Sample next = sample(true, 7);
        FrameBoundaryDiagnostics.Result run(FrameBoundaryDiagnostics.Sample first,
                FrameBoundaryDiagnostics.Capture capture) throws InterruptedException {
            return FrameBoundaryDiagnostics.inspect(7, first, () -> { reads++; return next; }, capture,
                () -> now, delay -> { sleeps++; now += delay; });
        }
    }
    static FrameBoundaryDiagnostics.Sample sample(boolean safe, Integer page) {
        return new FrameBoundaryDiagnostics.Sample(safe, page);
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Probe matching = new Probe();
        FrameBoundaryDiagnostics.Result matched = matching.run(sample(true, 7), () -> matching.captures++);
        check(!matched.firstMismatch && matching.captures == 0 && matching.reads == 0,
            "Passing windows must not take diagnostic screenshots or extra observations");

        Probe settles = new Probe();
        FrameBoundaryDiagnostics.Result failed = settles.run(sample(true, 8), () -> settles.captures++);
        check(failed.firstMismatch && failed.samples == 3 && settles.captures == 1 && failed.elapsedMs == 400,
            "First page8 failure must remain failed when both later observations return page7");

        Probe unsafe = new Probe();
        FrameBoundaryDiagnostics.Result refused = unsafe.run(sample(false, 7), () -> unsafe.captures++);
        check(refused.firstMismatch && unsafe.captures == 0 && unsafe.reads == 0 && unsafe.sleeps == 0,
            "An unexpected app or series must never be captured or explored");

        Probe losesScope = new Probe(); losesScope.next = sample(false, null);
        FrameBoundaryDiagnostics.Result lost = losesScope.run(sample(true, 8), () -> losesScope.captures++);
        check(lost.firstMismatch && losesScope.reads == 1 && lost.samples == 2,
            "Read-only observation stops when controlled scope disappears");

        Probe captureFails = new Probe();
        FrameBoundaryDiagnostics.Result limited = captureFails.run(sample(true, null), () -> { throw new IOException(); });
        check(limited.firstMismatch && limited.samples == 3 && IOException.class.getName().equals(limited.captureFailure),
            "Unavailable screenshot must not hide invalid geometry/page failure or prevent safe scalar observations");

        Probe slowCapture = new Probe();
        FrameBoundaryDiagnostics.Result expired = slowCapture.run(sample(true, 8), () -> { slowCapture.now += 1100; });
        check(expired.firstMismatch && slowCapture.reads == 0 && slowCapture.sleeps == 0,
            "Slow platform capture cannot extend the read-observation deadline");

        Probe interrupted = new Probe();
        boolean stopped = false;
        try { interrupted.run(sample(true, 8), () -> { throw new InterruptedException(); }); }
        catch (InterruptedException expected) { stopped = true; }
        check(stopped && interrupted.reads == 0, "Cancellation must not schedule more diagnostics");

        Probe invalid = new Probe();
        check(invalid.run(sample(true, null), () -> invalid.captures++).firstMismatch,
            "A malformed page sample cannot pass because later samples settle");
        System.out.println("GREEN8 boundary diagnostics: no pass-path effects, first-failure retention, scope safety, bounded observations, capture failure, deadline, cancellation, invalid sample");
    }
}

package app.mihon.validation.framework;

import java.util.function.LongSupplier;

/** Observes a failed boundary without changing its first outcome or sending reader input. */
final class FrameBoundaryDiagnostics {
    interface Observer { Sample read() throws Exception; }
    interface Capture { void capture() throws Exception; }
    interface Sleeper { void sleep(long milliseconds) throws Exception; }

    static final class Sample {
        final boolean controlledReader;
        final Integer roundedPage;
        Sample(boolean controlledReader, Integer roundedPage) {
            this.controlledReader = controlledReader;
            this.roundedPage = roundedPage;
        }
        boolean matches(int expected) {
            return controlledReader && roundedPage != null && roundedPage == expected;
        }
    }

    static final class Result {
        final boolean firstMismatch;
        int samples = 1;
        String captureFailure;
        String observationFailure;
        long elapsedMs;
        Result(boolean firstMismatch) { this.firstMismatch = firstMismatch; }
    }

    static Result inspect(int expected, Sample first, Observer observer, Capture capture,
            LongSupplier clock, Sleeper sleeper) throws InterruptedException {
        Result result = new Result(!first.matches(expected));
        if (!result.firstMismatch || !first.controlledReader) return result;
        long started = clock.getAsLong();
        long deadline = started + 1000;
        try {
            try {
                capture.capture();
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception failure) {
                result.captureFailure = failure.getClass().getName();
            }
            for (int sample = 1; sample < 3; sample++) {
                long remaining = deadline - clock.getAsLong();
                if (remaining <= 200) break;
                try {
                    sleeper.sleep(200);
                    if (clock.getAsLong() >= deadline) break;
                    Sample observed = observer.read();
                    result.samples++;
                    if (!observed.controlledReader) break;
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception failure) {
                    result.observationFailure = failure.getClass().getName();
                    break;
                }
            }
        } finally {
            result.elapsedMs = clock.getAsLong() - started;
        }
        return result;
    }
}

package app.mihon.validation.framework;

import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** One UI action followed by an observed state, without blind toggle retries. */
final class UiStateTransition {
    private UiStateTransition() { }

    static boolean afterSingleAction(Runnable action, BooleanSupplier expected, LongSupplier clock,
            LongConsumer pause, long timeoutMillis) {
        if (timeoutMillis < 0 || timeoutMillis > 30000) throw new IllegalArgumentException("Invalid UI timeout");
        action.run();
        long started = clock.getAsLong();
        while (true) {
            long elapsed = clock.getAsLong() - started;
            if (elapsed < 0 || elapsed > timeoutMillis) return false;
            if (expected.getAsBoolean()) return clock.getAsLong() - started <= timeoutMillis;
            if (elapsed >= timeoutMillis) return false;
            pause.accept(Math.min(50, timeoutMillis - elapsed));
        }
    }
}

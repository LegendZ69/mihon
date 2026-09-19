package app.mihon.validation.framework;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Device-independent guards used by the inspector and style-only framework plans. */
final class ReaderSettingsPolicy {
    private ReaderSettingsPolicy() { }

    interface Action { void run() throws Exception; }
    interface SaveObservation { SaveState get() throws Exception; }
    enum SaveState { SAVED, SAVING, FAILED, UNKNOWN }

    static boolean matchesPageLabel(String actual, String expected) {
        if (actual == null || expected == null || !expected.matches("Image [1-9][0-9]{0,4}")) return false;
        if (expected.equals(actual)) return true;
        String prefix = expected + " · ";
        return actual.startsWith(prefix) && actual.substring(prefix.length()).matches("[1-9][0-9]* saved pages");
    }

    /** Fail closed if persistence fails; the action must never precede its durable intent. */
    static final class StyleMutation {
        private boolean restoreRequired;
        boolean requiresRestoration() { return restoreRequired; }

        void mutate(Action persistIntent, Action mutation) throws Exception {
            restoreRequired = true;
            persistIntent.run();
            mutation.run();
        }

        void restored(Action verify, Action persistRestored) throws Exception {
            verify.run();
            persistRestored.run();
            restoreRequired = false;
        }
    }

    /** A debounced field cannot pass merely because its first observed status was still Saved. */
    static void awaitSaved(SaveObservation observation, LongSupplier clock, LongConsumer pause,
            long timeoutMillis) throws Exception {
        if (timeoutMillis < 400 || timeoutMillis > 30000) throw new IllegalArgumentException("Invalid save timeout");
        long started = clock.getAsLong();
        long savedSince = -1;
        while (true) {
            long elapsed = clock.getAsLong() - started;
            if (elapsed < 0 || elapsed > timeoutMillis) break;
            SaveState state = observation.get();
            long observed = clock.getAsLong();
            elapsed = observed - started;
            if (elapsed < 0 || elapsed > timeoutMillis) break;
            if (state == SaveState.FAILED) throw new IllegalStateException("Settings save failed; restoration remains required");
            if (state == SaveState.SAVED) {
                if (savedSince < 0) savedSince = observed;
                if (observed - savedSince >= 400) return;
            } else savedSince = -1;
            if (elapsed >= timeoutMillis) break;
            pause.accept(Math.min(50, timeoutMillis - elapsed));
        }
        throw new IllegalStateException("Settings did not report Saved before the timeout");
    }
}

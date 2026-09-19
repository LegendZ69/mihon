package app.mihon.validation.framework;

/** Exactly ten observed Activity exits/reopens; comparison toggles cannot satisfy this protocol. */
final class ReaderLifecyclePolicy {
    static final int CYCLES = 10;
    enum Screen { READER, SERIES }
    static final class State {
        final Screen screen;
        final String activityToken;
        final int pid;
        State(Screen screen, String activityToken, int pid) {
            this.screen = screen;
            this.activityToken = activityToken;
            this.pid = pid;
        }
    }
    interface Driver {
        State observe(String phase, int cycle) throws Exception;
        void exitReader() throws Exception;
        void reopenChapter() throws Exception;
        void verifyRestoredReader(int cycle) throws Exception;
        void completed(int cycle) throws Exception;
    }
    static void run(int cycles, Driver driver) throws Exception {
        require(cycles == CYCLES, "Reader lifecycle requires exactly ten cycles");
        State initial = driver.observe("initial", 0);
        requireState(initial, Screen.READER, initial == null ? -1 : initial.pid);
        State reader = initial;
        for (int cycle = 1; cycle <= CYCLES; cycle++) {
            driver.exitReader();
            State closed = driver.observe("exited", cycle);
            requireState(closed, Screen.SERIES, initial.pid);
            require(!closed.activityToken.equals(reader.activityToken), "Reader Activity did not exit");
            driver.reopenChapter();
            State reopened = driver.observe("reopened", cycle);
            requireState(reopened, Screen.READER, initial.pid);
            require(!reopened.activityToken.equals(reader.activityToken), "Reader Activity was reused, not recreated");
            driver.verifyRestoredReader(cycle);
            driver.completed(cycle);
            reader = reopened;
        }
    }
    private static void requireState(State state, Screen expected, int pid) {
        require(state != null, "Lifecycle observation is unavailable");
        require(state.screen == expected, "Required lifecycle screen was not observed");
        require(state.activityToken != null && !state.activityToken.isEmpty(), "Activity identity is unavailable");
        require(state.pid > 0 && state.pid == pid, "Subject process changed or PID is unavailable");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

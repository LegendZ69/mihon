package app.mihon.validation.framework;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FrameViewportResetTest {
    static final class Viewer implements FrameViewportReset.Navigator {
        float page = 7.000000476837158f;
        int offset = 137;
        boolean ignoreJump;
        final List<Integer> requests = new ArrayList<>();
        public float currentPage() { return page; }
        public void jumpToPage(int value) {
            requests.add(value);
            if (ignoreJump) return;
            // Same-page optimization models the old harness and UI slider behavior.
            if (FrameViewportReset.pageNumber(page) != value) { page = value; offset = 0; }
        }
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Viewer old = new Viewer();
        if (old.currentPage() != 7.000000476837158f) old.jumpToPage(7);
        check(old.offset == 137 && old.requests.isEmpty(), "Old same-page path must retain the wrong offset");
        System.out.println("RED old same-page simulation retains137px offset with zero navigation");
        Viewer viewer = new Viewer();
        check(FrameViewportReset.reset(7, 1, 12, viewer) == 6, "Adjacent page is within chapter");
        check(viewer.requests.equals(Arrays.asList(6, 7)) && viewer.offset == 0, "Must leave then return to reset offset");
        viewer.offset = -13;
        FrameViewportReset.reset(7, 1, 12, viewer);
        check(viewer.requests.equals(Arrays.asList(6, 7, 6, 7)) && viewer.offset == 0, "Repeat resets even with unchanged page identity");
        check(FrameViewportReset.pageNumber(7.000000476837158f) == 7, "Bounded slider rounding noise must be accepted");
        check(FrameViewportReset.adjacentPage(1, 1, 12) == 2 && FrameViewportReset.adjacentPage(12, 1, 12) == 11,
            "Both endpoints stay in the same chapter");
        Viewer wrong = new Viewer(); wrong.ignoreJump = true;
        boolean rejected = false;
        try { FrameViewportReset.reset(7, 1, 12, wrong); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected && wrong.requests.equals(Arrays.asList(6)), "Stop after an unobserved jump; never blind-bounce again");
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 7.25f, 0, 100001}) {
            rejected = false;
            try { FrameViewportReset.pageNumber(value); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid/noninteger page must fail before navigation");
        }
        Viewer singleton = new Viewer();
        rejected = false;
        try { FrameViewportReset.reset(1, 1, 1, singleton); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && singleton.requests.isEmpty(), "Cannot normalize a one-page range by crossing a chapter");
        Viewer other = new Viewer(); other.page = 9;
        FrameViewportReset.reset(7, 1, 12, other);
        check(other.requests.equals(Arrays.asList(7, 6, 7)) && other.offset == 0, "Failure restoration first returns to the selected page");
        System.out.println("GREEN8cases offset reset, repeated reset, rounding, endpoints, failed jump, invalid pages, singleton, return from other page");
    }
}

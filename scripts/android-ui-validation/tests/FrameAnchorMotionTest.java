package app.mihon.validation.framework;

import java.util.ArrayList;
import java.util.List;

public final class FrameAnchorMotionTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        FrameAnchorMotion.requireSlop(24);
        FrameAnchorPolicy policy = FrameAnchorPolicy.parse("framePairs", "webgpu", "100", true);
        check(policy.primed && policy.protocol().equals("slop_primed_interior_drag_v2"), "Candidate must be explicitly attributed");
        long[] clock = {1000};
        List<float[]> events = new ArrayList<>();
        FrameAnchorMotion.run(1000, 600, 1300, 100,
            (action, x, y) -> events.add(new float[]{action, x, y, clock[0]}), time -> clock[0] = time);
        check(events.size() == 28, "Exactly one down, one prime, 25 body moves and one up");
        check(events.get(0)[2] == 1396 && events.get(1)[2] == 1300, "Prime must be one fixed beyond-slop move");
        check(events.get(1)[3] == 1016 && events.get(2)[3] >= 1136, "Body must follow the declared prime hold");
        check(events.get(27)[0] == 1 && events.get(27)[2] == 1200 && clock[0] == 1856,
            "Complete body is 100 pixels with bounded 856 ms gesture duration");
        for (int index = 2; index < events.size(); index++) {
            check(events.get(index)[2] <= events.get(index - 1)[2], "No reversed movement or adaptive correction");
        }
        // A classifier consuming the first beyond-slop event sees the complete body afterward.
        float bodyDisplacement = events.get(1)[2] - events.get(27)[2];
        check(bodyDisplacement == 100, "Discarding only the prime must not shorten the motion body");
        for (float slop : new float[]{0, -1, 96, 200, Float.NaN, Float.POSITIVE_INFINITY}) {
            boolean failed = false;
            try { FrameAnchorMotion.requireSlop(slop); } catch (IllegalArgumentException expected) { failed = true; }
            check(failed, "Unsupported observed touch slop must fail before input");
        }
        boolean noAnchorRejected = false;
        try { FrameAnchorPolicy.parse("framePairs", "webgpu", "0", true); }
        catch (IllegalArgumentException expected) { noAnchorRejected = true; }
        check(noAnchorRejected, "Priming cannot silently affect page-start protocol");
        for (int interruptedAt : new int[]{0, 1, 7, 26}) {
            List<Integer> actions = new ArrayList<>();
            int[] waits = {0};
            boolean interrupted = false;
            try {
                FrameAnchorMotion.run(1000, 600, 1300, 100, (action, x, y) -> actions.add(action), time -> {
                    if (waits[0]++ == interruptedAt) throw new InterruptedException();
                });
            } catch (InterruptedException expected) { interrupted = true; }
            check(interrupted && actions.get(actions.size() - 1) == 3 && !actions.contains(1),
                "Interrupted gesture must cancel once without a release or retry");
        }
        System.out.println("GREEN primed anchor schedule: bounded input, explicit scope, slop rejection and cancellation; device pixels untested");
    }
}

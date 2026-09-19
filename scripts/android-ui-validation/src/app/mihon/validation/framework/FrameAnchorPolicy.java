package app.mihon.validation.framework;

/** Explicit fixture-only premeasurement protocol. Zero preserves the original page-start behavior. */
final class FrameAnchorPolicy {
    interface Action { void run() throws Exception; }
    interface Read { State read() throws Exception; }
    static final class State {
        final int page;
        final String viewport;
        final String pixels;
        State(int page, String viewport, String pixels) {
            this.page = page; this.viewport = viewport; this.pixels = pixels;
        }
    }
    final int requestedPixels;
    final boolean primed;
    private FrameAnchorPolicy(int pixels, boolean primed) { requestedPixels = pixels; this.primed = primed; }
    boolean enabled() { return requestedPixels != 0; }
    static FrameAnchorPolicy parse(String plan, String backend, String raw) {
        return parse(plan, backend, raw, false);
    }
    static FrameAnchorPolicy parse(String plan, String backend, String raw, boolean primed) {
        if (raw == null || raw.equals("0")) {
            if (primed) throw new IllegalArgumentException("Priming requires the explicit 100-pixel anchor");
            return new FrameAnchorPolicy(0, false);
        }
        if (!raw.equals("100") || !plan.equals("framePairs") || !"webgpu".equals(backend)) {
            throw new IllegalArgumentException("Interior anchor requires framePairs, webgpu and explicit frameAnchorPixels=100");
        }
        return new FrameAnchorPolicy(100, primed);
    }
    String protocol() { return primed ? "slop_primed_interior_drag_v2" : "fixed_interior_drag_v1"; }
    void requireTarget(String chapter, int page) {
        if (enabled() && (!"002 - Twelve-page corpus".equals(chapter) || page != 7)) {
            throw new IllegalArgumentException("Interior anchor is restricted to controlled chapter002, reader page7");
        }
    }
    void reset(Action canonicalReset, Read read, Action fixedDrag) throws Exception {
        canonicalReset.run();
        if (!enabled()) return;
        State before = read.read();
        check(before.page == 7 && before.viewport != null && !before.viewport.isEmpty(), "Anchor must start at page7 in the controlled viewport");
        fixedDrag.run();
        State after = read.read();
        check(after.page == 7 && before.viewport.equals(after.viewport), "Interior anchor changed page or viewport");
        check(before.pixels != null && after.pixels != null && !before.pixels.equals(after.pixels),
            "Interior anchor did not change source-coordinate viewport pixels");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}

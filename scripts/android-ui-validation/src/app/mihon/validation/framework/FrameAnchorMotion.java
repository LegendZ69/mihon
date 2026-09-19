package app.mihon.validation.framework;

/** Experimental input schedule only; exact device pixels remain the acceptance oracle. */
final class FrameAnchorMotion {
    static final int PRIME_PIXELS = 96;
    static final int PRIME_MOVE_MS = 16;
    static final int PRIME_HOLD_MS = 120;
    static final int MOTION_MS = 420;
    static final int RELEASE_HOLD_MS = 300;
    interface Touch { void send(int action, float x, float y) throws Exception; }
    interface Wait { void until(long uptimeMs) throws Exception; }

    static void requireSlop(float slop) {
        if (!Float.isFinite(slop) || slop <= 0 || slop >= PRIME_PIXELS) {
            throw new IllegalArgumentException("Observed touch slop cannot support the declared 96-pixel prime");
        }
    }

    static void run(long down, float x, float bodyStartY, int pixels, Touch touch, Wait wait) throws Exception {
        if (pixels != 100) throw new IllegalArgumentException("Only the declared 100-pixel body is supported");
        boolean released = false;
        float lastY = bodyStartY + PRIME_PIXELS;
        touch.send(0, x, lastY);
        try {
            wait.until(down + PRIME_MOVE_MS);
            lastY = bodyStartY;
            touch.send(2, x, lastY);
            long bodyStart = down + PRIME_MOVE_MS + PRIME_HOLD_MS;
            wait.until(bodyStart);
            for (int move = 1; move <= 25; move++) {
                wait.until(bodyStart + move * MOTION_MS / 25);
                double fraction = (1 - Math.cos(Math.PI * move / 25.0)) / 2;
                lastY = bodyStartY - pixels * (float) fraction;
                touch.send(2, x, lastY);
            }
            wait.until(bodyStart + MOTION_MS + RELEASE_HOLD_MS);
            touch.send(1, x, lastY);
            released = true;
        } finally {
            if (!released) touch.send(3, x, lastY);
        }
    }
}

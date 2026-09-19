package app.mihon.validation.framework;

/** A real page change forces reader navigation even when the selected page already matches. */
final class FrameViewportReset {
    interface Navigator {
        float currentPage() throws Exception;
        void jumpToPage(int page) throws Exception;
    }

    private FrameViewportReset() { }

    static int pageNumber(float value) {
        int page = Math.round(value);
        if (!Float.isFinite(value) || page < 1 || page > 100000 || Math.abs(value - page) > 0.001f) {
            throw new IllegalArgumentException("Slider does not identify a bounded integer page");
        }
        return page;
    }

    static int adjacentPage(int selected, float minimum, float maximum) {
        int min = pageNumber(minimum), max = pageNumber(maximum);
        if (selected < min || selected > max || min == max) {
            throw new IllegalArgumentException("A distinct page in the same controlled chapter is required");
        }
        return selected > min ? selected - 1 : selected + 1;
    }

    static int reset(int selected, float minimum, float maximum, Navigator navigator) throws Exception {
        int adjacent = adjacentPage(selected, minimum, maximum);
        if (pageNumber(navigator.currentPage()) != selected) jumpAndVerify(selected, navigator);
        jumpAndVerify(adjacent, navigator);
        jumpAndVerify(selected, navigator);
        return adjacent;
    }

    private static void jumpAndVerify(int page, Navigator navigator) throws Exception {
        navigator.jumpToPage(page);
        if (pageNumber(navigator.currentPage()) != page) {
            throw new IllegalStateException("Observed page did not follow the requested canonical reset");
        }
    }
}

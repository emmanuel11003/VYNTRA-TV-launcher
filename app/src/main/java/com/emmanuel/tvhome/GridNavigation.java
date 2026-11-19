package com.emmanuel.tvhome;

/** Physical left/right boundaries for a grid whose first column follows its reading direction. */
final class GridNavigation {
    private GridNavigation() { }

    static boolean atHorizontalEdge(int position, int count, int columns, boolean rtl, boolean left) {
        boolean towardStart = left != rtl;
        if (towardStart) return position % columns == 0;
        return position % columns == columns - 1 || position == count - 1;
    }

    /**
     * Vertical scroll delta to bring a focused grid row cleanly on screen.
     * Uses the full item bottom plus glass padding (not only the inner card rect passed by
     * RecyclerView.requestChildFocus), so Down scrolls straight to the final resting row
     * position and subsequent sideways moves in the same row produce 0 drift.
     */
    static int verticalRevealDy(int childTop, int childBottom, int rectTop, int rectHeight, int glassPad,
                                int limitTop, int limitBottom, int viewportHeight, int topTolerance,
                                boolean canScrollUp, boolean canScrollDown) {
        int bottom = Math.max(childBottom + glassPad, childTop + rectTop + rectHeight);
        int dy;
        if (childTop < limitTop - topTolerance) dy = childTop - limitTop;
        else if (bottom <= viewportHeight) dy = 0;
        else dy = Math.min(childTop - limitTop, bottom - limitBottom);
        if (dy > 0 && !canScrollDown) return 0;
        if (dy < 0 && !canScrollUp) return 0;
        return dy;
    }
}

package gregochr.cinefilm.credits;

import java.util.List;

/**
 * The picture rectangle of a film inside its frames, without letterbox or pillarbox bars. Every share the analysis
 * measures is taken inside this rectangle: a 2.39:1 film's bars are about a quarter of a 16:9 frame and would
 * otherwise count as dark.
 *
 * @param left the first picture column
 * @param top the first picture row
 * @param width the picture width
 * @param height the picture height
 */
public record ActiveArea(int left, int top, int width, int height) {

    /**
     * The whole frame.
     *
     * @param frame any frame of the size
     * @return the area covering all of it
     */
    public static ActiveArea whole(GreyFrame frame) {
        return new ActiveArea(0, 0, frame.width(), frame.height());
    }

    /**
     * Finds the picture area from sample frames of the same size taken mid-film. A row or column is a bar when it is
     * near-black in <em>every</em> sample, counted inwards from each edge. The whole frame is returned when every
     * frame is black or the result would be under half the frame in either dimension.
     *
     * @param samples at least one frame, all the same size
     * @return the picture rectangle
     * @throws IllegalArgumentException when there are no samples or their sizes differ
     */
    public static ActiveArea of(List<GreyFrame> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("At least one sample frame is needed");
        }
        GreyFrame first = samples.getFirst();
        int width = first.width();
        int height = first.height();
        for (GreyFrame frame : samples) {
            if (frame.width() != width || frame.height() != height) {
                throw new IllegalArgumentException("Sample frames must all be " + width + "x" + height);
            }
        }
        boolean[] rowBar = new boolean[height];
        for (int y = 0; y < height; y++) {
            rowBar[y] = true;
            for (GreyFrame frame : samples) {
                rowBar[y] &= isBarRow(frame, y);
            }
        }
        int top = leading(rowBar);
        if (top == height) {
            return whole(first);
        }
        int bottom = trailing(rowBar);
        boolean[] columnBar = new boolean[width];
        for (int x = 0; x < width; x++) {
            columnBar[x] = true;
            for (GreyFrame frame : samples) {
                columnBar[x] &= isBarColumn(frame, x, top, height - bottom);
            }
        }
        int left = leading(columnBar);
        if (left == width) {
            return whole(first);
        }
        int right = trailing(columnBar);
        int areaWidth = width - left - right;
        int areaHeight = height - top - bottom;
        if (areaWidth < width * CreditsTuning.MIN_ACTIVE_SHARE
            || areaHeight < height * CreditsTuning.MIN_ACTIVE_SHARE) {
            return whole(first);
        }
        return new ActiveArea(left, top, areaWidth, areaHeight);
    }

    private static boolean isBarRow(GreyFrame frame, int y) {
        int stray = 0;
        for (int x = 0; x < frame.width(); x++) {
            if (frame.luma(x, y) > CreditsTuning.BAR_MAX_LUMA) {
                stray++;
            }
        }
        return stray <= frame.width() * CreditsTuning.BAR_STRAY_SHARE;
    }

    private static boolean isBarColumn(GreyFrame frame, int x, int fromRow, int toRow) {
        int stray = 0;
        for (int y = fromRow; y < toRow; y++) {
            if (frame.luma(x, y) > CreditsTuning.BAR_MAX_LUMA) {
                stray++;
            }
        }
        return stray <= (toRow - fromRow) * CreditsTuning.BAR_STRAY_SHARE;
    }

    private static int leading(boolean[] bar) {
        int n = 0;
        while (n < bar.length && bar[n]) {
            n++;
        }
        return n;
    }

    /** Bars counted back from the end; the caller has already seen that at least one entry is not a bar. */
    private static int trailing(boolean[] bar) {
        int last = bar.length - 1;
        while (bar[last]) {
            last--;
        }
        return bar.length - 1 - last;
    }
}

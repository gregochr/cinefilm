package gregochr.cinefilm.credits;

/**
 * What is measured on one frame, inside the active area and relative to the window's peak luma. A frame that is
 * mostly dark measures its bright and sharp-edge limits against its own 99.9th percentile luma (clamped to between
 * half the window's peak and the peak), so dim or anti-aliased credits text far below the window's peak still counts.
 *
 * @param darkShare the share of pixels below 15% of the window's peak
 * @param brightShare the share of pixels above 65% of the frame's text limit
 * @param textLike whether the bright pixels are thin horizontal runs with sharp edges, in lines, mostly in the
 *     central 70% of the width, and not mostly in the bottom band (burned-in subtitles)
 */
public record FrameFeatures(double darkShare, double brightShare, boolean textLike) {

    /**
     * Measures a frame.
     *
     * @param frame the frame
     * @param area the picture rectangle to measure in
     * @param peak the window's peak luma ({@link PeakLuma})
     * @return the features
     */
    public static FrameFeatures measure(GreyFrame frame, ActiveArea area, int peak) {
        double darkLimit = Math.max(CreditsTuning.DARK_MIN_LUMA, CreditsTuning.DARK_FRACTION * peak);
        int top = area.top();
        int bottom = area.top() + area.height();
        int left = area.left();
        int right = area.left() + area.width();
        long[] histogram = new long[256];
        long dark = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int value = frame.luma(x, y);
                histogram[value]++;
                if (value < darkLimit) {
                    dark++;
                }
            }
        }
        double pixels = (double) area.width() * area.height();
        double darkShare = dark / pixels;
        int textLimit = darkShare >= CreditsTuning.TEXT_DARK_SHARE
            ? Math.max(peak / 2, Math.min(peak, percentile(histogram, area.width() * (long) area.height())))
            : peak;
        double brightLimit = CreditsTuning.BRIGHT_FRACTION * textLimit;
        double sharpLimit = CreditsTuning.SHARP_EDGE_FRACTION * textLimit;
        int thinMax = Math.max(CreditsTuning.THIN_RUN_MIN_PX,
            (int) (area.width() * CreditsTuning.THIN_RUN_WIDTH_FRACTION));
        int margin = (int) (area.width() * CreditsTuning.CENTRAL_MARGIN_FRACTION);
        int centralFrom = left + margin;
        int centralTo = right - margin;
        int bandFrom = top + (int) (CreditsTuning.SUBTITLE_BAND_START * area.height());
        long bright = 0;
        long text = 0;
        long bandText = 0;
        for (int y = top; y < bottom; y++) {
            int rowRuns = 0;
            long rowText = 0;
            int x = left;
            while (x < right) {
                if (frame.luma(x, y) <= brightLimit) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < right && frame.luma(x, y) > brightLimit) {
                    x++;
                }
                bright += x - start;
                boolean thin = x - start <= thinMax;
                boolean sharp = start > left && x < right
                    && frame.luma(start - 1, y) <= sharpLimit && frame.luma(x, y) <= sharpLimit;
                if (thin && sharp) {
                    rowRuns++;
                    rowText += Math.max(0, Math.min(x, centralTo) - Math.max(start, centralFrom));
                }
            }
            if (rowRuns >= CreditsTuning.MIN_RUNS_PER_TEXT_ROW) {
                text += rowText;
                if (y >= bandFrom) {
                    bandText += rowText;
                }
            }
        }
        boolean subtitles = text > 0 && (double) bandText / text >= CreditsTuning.SUBTITLE_MIN_SHARE;
        boolean textLike = bright > 0 && text >= CreditsTuning.TEXT_LIKE_SHARE * bright && !subtitles;
        return new FrameFeatures(darkShare, bright / pixels, textLike);
    }

    /** The smallest luma at which the histogram reaches the frame's text-limit percentile (as {@link PeakLuma}). */
    private static int percentile(long[] histogram, long total) {
        long target = (long) Math.ceil(total * CreditsTuning.TEXT_LIMIT_PERCENTILE);
        long seen = 0;
        int value = 0;
        while (value < 255) {
            seen += histogram[value];
            if (seen >= target) {
                break;
            }
            value++;
        }
        return value;
    }

    /**
     * Classifies the frame. Text on dark is tested first (dark share 0.70 or more, bright share 0.002 to 0.20,
     * text-like) so a sparse card, a name or two on black, is text and not black. Then black (dark share 0.98 or
     * more); otherwise picture.
     *
     * @return the class
     */
    public FrameClass frameClass() {
        if (darkShare >= CreditsTuning.TEXT_DARK_SHARE
            && brightShare >= CreditsTuning.TEXT_BRIGHT_MIN
            && brightShare <= CreditsTuning.TEXT_BRIGHT_MAX
            && textLike) {
            return FrameClass.TEXT_ON_DARK;
        }
        if (darkShare >= CreditsTuning.BLACK_DARK_SHARE) {
            return FrameClass.BLACK;
        }
        return FrameClass.PICTURE;
    }
}

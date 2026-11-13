package gregochr.cinefilm.credits;

/**
 * What is measured on one frame, inside the active area and relative to the window's peak luma.
 *
 * @param darkShare the share of pixels below 15% of the peak
 * @param brightShare the share of pixels above 65% of the peak
 * @param textLike whether the bright pixels are thin horizontal runs with sharp edges, in lines, mostly in the
 *     central 70% of the width
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
        double brightLimit = CreditsTuning.BRIGHT_FRACTION * peak;
        double sharpLimit = CreditsTuning.SHARP_EDGE_FRACTION * peak;
        int left = area.left();
        int right = area.left() + area.width();
        int thinMax = Math.max(CreditsTuning.THIN_RUN_MIN_PX,
            (int) (area.width() * CreditsTuning.THIN_RUN_WIDTH_FRACTION));
        int margin = (int) (area.width() * CreditsTuning.CENTRAL_MARGIN_FRACTION);
        int centralFrom = left + margin;
        int centralTo = right - margin;
        long dark = 0;
        long bright = 0;
        long text = 0;
        for (int y = area.top(); y < area.top() + area.height(); y++) {
            int rowRuns = 0;
            long rowText = 0;
            int x = left;
            while (x < right) {
                int value = frame.luma(x, y);
                if (value <= brightLimit) {
                    if (value < darkLimit) {
                        dark++;
                    }
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
            }
        }
        double pixels = (double) area.width() * area.height();
        boolean textLike = bright > 0 && text >= CreditsTuning.TEXT_LIKE_SHARE * bright;
        return new FrameFeatures(dark / pixels, bright / pixels, textLike);
    }

    /**
     * Classifies the frame. Text on dark is tested first (dark share 0.80 or more, bright share 0.002 to 0.20,
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

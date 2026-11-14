package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.RgbFrame;

/**
 * The measurements of one stored frame, for the dataset measurement tool. Pure.
 *
 * <p>The thresholds {@value #DARK_BELOW} and {@value #BRIGHT_ABOVE} are measurement placeholders, chosen only to show
 * how much of a frame is near black or near white; L3a re-derives the real ones on development frames. A pixel is dark
 * when it is strictly below {@value #DARK_BELOW} and bright when it is strictly above {@value #BRIGHT_ABOVE}.
 *
 * <p>Percentiles are exact integers by the nearest-rank method: over the {@code n} pixels sorted ascending, the
 * {@code p}th percentile is the value at rank {@code ceil(p * n / 100)} (rank 1 is the smallest).
 */
final class FrameStats {

    /** A grey pixel below this is counted as dark (a measurement placeholder). */
    static final int DARK_BELOW = 35;
    /** A grey pixel above this is counted as bright (a measurement placeholder). */
    static final int BRIGHT_ABOVE = 200;

    private static final int LEVELS = 256;

    private FrameStats() {
    }

    /**
     * The measurements of a grey frame.
     *
     * @param mean the mean grey value
     * @param p5 the 5th percentile
     * @param p50 the median (50th percentile)
     * @param p95 the 95th percentile
     * @param darkShare the share of pixels below {@value #DARK_BELOW}
     * @param brightShare the share of pixels above {@value #BRIGHT_ABOVE}
     */
    record Grey(double mean, int p5, int p50, int p95, double darkShare, double brightShare) {
    }

    /**
     * The measurements of a colour frame.
     *
     * @param meanR the mean red
     * @param meanG the mean green
     * @param meanB the mean blue
     * @param chroma the mean over pixels of {@code max(R, G, B) - min(R, G, B)}
     */
    record Colour(double meanR, double meanG, double meanB, double chroma) {
    }

    /**
     * Measures a grey frame.
     *
     * @param frame the frame
     * @return its measurements
     */
    static Grey grey(GreyFrame frame) {
        long[] histogram = new long[LEVELS];
        long sum = 0;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int luma = frame.luma(x, y);
                histogram[luma]++;
                sum += luma;
            }
        }
        long count = (long) frame.width() * frame.height();
        long dark = 0;
        long bright = 0;
        for (int level = 0; level < LEVELS; level++) {
            if (level < DARK_BELOW) {
                dark += histogram[level];
            }
            if (level > BRIGHT_ABOVE) {
                bright += histogram[level];
            }
        }
        return new Grey((double) sum / count, percentile(histogram, count, 5), percentile(histogram, count, 50),
            percentile(histogram, count, 95), (double) dark / count, (double) bright / count);
    }

    /**
     * Measures a colour frame.
     *
     * @param frame the frame
     * @return its measurements
     */
    static Colour colour(RgbFrame frame) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long chroma = 0;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int rgb = frame.rgb(x, y);
                int r = rgb >> 16 & 0xFF;
                int g = rgb >> 8 & 0xFF;
                int b = rgb & 0xFF;
                red += r;
                green += g;
                blue += b;
                chroma += Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            }
        }
        double count = (double) frame.width() * frame.height();
        return new Colour(red / count, green / count, blue / count, chroma / count);
    }

    private static int percentile(long[] histogram, long count, int percent) {
        long rank = (percent * count + 99) / 100;
        long seen = 0;
        for (int level = 0; level < LEVELS; level++) {
            seen += histogram[level];
            if (seen >= rank) {
                return level;
            }
        }
        throw new IllegalStateException("rank " + rank + " is beyond the " + count + " pixels");
    }
}

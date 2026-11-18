package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;
import java.util.List;
import java.util.Optional;

/**
 * The opening type of a film and the kind of a single frame (plan, "Opening type"). Pure. Frames are the full-range
 * {@code FramePrep.grey} frames inside {@link ActiveCrop}, never the product's limited-range ones.
 */
public final class OpeningType {

    /** {@code DARK} when the median is below this (plan, "Opening type", {@code frameKind}). */
    static final int DARK_MEDIAN_BELOW = 4;
    /** {@code PLATE} needs a mean of at least this (plan, "Opening type", {@code frameKind}). */
    static final double PLATE_MEAN_AT_LEAST = 121;
    /** {@code PLATE} needs a 10th to 90th percentile spread of at most this (plan, "Opening type"). */
    static final int PLATE_SPREAD_AT_MOST = 47;
    /** The first look, after the start (plan, "Opening type", {@code of}): decides DARK. */
    static final long FIRST_LOOK_MS = 5_000;
    /** The second look, after the start (plan, "Opening type", {@code of}). */
    static final long SECOND_LOOK_MS = 20_000;
    /** The third look, after the start (plan, "Opening type", {@code of}). */
    static final long THIRD_LOOK_MS = 45_000;

    private static final int LEVELS = 256;
    private static final int P10 = 10;
    private static final int P50 = 50;
    private static final int P90 = 90;

    private OpeningType() {
    }

    /**
     * The kind of a frame from its stats.
     *
     * @param mean the mean luma
     * @param p10 the 10th percentile
     * @param p50 the median
     * @param p90 the 90th percentile
     * @return DARK, else PLATE, else LIT
     */
    public static FrameKind frameKind(double mean, int p10, int p50, int p90) {
        if (p50 < DARK_MEDIAN_BELOW) {
            return FrameKind.DARK;
        }
        if (mean >= PLATE_MEAN_AT_LEAST && p90 - p10 <= PLATE_SPREAD_AT_MOST) {
            return FrameKind.PLATE;
        }
        return FrameKind.LIT;
    }

    /**
     * The kind of a cropped grey frame; nearest-rank percentiles, mean {@code (double) sum / n}.
     *
     * @param crop the frame inside the active crop
     * @return its kind
     */
    public static FrameKind frameKind(GreyFrame crop) {
        long[] histogram = new long[LEVELS];
        long sum = 0;
        for (int y = 0; y < crop.height(); y++) {
            for (int x = 0; x < crop.width(); x++) {
                int luma = crop.luma(x, y);
                histogram[luma]++;
                sum += luma;
            }
        }
        long count = (long) crop.width() * crop.height();
        return frameKind((double) sum / count, FrameStats.percentile(histogram, count, P10),
            FrameStats.percentile(histogram, count, P50), FrameStats.percentile(histogram, count, P90));
    }

    /**
     * The opening type: DARK if the +5 s frame is DARK, else PLATE if any of +5, +20 and +45 s is PLATE, else LIT.
     * Each is the entry nearest that time; on a tie the earlier entry wins.
     *
     * @param coarse the coarse frames' kinds, sorted by offset
     * @param startMs the start of the film's opening
     * @return the type, or empty when the list is empty or no entry is at or after {@code startMs}
     */
    public static Optional<FrameKind> of(List<KindAt> coarse, long startMs) {
        if (coarse.isEmpty() || coarse.get(coarse.size() - 1).offsetMs() < startMs) {
            return Optional.empty();
        }
        FrameKind first = nearest(coarse, startMs + FIRST_LOOK_MS);
        if (first == FrameKind.DARK) {
            return Optional.of(FrameKind.DARK);
        }
        if (first == FrameKind.PLATE || nearest(coarse, startMs + SECOND_LOOK_MS) == FrameKind.PLATE
            || nearest(coarse, startMs + THIRD_LOOK_MS) == FrameKind.PLATE) {
            return Optional.of(FrameKind.PLATE);
        }
        return Optional.of(FrameKind.LIT);
    }

    private static FrameKind nearest(List<KindAt> coarse, long timeMs) {
        KindAt best = coarse.get(0);
        long bestGap = Long.MAX_VALUE;
        for (KindAt entry : coarse) {
            long gap = Math.abs(entry.offsetMs() - timeMs);
            if (gap < bestGap) {
                best = entry;
                bestGap = gap;
            }
        }
        return best.kind();
    }
}

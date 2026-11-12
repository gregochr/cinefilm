package gregochr.cinefilm.subtitle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fits the start times of a forced subtitle file to the start times of the full English subtitle track inside the
 * film's own media file. Pure: two lists of times in, a result out.
 *
 * <p>A full track has a cue every few seconds and contains every line the forced file has (translations of the foreign
 * dialogue included), so the forced cues are a subset of it in time. That makes it a far stronger reference than a
 * download or speech, and the match window is tight on purpose ({@value #WINDOW_MS} ms): with a cue every ~5 s a 2 s
 * window would match at random.
 *
 * <p>Candidate offsets are the differences {@code reference - served} within {@value #SEARCH_MS} ms, bucketed to
 * {@value #BUCKET_MS} ms; the {@value #MAX_CANDIDATES} most frequent buckets are scored, then the best is refined in
 * {@value #REFINE_STEP_MS} ms steps for {@value #WINDOW_MS} ms either way (a window's width, so cues that wander
 * either side of one offset are fitted at the middle, not at a bucket that only half of them fall in). An offset
 * scores the served cues that have a reference start within the window. Best = most matched, then least total
 * distance, then smallest absolute offset, then the lower offset (as {@link SubtitleComparison}).
 *
 * <p>{@link Verdict#ALIGNED} needs at least {@value #MIN_CUES} served cues, a matched share of at least 0.80, a fitted
 * offset within 10 s, a median residual of at most {@value #MAX_MEDIAN_RESIDUAL_MS} ms, and a share that beats the
 * share at deliberately wrong offsets (best plus and minus 5 s) by at least 0.4, so a dense reference cannot produce
 * a fit by chance. Fewer than {@value #MIN_CUES} cues is {@link Verdict#TOO_FEW}; anything else is
 * {@link Verdict#CHECK} with reasons in plain words.
 */
public final class TimingFit {

    /** A served cue matches when its shifted start is this close to a reference start (inclusive). */
    static final long WINDOW_MS = 300;
    /** The largest offset an {@link Verdict#ALIGNED} fit may have, in milliseconds. */
    static final long MAX_ALIGNED_OFFSET_MS = 10_000;
    /**
     * How far either way offsets are looked for. Wider than the largest accepted offset, so a file that is 14 s out
     * is reported as "more than 10 s out" rather than as lines that do not line up.
     */
    static final long SEARCH_MS = 60_000;
    /** Offsets are bucketed to this many milliseconds. */
    static final long BUCKET_MS = 100;
    /** The refinement step around the best bucket. */
    static final long REFINE_STEP_MS = 10;
    /** The most candidate buckets scored. */
    static final int MAX_CANDIDATES = 40;
    /** Fewer served cues than this cannot be checked. */
    static final int MIN_CUES = 8;
    /** The least share of served cues that must line up. */
    static final double MIN_SHARE = 0.80;
    /** The largest median distance of matched cues, in milliseconds. */
    static final long MAX_MEDIAN_RESIDUAL_MS = 120;
    /** How far the best share must beat the share at a wrong offset. */
    static final double MIN_MARGIN = 0.4;
    /** The deliberately wrong offsets the chance share is measured at (either side of the best). */
    static final long WRONG_OFFSET_MS = 5_000;

    /** Most matches, then the least total distance, then the smaller absolute offset, then the lower offset. */
    private static final Comparator<Score> BEST_FIRST = Comparator.comparingInt(Score::matched).reversed()
        .thenComparingLong(Score::distance).thenComparingLong(score -> Math.abs(score.offsetMs()))
        .thenComparingLong(Score::offsetMs);

    private TimingFit() {
    }

    /** The verdict of a timing fit. */
    public enum Verdict {
        /** The file's cue times line up with the film's English track at one offset. */
        ALIGNED,
        /** They do not: the owner should look. */
        CHECK,
        /** The file has too few lines to tell. */
        TOO_FEW,
        /** The film has no English subtitle track to compare with. */
        NO_REFERENCE
    }

    /**
     * The outcome.
     *
     * @param verdict the verdict
     * @param fittedOffsetMs the milliseconds to add to every cue to line up best, a multiple of 10 (0 when nothing was
     *     compared)
     * @param matchedShare the share (0 to 1) of served cues within the window at that offset
     * @param matchedCount how many served cues that is
     * @param servedCount how many cues the file has
     * @param medianResidualMs the median distance of the matched cues from their reference start at that offset
     * @param reasons plain sentences: why it is not aligned; empty when it is
     */
    public record Result(Verdict verdict, int fittedOffsetMs, double matchedShare, int matchedCount, int servedCount,
                         int medianResidualMs, List<String> reasons) {
    }

    /** An offset and how well it lines the files up. */
    private record Score(long offsetMs, int matched, long distance, long[] residuals) {
    }

    /** The result for a film with no usable reference track. */
    static Result noReference(int servedCount, String reason) {
        return new Result(Verdict.NO_REFERENCE, 0, 0.0, 0, servedCount, 0, List.of(reason));
    }

    /**
     * Fits a file's cue starts to a reference's.
     *
     * @param served the file's cue starts in milliseconds, as stored (no owner offset), on the timeline of the span
     *     the file covers (its own part, or the whole film)
     * @param reference the full track's cue starts on the same timeline, restricted to that span
     * @return the result
     */
    public static Result fit(List<Long> served, List<Long> reference) {
        int servedCount = served.size();
        if (servedCount < MIN_CUES) {
            return new Result(Verdict.TOO_FEW, 0, 0.0, 0, servedCount, 0,
                List.of("Too few lines to check (" + servedCount + ", at least " + MIN_CUES + " are needed)"));
        }
        if (reference.isEmpty()) {
            return noReference(servedCount, "The film's English track has no lines to compare with");
        }
        long[] s = sorted(served);
        long[] r = sorted(reference);
        Score best = best(s, r);
        double share = (double) best.matched() / servedCount;
        int median = median(best.residuals());
        double chance = Math.max(score(s, r, best.offsetMs() + WRONG_OFFSET_MS).matched(),
            score(s, r, best.offsetMs() - WRONG_OFFSET_MS).matched()) / (double) servedCount;
        List<String> reasons = new ArrayList<>();
        if (share < MIN_SHARE) {
            reasons.add("Only " + best.matched() + " of " + servedCount
                + " lines line up with the film's English track");
        }
        if (Math.abs(best.offsetMs()) > MAX_ALIGNED_OFFSET_MS) {
            reasons.add("Best fit is more than 10 s out (" + seconds(best.offsetMs()) + ")");
        }
        if (share >= MIN_SHARE && median > MAX_MEDIAN_RESIDUAL_MS) {
            reasons.add("The lines line up only loosely (typically " + median + " ms off)");
        }
        if (share >= MIN_SHARE && share - chance < MIN_MARGIN) {
            reasons.add("The film's English track has so many lines that this fit could be chance");
        }
        Verdict verdict = reasons.isEmpty() ? Verdict.ALIGNED : Verdict.CHECK;
        return new Result(verdict, (int) best.offsetMs(), share, best.matched(), servedCount, median,
            List.copyOf(reasons));
    }

    private static String seconds(long offsetMs) {
        return String.format(Locale.ROOT, "%.1f s %s", Math.abs(offsetMs) / 1000.0, offsetMs > 0 ? "later" : "earlier");
    }

    private static long[] sorted(List<Long> times) {
        long[] values = times.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(values);
        return values;
    }

    private static Score best(long[] s, long[] r) {
        Score best = null;
        for (long offset : candidates(s, r)) {
            Score score = score(s, r, offset);
            if (best == null || BEST_FIRST.compare(score, best) < 0) {
                best = score;
            }
        }
        if (best == null) {
            // No two times were within the search range of each other: nothing lines up at any offset.
            return score(s, r, 0);
        }
        Score refined = best;
        for (long offset = best.offsetMs() - WINDOW_MS; offset <= best.offsetMs() + WINDOW_MS;
             offset += REFINE_STEP_MS) {
            Score score = score(s, r, offset);
            if (BEST_FIRST.compare(score, refined) < 0) {
                refined = score;
            }
        }
        return refined;
    }

    /** The most frequent difference buckets within the search range, ties by smaller absolute offset then lower. */
    static List<Long> candidates(long[] s, long[] r) {
        Map<Long, Integer> counts = new HashMap<>();
        for (long start : s) {
            int from = lowerBound(r, start - SEARCH_MS - BUCKET_MS / 2);
            for (int j = from; j < r.length && r[j] - start <= SEARCH_MS + BUCKET_MS / 2; j++) {
                long bucket = Math.floorDiv(r[j] - start + BUCKET_MS / 2, BUCKET_MS) * BUCKET_MS;
                counts.merge(bucket, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
            .sorted(Map.Entry.<Long, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparingLong(e -> Math.abs(e.getKey())).thenComparingLong(Map.Entry::getKey))
            .limit(MAX_CANDIDATES).map(Map.Entry::getKey).toList();
    }

    private static int lowerBound(long[] sorted, long value) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid] < value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** How many served cues land within the window of a reference start after the offset, and how far off they are. */
    private static Score score(long[] s, long[] r, long offset) {
        List<Long> residuals = new ArrayList<>();
        long distance = 0;
        int j = 0;
        for (long start : s) {
            long shifted = start + offset;
            while (j < r.length && r[j] < shifted) {
                j++;
            }
            long nearest = Long.MAX_VALUE;
            if (j < r.length) {
                nearest = r[j] - shifted;
            }
            if (j > 0) {
                nearest = Math.min(nearest, shifted - r[j - 1]);
            }
            if (nearest <= WINDOW_MS) {
                residuals.add(nearest);
                distance += nearest;
            }
        }
        return new Score(offset, residuals.size(), distance, residuals.stream().mapToLong(Long::longValue).toArray());
    }

    /** The median of the residuals, rounded down; 0 when there are none. */
    private static int median(long[] residuals) {
        if (residuals.length == 0) {
            return 0;
        }
        long[] sorted = residuals.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return (int) (sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2);
    }
}

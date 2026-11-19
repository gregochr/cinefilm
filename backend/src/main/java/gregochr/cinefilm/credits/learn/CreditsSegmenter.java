package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.CreditsFrameReader;
import java.util.OptionalLong;

/**
 * Finds where a film's credits start from the model's per-frame probabilities
 * ({@code docs/engineering/credits-classifier-plan.md}, "Finding the start" and "L3c specification",
 * {@code CreditsSegmenter}). Pure: no clock, no I/O, no Spring.
 *
 * <p><strong>Frames are counted, not timed.</strong> Every length in {@link #coarse} is a count of adjacent coarse
 * frames, {@link CreditsFrameReader#COARSE_STEP_MS} (5 s) each, so 30 s is {@value #MIN_FRAMES} frames. A stretch is
 * an index range {@code [a, b)}, and {@code S(a, b)} is the sum of the frames' scores in it. The vetoes and the
 * reasons text of the plan's steps 6 and 7 belong to the product (L6a), not here.
 */
final class CreditsSegmenter {

    /** The shortest credits stretch, 30 s, in coarse frames. */
    static final int MIN_FRAMES = 6;

    /** A leading island of this many frames (60 s) or more is kept. */
    static final int ISLAND_KEEP_FRAMES = 12;

    /** The coarse frames, from the start, that must each reach {@code theta} (15 s). */
    static final int LEADING_FRAMES = 3;

    /** The spacing of dense offsets. */
    static final long DENSE_STEP_MS = 500;

    /** How far before the coarse start the dense search begins. */
    static final long DENSE_LOOKBACK_MS = 5_000;

    /** The dense frames of one 5 s window, both ends included. */
    static final int DENSE_WINDOW = 11;

    private static final double CLAMP = 1e-15;

    private CreditsSegmenter() {
    }

    /** What the segmenter decided. */
    enum Verdict {
        /** A start that is confident. */
        FOUND,
        /** A start that is not confident enough. */
        UNSURE,
        /** No stretch to call credits. */
        NOT_FOUND
    }

    /**
     * The result of the coarse steps 1 to 4.
     *
     * @param verdict the verdict
     * @param t the start's offset, empty for {@code NOT_FOUND}
     * @param tIndex the start's index, -1 for {@code NOT_FOUND}
     * @param uIndex the best stretch's end index ({@code u*}), -1 when there are fewer than
     *     {@value #MIN_FRAMES} frames
     * @param bestScore {@code S*}, NaN when there are fewer than {@value #MIN_FRAMES} frames
     * @param margin {@code S(t, u*)}, NaN for {@code NOT_FOUND}
     * @param minP the smallest {@code p} of the three frames from {@code t}, NaN for {@code NOT_FOUND}
     */
    record Coarse(Verdict verdict, OptionalLong t, int tIndex, int uIndex, double bestScore, double margin,
                  double minP) {

        private static Coarse notFound(int endIndex, double bestScore) {
            return new Coarse(Verdict.NOT_FOUND, OptionalLong.empty(), -1, endIndex, bestScore, Double.NaN,
                Double.NaN);
        }
    }

    /**
     * Steps 1 to 4: the best stretch, leaning late, leading islands dropped, then the confidence check.
     *
     * @param offsets the film's coarse offsets in ms, strictly ascending
     * @param p the probability of credits at each offset, the same length, none NaN
     * @param logitPi {@code logit(π)}, with {@code π} the training share of credits frames
     * @param params the calibrated numbers
     * @return the result; {@code NOT_FOUND} when there are fewer than {@value #MIN_FRAMES} frames, or when dropping
     *     islands leaves fewer than {@value #MIN_FRAMES} frames
     * @throws IllegalArgumentException when the arrays differ in length, offsets are not strictly ascending or a
     *     {@code p} is NaN
     */
    static Coarse coarse(long[] offsets, double[] p, double logitPi, SegmenterParams params) {
        if (offsets.length != p.length) {
            throw new IllegalArgumentException("offsets and p differ in length: " + offsets.length + ", " + p.length);
        }
        for (int i = 1; i < offsets.length; i++) {
            if (offsets[i] <= offsets[i - 1]) {
                throw new IllegalArgumentException("offsets are not strictly ascending at index " + i);
            }
        }
        int n = p.length;
        if (n < MIN_FRAMES) {
            return Coarse.notFound(-1, Double.NaN);
        }
        double[] score = scores(p, n, logitPi, params.lambda());
        double[] prefix = prefix(score);
        int[] best = best(prefix, n);
        int u = best[1];
        double bestScore = prefix[u] - prefix[best[0]];
        int t = latest(prefix, u, bestScore - params.delta());
        int[] run = firstRun(score, t, u, params.gapFrames());
        while (run != null && run[0] - t < ISLAND_KEEP_FRAMES) {
            int s = run[1];
            if (u - s < MIN_FRAMES) {
                return Coarse.notFound(u, bestScore);
            }
            t = latest(prefix, u, prefix[u] - prefix[s] - params.delta());
            run = firstRun(score, t, u, params.gapFrames());
        }
        double margin = prefix[u] - prefix[t];
        double minP = p[t];
        for (int i = t + 1; i < t + LEADING_FRAMES; i++) {
            minP = Math.min(minP, p[i]);
        }
        Verdict verdict = margin >= params.sigma() && minP >= params.theta() ? Verdict.FOUND : Verdict.UNSURE;
        return new Coarse(verdict, OptionalLong.of(offsets[t]), t, u, bestScore, margin, minP);
    }

    /**
     * Step 5: the dense search for the start.
     *
     * @param t the coarse start in ms
     * @param offsets the dense offsets in ms, strictly ascending, on one 500 ms grid
     * @param p the probability at each offset, NaN where a frame has no time features
     * @param thetaDense the least {@code p} of every frame of the window
     * @return the smallest offset {@code f >= t - 5000} such that the 11 offsets {@code f, f + 500, ..., f + 5000}
     *     are all present and none has a NaN {@code p} or one below {@code thetaDense}; empty when none qualifies
     *     (the caller uses {@code t})
     * @throws IllegalArgumentException when the arrays differ in length, offsets are not strictly ascending or are
     *     not on one 500 ms grid, or {@code thetaDense} is outside [0, 1]
     */
    static OptionalLong dense(long t, long[] offsets, double[] p, double thetaDense) {
        if (!(thetaDense >= 0 && thetaDense <= 1)) {
            throw new IllegalArgumentException("thetaDense must be in [0, 1]: " + thetaDense);
        }
        if (offsets.length != p.length) {
            throw new IllegalArgumentException("offsets and p differ in length: " + offsets.length + ", " + p.length);
        }
        for (int i = 1; i < offsets.length; i++) {
            if (offsets[i] <= offsets[i - 1]) {
                throw new IllegalArgumentException("offsets are not strictly ascending at index " + i);
            }
            if ((offsets[i] - offsets[0]) % DENSE_STEP_MS != 0) {
                throw new IllegalArgumentException("offsets are not on one " + DENSE_STEP_MS + " ms grid at index "
                    + i);
            }
        }
        long window = DENSE_STEP_MS * (DENSE_WINDOW - 1);
        for (int i = 0; i + DENSE_WINDOW <= offsets.length; i++) {
            // Strictly ascending on one grid: ends 5000 ms apart, eleven entries apart, means all eleven are present.
            if (offsets[i] >= t - DENSE_LOOKBACK_MS && offsets[i + DENSE_WINDOW - 1] - offsets[i] == window
                && allAtLeast(p, i, thetaDense)) {
                return OptionalLong.of(offsets[i]);
            }
        }
        return OptionalLong.empty();
    }

    /** Whether the {@link #DENSE_WINDOW} values from {@code from} are all at least {@code theta} (NaN is not). */
    private static boolean allAtLeast(double[] p, int from, double theta) {
        for (int i = from; i < from + DENSE_WINDOW; i++) {
            if (!(p[i] >= theta)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code S*} over the frames {@code [0, end)} only, the best film-only stretch the calibration's negatives use.
     *
     * @param p the probability of credits at each coarse frame
     * @param end the number of frames to look at
     * @param logitPi {@code logit(π)}
     * @param lambda the per-frame penalty
     * @return the largest {@code S(a, b)} with {@code b - a >= 6} and {@code b <= end}, or negative infinity when
     *     {@code end < 6}
     * @throws IllegalArgumentException when {@code end} is above the length of {@code p}, or a {@code p} in range is
     *     NaN
     */
    static double bestStretch(double[] p, int end, double logitPi, double lambda) {
        if (end > p.length) {
            throw new IllegalArgumentException("end is past the last frame: " + end + " of " + p.length);
        }
        if (end < MIN_FRAMES) {
            return Double.NEGATIVE_INFINITY;
        }
        double[] prefix = prefix(scores(p, end, logitPi, lambda));
        int[] best = best(prefix, end);
        return prefix[best[1]] - prefix[best[0]];
    }

    /** {@code logit(clamp(p_i)) - logitPi - lambda} for the first {@code end} frames. */
    private static double[] scores(double[] p, int end, double logitPi, double lambda) {
        double[] score = new double[end];
        for (int i = 0; i < end; i++) {
            if (Double.isNaN(p[i])) {
                throw new IllegalArgumentException("p is NaN at index " + i);
            }
            double clamped = Math.min(1 - CLAMP, Math.max(CLAMP, p[i]));
            score[i] = Math.log(clamped / (1 - clamped)) - logitPi - lambda;
        }
        return score;
    }

    /** {@code P[0] = 0, P[i + 1] = P[i] + score[i]}, in index order. */
    private static double[] prefix(double[] score) {
        double[] prefix = new double[score.length + 1];
        for (int i = 0; i < score.length; i++) {
            prefix[i + 1] = prefix[i] + score[i];
        }
        return prefix;
    }

    /**
     * Step 1: the stretch {@code [a, b)} of at least {@link #MIN_FRAMES} frames with the largest {@code S}, in one
     * pass. For each {@code b} the best {@code a} has the smallest {@code P[a]}; on equal sums the larger {@code b}
     * wins, then the larger {@code a}.
     *
     * @param prefix the prefix sums
     * @param n the frame count, at least {@link #MIN_FRAMES}
     * @return {@code {a, b}}
     */
    static int[] best(double[] prefix, int n) {
        int minA = 0;
        int bestA = 0;
        int bestB = MIN_FRAMES;
        double bestSum = Double.NEGATIVE_INFINITY;
        for (int b = MIN_FRAMES; b <= n; b++) {
            int candidate = b - MIN_FRAMES;
            if (prefix[candidate] <= prefix[minA]) {
                minA = candidate;
            }
            double sum = prefix[b] - prefix[minA];
            if (sum >= bestSum) {
                bestSum = sum;
                bestA = minA;
                bestB = b;
            }
        }
        return new int[] {bestA, bestB};
    }

    /** The largest {@code a <= u - 6} with {@code S(a, u) >= threshold}; the caller guarantees one exists. */
    private static int latest(double[] prefix, int u, double threshold) {
        int a = u - MIN_FRAMES;
        while (prefix[u] - prefix[a] < threshold) {
            a--;
        }
        return a;
    }

    /**
     * The first run of at least {@code gap} consecutive frames scoring below 0 inside {@code [from, to)}, taken
     * whole.
     *
     * @return {@code {first, endExclusive}}, or null when there is none
     */
    private static int[] firstRun(double[] score, int from, int to, int gap) {
        int i = from;
        while (i < to) {
            if (score[i] < 0) {
                int end = i;
                while (end < to && score[end] < 0) {
                    end++;
                }
                if (end - i >= gap) {
                    return new int[] {i, end};
                }
                i = end;
            } else {
                i++;
            }
        }
        return null;
    }
}

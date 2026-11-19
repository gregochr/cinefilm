package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.learn.CreditsSegmenter.Coarse;
import gregochr.cinefilm.credits.learn.CreditsSegmenter.Verdict;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Scores one film offline: the segmenter on the stored frames, with the dense search simulated on the stored dense
 * frames ({@code docs/engineering/credits-classifier-plan.md}, "Two model types", "How a film is scored", and "L3c
 * specification", {@code FilmScore}). Pure: no clock, no I/O, no Spring.
 *
 * <p>The score is computed in two parts so that the calibration's grid runs fast. {@link #coarsePart} is per film,
 * prediction set, {@code lambda}, {@code delta} and {@code G} (the segmenter's steps 1 to 3, and the negative's best
 * stretch, which depends on {@code lambda} only). {@link #finish} is per {@code sigma}, {@code theta} and
 * {@code theta_d}: the confidence check, the dense search (cached per {@code t} and {@code theta_d}) and the fields.
 * {@link #of} runs the segmenter whole, by a separate path, and a test proves the two agree.
 */
final class FilmScore {

    // Plan, "How a film is scored": every early metric is at t - 5 s (the dense step's earliest); the stored dense
    // frames cover [target - 25 s, target + 25 s); the product's range is [t - 10 s, t + 30 s); a found f must end
    // its 5 s window before both ranges end; negatives end 5 s before the target and violate from 0.8 sigma; the
    // margin is high from 2 sigma.
    static final long EARLY_SHIFT_MS = 5_000;
    static final long STORED_BEFORE_MS = 25_000;
    static final long STORED_AFTER_MS = 25_000;
    static final long SEARCH_BEFORE_MS = 10_000;
    static final long SEARCH_AFTER_MS = 30_000;
    static final long WINDOW_MS = 5_000;
    static final long NEGATIVE_CUT_MS = 5_000;
    static final double VIOLATION_SHARE = 0.8;
    static final double HIGH_BAND = 2;

    private static final long DENSE_STEP_MS = CreditsSegmenter.DENSE_STEP_MS;

    private FilmScore() {
    }

    /** The margin band of a {@code FOUND} start. */
    enum Band {
        LOW, HIGH
    }

    /** The stored facts of one film. Arrays are not copied: callers do not change them. */
    static final class Film {
        private final long[] coarseOffsets;
        private final List<KindAt> kinds;
        private final long[] denseOffsets;
        private final long target;
        private final Optional<FrameKind> plexOpening;

        /**
         * @param coarseOffsets the coarse offsets in ms, ascending
         * @param kinds each coarse frame's kind ({@code OpeningType.frameKind}), the same length
         * @param denseOffsets every stored dense offset in ms, ascending, on one 500 ms grid
         * @param target Plex's credits start in ms
         * @param plexOpening Plex's opening type, empty without one ({@code noOpeningFrame})
         * @throws IllegalArgumentException when the coarse arrays differ in length
         */
        Film(long[] coarseOffsets, FrameKind[] kinds, long[] denseOffsets, long target,
             Optional<FrameKind> plexOpening) {
            if (coarseOffsets.length != kinds.length) {
                throw new IllegalArgumentException("coarse offsets and kinds differ in length: "
                    + coarseOffsets.length + ", " + kinds.length);
            }
            List<KindAt> list = new ArrayList<>(kinds.length);
            for (int i = 0; i < kinds.length; i++) {
                list.add(new KindAt(coarseOffsets[i], kinds[i]));
            }
            this.coarseOffsets = coarseOffsets;
            this.kinds = List.copyOf(list);
            this.denseOffsets = denseOffsets;
            this.target = target;
            this.plexOpening = plexOpening;
        }

        Optional<FrameKind> plexOpening() {
            return plexOpening;
        }
    }

    /**
     * What one prediction set (an ensemble) says of one film.
     *
     * @param coarseP the probability at each coarse frame
     * @param denseP the probability at each stored dense frame, NaN where a frame has no time features
     * @param logitPi the ensemble's {@code logit(π)}: the mean of its models' {@code base}
     */
    record Prediction(double[] coarseP, double[] denseP, double logitPi) {

        /**
         * The ensemble of models: the mean of their probabilities, frame by frame in list order, and of their
         * {@code logitPi} (each member's is its {@code base}). A NaN dense {@code p} stays NaN.
         *
         * @param members at least one model's prediction, all of the same shape
         * @return the ensemble's prediction
         * @throws IllegalArgumentException when there are no members or their lengths differ
         */
        static Prediction ensemble(List<Prediction> members) {
            if (members.isEmpty()) {
                throw new IllegalArgumentException("an ensemble needs at least one model");
            }
            List<double[]> coarse = new ArrayList<>();
            List<double[]> dense = new ArrayList<>();
            double logitPi = 0;
            for (Prediction member : members) {
                coarse.add(member.coarseP);
                dense.add(member.denseP);
                logitPi += member.logitPi;
            }
            return new Prediction(mean(coarse), mean(dense), logitPi / members.size());
        }

        private static double[] mean(List<double[]> arrays) {
            double[] mean = new double[arrays.get(0).length];
            for (double[] array : arrays) {
                if (array.length != mean.length) {
                    throw new IllegalArgumentException("the models' predictions differ in length");
                }
                for (int i = 0; i < mean.length; i++) {
                    mean[i] += array[i];
                }
            }
            for (int i = 0; i < mean.length; i++) {
                mean[i] /= arrays.size();
            }
            return mean;
        }
    }

    /**
     * The first part: the segmenter's steps 1 to 3 and the negative.
     *
     * @param coarse the segmenter's result, its verdict computed with {@code sigma} and {@code theta} of 0, so only
     *     {@code NOT_FOUND} is final
     * @param negativeBest {@code S*} over the coarse frames before {@code target - 5 s}
     */
    record CoarsePart(Coarse coarse, double negativeBest) {
    }

    /**
     * One {@code evaluation.csv} row's values; the optionals are empty where the csv cell is empty. {@code f},
     * {@code covered}, {@code aligned}, the errors and the band are empty unless {@code FOUND}; {@code t} is empty for
     * {@code NOT_FOUND}; {@code goalError} is {@code f - target} when covered, else {@code t - target};
     * {@code earlyError} is {@code t - 5000 - target}; {@code margin} is {@code S(t, u*)} (NaN without a {@code t});
     * {@code negativeBest} is negative infinity with too few frames; {@code violation} is
     * {@code negativeBest >= 0.8 sigma}.
     */
    record Result(Verdict verdict, OptionalLong t, OptionalLong f, Optional<Boolean> covered,
                  Optional<Boolean> aligned, OptionalLong earlyError, OptionalLong goalError,
                  Optional<FrameKind> openingAtT, Optional<Band> marginBand, double margin, double negativeBest,
                  boolean violation) {
    }

    /**
     * The dense search's answers for one film under one prediction set, remembered per {@code t} and
     * {@code theta_d}: the search depends on nothing else.
     */
    static final class DenseCache {
        private final Film film;
        private final double[] denseP;
        private final Map<Search, OptionalLong> found = new HashMap<>();

        private record Search(long t, double thetaDense) {
        }

        /**
         * @param film the film
         * @param prediction its prediction set
         * @throws IllegalArgumentException when the dense {@code p} is not one per stored dense offset
         */
        DenseCache(Film film, Prediction prediction) {
            if (prediction.denseP().length != film.denseOffsets.length) {
                throw new IllegalArgumentException("dense offsets and p differ in length: "
                    + film.denseOffsets.length + ", " + prediction.denseP().length);
            }
            this.film = film;
            this.denseP = prediction.denseP();
        }

        /** The simulated {@code f} for a coarse start, from the stored frames in {@code [t - 10 s, t + 30 s)}. */
        OptionalLong search(long t, double thetaDense) {
            return found.computeIfAbsent(new Search(t, thetaDense), key -> compute(t, thetaDense));
        }

        private OptionalLong compute(long t, double thetaDense) {
            long[] offsets = film.denseOffsets;
            int from = firstAtLeast(offsets, t - SEARCH_BEFORE_MS);
            int to = firstAtLeast(offsets, t + SEARCH_AFTER_MS);
            return CreditsSegmenter.dense(t, Arrays.copyOfRange(offsets, from, to),
                Arrays.copyOfRange(denseP, from, to), thetaDense);
        }

        private static int firstAtLeast(long[] offsets, long limit) {
            int i = 0;
            while (i < offsets.length && offsets[i] < limit) {
                i++;
            }
            return i;
        }
    }

    /**
     * The negative's {@code S*}: the best stretch over the coarse frames with an offset before
     * {@code target - 5 s}.
     *
     * @return the score, negative infinity when fewer than 6 frames are before the cut
     */
    static double negativeBest(Film film, Prediction prediction, double lambda) {
        int end = 0;
        while (end < film.coarseOffsets.length && film.coarseOffsets[end] < film.target - NEGATIVE_CUT_MS) {
            end++;
        }
        return CreditsSegmenter.bestStretch(prediction.coarseP(), end, prediction.logitPi(), lambda);
    }

    /** The first part, computing the negative as well. */
    static CoarsePart coarsePart(Film film, Prediction prediction, double lambda, double delta, int gapFrames) {
        return coarsePart(film, prediction, lambda, delta, gapFrames, negativeBest(film, prediction, lambda));
    }

    /**
     * The first part, per film, prediction set, {@code lambda}, {@code delta} and {@code G}.
     *
     * @param negativeBest {@link #negativeBest} of the same film, prediction and {@code lambda}, computed once per
     *     {@code lambda} by a grid
     * @return the part
     */
    static CoarsePart coarsePart(Film film, Prediction prediction, double lambda, double delta, int gapFrames,
                                 double negativeBest) {
        SegmenterParams params = new SegmenterParams(lambda, delta, gapFrames, 0, 0, 0);
        return new CoarsePart(CreditsSegmenter.coarse(film.coarseOffsets, prediction.coarseP(),
            prediction.logitPi(), params), negativeBest);
    }

    /**
     * The second part: step 4 for {@code sigma} and {@code theta}, the dense search for {@code thetaDense}, and the
     * fields.
     *
     * @param part the first part of the same film and prediction set
     * @param cache the dense answers of the same film and prediction set
     * @return the row's values
     * @throws IllegalArgumentException when a number is out of its range
     */
    static Result finish(Film film, CoarsePart part, double sigma, double theta, double thetaDense,
                         DenseCache cache) {
        new SegmenterParams(0, 0, 1, sigma, theta, thetaDense);
        Coarse coarse = part.coarse();
        Verdict verdict = coarse.verdict();
        if (verdict != Verdict.NOT_FOUND) {
            verdict = coarse.margin() >= sigma && coarse.minP() >= theta ? Verdict.FOUND : Verdict.UNSURE;
        }
        OptionalLong f = OptionalLong.empty();
        if (verdict == Verdict.FOUND && coarse.t().getAsLong() - EARLY_SHIFT_MS >= film.target - STORED_BEFORE_MS) {
            f = cache.search(coarse.t().getAsLong(), thetaDense);
        }
        return result(film, coarse, verdict, f, part.negativeBest(), sigma);
    }

    /** The whole score in one call, by the segmenter's own verdict and an uncached dense search. */
    static Result of(Film film, Prediction prediction, SegmenterParams params) {
        Coarse coarse = CreditsSegmenter.coarse(film.coarseOffsets, prediction.coarseP(), prediction.logitPi(),
            params);
        OptionalLong f = OptionalLong.empty();
        if (coarse.verdict() == Verdict.FOUND
            && coarse.t().getAsLong() - EARLY_SHIFT_MS >= film.target - STORED_BEFORE_MS) {
            f = new DenseCache(film, prediction).compute(coarse.t().getAsLong(), params.thetaDense());
        }
        return result(film, coarse, coarse.verdict(), f, negativeBest(film, prediction, params.lambda()),
            params.sigma());
    }

    /** The fields both paths share, from the verdict and the dense search (empty when it was not attempted). */
    private static Result result(Film film, Coarse coarse, Verdict verdict, OptionalLong search, double negative,
                                 double sigma) {
        boolean violation = negative >= VIOLATION_SHARE * sigma;
        Optional<FrameKind> opening = coarse.t().isPresent() ? OpeningType.of(film.kinds, coarse.t().getAsLong())
            : Optional.empty();
        if (verdict != Verdict.FOUND) {
            return new Result(verdict, coarse.t(), OptionalLong.empty(), Optional.empty(), Optional.empty(),
                OptionalLong.empty(), OptionalLong.empty(), opening, Optional.empty(), coarse.margin(), negative,
                violation);
        }
        long t = coarse.t().getAsLong();
        boolean covered = search.isPresent()
            && search.getAsLong() + WINDOW_MS < Math.min(film.target + STORED_AFTER_MS, t + SEARCH_AFTER_MS);
        boolean aligned = film.denseOffsets.length > 0
            && Math.floorMod(t - film.denseOffsets[0], DENSE_STEP_MS) == 0;
        OptionalLong f = covered ? search : OptionalLong.empty();
        long goal = (covered ? search.getAsLong() : t) - film.target;
        Band band = coarse.margin() < HIGH_BAND * sigma ? Band.LOW : Band.HIGH;
        return new Result(verdict, coarse.t(), f, Optional.of(covered), Optional.of(aligned),
            OptionalLong.of(t - EARLY_SHIFT_MS - film.target), OptionalLong.of(goal), opening, Optional.of(band),
            coarse.margin(), negative, violation);
    }
}

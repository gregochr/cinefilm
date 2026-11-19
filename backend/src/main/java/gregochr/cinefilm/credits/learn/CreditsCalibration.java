package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.learn.CreditsSegmenter.Verdict;
import gregochr.cinefilm.credits.learn.FilmScore.CoarsePart;
import gregochr.cinefilm.credits.learn.FilmScore.DenseCache;
import gregochr.cinefilm.credits.learn.FilmScore.Prediction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Chooses a training run and the six segmenter numbers together, by the nested rule
 * ({@code docs/engineering/credits-classifier-plan.md}, "L3c specification", "Calibration and nested selection").
 * Pure: it takes records, loads no model and reads no file.
 *
 * <p>The grid has {@value #POINTS} points. Steps 1 to 3 depend on {@code lambda}, {@code delta} and {@code G}
 * only, so {@link FilmScore#coarsePart} runs 80 times per film and prediction set, and {@link FilmScore#finish} for
 * the other 250 numbers under each. Running counts are kept per grid point; no film's result is stored.
 *
 * <p><strong>Prediction sets.</strong> An ensemble is identified by the folds all its models exclude, as a bit mask
 * ({@link #set}). Outer fold {@code k}'s choice scores a film of fold {@code j} by the three triple models that
 * exclude {@code k}, {@code j} and one more: set {@code {k, j}} ({@link #innerSet}). Fold {@code k}'s own films are
 * then scored with that choice by the four pair models without {@code k}: set {@code {k}} ({@link #heldOutSet}).
 * The final choice scores a film of fold {@code j} by set {@code {j}} ({@link #finalSet}) too.
 */
final class CreditsCalibration {

    /** The development folds. */
    static final int FOLDS = 5;

    static final double[] LAMBDAS = {0, 0.5, 1, 2, 3};
    static final double[] DELTAS = {0, 2, 5, 10};
    /** {@code G} of 10, 15, 20 and 30 s, in coarse frames. */
    static final int[] GAP_FRAMES = {2, 3, 4, 6};
    static final double[] THETAS = {0.5, 0.7, 0.8, 0.9, 0.95};
    static final double[] SIGMAS = {5, 10, 15, 20, 30, 40, 60, 80, 120, 160};
    static final double[] THETA_DENSES = {0.5, 0.7, 0.8, 0.9, 0.95};

    /** The grid's size: 5 x 4 x 4 x 5 x 10 x 5. */
    static final int POINTS = 20_000;

    /** The early limit of ranking level 1, in ms. */
    static final long EARLY_FAR_MS = -15_000;
    /** The early limit of ranking level 2, in ms. */
    static final long EARLY_NEAR_MS = -5_000;
    /** Ranking level 4's goal, in ms. */
    static final long GOAL_NEAR_MS = 10_000;
    /** Ranking level 5's goal, in ms. */
    static final long GOAL_FAR_MS = 60_000;

    private static final int FIELDS = 6;
    // The counts' layout per point: early15 is field 0 (indexed by the point's offset alone), then these.
    private static final int EARLY_5 = 1;
    private static final int VIOLATIONS = 2;
    private static final int WITHIN_10 = 3;
    private static final int WITHIN_60 = 4;
    private static final int FILMS = 5;

    private CreditsCalibration() {
    }

    /**
     * One development film for the calibration.
     *
     * @param fold its development fold, 0 to 4
     * @param film its stored facts
     * @param runs per training run, in command-line order: the film's prediction for each set (key: {@link #set})
     *     that a choice may ask for. A choice reads only the sets it needs.
     */
    record DevFilm(int fold, FilmScore.Film film, List<Map<Integer, Prediction>> runs) {
        DevFilm {
            set(fold);
        }
    }

    /**
     * The ranking's levels 1 to 5 and the number of films: {@code FOUND} films with {@code earlyError} below -15000
     * and below -5000; violations; Plex-{@code DARK} or {@code PLATE} films within 10000 of the goal, and within
     * 60000 or not {@code FOUND}.
     */
    record Counts(int early15, int early5, int violations, int within10, int within60, int films) {
    }

    /** A choice: the run's index, the grid point, its six numbers and the counts over the films it was made on. */
    record Choice(int run, int point, SegmenterParams params, Counts counts) {
    }

    /** The mask of a set of folds, 0 to 4; throws {@code IllegalArgumentException} for any other. */
    static int set(int... folds) {
        int mask = 0;
        for (int fold : folds) {
            if (fold < 0 || fold >= FOLDS) {
                throw new IllegalArgumentException("fold must be 0 to " + (FOLDS - 1) + ": " + fold);
            }
            mask |= 1 << fold;
        }
        return mask;
    }

    /** Outer fold {@code k}'s choice scores a film of fold {@code j != k} by the triples that exclude both. */
    static int innerSet(int k, int j) {
        if (k == j) {
            throw new IllegalArgumentException("an inner set needs two folds: " + k);
        }
        return set(k, j);
    }

    /** The out-of-fold ensemble of fold {@code k}: the models without {@code k}. */
    static int heldOutSet(int k) {
        return set(k);
    }

    /** The final choice scores a film of fold {@code j} by its out-of-fold ensemble. */
    static int finalSet(int j) {
        return set(j);
    }

    /**
     * The training keys of an ensemble's models, as {@code CreditsTrainJob} writes them: two folds give the sorted
     * triple keys {@code "a,b,c"} holding both, one fold the pair keys {@code "a,b"} holding it.
     *
     * @throws IllegalArgumentException for any other set
     */
    static List<String> memberKeys(int set) {
        int count = Integer.bitCount(set);
        if (set < 0 || set >= 1 << FOLDS || count < 1 || count > 2) {
            throw new IllegalArgumentException("a set is one or two folds: " + set);
        }
        List<String> keys = new ArrayList<>();
        for (int a = 0; a < FOLDS; a++) {
            for (int b = a + 1; b < FOLDS; b++) {
                if (count == 1 && ((1 << a | 1 << b) & set) == set) {
                    keys.add(a + "," + b);
                }
                for (int c = b + 1; c < FOLDS && count == 2; c++) {
                    if (((1 << a | 1 << b | 1 << c) & set) == set) {
                        keys.add(a + "," + b + "," + c);
                    }
                }
            }
        }
        return keys;
    }

    /** {@code logit(π)} of an ensemble: the mean of its models' {@code base}, in order (at least one). */
    static double logitPi(double... bases) {
        if (bases.length == 0) {
            throw new IllegalArgumentException("an ensemble needs at least one model");
        }
        double sum = 0;
        for (double base : bases) {
            sum += base;
        }
        return sum / bases.length;
    }

    /** The grid point of one index into each array above, 0 to {@value #POINTS} - 1. */
    static int point(int lambda, int delta, int gap, int theta, int sigma, int thetaDense) {
        return ((((lambda * DELTAS.length + delta) * GAP_FRAMES.length + gap) * THETAS.length + theta)
            * SIGMAS.length + sigma) * THETA_DENSES.length + thetaDense;
    }

    /** The six numbers of a grid point. */
    static SegmenterParams params(int point) {
        int[] index = indices(point);
        return new SegmenterParams(LAMBDAS[index[0]], DELTAS[index[1]], GAP_FRAMES[index[2]], SIGMAS[index[4]],
            THETAS[index[3]], THETA_DENSES[index[5]]);
    }

    /** The indices of a point: lambda, delta, gap, theta, sigma, thetaDense. */
    private static int[] indices(int point) {
        int rest = point;
        int[] index = new int[6];
        index[5] = rest % THETA_DENSES.length;
        rest /= THETA_DENSES.length;
        index[4] = rest % SIGMAS.length;
        rest /= SIGMAS.length;
        index[3] = rest % THETAS.length;
        rest /= THETAS.length;
        index[2] = rest % GAP_FRAMES.length;
        rest /= GAP_FRAMES.length;
        index[1] = rest % DELTAS.length;
        index[0] = rest / DELTAS.length;
        return index;
    }

    /**
     * The ranking: whether {@code a} beats {@code b}, a total order. The levels in turn: fewer early by more than
     * 15 s, fewer early by more than 5 s, fewer violations, more within 10 s, more within 60 s or not found; then
     * the more conservative point (larger {@code sigma}, {@code theta}, {@code lambda}, {@code delta}, smaller
     * {@code G}, larger {@code thetaDense}); then the run given first.
     *
     * @return negative when {@code a} ranks first, positive when {@code b} does, never zero for two different
     *     {@code (run, point)}
     */
    static int compare(Counts a, int pointA, int runA, Counts b, int pointB, int runB) {
        int[] ranked = {
            Integer.compare(a.early15(), b.early15()),
            Integer.compare(a.early5(), b.early5()),
            Integer.compare(a.violations(), b.violations()),
            Integer.compare(b.within10(), a.within10()),
            Integer.compare(b.within60(), a.within60())
        };
        for (int level : ranked) {
            if (level != 0) {
                return level;
            }
        }
        int[] x = indices(pointA);
        int[] y = indices(pointB);
        // Larger sigma, theta, lambda, delta and thetaDense rank first; a smaller G does.
        int[] order = {4, 3, 0, 1, 2, 5};
        for (int slot : order) {
            int diff = slot == 2 ? Integer.compare(x[slot], y[slot]) : Integer.compare(y[slot], x[slot]);
            if (diff != 0) {
                return diff;
            }
        }
        return Integer.compare(runA, runB);
    }

    /**
     * Outer fold {@code k}'s choice: made on the films outside fold {@code k}, each scored by its
     * {@link #innerSet}. No film of fold {@code k} is read.
     *
     * @param k the outer fold
     * @param films every development film
     * @param runs the number of training runs
     * @return the choice
     */
    static Choice chooseOuter(int k, List<DevFilm> films, int runs) {
        List<DevFilm> inner = new ArrayList<>();
        for (DevFilm film : films) {
            if (film.fold() != k) {
                inner.add(film);
            }
        }
        return choose(inner, film -> innerSet(k, film.fold()), runs);
    }

    /**
     * The final choice: made on every development film, each scored by its {@link #finalSet}.
     *
     * @param films every development film
     * @param runs the number of training runs
     * @return the choice (L6a commits it)
     */
    static Choice chooseFinal(List<DevFilm> films, int runs) {
        return choose(films, film -> finalSet(film.fold()), runs);
    }

    /**
     * Scores a film with a choice, whole, by the prediction set given ({@link #heldOutSet} for outer fold
     * {@code k}'s own films under its choice).
     */
    static FilmScore.Result score(Choice choice, DevFilm film, int set) {
        return FilmScore.of(film.film(), prediction(film, choice.run(), set), choice.params());
    }

    private static Prediction prediction(DevFilm film, int run, int set) {
        Prediction prediction = film.runs().get(run).get(set);
        if (prediction == null) {
            throw new IllegalArgumentException("no prediction for run " + run + ", fold-set mask " + set);
        }
        return prediction;
    }

    private static Choice choose(List<DevFilm> films, ToIntFunction<DevFilm> setOf, int runs) {
        if (films.isEmpty() || runs < 1) {
            throw new IllegalArgumentException("a choice needs films and at least one run");
        }
        Choice best = null;
        for (int run = 0; run < runs; run++) {
            int[] counts = new int[POINTS * FIELDS];
            for (DevFilm film : films) {
                tally(film.film(), prediction(film, run, setOf.applyAsInt(film)), counts);
            }
            for (int point = 0; point < POINTS; point++) {
                Counts at = counts(counts, point);
                if (best == null || compare(at, point, run, best.counts(), best.point(), best.run()) < 0) {
                    best = new Choice(run, point, params(point), at);
                }
            }
        }
        return best;
    }

    static Counts counts(int[] counts, int point) {
        int at = point * FIELDS;
        return new Counts(counts[at], counts[at + EARLY_5], counts[at + VIOLATIONS],
            counts[at + WITHIN_10], counts[at + WITHIN_60], counts[at + FILMS]);
    }

    /** Scores one film at every grid point and adds each result to that point's counts. */
    private static void tally(FilmScore.Film film, Prediction prediction, int[] counts) {
        DenseCache cache = new DenseCache(film, prediction);
        boolean ranked = film.plexOpening().filter(kind -> kind != FrameKind.LIT).isPresent();
        for (int li = 0; li < LAMBDAS.length; li++) {
            double negative = FilmScore.negativeBest(film, prediction, LAMBDAS[li]);
            for (int di = 0; di < DELTAS.length; di++) {
                for (int gi = 0; gi < GAP_FRAMES.length; gi++) {
                    CoarsePart part = FilmScore.coarsePart(film, prediction, LAMBDAS[li], DELTAS[di],
                        GAP_FRAMES[gi], negative);
                    for (int ti = 0; ti < THETAS.length; ti++) {
                        for (int si = 0; si < SIGMAS.length; si++) {
                            for (int ui = 0; ui < THETA_DENSES.length; ui++) {
                                FilmScore.Result result = FilmScore.finish(film, part, SIGMAS[si], THETAS[ti],
                                    THETA_DENSES[ui], cache);
                                add(result, ranked, counts, point(li, di, gi, ti, si, ui) * FIELDS);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Adds a film's result to the counts at offset {@code at}; {@code ranked} is Plex DARK or PLATE. */
    static void add(FilmScore.Result result, boolean ranked, int[] counts, int at) {
        counts[at + FILMS]++;
        if (result.violation()) {
            counts[at + VIOLATIONS]++;
        }
        boolean found = result.verdict() == Verdict.FOUND;
        if (found && result.earlyError().getAsLong() < EARLY_FAR_MS) {
            counts[at]++;
        }
        if (found && result.earlyError().getAsLong() < EARLY_NEAR_MS) {
            counts[at + EARLY_5]++;
        }
        if (!ranked) {
            return;
        }
        long goal = found ? Math.abs(result.goalError().getAsLong()) : Long.MAX_VALUE;
        if (goal <= GOAL_NEAR_MS) {
            counts[at + WITHIN_10]++;
        }
        if (!found || goal <= GOAL_FAR_MS) {
            counts[at + WITHIN_60]++;
        }
    }
}

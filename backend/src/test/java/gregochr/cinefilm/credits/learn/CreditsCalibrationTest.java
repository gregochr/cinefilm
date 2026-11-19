package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.learn.CreditsCalibration.Choice;
import gregochr.cinefilm.credits.learn.CreditsCalibration.Counts;
import gregochr.cinefilm.credits.learn.CreditsCalibration.DevFilm;
import gregochr.cinefilm.credits.learn.FilmScore.Film;
import gregochr.cinefilm.credits.learn.FilmScore.Prediction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Tests of the ranking, the grid and the nested selection. The films are 40 coarse frames at {@code 100000 + 5000 i}
 * with {@code logitPi = 0}; a frame of {@code p = 0.99} scores {@code ln 99 - lambda = 4.595 - lambda}.
 */
class CreditsCalibrationTest {

    private static final long BASE = 100_000;
    private static final long STEP = 5_000;
    /** Indices of the base point's numbers: lambda, delta, gap, theta, sigma, thetaDense. */
    private static final int[] MIDDLE = {2, 1, 1, 2, 5, 2};
    private static final Counts EQUAL = new Counts(0, 0, 0, 5, 5, 10);

    private static int pointOf(int[] i) {
        return CreditsCalibration.point(i[0], i[1], i[2], i[3], i[4], i[5]);
    }

    private static Counts counts(int early15, int early5, int violations, int within10, int within60) {
        return new Counts(early15, early5, violations, within10, within60, 10);
    }

    private static void assertFirst(Counts a, int pointA, int runA, Counts b, int pointB, int runB) {
        assertThat(CreditsCalibration.compare(a, pointA, runA, b, pointB, runB)).isNegative();
        assertThat(CreditsCalibration.compare(b, pointB, runB, a, pointA, runA)).isPositive();
    }

    @Test
    void eachRankingLevelDecidesEvenWhenEveryLaterLevelFavoursTheOther() {
        int p = pointOf(MIDDLE);
        assertFirst(counts(0, 9, 9, 0, 0), p, 1, counts(1, 0, 0, 5, 5), p, 0);
        assertFirst(counts(0, 0, 9, 0, 0), p, 1, counts(0, 1, 0, 5, 5), p, 0);
        assertFirst(counts(0, 0, 0, 0, 0), p, 1, counts(0, 0, 1, 5, 5), p, 0);
        assertFirst(counts(0, 0, 0, 6, 0), p, 1, counts(0, 0, 0, 5, 9), p, 0);
        assertFirst(counts(0, 0, 0, 5, 6), p, 1, counts(0, 0, 0, 5, 5), p, 0);
    }

    @Test
    void whenTheCountsTieTheMoreConservativePointWinsInTheStatedOrderThenTheFirstRun() {
        // slots: sigma 4, theta 3, lambda 0, delta 1, G 2 (smaller wins), thetaDense 5; each rule beats every later
        // one, so the point wins on rule i while losing on all the rules after it.
        int[] slots = {4, 3, 0, 1, 2, 5};
        for (int i = 0; i < slots.length; i++) {
            int[] better = MIDDLE.clone();
            int[] worse = MIDDLE.clone();
            for (int j = i; j < slots.length; j++) {
                int slot = slots[j];
                int up = slot == 2 ? -1 : 1;
                int direction = j == i ? up : -up;
                better[slot] += direction;
                worse[slot] -= direction;
            }
            assertFirst(EQUAL, pointOf(better), 1, EQUAL, pointOf(worse), 0);
        }
        assertFirst(EQUAL, pointOf(MIDDLE), 0, EQUAL, pointOf(MIDDLE), 1);
        assertThat(CreditsCalibration.compare(EQUAL, pointOf(MIDDLE), 2, EQUAL, pointOf(MIDDLE), 2)).isZero();
    }

    @Test
    void theGridHasTwentyThousandPointsInANestedOrderAndEachDecodesToItsNumbers() {
        int expected = 0;
        for (int l = 0; l < 5; l++) {
            for (int d = 0; d < 4; d++) {
                for (int g = 0; g < 4; g++) {
                    for (int t = 0; t < 5; t++) {
                        for (int s = 0; s < 10; s++) {
                            for (int u = 0; u < 5; u++) {
                                int point = CreditsCalibration.point(l, d, g, t, s, u);
                                assertThat(point).isEqualTo(expected++);
                                assertThat(CreditsCalibration.params(point)).isEqualTo(new SegmenterParams(
                                    CreditsCalibration.LAMBDAS[l], CreditsCalibration.DELTAS[d],
                                    CreditsCalibration.GAP_FRAMES[g], CreditsCalibration.SIGMAS[s],
                                    CreditsCalibration.THETAS[t], CreditsCalibration.THETA_DENSES[u]));
                            }
                        }
                    }
                }
            }
        }
        assertThat(expected).isEqualTo(CreditsCalibration.POINTS).isEqualTo(20_000);
        assertThat(CreditsCalibration.params(0)).isEqualTo(new SegmenterParams(0, 0, 2, 5, 0.5, 0.5));
        assertThat(CreditsCalibration.params(19_999)).isEqualTo(new SegmenterParams(3, 10, 6, 160, 0.95, 0.95));
        assertThat(CreditsCalibration.GAP_FRAMES).containsExactly(2, 3, 4, 6);
        assertThat(CreditsCalibration.LAMBDAS).containsExactly(0, 0.5, 1, 2, 3);
        assertThat(CreditsCalibration.DELTAS).containsExactly(0, 2, 5, 10);
        assertThat(CreditsCalibration.THETAS).containsExactly(0.5, 0.7, 0.8, 0.9, 0.95);
        assertThat(CreditsCalibration.SIGMAS).containsExactly(5, 10, 15, 20, 30, 40, 60, 80, 120, 160);
        assertThat(CreditsCalibration.THETA_DENSES).containsExactly(0.5, 0.7, 0.8, 0.9, 0.95);
    }

    @Test
    void predictionSetsAreMasksOfTheFoldsTheirModelsExclude() {
        assertThat(CreditsCalibration.set(0, 2)).isEqualTo(5);
        assertThat(CreditsCalibration.innerSet(1, 3)).isEqualTo(10).isEqualTo(CreditsCalibration.innerSet(3, 1));
        assertThat(CreditsCalibration.heldOutSet(4)).isEqualTo(16);
        assertThat(CreditsCalibration.finalSet(2)).isEqualTo(4);
        assertThatThrownBy(() -> CreditsCalibration.innerSet(2, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditsCalibration.set(5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditsCalibration.set(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logitPiIsTheMeanOfTheEnsemblesBases() {
        assertThat(CreditsCalibration.logitPi(1, 2, 6)).isEqualTo(3);
        assertThat(CreditsCalibration.logitPi(-1.5)).isEqualTo(-1.5);
        assertThatThrownBy(CreditsCalibration::logitPi).isInstanceOf(IllegalArgumentException.class);
    }

    private static double[] constant(double p, int n) {
        double[] values = new double[n];
        Arrays.fill(values, p);
        return values;
    }

    /** Twenty frames of 0.01 then twenty of 0.99: credits from index 20, offset 200000. */
    private static double[] creditsAt20() {
        double[] p = new double[40];
        for (int i = 0; i < 40; i++) {
            p[i] = i < 20 ? 0.01 : 0.99;
        }
        return p;
    }

    private static Film film(int frames, long target, Optional<FrameKind> plex) {
        long[] offsets = new long[frames];
        FrameKind[] kinds = new FrameKind[frames];
        for (int i = 0; i < frames; i++) {
            offsets[i] = BASE + STEP * i;
            kinds[i] = FrameKind.LIT;
        }
        return new Film(offsets, kinds, new long[0], target, plex);
    }

    private static Prediction prediction(double[] p) {
        return new Prediction(p, new double[0], 0);
    }

    /** A map holding the same prediction under every set of one or two folds. */
    private static Map<Integer, Prediction> everySet(Prediction prediction) {
        Map<Integer, Prediction> sets = new HashMap<>();
        for (int a = 0; a < 5; a++) {
            sets.put(CreditsCalibration.set(a), prediction);
            for (int b = a + 1; b < 5; b++) {
                sets.put(CreditsCalibration.set(a, b), prediction);
            }
        }
        return sets;
    }

    @SafeVarargs
    private static DevFilm dev(int fold, Film film, Map<Integer, Prediction>... runs) {
        return new DevFilm(fold, film, List.of(runs));
    }

    private static FilmScore.Result found(long early, long goal, boolean violation) {
        return new FilmScore.Result(CreditsSegmenter.Verdict.FOUND, OptionalLong.of(0), OptionalLong.empty(),
            Optional.of(false), Optional.of(false), OptionalLong.of(early), OptionalLong.of(goal), Optional.empty(),
            Optional.of(FilmScore.Band.LOW), 0, 0, violation);
    }

    private static FilmScore.Result unsure(boolean violation) {
        return new FilmScore.Result(CreditsSegmenter.Verdict.UNSURE, OptionalLong.of(0), OptionalLong.empty(),
            Optional.empty(), Optional.empty(), OptionalLong.empty(), OptionalLong.empty(), Optional.empty(),
            Optional.empty(), 0, 0, violation);
    }

    /** The counts of point 1 (offset 6) after one film; point 0 must stay empty. */
    private static Counts added(FilmScore.Result result, boolean ranked) {
        int[] counts = new int[12];
        CreditsCalibration.add(result, ranked, counts, 6);
        assertThat(CreditsCalibration.counts(counts, 0)).isEqualTo(new Counts(0, 0, 0, 0, 0, 0));
        return CreditsCalibration.counts(counts, 1);
    }

    @Test
    void aResultIsCountedAtEachLevelOnlyFromItsLimit() {
        // Level 1: earlyError below -15000 (-15000 itself is not); it is also below -5000.
        assertThat(added(found(-15_000, 99_999, false), false)).isEqualTo(new Counts(0, 1, 0, 0, 0, 1));
        assertThat(added(found(-15_001, 99_999, false), false)).isEqualTo(new Counts(1, 1, 0, 0, 0, 1));
        assertThat(added(found(-5_000, 99_999, false), false)).isEqualTo(new Counts(0, 0, 0, 0, 0, 1));
        assertThat(added(found(-5_001, 99_999, false), false)).isEqualTo(new Counts(0, 1, 0, 0, 0, 1));
        assertThat(added(found(0, 99_999, true), false)).isEqualTo(new Counts(0, 0, 1, 0, 0, 1));
        assertThat(added(unsure(true), false)).isEqualTo(new Counts(0, 0, 1, 0, 0, 1));
        // Levels 4 and 5, for a Plex DARK or PLATE film: |goalError| within 10 s, within 60 s.
        assertThat(added(found(0, 10_000, false), true)).isEqualTo(new Counts(0, 0, 0, 1, 1, 1));
        assertThat(added(found(0, -10_000, false), true)).isEqualTo(new Counts(0, 0, 0, 1, 1, 1));
        assertThat(added(found(0, 10_001, false), true)).isEqualTo(new Counts(0, 0, 0, 0, 1, 1));
        assertThat(added(found(0, -60_000, false), true)).isEqualTo(new Counts(0, 0, 0, 0, 1, 1));
        assertThat(added(found(0, 60_000, false), true)).isEqualTo(new Counts(0, 0, 0, 0, 1, 1));
        assertThat(added(found(0, 60_001, false), true)).isEqualTo(new Counts(0, 0, 0, 0, 0, 1));
        assertThat(added(found(0, -60_001, false), true)).isEqualTo(new Counts(0, 0, 0, 0, 0, 1));
        assertThat(added(unsure(false), true)).isEqualTo(new Counts(0, 0, 0, 0, 1, 1));
        assertThat(added(found(0, 0, false), false)).isEqualTo(new Counts(0, 0, 0, 0, 0, 1));
        assertThat(added(unsure(false), false)).isEqualTo(new Counts(0, 0, 0, 0, 0, 1));
    }

    @Test
    void theChoiceIsTheLexicographicBestOverTheWholeGridWorkedByHand() {
        // Credits from index 20 = 200000 = Plex's target, DARK. 0.99 scores 4.5951 - lambda, so S* = 20 * that, and
        // leaning late by m frames costs m * that. Levels 1 to 3 are 0 everywhere (t >= target; the negative window
        // is all negative). Level 4 is 1 where FOUND with a shift of at most two frames (no dense frames: goal at
        // t), level 5 is 1 everywhere. Then: sigma 80 (m = 0, lambda 0: 91.9); theta 0.95; lambda 0.5 (81.9 >= 80,
        // lambda 1 gives 71.9); delta 2 (delta 5 shifts a frame: 19 * 4.0951 = 77.8 < 80, UNSURE); G 2 and theta_d
        // 0.95 as the smallest and largest, nothing deciding them.
        Film film = film(40, BASE + 20 * STEP, Optional.of(FrameKind.DARK));
        Map<Integer, Prediction> sets = everySet(prediction(creditsAt20()));

        Choice choice = CreditsCalibration.chooseFinal(List.of(dev(0, film, sets)), 1);

        assertThat(choice.run()).isZero();
        assertThat(choice.params()).isEqualTo(new SegmenterParams(0.5, 2, 2, 80, 0.95, 0.95));
        assertThat(choice.point()).isEqualTo(CreditsCalibration.point(1, 1, 0, 4, 7, 4));
        assertThat(choice.counts()).isEqualTo(new Counts(0, 0, 0, 1, 1, 1));
    }

    @Test
    void whenEveryPointTiesTheMostConservativeCornerIsChosen() {
        // No credits at all (p 0.01 everywhere): every point is NOT_FOUND (the one run is an island) with no
        // violation, so the counts are the same at all 20000 points and the tie rules pick lambda 3, delta 10,
        // G 2 frames, sigma 160, theta 0.95, theta_d 0.95.
        Film film = film(40, BASE + 20 * STEP, Optional.of(FrameKind.PLATE));
        Map<Integer, Prediction> sets = everySet(prediction(constant(0.01, 40)));

        Choice choice = CreditsCalibration.chooseFinal(List.of(dev(0, film, sets)), 1);

        assertThat(choice.params()).isEqualTo(new SegmenterParams(3, 10, 2, 160, 0.95, 0.95));
        assertThat(choice.counts()).isEqualTo(new Counts(0, 0, 0, 0, 1, 1));
    }

    @Test
    void aFilmWithoutADarkOrPlateOpeningIsInNeitherGoalCount() {
        Map<Integer, Prediction> sets = everySet(prediction(constant(0.01, 40)));
        for (Optional<FrameKind> plex : List.of(Optional.of(FrameKind.LIT), Optional.<FrameKind>empty())) {
            Choice choice = CreditsCalibration.chooseFinal(List.of(dev(0, film(40, BASE + 20 * STEP, plex), sets)), 1);
            assertThat(choice.counts()).isEqualTo(new Counts(0, 0, 0, 0, 0, 1));
        }
    }

    @Test
    void earlyFoundFilmsAndViolationsAreCountedWhenNoPointAvoidsThem() {
        // 40 frames of 0.9999 (ln 9999 = 9.21): FOUND at every point (S >= 39 * 6.2 = 242 >= 160) with t at or
        // near index 0, far before the target at 245000 (earlyError about -150000), and the 28 negative frames
        // before 240000 score 28 * 6.2 = 174 >= 0.8 * 160 = 128 at every point. Plex DARK, goal about -145000: no
        // goal count but the not-found one is also 0. All points tie: the conservative corner.
        Film film = film(40, BASE + 29 * STEP, Optional.of(FrameKind.DARK));
        Map<Integer, Prediction> sets = everySet(prediction(constant(0.9999, 40)));

        Choice choice = CreditsCalibration.chooseFinal(List.of(dev(0, film, sets)), 1);

        assertThat(choice.params()).isEqualTo(new SegmenterParams(3, 10, 2, 160, 0.95, 0.95));
        assertThat(choice.counts()).isEqualTo(new Counts(1, 1, 1, 0, 0, 1));
    }

    @Test
    void theRunGivenFirstWinsATieAndABetterRunWinsOverIt() {
        Film film = film(40, BASE + 20 * STEP, Optional.of(FrameKind.DARK));
        Map<Integer, Prediction> good = everySet(prediction(creditsAt20()));
        Map<Integer, Prediction> blind = everySet(prediction(constant(0.01, 40)));

        assertThat(CreditsCalibration.chooseFinal(List.of(dev(0, film, good, good)), 2).run()).isZero();
        assertThat(CreditsCalibration.chooseFinal(List.of(dev(0, film, blind, good)), 2).run()).isEqualTo(1);
        assertThat(CreditsCalibration.chooseFinal(List.of(dev(0, film, good, blind)), 2).run()).isZero();
    }

    /** Logs which set of which film's predictions was asked for. */
    private static final class Recording extends HashMap<Integer, Prediction> {
        private final int fold;
        private final List<int[]> log;

        Recording(int fold, Prediction prediction, List<int[]> log) {
            super(everySet(prediction));
            this.fold = fold;
            this.log = log;
        }

        @Override
        public Prediction get(Object key) {
            log.add(new int[] {fold, (Integer) key});
            return super.get(key);
        }
    }

    private static List<DevFilm> recordedFilms(List<int[]> log) {
        List<DevFilm> films = new ArrayList<>();
        for (int fold = 0; fold < 5; fold++) {
            Prediction prediction = prediction(fold % 2 == 0 ? creditsAt20() : constant(0.01, 40));
            films.add(dev(fold, film(40, BASE + 20 * STEP, Optional.of(FrameKind.DARK)),
                new Recording(fold, prediction, log)));
        }
        return films;
    }

    /** Every model key of the set holds every fold given: no model scoring that film trained on those folds. */
    private static void assertMembersHold(int set, int... folds) {
        for (String key : CreditsCalibration.memberKeys(set)) {
            for (int fold : folds) {
                assertThat(List.of(key.split(","))).contains(String.valueOf(fold));
            }
        }
    }

    @Test
    void aSetNamesItsModelsByTheKeysTheTrainingJobWrites() {
        assertThat(CreditsCalibration.memberKeys(CreditsCalibration.innerSet(1, 3)))
            .containsExactly("0,1,3", "1,2,3", "1,3,4");
        assertThat(CreditsCalibration.memberKeys(CreditsCalibration.heldOutSet(2)))
            .containsExactly("0,2", "1,2", "2,3", "2,4");
        for (int bad : new int[] {0, 7, 32, -1}) {
            assertThatThrownBy(() -> CreditsCalibration.memberKeys(bad))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void noOuterFoldChoiceSawAFilmOfItsFoldAndNoModelScoringAFilmWasTrainedOnItsFold() {
        List<int[]> log = new ArrayList<>();
        List<DevFilm> films = recordedFilms(log);
        for (int k = 0; k < 5; k++) {
            log.clear();
            Choice choice = CreditsCalibration.chooseOuter(k, films, 1);

            assertThat(log).hasSize(4);
            int fold = k;
            // A set is the folds its models exclude: each model scoring a fold j film excluded j and k.
            for (int[] access : log) {
                assertThat(access[0]).isNotEqualTo(fold);
                assertThat(access[1]).isEqualTo(CreditsCalibration.innerSet(fold, access[0]));
                assertMembersHold(access[1], fold, access[0]);
            }
            assertThat(choice.counts().films()).isEqualTo(4);

            log.clear();
            // Fold k's own films are scored with the choice by the models without k.
            CreditsCalibration.score(choice, films.get(k), CreditsCalibration.heldOutSet(k));
            assertThat(log).hasSize(1);
            assertThat(log.get(0)).containsExactly(k, CreditsCalibration.set(k));
            assertMembersHold(log.get(0)[1], k);
        }
        log.clear();
        Choice finalChoice = CreditsCalibration.chooseFinal(films, 1);
        assertThat(log).hasSize(5);
        for (int[] access : log) {
            assertThat(access[1]).isEqualTo(CreditsCalibration.finalSet(access[0]));
            assertMembersHold(access[1], access[0]);
        }
        assertThat(finalChoice.counts().films()).isEqualTo(5);
    }

    @Test
    void scoringWithAChoiceUsesItsRunAndNumbers() {
        Film film = film(40, BASE + 20 * STEP, Optional.of(FrameKind.DARK));
        DevFilm devFilm = dev(0, film, everySet(prediction(constant(0.01, 40))), everySet(prediction(creditsAt20())));
        SegmenterParams params = new SegmenterParams(0, 0, 3, 10, 0.9, 0.9);

        FilmScore.Result run0 = CreditsCalibration.score(new Choice(0, 0, params, EQUAL), devFilm, 1);
        FilmScore.Result run1 = CreditsCalibration.score(new Choice(1, 0, params, EQUAL), devFilm, 1);

        assertThat(run0.verdict()).isEqualTo(CreditsSegmenter.Verdict.NOT_FOUND);
        assertThat(run1.verdict()).isEqualTo(CreditsSegmenter.Verdict.FOUND);
        assertThat(run1.t()).hasValue(BASE + 20 * STEP);
    }

    @Test
    void invalidInputsAreRefused() {
        Film film = film(40, BASE, Optional.empty());
        Map<Integer, Prediction> sets = everySet(prediction(constant(0.01, 40)));
        for (int fold : new int[] {-1, 5}) {
            assertThatThrownBy(() -> new DevFilm(fold, film, List.of(sets)))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CreditsCalibration.chooseFinal(List.of(), 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditsCalibration.chooseFinal(List.of(dev(0, film, sets)), 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditsCalibration.chooseFinal(List.of(dev(0, film, new HashMap<>())), 1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

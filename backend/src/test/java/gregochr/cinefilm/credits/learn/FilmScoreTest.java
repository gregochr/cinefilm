package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import gregochr.cinefilm.credits.learn.CreditsSegmenter.Verdict;
import gregochr.cinefilm.credits.learn.FilmScore.Band;
import gregochr.cinefilm.credits.learn.FilmScore.CoarsePart;
import gregochr.cinefilm.credits.learn.FilmScore.DenseCache;
import gregochr.cinefilm.credits.learn.FilmScore.Film;
import gregochr.cinefilm.credits.learn.FilmScore.Prediction;
import gregochr.cinefilm.credits.learn.FilmScore.Result;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Exact-value tests of the offline film score. Coarse frames are written by their score {@code l}: with
 * {@code logitPi = 0} and {@code lambda = 0}, a frame of {@code +4} ("A") is {@code p = 0.982} and one of {@code -4}
 * ("F") is {@code p = 0.018}; {@code p = 0.5} with {@code logitPi = -2} scores exactly {@code +2}, so sums are exact
 * and boundaries can be hit. Coarse offsets are {@code 100000 + 5000 i}.
 */
class FilmScoreTest {

    private static final double A = 4;
    private static final double F = -4;
    private static final long BASE = 100_000;
    private static final long STEP = 5_000;
    /** lambda 0, delta 0, G 3 frames, sigma 10, theta 0.9, theta_d 0.9. */
    private static final SegmenterParams PARAMS = new SegmenterParams(0, 0, 3, 10, 0.9, 0.9);
    private static final double HIGH = 0.99;
    private static final double LOW = 0.1;

    private static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    private static long[] coarseOffsets(int n) {
        long[] offsets = new long[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = BASE + STEP * i;
        }
        return offsets;
    }

    /** Twelve film frames, then eighteen credits frames: {@code t} is index 12, 160000, margin 72. */
    private static double[] creditsAt12() {
        double[] p = new double[30];
        for (int i = 0; i < 30; i++) {
            p[i] = sigmoid(i < 12 ? F : A);
        }
        return p;
    }

    private static FrameKind[] kinds(int n, FrameKind kind) {
        FrameKind[] kinds = new FrameKind[n];
        Arrays.fill(kinds, kind);
        return kinds;
    }

    private static long[] denseOffsets(long first, int n) {
        long[] offsets = new long[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = first + 500L * i;
        }
        return offsets;
    }

    /** NaN at both ends (no time features), {@code LOW} before {@code highFrom} and {@code HIGH} from it. */
    private static double[] denseP(long[] offsets, long highFrom) {
        double[] p = new double[offsets.length];
        for (int i = 0; i < p.length; i++) {
            p[i] = offsets[i] >= highFrom ? HIGH : LOW;
        }
        p[0] = Double.NaN;
        p[p.length - 1] = Double.NaN;
        return p;
    }

    private static Film film(double[] coarseP, long target, long[] dense) {
        return new Film(coarseOffsets(coarseP.length), kinds(coarseP.length, FrameKind.LIT), dense, target,
            Optional.of(FrameKind.LIT));
    }

    private static Prediction prediction(double[] coarseP, double[] denseP) {
        return new Prediction(coarseP, denseP, 0);
    }

    /** The two parts, as the calibration calls them, for one set of six numbers. */
    private static Result twoPart(Film film, Prediction prediction, SegmenterParams params) {
        CoarsePart part = FilmScore.coarsePart(film, prediction, params.lambda(), params.delta(),
            params.gapFrames());
        return FilmScore.finish(film, part, params.sigma(), params.theta(), params.thetaDense(),
            new DenseCache(film, prediction));
    }

    private static Result score(Film film, Prediction prediction) {
        Result whole = FilmScore.of(film, prediction, PARAMS);
        assertThat(twoPart(film, prediction, PARAMS)).isEqualTo(whole);
        return whole;
    }

    @Test
    void aCoveredSearchGivesGoalsAtFAndEarlyMetricsAtTMinus5s() {
        // t = 160000 (index 12), margin 18 * 4 = 72, HIGH (72 >= 2 * 10). Target 163000: earlyError =
        // 160000 - 5000 - 163000 = -8000. Dense 138000..187500, high from 161500: windows starting earlier hold a
        // low frame, so f = 161500, f + 5000 = 166500 < min(188000, 190000): covered, goalError = -1500.
        // t - d0 = 22000 = 44 * 500: aligned. The negative frames (< 158000) are 12 F frames: -24, no violation.
        double[] p = creditsAt12();
        long[] dense = denseOffsets(138_000, 100);
        Result r = score(film(p, 163_000, dense), prediction(p, denseP(dense, 161_500)));

        assertThat(r.verdict()).isEqualTo(Verdict.FOUND);
        assertThat(r.t()).hasValue(160_000);
        assertThat(r.f()).hasValue(161_500);
        assertThat(r.covered()).contains(true);
        assertThat(r.aligned()).contains(true);
        assertThat(r.earlyError()).hasValue(-8_000);
        assertThat(r.goalError()).hasValue(-1_500);
        assertThat(r.openingAtT()).contains(FrameKind.LIT);
        assertThat(r.marginBand()).contains(Band.HIGH);
        assertThat(r.margin()).isCloseTo(72, within(1e-9));
        assertThat(r.negativeBest()).isCloseTo(-24, within(1e-9));
        assertThat(r.violation()).isFalse();
    }

    @Test
    void aSearchWithNoWindowIsNotCoveredAndTheGoalIsAtT() {
        // High only from 184000, and the last dense frame (187500) has no time features: 184000..187000 is 7
        // frames, no window of 11. No f: not covered, goalError = t - target = 160000 - 163000.
        double[] p = creditsAt12();
        long[] dense = denseOffsets(138_000, 100);
        Result r = score(film(p, 163_000, dense), prediction(p, denseP(dense, 184_000)));

        assertThat(r.verdict()).isEqualTo(Verdict.FOUND);
        assertThat(r.f()).isEmpty();
        assertThat(r.covered()).contains(false);
        assertThat(r.goalError()).hasValue(-3_000);
        assertThat(r.earlyError()).hasValue(-8_000);
    }

    @Test
    void anFIsCoveredOnlyWhenItsWindowEndsBeforeTheStoredRangesEnd() {
        // Dense 138000..207500, past target + 25 s = 188000. A window of 5 s from f ends at f + 5000: f = 182500
        // ends at 187500 < 188000, covered; f = 183000 ends at 188000, not before it; f = 184500 ends at 189500 <
        // t + 30 s = 190000 but past 188000. An uncovered f is not reported and the goal is at t (-3000).
        double[] p = creditsAt12();
        long[] dense = denseOffsets(138_000, 140);
        Result inside = score(film(p, 163_000, dense), prediction(p, denseP(dense, 182_500)));
        assertThat(inside.f()).hasValue(182_500);
        assertThat(inside.covered()).contains(true);
        assertThat(inside.goalError()).hasValue(182_500 - 163_000);
        for (long highFrom : new long[] {183_000, 184_500}) {
            Result r = score(film(p, 163_000, dense), prediction(p, denseP(dense, highFrom)));
            assertThat(r.f()).isEmpty();
            assertThat(r.covered()).contains(false);
            assertThat(r.goalError()).hasValue(-3_000);
        }
    }

    @Test
    void alignmentIsTMinusTheFirstDenseOffsetOnTheFiveHundredMsGrid() {
        // Coarse offsets 100100 + 5000 i: t = 160100. First dense offset 138100: t - d0 = 22000, aligned (t + d0 =
        // 298200 would not be); 138200 gives 21900 = 43 * 500 + 400, not aligned.
        long[] shifted = new long[30];
        for (int i = 0; i < 30; i++) {
            shifted[i] = BASE + 100 + STEP * i;
        }
        for (long first : new long[] {138_100, 138_200}) {
            long[] dense = denseOffsets(first, 100);
            Film film = new Film(shifted, kinds(30, FrameKind.LIT), dense, 163_100, Optional.empty());
            assertThat(score(film, prediction(creditsAt12(), denseP(dense, 161_600))).aligned())
                .contains(first == 138_100);
        }
    }

    @Test
    void aStartWellBeforeTheTargetIsNotCoveredEvenWhereStoredFramesWouldGiveAnF() {
        // Target 200000: t - 5 s = 155000 < target - 25 s = 175000. Stored 175000..224500, high but the NaN ends:
        // without the guard the search in [150000, 190000) gives f = 175500 and covered. Guarded: not covered,
        // goalError = 160000 - 200000 = -40000, earlyError = 160000 - 5000 - 200000 = -45000.
        double[] p = creditsAt12();
        long[] dense = denseOffsets(175_000, 100);
        Film film = film(p, 200_000, dense);
        Prediction prediction = prediction(p, denseP(dense, 175_000));

        assertThat(new DenseCache(film, prediction).search(160_000, 0.9)).hasValue(175_500);
        Result r = score(film, prediction);
        assertThat(r.verdict()).isEqualTo(Verdict.FOUND);
        assertThat(r.f()).isEmpty();
        assertThat(r.covered()).contains(false);
        assertThat(r.goalError()).hasValue(-40_000);
        assertThat(r.earlyError()).hasValue(-45_000);
        // At the edge (target 180000, dense from 155000, first frame NaN): covered, f = 155500. One ms below
        // (target 180001, dense from 155001): not covered.
        long[] edge = denseOffsets(155_000, 100);
        Result at = score(film(p, 180_000, edge), prediction(p, denseP(edge, 155_000)));
        assertThat(at.f()).hasValue(155_500);
        assertThat(at.covered()).contains(true);
        long[] below = denseOffsets(155_001, 100);
        Result r2 = score(film(p, 180_001, below), prediction(p, denseP(below, 155_001)));
        assertThat(r2.covered()).contains(false);
        assertThat(r2.goalError()).hasValue(160_000 - 180_001);
    }

    @Test
    void theOpeningAtTCanDifferFromPlexs() {
        // t = 160000: the frame nearest t + 5 s (165000, index 13) is DARK, so the opening is DARK; Plex says PLATE.
        double[] p = creditsAt12();
        FrameKind[] kinds = kinds(30, FrameKind.LIT);
        kinds[13] = FrameKind.DARK;
        Film dark = new Film(coarseOffsets(30), kinds, new long[0], 163_000, Optional.of(FrameKind.PLATE));
        Result r = score(dark, prediction(p, new double[0]));

        assertThat(r.openingAtT()).contains(FrameKind.DARK);
        assertThat(dark.plexOpening()).contains(FrameKind.PLATE);

        FrameKind[] plate = kinds(30, FrameKind.LIT);
        plate[16] = FrameKind.PLATE;
        Film plated = new Film(coarseOffsets(30), plate, new long[0], 163_000, Optional.empty());
        assertThat(score(plated, prediction(p, new double[0])).openingAtT()).contains(FrameKind.PLATE);
    }

    @Test
    void theMarginBandIsLowBelowTwiceSigmaAndHighFromIt() {
        // Six frames of p 0.5 with logitPi -2 score exactly +2: S* = 12, t = index 0, margin 12.
        Film exact = exactFilm();
        Prediction prediction = exactPrediction();
        // Without stored dense frames a FOUND start is neither covered nor aligned.
        Result none = FilmScore.of(exact, prediction, new SegmenterParams(0, 0, 3, 12, 0.5, 0.5));
        assertThat(none.covered()).isEqualTo(none.aligned()).contains(false);
        assertThat(band(exact, prediction, 6)).contains(Band.HIGH);
        assertThat(band(exact, prediction, 6.5)).contains(Band.LOW);
        assertThat(band(exact, prediction, 12)).contains(Band.LOW);
    }

    private static Optional<Band> band(Film film, Prediction prediction, double sigma) {
        SegmenterParams params = new SegmenterParams(0, 0, 3, sigma, 0.5, 0.5);
        Result r = FilmScore.of(film, prediction, params);
        assertThat(twoPart(film, prediction, params)).isEqualTo(r);
        assertThat(r.verdict()).isEqualTo(Verdict.FOUND);
        return r.marginBand();
    }

    /** Six frames at +2 each; the target is 30001 ms in, so all six lie before target - 5 s. */
    private static Film exactFilm() {
        return film(new double[6], BASE + 30_001, new long[0]);
    }

    private static Prediction exactPrediction() {
        return new Prediction(new double[] {0.5, 0.5, 0.5, 0.5, 0.5, 0.5}, new double[0], -2);
    }

    @Test
    void theVerdictNeedsSigmaAndEachOfThetaAtOrAbove() {
        Film exact = exactFilm();
        Prediction prediction = exactPrediction();
        // margin 12 against sigma 12 and minP 0.5 against theta 0.5: both at the limit, FOUND.
        assertThat(verdict(exact, prediction, new SegmenterParams(0, 0, 3, 12, 0.5, 0.5))).isEqualTo(Verdict.FOUND);
        assertThat(verdict(exact, prediction, new SegmenterParams(0, 0, 3, 12.5, 0.5, 0.5)))
            .isEqualTo(Verdict.UNSURE);
        assertThat(verdict(exact, prediction, new SegmenterParams(0, 0, 3, 12, 0.51, 0.5)))
            .isEqualTo(Verdict.UNSURE);
    }

    private static Verdict verdict(Film film, Prediction prediction, SegmenterParams params) {
        Result r = FilmScore.of(film, prediction, params);
        assertThat(twoPart(film, prediction, params)).isEqualTo(r);
        return r.verdict();
    }

    @Test
    void anUnsureStartKeepsItsStartAndOpeningButNothingElse() {
        double[] p = creditsAt12();
        long[] dense = denseOffsets(138_000, 100);
        SegmenterParams params = new SegmenterParams(0, 0, 3, 10, 0.99, 0.9);
        Film film = film(p, 163_000, dense);
        Prediction prediction = prediction(p, denseP(dense, 161_500));
        Result r = FilmScore.of(film, prediction, params);

        assertThat(r.verdict()).isEqualTo(Verdict.UNSURE);
        assertThat(r.t()).hasValue(160_000);
        assertThat(r.openingAtT()).contains(FrameKind.LIT);
        assertThat(r.margin()).isCloseTo(72, within(1e-9));
        assertThat(r.f()).isEmpty();
        assertThat(r.covered()).isEmpty();
        assertThat(r.aligned()).isEmpty();
        assertThat(r.earlyError()).isEmpty();
        assertThat(r.goalError()).isEmpty();
        assertThat(r.marginBand()).isEmpty();
        assertThat(twoPart(film, prediction, params)).isEqualTo(r);
    }

    @Test
    void aFilmWithTooFewFramesIsNotFoundAndHasNothing() {
        double[] p = {0.9, 0.9, 0.9, 0.9, 0.9};
        Result r = score(film(p, 163_000, new long[0]), prediction(p, new double[0]));

        assertThat(r.verdict()).isEqualTo(Verdict.NOT_FOUND);
        assertThat(r.t()).isEmpty();
        assertThat(r.openingAtT()).isEmpty();
        assertThat(r.margin()).isNaN();
        assertThat(r.negativeBest()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(r.violation()).isFalse();
    }

    @Test
    void aNegativeIsAViolationFromPointEightSigma() {
        // Six frames of +2 before the cut: S* = 12. 0.8 * 15 = 12 exactly: a violation; 0.8 * 15.5 = 12.4: not.
        Film exact = exactFilm();
        Prediction prediction = exactPrediction();
        assertThat(FilmScore.negativeBest(exact, prediction, 0)).isEqualTo(12);
        for (double sigma : new double[] {15, 15.5}) {
            SegmenterParams params = new SegmenterParams(0, 0, 3, sigma, 0.5, 0.5);
            assertThat(FilmScore.of(exact, prediction, params).violation()).isEqualTo(sigma == 15);
            assertThat(twoPart(exact, prediction, params).violation()).isEqualTo(sigma == 15);
        }
    }

    @Test
    void theNegativeLeavesOutAFrameAtTargetMinus5sAndUsesLambda() {
        // Target 130000: frames before 125000 are indices 0..4 (100000..120000), five frames: negative infinity.
        Prediction prediction = exactPrediction();
        Film five = film(new double[6], BASE + 30_000, new long[0]);
        assertThat(FilmScore.negativeBest(five, prediction, 0)).isEqualTo(Double.NEGATIVE_INFINITY);
        // One more ms makes the sixth frame (125000 < 125001) count. lambda 0.5 scores 1.5 per frame: 9.
        assertThat(FilmScore.negativeBest(exactFilm(), prediction, 0.5)).isEqualTo(9);
    }

    @Test
    void thePartsEqualOneCallOnEveryFixtureFilmAtEveryGridPoint() {
        double[] p = creditsAt12();
        double[] mild = new double[30];
        double[] none = new double[30];
        for (int i = 0; i < 30; i++) {
            mild[i] = sigmoid(i < 12 ? 0.2 : (i % 7 == 0 ? -1 : 1.5));
            none[i] = sigmoid(F);
        }
        long[] grid = denseOffsets(138_000, 100);
        long[] long140 = denseOffsets(138_000, 140);
        long[] far = denseOffsets(175_000, 100);
        List<FilmFixture> fixtures = List.of(
            new FilmFixture(film(p, 163_000, grid), prediction(p, denseP(grid, 161_500))),
            new FilmFixture(film(p, 163_000, grid), prediction(p, denseP(grid, 184_000))),
            new FilmFixture(film(p, 163_000, long140), prediction(p, denseP(long140, 184_500))),
            new FilmFixture(film(p, 200_000, far), prediction(p, denseP(far, 175_000))),
            new FilmFixture(film(mild, 170_000, grid), prediction(mild, denseP(grid, 150_000))),
            new FilmFixture(film(none, 163_000, grid), prediction(none, denseP(grid, 161_500))),
            new FilmFixture(exactFilm(), exactPrediction()),
            new FilmFixture(film(new double[5], 163_000, new long[0]),
                prediction(new double[5], new double[0])));

        for (FilmFixture fixture : fixtures) {
            DenseCache cache = new DenseCache(fixture.film(), fixture.prediction());
            for (double lambda : new double[] {0, 1, 3}) {
                for (double delta : new double[] {0, 5, 10}) {
                    for (int gap : new int[] {2, 3, 6}) {
                        CoarsePart part = FilmScore.coarsePart(fixture.film(), fixture.prediction(), lambda, delta,
                            gap);
                        for (double theta : CreditsCalibration.THETAS) {
                            for (double sigma : CreditsCalibration.SIGMAS) {
                                for (double thetaDense : CreditsCalibration.THETA_DENSES) {
                                    SegmenterParams params = new SegmenterParams(lambda, delta, gap, sigma, theta,
                                        thetaDense);
                                    assertThat(FilmScore.finish(fixture.film(), part, sigma, theta, thetaDense,
                                        cache)).isEqualTo(FilmScore.of(fixture.film(), fixture.prediction(), params));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private record FilmFixture(Film film, Prediction prediction) {
    }

    @Test
    void theDenseCacheAnswersPerStartAndPerThetaDense() {
        // High (0.95) from 161500, 0.6 from 156000 to 161000, 0.1 before.
        long[] dense = denseOffsets(138_000, 100);
        double[] p = new double[100];
        for (int i = 0; i < 100; i++) {
            p[i] = dense[i] >= 161_500 ? 0.95 : (dense[i] >= 156_000 ? 0.6 : LOW);
        }
        Film film = film(creditsAt12(), 163_000, dense);
        DenseCache cache = new DenseCache(film, prediction(creditsAt12(), p));

        assertThat(cache.search(160_000, 0.9)).hasValue(161_500);
        // theta_d 0.5: the 0.6 frames count too, so the window from 156000 (inside [t - 5 s, ...)) qualifies.
        assertThat(cache.search(160_000, 0.5)).hasValue(156_000);
        assertThat(cache.search(160_000, 0.9)).hasValue(161_500);
        // A start 3 s later looks from 158000 on: 0.6 frames from there, high from 161500: window from 158000 at
        // theta_d 0.5 and from 161500 at 0.9.
        assertThat(cache.search(163_000, 0.5)).hasValue(158_000);
        assertThat(cache.search(163_000, 0.9)).hasValue(161_500);
        // A start at 120000 reads [110000, 150000) only: the stored 138000..149500 are all 0.1.
        assertThat(cache.search(120_000, 0.5)).isEqualTo(OptionalLong.empty());
    }

    @Test
    void theDenseSearchReadsOnlyFramesFromTMinus10sToBeforeTPlus30s() {
        // t = 160000, range [150000, 190000). High from 185000: its window needs the frame at 190000, out of range.
        // High from 189500 is out of reach too; at t = 165000 the range reaches 195000 and 189500..194500 is one.
        long[] dense = denseOffsets(138_000, 140);
        double[] from185 = new double[140];
        double[] from1895 = new double[140];
        for (int i = 0; i < 140; i++) {
            from185[i] = dense[i] >= 185_000 ? HIGH : LOW;
            from1895[i] = dense[i] >= 189_500 ? HIGH : LOW;
        }
        Film film = film(creditsAt12(), 163_000, dense);
        DenseCache early = new DenseCache(film, prediction(creditsAt12(), from185));
        DenseCache late = new DenseCache(film, prediction(creditsAt12(), from1895));

        assertThat(early.search(160_000, 0.9)).isEqualTo(OptionalLong.empty());
        assertThat(late.search(160_000, 0.9)).isEqualTo(OptionalLong.empty());
        assertThat(late.search(165_000, 0.9)).hasValue(189_500);
    }

    @Test
    void anEnsembleIsTheMeanOfItsModelsAndTheirBases() {
        Prediction a = new Prediction(new double[] {0.25, 0.75}, new double[] {Double.NaN, 0.5}, 1);
        Prediction b = new Prediction(new double[] {0.75, 0.75}, new double[] {Double.NaN, 1}, 2);
        Prediction c = new Prediction(new double[] {0.5, 0.0}, new double[] {Double.NaN, 0}, 6);

        Prediction ensemble = Prediction.ensemble(List.of(a, b, c));

        assertThat(ensemble.coarseP()).containsExactly(0.5, 0.5);
        assertThat(ensemble.denseP()[0]).isNaN();
        assertThat(ensemble.denseP()[1]).isEqualTo(0.5);
        assertThat(ensemble.logitPi()).isEqualTo(3);
        assertThat(Prediction.ensemble(List.of(a)).coarseP()).containsExactly(0.25, 0.75);
    }

    @Test
    void anEnsembleRefusesNoModelsAndModelsOfDifferentLengths() {
        Prediction a = new Prediction(new double[] {0.25}, new double[0], 1);
        assertThatThrownBy(() -> Prediction.ensemble(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Prediction.ensemble(List.of(a, new Prediction(new double[] {0.1, 0.2},
            new double[0], 1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Prediction.ensemble(List.of(a, new Prediction(new double[] {0.1},
            new double[1], 1)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidInputsAreRefused() {
        assertThatThrownBy(() -> new Film(new long[2], new FrameKind[1], new long[0], 0, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class);
        double[] p = creditsAt12();
        Film film = film(p, 163_000, denseOffsets(138_000, 100));
        assertThatThrownBy(() -> new DenseCache(film, prediction(p, new double[3])))
            .isInstanceOf(IllegalArgumentException.class);
        Prediction ok = prediction(p, new double[100]);
        CoarsePart part = FilmScore.coarsePart(film, ok, 0, 0, 3);
        DenseCache cache = new DenseCache(film, ok);
        assertThatThrownBy(() -> FilmScore.finish(film, part, Double.NaN, 0.9, 0.9, cache))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

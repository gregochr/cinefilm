package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.credits.learn.CreditsSegmenter.Coarse;
import gregochr.cinefilm.credits.learn.CreditsSegmenter.Verdict;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.Test;

/**
 * Exact-value tests. A frame is written by its score {@code ℓ}: {@code seq} turns pairs of (ℓ, count) into
 * {@code p = 1 / (1 + e^-(ℓ + logitPi))}, so that with {@code logitPi = 0} and {@code lambda = 0} a frame scoring +4
 * (written A) has p 0.982 and one scoring -4 (F) has p 0.018, and {@code ℓ = 0} is {@code p = 0.5} exactly. The
 * logit round trip is exact only to about 1e-15, so sums are compared within 1e-9 and ties are made of zero scores,
 * which add exactly.
 */
class CreditsSegmenterTest {

    private static final double A = 4;
    private static final double F = -4;
    private static final double TOLERANCE = 1e-9;
    private static final long BASE = 100_000;
    private static final long STEP = CreditsFrameReader.COARSE_STEP_MS;
    /** lambda 0, delta 0, G 3 frames, sigma 10, theta 0.9, theta_d 0.9. */
    private static final SegmenterParams PARAMS = new SegmenterParams(0, 0, 3, 10, 0.9, 0.9);
    private static final double P_A = sigmoid(A);

    private static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    /** Pairs of (score, count) as probabilities for the given {@code logitPi}. */
    private static double[] seqAt(double logitPi, double... runs) {
        int n = 0;
        for (int i = 1; i < runs.length; i += 2) {
            n += (int) runs[i];
        }
        double[] p = new double[n];
        int at = 0;
        for (int i = 0; i < runs.length; i += 2) {
            for (int k = 0; k < (int) runs[i + 1]; k++) {
                p[at++] = sigmoid(runs[i] + logitPi);
            }
        }
        return p;
    }

    private static double[] seq(double... runs) {
        return seqAt(0, runs);
    }

    private static long[] offsets(int n) {
        long[] offsets = new long[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = BASE + STEP * i;
        }
        return offsets;
    }

    private static Coarse coarse(double[] p, SegmenterParams params) {
        return CreditsSegmenter.coarse(offsets(p.length), p, 0, params);
    }

    private static Coarse coarse(double[] p) {
        return coarse(p, PARAMS);
    }

    private static SegmenterParams withGap(int gapFrames) {
        return new SegmenterParams(0, 0, gapFrames, 10, 0.9, 0.9);
    }

    private static void assertFound(Coarse c, int startIndex, int endIndex, double margin) {
        assertThat(c.verdict()).isEqualTo(Verdict.FOUND);
        assertThat(c.tIndex()).isEqualTo(startIndex);
        assertThat(c.t()).hasValue(BASE + STEP * startIndex);
        assertThat(c.uIndex()).isEqualTo(endIndex);
        assertThat(c.margin()).isCloseTo(margin, within(TOLERANCE));
    }

    /**
     * Runs the dense step from the coarse start on a grid from {@code t - 15 s} to {@code t + 35 s} that is high from
     * {@code t + highFrom} on, and checks {@code f = t + expected}.
     */
    private static void assertDenseStart(Coarse c, long highFrom, long expected) {
        long t = c.t().getAsLong();
        long[] offs = grid(t - 15_000, t + 35_000);
        double[] p = denseP(offs, o -> o >= t + highFrom);

        assertThat(CreditsSegmenter.dense(t, offs, p, PARAMS.thetaDense())).hasValue(t + expected);
    }

    private static void assertNotFound(Coarse c) {
        assertThat(c.verdict()).isEqualTo(Verdict.NOT_FOUND);
        assertThat(c.t()).isEmpty();
        assertThat(c.tIndex()).isEqualTo(-1);
        assertThat(c.margin()).isNaN();
        assertThat(c.minP()).isNaN();
    }

    @Test
    void theLengthsAreCountsOfCoarseFramesOfFiveSeconds() {
        assertThat(CreditsSegmenter.MIN_FRAMES * STEP).isEqualTo(30_000);
        assertThat(CreditsSegmenter.LEADING_FRAMES * STEP).isEqualTo(15_000);
        assertThat(CreditsSegmenter.ISLAND_KEEP_FRAMES * STEP).isEqualTo(60_000);
        assertThat(CreditsSegmenter.DENSE_STEP_MS * (CreditsSegmenter.DENSE_WINDOW - 1)).isEqualTo(5_000);
        assertThat(CreditsSegmenter.DENSE_LOOKBACK_MS).isEqualTo(5_000);
    }

    // ---- the ten scenarios ----

    @Test
    void aCleanStepStartsAtTheStep() {
        // F x10, A x20: the best stretch is [10, 30) with S = 20 * 4 = 80.
        Coarse c = coarse(seq(F, 10, A, 20));

        assertFound(c, 10, 30, 80);
        assertThat(c.bestScore()).isCloseTo(80, within(TOLERANCE));
        assertThat(c.minP()).isEqualTo(P_A);
        // t = 150000; the dense frames are high from t - 2000: f = 148000.
        assertDenseStart(c, -2_000, -2_000);
    }

    @Test
    void aShortHalfProbabilityStretchEarlyDoesNotMoveTheStart() {
        // logitPi = -1: a frame with p 0.5 scores +1. F x10, eight p 0.5 frames (S = 8), F x6, A x20 (S = 80).
        // Joining them would be 8 - 24 + 80 = 64, so the best stretch is [24, 44).
        double[] p = seqAt(-1, F, 10, 1, 8, F, 6, A, 20);

        Coarse c = CreditsSegmenter.coarse(offsets(p.length), p, -1, PARAMS);

        assertFound(c, 24, 44, 80);
        assertThat(c.bestScore()).isCloseTo(80, within(TOLERANCE));
        // The dense frames are high from t exactly: f = t.
        assertDenseStart(c, 0, 0);
    }

    @Test
    void aMidCreditsGapShorterThanGIsBridged() {
        // A x20, F x2, A x20 after F x10: S(10, 52) = 80 - 8 + 80 = 152; the run of 2 is below G = 3.
        Coarse c = coarse(seq(F, 10, A, 20, F, 2, A, 20));

        assertFound(c, 10, 52, 152);
        // High from t - 6000: f is held at t - 5000.
        assertDenseStart(c, -6_000, -5_000);
    }

    @Test
    void aMidCreditsGapOfGIsBridgedWhenItIsDeepInsideTheCredits() {
        // The run of 3 starts 20 frames after t, so it is kept: S = 80 - 12 + 80 = 148.
        assertFound(coarse(seq(F, 10, A, 20, F, 3, A, 20)), 10, 53, 148);
    }

    @Test
    void aGapTooLongToBridgeLeavesTheLaterStretch() {
        // S(60, 85) = 100 beats S(10, 85) = 80 - 120 + 100 = 60 and S(10, 30) = 80.
        Coarse c = coarse(seq(F, 10, A, 20, F, 30, A, 25));

        assertFound(c, 60, 85, 100);
        assertDenseStart(c, -1_000, -1_000);
    }

    @Test
    void aPostCreditsSceneAfterTheCreditsIsLeftOut() {
        // F x10, A x20, F x8: the best stretch ends at 30.
        Coarse c = coarse(seq(F, 10, A, 20, F, 8));

        assertFound(c, 10, 30, 80);
        assertThat(c.bestScore()).isCloseTo(80, within(TOLERANCE));
        // High from t - 500: f = t - 500.
        assertDenseStart(c, -500, -500);
    }

    @Test
    void anIsolatedFalsePositiveBeforeTheCreditsIsLeftOut() {
        // F x5, A, F x5, A x20: S(11, 31) = 80, and joining the lone frame costs 4 - 20 more than it gives.
        Coarse c = coarse(seq(F, 5, A, 1, F, 5, A, 20));

        assertFound(c, 11, 31, 80);
        // High from t + 1000: f = t + 1000.
        assertDenseStart(c, 1_000, 1_000);
    }

    @Test
    void anIsolatedFalsePositiveAloneIsNotFound() {
        // Every stretch of 6 holding the lone frame scores about -16; whichever the tie picks, dropping its leading
        // film run leaves fewer than 6 frames.
        assertNotFound(coarse(seq(F, 10, A, 1, F, 19)));
    }

    @Test
    void oneCardThenPictureThenCreditsStartsAtTheCredits() {
        // A x8 (the card), F x6 (the picture), A x20: S(0, 34) = 32 - 24 + 80 = 88. The run of 6 comes 8 frames
        // after t, under 12, so the card is dropped: s = 14 and S(14, 34) = 80.
        Coarse c = coarse(seq(A, 8, F, 6, A, 20));

        assertFound(c, 14, 34, 80);
        assertThat(c.bestScore()).isCloseTo(88, within(TOLERANCE));
        // The credits' text starts 2 s after the coarse frame: f = t + 2000.
        assertDenseStart(c, 2_000, 2_000);
    }

    @Test
    void deltaAppliesAfterADroppedIslandToo() {
        // A x8, F x6, A x20 with delta 5: t starts at 1 (S 84 >= 88 - 5). The island is dropped, s = 14, and the new
        // start is the largest a with S(a, 34) >= S(14, 34) - 5 = 75: a = 15 (S = 76).
        assertFound(coarse(seq(A, 8, F, 6, A, 20), new SegmenterParams(0, 5, 3, 10, 0.9, 0.9)), 15, 34, 76);
    }

    @Test
    void twoLeadingIslandsAreDroppedOneAfterTheOther() {
        // A x5, F x4, A x6, F x4, A x20: S(0, 39) = 92. The first run is 5 after t: s = 9, S(9, 39) = 88. The second
        // is 6 after t: s = 19, S(19, 39) = 80.
        Coarse c = coarse(seq(A, 5, F, 4, A, 6, F, 4, A, 20));

        assertFound(c, 19, 39, 80);
        assertThat(c.bestScore()).isCloseTo(92, within(TOLERANCE));
    }

    @Test
    void epilogueCardsRunningIntoTheCreditsStartAtTheCards() {
        // F x10, cards A x8, F x2 (below G), A x20: S(10, 40) = 32 - 8 + 80 = 104.
        Coarse c = coarse(seq(F, 10, A, 8, F, 2, A, 20));

        assertFound(c, 10, 40, 104);
        // High from t - 3000: f = t - 3000.
        assertDenseStart(c, -3_000, -3_000);
    }

    @Test
    void aFilmWithNoCreditsIsNotFound() {
        // Every stretch scores about -24, and the run of film frames from t reaches u*.
        assertNotFound(coarse(seq(F, 30)));
    }

    @Test
    void aWeakHalfProbabilityStretchAmidFilmIsUnsure() {
        // F x10, p 0.5 x8 (score 0), F x10: S* = 0 exactly over [12, 18) (zero scores add exactly, so the ties are
        // exact: the larger b is 18). The margin 0 is below sigma 10.
        Coarse c = coarse(seq(F, 10, 0, 8, F, 10));

        assertThat(c.verdict()).isEqualTo(Verdict.UNSURE);
        assertThat(c.tIndex()).isEqualTo(12);
        assertThat(c.t()).hasValue(BASE + STEP * 12);
        assertThat(c.uIndex()).isEqualTo(18);
        assertThat(c.bestScore()).isZero();
        assertThat(c.margin()).isZero();
        assertThat(c.minP()).isEqualTo(0.5);
    }

    // ---- boundaries of the counts ----

    @Test
    void fiveFramesAreNotFoundAndSixAreEnough() {
        Coarse five = coarse(seq(A, 5));
        Coarse six = coarse(seq(A, 6));
        Coarse seven = coarse(seq(A, 7));

        assertNotFound(five);
        assertThat(five.uIndex()).isEqualTo(-1);
        assertThat(five.bestScore()).isNaN();
        assertFound(six, 0, 6, 24);
        assertFound(seven, 0, 7, 28);
    }

    @Test
    void theStartLeansLateByDelta() {
        // Nine A frames: S* = 36 over [0, 9). With delta 11 the start is the largest a <= 3 with S(a, 9) >= 25:
        // a = 3 scores 24, a = 2 scores 28. With delta 13 (>= 23) a = 3 qualifies.
        double[] p = seq(A, 9);

        assertFound(coarse(p, new SegmenterParams(0, 0, 3, 10, 0.9, 0.9)), 0, 9, 36);
        assertFound(coarse(p, new SegmenterParams(0, 11, 3, 10, 0.9, 0.9)), 2, 9, 28);
        assertFound(coarse(p, new SegmenterParams(0, 13, 3, 10, 0.9, 0.9)), 3, 9, 24);
    }

    @Test
    void aRunOfGMinusOneIsKeptAndARunOfGIsDropped() {
        // A x4, F x k, A x20 with G = 3: k = 2 is not a run, so t = 0 (S = 88). k = 3 is a run 4 frames after t:
        // s = 7 and S(7, 27) = 80.
        assertFound(coarse(seq(A, 4, F, 2, A, 20), withGap(3)), 0, 26, 88);
        assertFound(coarse(seq(A, 4, F, 3, A, 20), withGap(3)), 7, 27, 80);
        assertFound(coarse(seq(A, 4, F, 3, A, 20), withGap(4)), 0, 27, 84);
    }

    @Test
    void aScoreOfZeroDoesNotMakeARun() {
        // A x5, F x2, 0 x1, F x2, A x20 with G = 3: the zero splits the film frames into two runs of 2, so nothing is
        // dropped. S(0, 30) = 20 - 8 + 0 - 8 + 80 = 84.
        double[] p = seq(A, 5, F, 2, 0, 1, F, 2, A, 20);

        assertFound(coarse(p, withGap(3)), 0, 30, 84);
    }

    @Test
    void aRunIsTakenWholeNotJustItsFirstGFrames() {
        // A x4, F x5, A x20 with G = 3: S(0, 29) = 16 - 20 + 80 = 76, the run is [4, 9), s = 9 and S(9, 29) = 80.
        // Stopping after G frames would give s = 7 and then a = 11 (S = 72 >= 72).
        assertFound(coarse(seq(A, 4, F, 5, A, 20), withGap(3)), 9, 29, 80);
    }

    @Test
    void aRunLongerThanGEndingFewerThanSixFramesBeforeTheEndIsNotFound() {
        // A x10, F x4, A x k with G = 3: the run is [10, 14), before = 10 < 12, s = 14, and u* = 14 + k.
        // k = 5 leaves 5 frames: NOT_FOUND. k = 6 leaves 6: FOUND at 14.
        Coarse five = coarse(seq(A, 10, F, 4, A, 5), withGap(3));

        assertNotFound(five);
        assertThat(five.uIndex()).isEqualTo(19);
        assertThat(five.bestScore()).isCloseTo(44, within(TOLERANCE));
        assertFound(coarse(seq(A, 10, F, 4, A, 6), withGap(3)), 14, 20, 24);
    }

    @Test
    void elevenFramesBeforeARunAreDroppedAndTwelveAreKept() {
        // A x n, F x6, A x20: the run is n after t = 0.
        assertFound(coarse(seq(A, 11, F, 6, A, 20)), 17, 37, 80);
        assertFound(coarse(seq(A, 12, F, 6, A, 20)), 0, 38, 104);
    }

    // ---- the confidence step ----

    @Test
    void theMarginMustReachSigma() {
        double[] p = seq(F, 10, A, 6);
        double margin = coarse(p, new SegmenterParams(0, 0, 3, -1e9, 0.9, 0.9)).margin();

        assertThat(margin).isCloseTo(24, within(TOLERANCE));
        assertThat(coarse(p, new SegmenterParams(0, 0, 3, margin, 0.9, 0.9)).verdict()).isEqualTo(Verdict.FOUND);
        Coarse above = coarse(p, new SegmenterParams(0, 0, 3, Math.nextUp(margin), 0.9, 0.9));
        assertThat(above.verdict()).isEqualTo(Verdict.UNSURE);
        assertThat(above.tIndex()).isEqualTo(10);
        assertThat(above.margin()).isEqualTo(margin);
    }

    @Test
    void eachOfTheFirstThreeFramesMustReachTheta() {
        for (int k = 0; k < 3; k++) {
            double[] p = seq(F, 10, A, 20);
            p[10 + k] = 0.9;
            SegmenterParams exact = new SegmenterParams(0, 0, 3, 10, 0.9, 0.9);
            SegmenterParams above = new SegmenterParams(0, 0, 3, 10, Math.nextUp(0.9), 0.9);

            Coarse found = coarse(p, exact);
            Coarse unsure = coarse(p, above);

            assertThat(found.verdict()).as("frame %d at theta", k).isEqualTo(Verdict.FOUND);
            assertThat(found.minP()).isEqualTo(0.9);
            assertThat(unsure.verdict()).as("frame %d above theta", k).isEqualTo(Verdict.UNSURE);
            assertThat(unsure.tIndex()).isEqualTo(10);
        }
    }

    @Test
    void theFourthFrameDoesNotCountForTheta() {
        double[] p = seq(F, 10, A, 20);
        p[13] = 0.1;

        Coarse c = coarse(p);

        assertThat(c.verdict()).isEqualTo(Verdict.FOUND);
        assertThat(c.tIndex()).isEqualTo(10);
        assertThat(c.minP()).isEqualTo(P_A);
    }

    @Test
    void theSmallestOfTheThreeIsReported() {
        double[] p = seq(F, 10, A, 20);
        p[10] = 0.97;
        p[11] = 0.95;
        p[12] = 0.99;

        assertThat(coarse(p).minP()).isEqualTo(0.95);
    }

    // ---- the score, and the ties ----

    @Test
    void theScoreIsTheLogitMinusLogitPiMinusLambda() {
        // p 0.5 scores 0 - (-1) - 0.5 = +0.5 over 8 frames: S* = 4 exactly.
        double[] p = new double[8];
        Arrays.fill(p, 0.5);
        SegmenterParams params = new SegmenterParams(0.5, 0, 3, 4, 0.5, 0.5);

        Coarse c = CreditsSegmenter.coarse(offsets(8), p, -1, params);

        assertThat(c.verdict()).isEqualTo(Verdict.FOUND);
        assertThat(c.bestScore()).isEqualTo(4.0);
        assertThat(c.margin()).isEqualTo(4.0);
        assertThat(CreditsSegmenter.bestStretch(p, 8, -1, 0.5)).isEqualTo(4.0);
        assertThat(CreditsSegmenter.bestStretch(p, 8, 0, 0.5)).isEqualTo(-3.0);
        assertThat(CreditsSegmenter.bestStretch(p, 8, 0.5, 0)).isEqualTo(-3.0);
    }

    @Test
    void aProbabilityIsClampedToOneE15FromEachEnd() {
        double[] zeros = new double[6];
        double[] ones = new double[6];
        Arrays.fill(ones, 1.0);
        double[] tiny = new double[6];
        Arrays.fill(tiny, 1e-16);
        double[] nearOne = new double[6];
        Arrays.fill(nearOne, 1 - 1e-16);
        double[] justAbove = new double[6];
        Arrays.fill(justAbove, 2e-15);
        double top = 1 - 1e-15;
        double low = Math.log(1e-15 / (1 - 1e-15));
        double high = Math.log(top / (1 - top));

        assertThat(CreditsSegmenter.bestStretch(zeros, 6, 0, 0)).isCloseTo(6 * low, within(TOLERANCE));
        assertThat(CreditsSegmenter.bestStretch(tiny, 6, 0, 0)).isCloseTo(6 * low, within(TOLERANCE));
        assertThat(CreditsSegmenter.bestStretch(ones, 6, 0, 0)).isCloseTo(6 * high, within(TOLERANCE));
        assertThat(CreditsSegmenter.bestStretch(nearOne, 6, 0, 0)).isCloseTo(6 * high, within(TOLERANCE));
        assertThat(CreditsSegmenter.bestStretch(justAbove, 6, 0, 0)).isGreaterThan(6 * low + 1);
    }

    @Test
    void stretchesOfEqualSumTakeTheLargerEndThenTheLargerStart() {
        // All sums 6: every b from 6 to 8 ties, so the largest b, 8, and the start is the one for that b.
        assertThat(CreditsSegmenter.best(new double[] {0, 1, 2, 3, 4, 5, 6, 6, 6}, 8)).containsExactly(0, 8);
        // All zero: for b = 8 the starts 0, 1 and 2 tie and the larger, 2, wins.
        assertThat(CreditsSegmenter.best(new double[9], 8)).containsExactly(2, 8);
        // The maximum 10 is reached at b = 6 and b = 7 (start 1 for b = 7, as P[1] = P[0]); b = 8 is worse.
        assertThat(CreditsSegmenter.best(new double[] {0, 0, 0, 0, 0, 0, 10, 10, 0}, 8)).containsExactly(1, 7);
        // A strictly larger sum later beats an earlier one: b = 6 gives 1, b = 8 gives 5 (starts 0 to 2 tie at 0).
        assertThat(CreditsSegmenter.best(new double[] {0, 0, 0, 0, 0, 0, 1, 1, 5}, 8)).containsExactly(2, 8);
    }

    @Test
    void theEndOfTheBestStretchIsTheLargerOnATieInTheScoresToo() {
        // A x10 then two p 0.5 frames (score 0) after F x10: S(10, 20) = S(10, 22) exactly, so u* = 22.
        Coarse c = coarse(seq(F, 10, A, 10, 0, 2));

        assertThat(c.uIndex()).isEqualTo(22);
        assertThat(c.tIndex()).isEqualTo(10);
        assertThat(c.verdict()).isEqualTo(Verdict.FOUND);
    }

    // ---- input checks of the coarse step ----

    @Test
    void aLogitPiThatIsNotFiniteIsRefused() {
        double[] p = seq(A, 8);
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            String message = "logitPi must be finite: " + bad;
            assertThatThrownBy(() -> CreditsSegmenter.coarse(offsets(8), p, bad, PARAMS))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
            assertThatThrownBy(() -> CreditsSegmenter.coarse(offsets(3), new double[3], bad, PARAMS))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
            assertThatThrownBy(() -> CreditsSegmenter.bestStretch(p, 8, bad, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
            assertThatThrownBy(() -> CreditsSegmenter.bestStretch(p, 3, bad, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        }
    }

    @Test
    void offsetsAndProbabilitiesMustBeTheSameLength() {
        assertThatThrownBy(() -> CreditsSegmenter.coarse(new long[6], new double[7], 0, PARAMS))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("offsets and p differ in length: 6, 7");
    }

    @Test
    void offsetsMustAscendStrictly() {
        long[] offsets = offsets(8);
        offsets[5] = offsets[4];

        assertThatThrownBy(() -> CreditsSegmenter.coarse(offsets, seq(A, 8), 0, PARAMS))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("offsets are not strictly ascending at index 5");
    }

    @Test
    void aNaNProbabilityIsRefused() {
        double[] p = seq(A, 8);
        p[3] = Double.NaN;

        assertThatThrownBy(() -> coarse(p)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("p is NaN at index 3");
    }

    // ---- bestStretch ----

    @Test
    void bestStretchLooksOnlyAtTheFramesBeforeEnd() {
        double[] p = seq(F, 10, A, 20);

        assertThat(CreditsSegmenter.bestStretch(p, 10, 0, 0)).isCloseTo(-24, within(TOLERANCE));
        assertThat(CreditsSegmenter.bestStretch(p, 16, 0, 0)).isCloseTo(24, within(TOLERANCE));
        assertThat(CreditsSegmenter.bestStretch(p, 20, 0, 0)).isCloseTo(40, within(TOLERANCE));
        assertThat(CreditsSegmenter.bestStretch(p, 30, 0, 0)).isCloseTo(80, within(TOLERANCE));
    }

    @Test
    void bestStretchNeedsSixFrames() {
        double[] p = seq(A, 8);

        assertThat(CreditsSegmenter.bestStretch(p, 5, 0, 0)).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(CreditsSegmenter.bestStretch(p, 0, 0, 0)).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(CreditsSegmenter.bestStretch(p, -1, 0, 0)).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(CreditsSegmenter.bestStretch(p, 6, 0, 0)).isCloseTo(24, within(TOLERANCE));
        assertThat(CreditsSegmenter.bestStretch(p, 7, 0, 0)).isCloseTo(28, within(TOLERANCE));
    }

    @Test
    void bestStretchRefusesAnEndPastTheLastFrame() {
        assertThatThrownBy(() -> CreditsSegmenter.bestStretch(seq(A, 8), 9, 0, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("end is past the last frame: 9 of 8");
    }

    @Test
    void bestStretchIgnoresANaNAfterEndButNotBeforeIt() {
        double[] p = seq(A, 8);
        p[7] = Double.NaN;

        assertThat(CreditsSegmenter.bestStretch(p, 7, 0, 0)).isCloseTo(28, within(TOLERANCE));
        assertThatThrownBy(() -> CreditsSegmenter.bestStretch(p, 8, 0, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("p is NaN at index 7");
    }

    // ---- the dense step ----

    private static final long T = 20_000;

    /** Offsets {@code from, from + 500, ..., to}. */
    private static long[] grid(long from, long to) {
        return java.util.stream.LongStream.rangeClosed(0, (to - from) / 500).map(i -> from + 500 * i).toArray();
    }

    /** 0.95 where {@code high} holds, 0.1 elsewhere. */
    private static double[] denseP(long[] offsets, LongPredicate high) {
        double[] p = new double[offsets.length];
        for (int i = 0; i < p.length; i++) {
            p[i] = high.test(offsets[i]) ? 0.95 : 0.1;
        }
        return p;
    }

    @Test
    void theStartIsTheFirstFrameOfTheFirstWindowThatIsHighThroughout() {
        long[] offsets = grid(T - 15_000, T + 35_000);
        // High from t - 2000: f = 18000, whose window [18000, 23000] is all high.
        double[] p = denseP(offsets, o -> o >= T - 2_000);

        assertThat(CreditsSegmenter.dense(T, offsets, p, 0.9)).hasValue(T - 2_000);
    }

    @Test
    void aWindowThatStartsBeforeTMinusFiveThousandIsNotConsidered() {
        long[] offsets = grid(T - 15_000, T + 35_000);

        // High from t - 5500: f = t - 5500 is excluded, the next is t - 5000.
        assertThat(CreditsSegmenter.dense(T, offsets, denseP(offsets, o -> o >= T - 5_500), 0.9))
            .hasValue(T - 5_000);
        // High from t - 5000 exactly: f = t - 5000.
        assertThat(CreditsSegmenter.dense(T, offsets, denseP(offsets, o -> o >= T - 5_000), 0.9))
            .hasValue(T - 5_000);
        // High from t - 4500: f = t - 4500.
        assertThat(CreditsSegmenter.dense(T, offsets, denseP(offsets, o -> o >= T - 4_500), 0.9))
            .hasValue(T - 4_500);
    }

    @Test
    void theLowerBoundIsTheFirstOffsetAtOrAfterTMinusFiveThousandOnTheGrid() {
        // t = 20300: t - 5000 = 15300, the grid 100 + 500 i has 15100 and 15600.
        long[] offsets = grid(100, 60_100);
        double[] p = denseP(offsets, o -> true);

        assertThat(CreditsSegmenter.dense(20_300, offsets, p, 0.9)).hasValue(15_600);
        assertThat(CreditsSegmenter.dense(20_100, offsets, p, 0.9)).hasValue(15_100);
    }

    @Test
    void aWindowOfElevenOffsetsQualifiesAndOneOfTenDoesNot() {
        long[] eleven = grid(T - 5_000, T);
        long[] ten = grid(T - 5_000, T - 500);

        assertThat(CreditsSegmenter.dense(T, eleven, denseP(eleven, o -> true), 0.9)).hasValue(T - 5_000);
        assertThat(CreditsSegmenter.dense(T, ten, denseP(ten, o -> true), 0.9)).isEmpty();
    }

    @Test
    void aMissingOffsetBreaksTheWindowEvenWhenElevenOffsetsRemain() {
        // 15000 ... 20500 without 17500 has 11 entries spanning 5500 ms, and 10 entries spanning 5000 without 20500.
        long[] withHole = java.util.stream.LongStream.rangeClosed(30, 41).map(i -> i * 500).filter(o -> o != 17_500)
            .toArray();
        long[] shortHole = java.util.stream.LongStream.rangeClosed(30, 40).map(i -> i * 500).filter(o -> o != 17_500)
            .toArray();

        assertThat(withHole).hasSize(11);
        assertThat(CreditsSegmenter.dense(T, withHole, denseP(withHole, o -> true), 0.9)).isEmpty();
        assertThat(shortHole).hasSize(10);
        assertThat(CreditsSegmenter.dense(T, shortHole, denseP(shortHole, o -> true), 0.9)).isEmpty();
    }

    @Test
    void aFrameWithoutTimeFeaturesBreaksEveryWindowHoldingIt() {
        long[] offsets = grid(T - 15_000, T + 35_000);
        double[] p = denseP(offsets, o -> true);
        p[(int) ((T - (T - 15_000)) / 500)] = Double.NaN;

        // The NaN is at offset 20000: no window [f, f + 5000] with f <= 20000 <= f + 5000 is usable, so f = 20500.
        assertThat(CreditsSegmenter.dense(T, offsets, p, 0.9)).hasValue(T + 500);
    }

    @Test
    void aNaNAtTheFirstFrameOnlyMovesTheStartByOneFrame() {
        long[] offsets = grid(T - 15_000, T + 35_000);
        double[] p = denseP(offsets, o -> true);
        p[(int) (10_000 / 500)] = Double.NaN;

        assertThat(CreditsSegmenter.dense(T, offsets, p, 0.9)).hasValue(T - 4_500);
    }

    @Test
    void aFrameOnTheThresholdQualifiesAndOneJustBelowDoesNot() {
        long[] offsets = grid(T - 5_000, T);
        double[] p = denseP(offsets, o -> true);
        Arrays.fill(p, 0.9);

        assertThat(CreditsSegmenter.dense(T, offsets, p, 0.9)).hasValue(T - 5_000);
        assertThat(CreditsSegmenter.dense(T, offsets, p, Math.nextUp(0.9))).isEmpty();
    }

    @Test
    void everyFrameOfTheWindowIsChecked() {
        long[] offsets = grid(T - 5_000, T + 5_000);
        for (int low = 0; low < 21; low++) {
            double[] p = denseP(offsets, o -> true);
            p[low] = 0.1;
            OptionalLong f = CreditsSegmenter.dense(T, offsets, p, 0.9);

            // Windows start at index 0 .. 10 and cover i .. i + 10. Index 10 is in every one of them.
            if (low < 10) {
                assertThat(f).as("low at %d", low).hasValue(T + (low + 1) * 500L - 5_000);
            } else if (low == 10) {
                assertThat(f).as("low at %d", low).isEmpty();
            } else {
                assertThat(f).as("low at %d", low).hasValue(T - 5_000);
            }
        }
    }

    @Test
    void aThetaDenseOfOneIsAcceptedAndNeedsEveryFrameAtOne() {
        long[] offsets = grid(T - 5_000, T);
        double[] p = new double[offsets.length];
        Arrays.fill(p, 1.0);

        assertThat(CreditsSegmenter.dense(T, offsets, p, 1.0)).hasValue(T - 5_000);
        assertThat(CreditsSegmenter.dense(T, offsets, p, 0.0)).hasValue(T - 5_000);
    }

    @Test
    void whenNoWindowQualifiesTheResultIsEmptyAndTheCallerUsesT() {
        long[] offsets = grid(T - 15_000, T + 35_000);
        // p 0.5 everywhere: below 0.9.
        double[] p = denseP(offsets, o -> false);
        Arrays.fill(p, 0.5);

        assertThat(CreditsSegmenter.dense(T, offsets, p, 0.9)).isEmpty();
    }

    @Test
    void aHighStretchOfOnlyTenFramesIsNotAWindow() {
        long[] offsets = grid(T - 15_000, T + 35_000);

        assertThat(CreditsSegmenter.dense(T, offsets, denseP(offsets, o -> o >= T && o < T + 5_000), 0.9)).isEmpty();
        assertThat(CreditsSegmenter.dense(T, offsets, denseP(offsets, o -> o >= T && o <= T + 5_000), 0.9))
            .hasValue(T);
    }

    @Test
    void aDenseRangeCutShortAtTheFilesEndLeavesNoWindow() {
        // The file ends at t + 4500: only ten frames [t, t + 4500] are high, so no f has p throughout [f, f + 5 s].
        long[] cut = grid(T - 15_000, T + 4_500);
        long[] whole = grid(T - 15_000, T + 5_000);

        assertThat(CreditsSegmenter.dense(T, cut, denseP(cut, o -> o >= T), 0.9)).isEmpty();
        assertThat(CreditsSegmenter.dense(T, whole, denseP(whole, o -> o >= T), 0.9)).hasValue(T);
    }

    @Test
    void aWindowEarlierThanTheCoarseStartIsFoundWhenItIsHighThroughout() {
        // All low except [t - 3000, t + 2000] (11 frames): f = t - 3000.
        long[] offsets = grid(T - 15_000, T + 35_000);
        double[] p = denseP(offsets, o -> o >= T - 3_000 && o <= T + 2_000);

        assertThat(CreditsSegmenter.dense(T, offsets, p, 0.9)).hasValue(T - 3_000);
    }

    @Test
    void theDenseFallbackLeavesTheCoarseStartWhenNoWindowQualifies() {
        // From the clean step: every dense frame is 0.5, below theta_d, so dense is empty and the start stays t.
        Coarse c = coarse(seq(F, 10, A, 20));
        long t = c.t().getAsLong();
        long[] offs = grid(t - 15_000, t + 35_000);
        double[] p = new double[offs.length];
        Arrays.fill(p, 0.5);

        assertFound(c, 10, 30, 80);
        OptionalLong f = CreditsSegmenter.dense(t, offs, p, PARAMS.thetaDense());
        assertThat(f).isEmpty();
        assertThat(f.orElse(t)).isEqualTo(150_000);
    }

    @Test
    void aDenseRangeCutShortAtTheFilesEndKeepsTheCoarseStart() {
        // From the clean step: the file ends at t + 4500, so only ten frames [t, t + 4500] are high.
        Coarse c = coarse(seq(F, 10, A, 20));
        long t = c.t().getAsLong();
        long[] offs = grid(t - 15_000, t + 4_500);
        double[] p = denseP(offs, o -> o >= t);

        assertFound(c, 10, 30, 80);
        OptionalLong f = CreditsSegmenter.dense(t, offs, p, PARAMS.thetaDense());
        assertThat(f).isEmpty();
        assertThat(f.orElse(t)).isEqualTo(150_000);
    }

    @Test
    void anEmptyDenseRangeHasNoStart() {
        assertThat(CreditsSegmenter.dense(T, new long[0], new double[0], 0.9)).isEmpty();
    }

    @Test
    void theDenseInputsAreChecked() {
        assertThatThrownBy(() -> CreditsSegmenter.dense(T, new long[2], new double[3], 0.9))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("offsets and p differ in length: 2, 3");
        assertThatThrownBy(() -> CreditsSegmenter.dense(T, new long[] {0, 500, 500}, new double[3], 0.9))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("offsets are not strictly ascending at index 2");
        assertThatThrownBy(() -> CreditsSegmenter.dense(T, new long[] {0, 500, 1_250}, new double[3], 0.9))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("offsets are not on one 500 ms grid at index 2");
        assertThatThrownBy(() -> CreditsSegmenter.dense(T, new long[0], new double[0], 1.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("thetaDense must be in [0, 1]: 1.5");
        assertThatThrownBy(() -> CreditsSegmenter.dense(T, new long[0], new double[0], Double.NaN))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thetaDense");
        assertThatThrownBy(() -> CreditsSegmenter.dense(T, new long[0], new double[0], -0.1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thetaDense");
    }
}

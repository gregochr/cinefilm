package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import gregochr.cinefilm.credits.learn.GradientBoostedTrees.Leaf;
import gregochr.cinefilm.credits.learn.GradientBoostedTrees.Node;
import gregochr.cinefilm.credits.learn.GradientBoostedTrees.Split;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GradientBoostedTreesTest {

    private static final double EPS = 1e-12;

    /** A one-tree, depth-one fit with learning rate 1, lambda 1 and the given bins and minLeaf. */
    private static GbtParams stump(int bins, int minLeaf) {
        return new GbtParams(1, 1, 1.0, bins, minLeaf, 1.0);
    }

    /** A row whose feature 0 is {@code x}; every other feature is 0. */
    private static double[] row(double x) {
        double[] r = new double[CreditsFeatures.COUNT];
        r[0] = x;
        return r;
    }

    private static double[][] rowsOf(double... xs) {
        double[][] rows = new double[xs.length][];
        for (int i = 0; i < xs.length; i++) {
            rows[i] = row(xs[i]);
        }
        return rows;
    }

    private static double[][] oneToN(int n) {
        double[] xs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = i + 1;
        }
        return rowsOf(xs);
    }

    private static Split root(GradientBoostedTrees model, int tree) {
        return (Split) model.trees().get(tree);
    }

    private static double leaf(Node node) {
        return ((Leaf) node).value();
    }

    // ---- bins ----

    @Test
    void thresholdsAreTheSortedValuesAtKnOverBins() {
        // n = 8, bins = 4: indices 2, 4, 6 of the sorted values 1..8 are 3, 5, 7.
        double[] column = {8, 1, 7, 2, 6, 3, 5, 4};

        assertThat(GradientBoostedTrees.thresholds(column, 4)).containsExactly(3, 5, 7);
    }

    @Test
    void repeatedThresholdsAreKeptOnce() {
        // n = 8, bins = 4: indices 2, 4, 6 hold 1, 2, 2; the second 2 is dropped.
        double[] column = {1, 1, 1, 1, 2, 2, 2, 2};

        assertThat(GradientBoostedTrees.thresholds(column, 4)).containsExactly(1, 2);
    }

    @Test
    void aFeatureWithOneDistinctValueHasNoThreshold() {
        assertThat(GradientBoostedTrees.thresholds(new double[] {5, 5, 5}, 2)).isEmpty();
    }

    @Test
    void aFeatureWithTwoValuesHasOneThresholdEvenWhenTheFirstIndexIsTheLowerValue() {
        // n = 3, bins = 2: index 1 of [1, 1, 2] is 1.
        assertThat(GradientBoostedTrees.thresholds(new double[] {1, 2, 1}, 2)).containsExactly(1);
    }

    @Test
    void asManyBinsAsRowsGiveEveryValueButTheSmallestAsAThreshold() {
        // n = 6, bins = 6: thresholds are the values at indices 1..5, that is 2..6.
        assertThat(GradientBoostedTrees.thresholds(new double[] {6, 5, 4, 3, 2, 1}, 6))
                .containsExactly(2, 3, 4, 5, 6);
    }

    // ---- base ----

    @Test
    void theBaseIsTheLogOddsOfTheShareOfCredits() {
        // one positive of four: p = 1/4, ln(p / (1 - p)) = ln(1/3).
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 0, 1}, stump(4, 1));

        assertThat(model.base()).isCloseTo(Math.log(1.0 / 3), within(EPS));
    }

    // ---- a split's gain, a leaf value, the tie-breaks ----

    @Test
    void theSplitOfTheLargestGainIsChosenAndEachLeafIsMinusLrGOverHPlusLambda() {
        // x = 1..4, labels 0 0 1 1: base 0, p = 0.5, g = 0.5 0.5 -0.5 -0.5, h = 0.25 each, lambda 1.
        // Thresholds (indices 1, 2, 3): 2, 3, 4. Parent G = 0, so its term is 0.
        //   v=2: left {1,2} G=1,   H=0.5:  1/1.5 + right G=-1,   H=0.5:  1/1.5      = 1.3333
        //   v=3: left {1,2,3} G=.5, H=.75: .25/1.75 + right G=-.5, H=.25: .25/1.25   = 0.3429
        //   v=4: right is empty, so minLeaf 1 blocks it.
        // Split at 2. Leaves: left -1/(0.5+1) = -2/3, right -(-1)/(0.5+1) = +2/3.
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 1, 1}, stump(4, 1));

        Split split = root(model, 0);
        assertThat(split.feature()).isZero();
        assertThat(split.threshold()).isEqualTo(2.0);
        assertThat(leaf(split.left())).isCloseTo(-2.0 / 3, within(EPS));
        assertThat(leaf(split.right())).isCloseTo(2.0 / 3, within(EPS));
    }

    @Test
    void theLearningRateScalesTheLeaves() {
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 1, 1},
                new GbtParams(1, 1, 0.5, 4, 1, 1.0));

        assertThat(leaf(root(model, 0).left())).isCloseTo(-1.0 / 3, within(EPS));
        assertThat(leaf(root(model, 0).right())).isCloseTo(1.0 / 3, within(EPS));
    }

    @Test
    void lambdaShrinksTheLeaves() {
        // lambda 0: left -1/0.5 = -2.
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 1, 1},
                new GbtParams(1, 1, 1.0, 4, 1, 0.0));

        assertThat(leaf(root(model, 0).left())).isCloseTo(-2.0, within(EPS));
    }

    @Test
    void aTieBetweenFeaturesGoesToTheLowestIndex() {
        // Features 5 and 0 hold the same column, so their gains are equal; feature 0 wins.
        double[][] rows = oneToN(4);
        for (double[] r : rows) {
            r[5] = r[0];
        }

        GradientBoostedTrees model = GradientBoostedTrees.fit(rows, new int[] {0, 0, 1, 1}, stump(4, 1));

        assertThat(root(model, 0).feature()).isZero();
    }

    @Test
    void aBetterLaterFeatureBeatsAnEarlierOne() {
        // Feature 0 is constant and feature 7 separates the classes.
        double[][] rows = oneToN(4);
        for (double[] r : rows) {
            r[7] = r[0];
            r[0] = 0;
        }

        GradientBoostedTrees model = GradientBoostedTrees.fit(rows, new int[] {0, 0, 1, 1}, stump(4, 1));

        assertThat(root(model, 0).feature()).isEqualTo(7);
    }

    @Test
    void aTieBetweenThresholdsGoesToTheLowest() {
        // x = 1..8, labels 0 0 1 1 1 1 0 0: base 0, g = +-0.5, h = 0.25, lambda 1; thresholds are 2..8.
        //   v=2: left G=1, H=.5: 1/1.5 + right G=-1, H=1.5: 1/2.5 = 1.0667
        //   v=6: left G=-1, H=1.5: 1/2.5 + right G=1, H=.5: 1/1.5 = 1.0667 (the same sums, in the same order)
        // The lowest, 2, wins; v=4 has gain 0 and the others are smaller.
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(8), new int[] {0, 0, 1, 1, 1, 1, 0, 0},
                stump(8, 1));

        assertThat(root(model, 0).threshold()).isEqualTo(2.0);
    }

    @Test
    void aSplitWhoseGainIsNotAboveTheFloorIsNotMade() {
        // One feature with two values, but one positive on each side: the sums are equal, so the gain is 0.
        // x = 1 1 2 2, labels 0 1 0 1: g = +.5 -.5 +.5 -.5, G = 0 on both sides.
        GradientBoostedTrees model = GradientBoostedTrees.fit(rowsOf(1, 1, 2, 2), new int[] {0, 1, 0, 1},
                stump(4, 1));

        assertThat(model.trees().get(0)).isInstanceOf(Leaf.class);
    }

    @Test
    void aTreeOfConstantFeaturesIsOneLeafThatIsZeroAtTheBase() {
        // G = 0 at the base, so the single leaf is 0 and the prediction stays at the base probability.
        double[][] rows = new double[4][CreditsFeatures.COUNT];

        GradientBoostedTrees model = GradientBoostedTrees.fit(rows, new int[] {0, 1, 0, 0}, stump(4, 1));

        assertThat(leaf(model.trees().get(0))).isCloseTo(0.0, within(EPS));
        assertThat(model.predict(rows[0])).isCloseTo(0.25, within(EPS));
    }

    // ---- minLeaf, depth ----

    @Test
    void minLeafOneAllowsASplitThatLeavesOneRow() {
        // x = 1..6, one positive (the last): thresholds 2..6; v=5 puts rows 1..5 left and row 6 right.
        //   p = 1/6, g = 1/6 (x5) and -5/6, h = 5/36, lambda 1.
        //   v=5: G=5/6 H=25/36: .6944/1.6944 + G=-5/6 H=5/36: .6944/1.1389 = 1.0196 (the largest)
        //   v=4: .4444/1.5556 + .4444/1.2778 = 0.6335
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(6), new int[] {0, 0, 0, 0, 0, 1}, stump(6, 1));

        assertThat(root(model, 0).threshold()).isEqualTo(5.0);
    }

    @Test
    void minLeafTwoBlocksThatSplitAndTakesTheNextBest() {
        // The same rows with minLeaf 2: v=5 leaves one row on the right, so v=4 (0.6335) wins over v=3 (0.353).
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(6), new int[] {0, 0, 0, 0, 0, 1}, stump(6, 2));

        assertThat(root(model, 0).threshold()).isEqualTo(4.0);
    }

    @Test
    void whenNoSplitLeavesMinLeafRowsOnBothSidesTheTreeIsALeaf() {
        // Four rows and minLeaf 3: every split leaves fewer than 3 on one side.
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 1, 1}, stump(4, 3));

        assertThat(model.trees().get(0)).isInstanceOf(Leaf.class);
    }

    @Test
    void aNodeAtDepthIsALeafAndAShallowerNodeSplitsAgain() {
        // x = 1..8, labels 0 0 1 1 1 1 0 0. Root splits at 2. The right child {3..8} has g -.5 x4, +.5 x2:
        // G = -1, H = 1.5, parent term 1/2.5 = 0.4; v=6: left G=-2 H=1: 4/2 + right G=1 H=.5: 1/1.5 - 0.4 = 2.27.
        int[] labels = {0, 0, 1, 1, 1, 1, 0, 0};

        GradientBoostedTrees shallow = GradientBoostedTrees.fit(oneToN(8), labels, new GbtParams(1, 1, 1.0, 8, 1, 1.0));
        GradientBoostedTrees deep = GradientBoostedTrees.fit(oneToN(8), labels, new GbtParams(1, 2, 1.0, 8, 1, 1.0));

        assertThat(root(shallow, 0).right()).isInstanceOf(Leaf.class);
        Split right = (Split) root(deep, 0).right();
        assertThat(right.threshold()).isEqualTo(6.0);
        assertThat(root(deep, 0).left()).isInstanceOf(Leaf.class);
    }

    // ---- predict ----

    @Test
    void aStumpPredictsTheLogisticOfBasePlusLeaf() {
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 1, 1}, stump(4, 1));

        // base 0; x <= 2 goes left (-2/3), x = 3.5 goes right (+2/3).
        assertThat(model.predict(row(1))).isCloseTo(1 / (1 + Math.exp(2.0 / 3)), within(EPS));
        assertThat(model.predict(row(2))).isCloseTo(1 / (1 + Math.exp(2.0 / 3)), within(EPS));
        assertThat(model.predict(row(3.5))).isCloseTo(1 / (1 + Math.exp(-2.0 / 3)), within(EPS));
    }

    @Test
    void theSecondTreeIsFittedToTheFirstTreesScoresAndPredictionSumsBoth() {
        // After tree 1 the scores are -2/3 (x=1,2) and +2/3 (x=3,4).
        //   x=1,2: p = 1/(1+e^(2/3)), g = p, h = p(1-p);  x=3,4: p' = 1-p, g = -p, h = p(1-p).
        // Tree 2 splits at 2 again (gain 2*(2p)^2/(2h+1) = 0.636, against 0.163 at 3).
        //   left leaf = -(2p)/(2h+1), right leaf = +(2p)/(2h+1); predict(x=1) = 1/(1+e^-(-2/3 + left leaf)).
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 1, 1},
                new GbtParams(2, 1, 1.0, 4, 1, 1.0));

        double p = 1 / (1 + Math.exp(2.0 / 3));
        double h = p * (1 - p);
        double second = 2 * p / (2 * h + 1);
        assertThat(model.trees()).hasSize(2);
        assertThat(root(model, 1).threshold()).isEqualTo(2.0);
        assertThat(leaf(root(model, 1).left())).isCloseTo(-second, within(EPS));
        assertThat(leaf(root(model, 1).right())).isCloseTo(second, within(EPS));
        assertThat(model.predict(row(1))).isCloseTo(1 / (1 + Math.exp(2.0 / 3 + second)), within(EPS));
        assertThat(model.predict(row(4))).isCloseTo(1 / (1 + Math.exp(-(2.0 / 3 + second))), within(EPS));
    }

    @Test
    void aRowEqualToTheThresholdGoesLeft() {
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 1, 1}, stump(4, 1));

        assertThat(model.predict(row(2))).isLessThan(0.5);
        assertThat(model.predict(row(2.0000001))).isGreaterThan(0.5);
    }

    @Test
    void predictRefusesARowOfTheWrongLength() {
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 1, 1}, stump(4, 1));

        assertThatThrownBy(() -> model.predict(new double[3])).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- input refusals ----

    @Test
    void noRowsIsRefused() {
        assertThatThrownBy(() -> GradientBoostedTrees.fit(new double[0][], new int[0], stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no rows");
    }

    @Test
    void nullRowsAreRefused() {
        assertThatThrownBy(() -> GradientBoostedTrees.fit(null, new int[0], stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no rows");
    }

    @Test
    void aCountOfLabelsThatDiffersFromTheRowsIsRefused() {
        assertThatThrownBy(() -> GradientBoostedTrees.fit(oneToN(4), new int[] {0, 1, 0}, stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one label per row");
    }

    @Test
    void nullLabelsAreRefused() {
        assertThatThrownBy(() -> GradientBoostedTrees.fit(oneToN(4), null, stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one label per row");
    }

    @Test
    void aRowOfTheWrongLengthIsRefused() {
        double[][] rows = oneToN(4);
        rows[2] = new double[CreditsFeatures.COUNT - 1];

        assertThatThrownBy(() -> GradientBoostedTrees.fit(rows, new int[] {0, 0, 1, 1}, stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("each row needs");
    }

    @Test
    void aNullRowIsRefused() {
        double[][] rows = oneToN(4);
        rows[1] = null;

        assertThatThrownBy(() -> GradientBoostedTrees.fit(rows, new int[] {0, 0, 1, 1}, stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("each row needs");
    }

    @Test
    void aNaNValueIsRefused() {
        double[][] rows = oneToN(4);
        rows[3][4] = Double.NaN;

        assertThatThrownBy(() -> GradientBoostedTrees.fit(rows, new int[] {0, 0, 1, 1}, stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NaN or infinite");
    }

    @Test
    void anInfiniteValueIsRefused() {
        double[][] rows = oneToN(4);
        rows[0][32] = Double.NEGATIVE_INFINITY;

        assertThatThrownBy(() -> GradientBoostedTrees.fit(rows, new int[] {0, 0, 1, 1}, stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NaN or infinite");
    }

    @Test
    void aLabelOfTwoIsRefused() {
        assertThatThrownBy(() -> GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 1, 2}, stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("label must be 0 or 1");
    }

    @Test
    void aNegativeLabelIsRefused() {
        assertThatThrownBy(() -> GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 1, -1}, stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("label must be 0 or 1");
    }

    @Test
    void onlyFilmLabelsAreRefused() {
        assertThatThrownBy(() -> GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 0, 0}, stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both classes");
    }

    @Test
    void onlyCreditsLabelsAreRefused() {
        assertThatThrownBy(() -> GradientBoostedTrees.fit(oneToN(4), new int[] {1, 1, 1, 1}, stump(4, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both classes");
    }

    // ---- JSON ----

    @Test
    void theJsonHasTheFourKeysInOrder() {
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(4), new int[] {0, 0, 1, 1}, stump(4, 1));

        assertThat(model.toJson()).isEqualTo("{\"featureVersion\":1,\"base\":0.0,\"params\":{\"trees\":1,"
                + "\"depth\":1,\"learningRate\":1.0,\"bins\":4,\"minLeaf\":1,\"lambda\":1.0},\"trees\":["
                + "{\"feature\":0,\"threshold\":2.0,\"left\":{\"leaf\":-0.6666666666666666},"
                + "\"right\":{\"leaf\":0.6666666666666666}}]}");
    }

    /** 40 rows over two varying features with a noisy boundary, so the trees grow several levels. */
    private static GradientBoostedTrees bigFit() {
        double[][] rows = new double[40][CreditsFeatures.COUNT];
        int[] labels = new int[40];
        for (int i = 0; i < 40; i++) {
            rows[i][0] = (i * 7) % 40;
            rows[i][1] = (i * 13) % 17;
            labels[i] = (rows[i][0] + rows[i][1] * 2 > 30) ? 1 : 0;
        }
        labels[3] = 1 - labels[3];
        return GradientBoostedTrees.fit(rows, labels, new GbtParams(6, 3, 0.3, 8, 2, 1.0));
    }

    @Test
    void theModelReadBackFromItsJsonPredictsIdentically() {
        GradientBoostedTrees model = bigFit();

        GradientBoostedTrees back = GradientBoostedTrees.fromJson(model.toJson());

        for (int i = 0; i < 40; i++) {
            double[] r = new double[CreditsFeatures.COUNT];
            r[0] = (i * 7) % 40;
            r[1] = (i * 13) % 17;
            assertThat(back.predict(r)).isEqualTo(model.predict(r));
        }
    }

    @Test
    void theModelReadBackWritesTheSameJsonAndHoldsTheSameSettings() {
        GradientBoostedTrees model = bigFit();

        GradientBoostedTrees back = GradientBoostedTrees.fromJson(model.toJson());

        assertThat(back.toJson()).isEqualTo(model.toJson());
        assertThat(back.params()).isEqualTo(new GbtParams(6, 3, 0.3, 8, 2, 1.0));
        assertThat(back.base()).isEqualTo(model.base());
        assertThat(back.trees()).isEqualTo(model.trees());
    }

    @Test
    void aModelOfAnotherFeatureVersionIsRefusedNamingBothVersions() throws IOException {
        String fixture;
        try (InputStream in = GradientBoostedTreesTest.class
                .getResourceAsStream("/credits/gbt-model-feature-version-999.json")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThatThrownBy(() -> GradientBoostedTrees.fromJson(fixture))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("999")
                .hasMessageContaining(String.valueOf(CreditsFeatures.FEATURE_VERSION));
    }

    @Test
    void aModelWithAMissingFieldIsRefusedNamingIt() {
        assertThatThrownBy(() -> GradientBoostedTrees.fromJson("{\"featureVersion\":1}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("params");
    }

    @Test
    void aModelWithOutOfRangeSettingsIsRefused() {
        String json = "{\"featureVersion\":1,\"base\":0.0,\"params\":{\"trees\":0,\"depth\":1,\"learningRate\":1.0,"
                + "\"bins\":4,\"minLeaf\":1,\"lambda\":1.0},\"trees\":[]}";

        assertThatThrownBy(() -> GradientBoostedTrees.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trees");
    }

    // ---- determinism ----

    // ---- the whole tree against a plain reference ----

    /** The tree of the spec written the slow way: direct sums over the rows of each side, in row order. */
    private static Node reference(double[][] rows, double[] g, double[] h, int[] idx, int depth, GbtParams p) {
        double sumG = 0;
        double sumH = 0;
        for (int i : idx) {
            sumG += g[i];
            sumH += h[i];
        }
        double bestGain = 1e-12;
        int bestFeature = -1;
        double bestThreshold = 0;
        for (int f = 0; depth < p.depth() && f < CreditsFeatures.COUNT; f++) {
            double[] column = new double[rows.length];
            for (int i = 0; i < rows.length; i++) {
                column[i] = rows[i][f];
            }
            for (double t : GradientBoostedTrees.thresholds(column, p.bins())) {
                double lg = 0;
                double lh = 0;
                double rg = 0;
                double rh = 0;
                int left = 0;
                for (int i : idx) {
                    if (rows[i][f] <= t) {
                        lg += g[i];
                        lh += h[i];
                        left++;
                    } else {
                        rg += g[i];
                        rh += h[i];
                    }
                }
                if (left < p.minLeaf() || idx.length - left < p.minLeaf()) {
                    continue;
                }
                double gain = lg * lg / (lh + p.lambda()) + rg * rg / (rh + p.lambda())
                        - sumG * sumG / (sumH + p.lambda());
                if (gain > bestGain) {
                    bestGain = gain;
                    bestFeature = f;
                    bestThreshold = t;
                }
            }
        }
        if (bestFeature < 0) {
            return new Leaf(-p.learningRate() * sumG / (sumH + p.lambda()));
        }
        final int feature = bestFeature;
        final double threshold = bestThreshold;
        int[] left = Arrays.stream(idx).filter(i -> rows[i][feature] <= threshold).toArray();
        int[] right = Arrays.stream(idx).filter(i -> rows[i][feature] > threshold).toArray();
        return new Split(feature, threshold, reference(rows, g, h, left, depth + 1, p),
                reference(rows, g, h, right, depth + 1, p));
    }

    private static void assertSameTree(Node actual, Node expected) {
        if (expected instanceof Leaf want) {
            assertThat(actual).isInstanceOf(Leaf.class);
            assertThat(leaf(actual)).isCloseTo(want.value(), within(1e-9));
            return;
        }
        Split want = (Split) expected;
        assertThat(actual).isInstanceOf(Split.class);
        Split got = (Split) actual;
        assertThat(got.feature()).isEqualTo(want.feature());
        assertThat(got.threshold()).isEqualTo(want.threshold());
        assertSameTree(got.left(), want.left());
        assertSameTree(got.right(), want.right());
    }

    @Test
    void aFirstTreeOnRandomRowsIsTheTreeOfTheSlowReference() {
        GbtParams p = new GbtParams(1, 3, 0.7, 10, 3, 0.5);
        for (long seed = 1; seed <= 12; seed++) {
            Random random = new Random(seed);
            int n = 60;
            double[][] rows = new double[n][CreditsFeatures.COUNT];
            int[] labels = new int[n];
            double[] g = new double[n];
            double[] h = new double[n];
            int positives = 0;
            for (int i = 0; i < n; i++) {
                for (int f = 0; f < 5; f++) {
                    rows[i][f] = random.nextInt(12);
                }
                labels[i] = rows[i][0] + rows[i][3] + random.nextInt(8) > 14 ? 1 : 0;
                positives += labels[i];
            }
            if (positives == 0 || positives == n) {
                continue;
            }
            double pr = (double) positives / n;
            for (int i = 0; i < n; i++) {
                g[i] = pr - labels[i];
                h[i] = pr * (1 - pr);
            }
            int[] all = IntStream.range(0, n).toArray();

            GradientBoostedTrees model = GradientBoostedTrees.fit(rows, labels, p);

            assertSameTree(model.trees().get(0), reference(rows, g, h, all, 0, p));
        }
    }

    @Test
    void aSplitThatLeavesExactlyMinLeafRowsOnTheLeftIsAllowed() {
        // x = 1..6, the only positive first: v=2 leaves rows 1 and 2 on the left, exactly minLeaf 2.
        GradientBoostedTrees model = GradientBoostedTrees.fit(oneToN(6), new int[] {1, 0, 0, 0, 0, 0}, stump(6, 2));

        assertThat(root(model, 0).threshold()).isEqualTo(2.0);
    }

    @Test
    void twoFitsOfTheSameRowsWriteByteIdenticalJson() {
        assertThat(bigFit().toJson()).isEqualTo(bigFit().toJson());
    }
}

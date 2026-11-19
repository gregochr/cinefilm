package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class LogisticRegressionTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Six rows, two dimensions, three of each class; neither dimension separates them alone. */
    private static final float[][] SIX = {{1, 4}, {2, 1}, {3, 3}, {4, 2}, {5, 6}, {6, 3}};
    private static final int[] SIX_LABELS = {0, 0, 1, 0, 1, 1};

    /** Four rows, one dimension, two of each class, not separable and not symmetric. */
    private static final float[][] FOUR = {{0}, {1}, {2}, {7}};
    private static final int[] FOUR_LABELS = {0, 1, 0, 1};

    @Test
    void standardisationGivesTheMeanAndTheRootMeanSquaredDeviation() {
        float[][] rows = {{1, 5}, {2, 5}, {3, 5}, {6, 5}};
        double[] mean = new double[2];
        double[] scale = new double[2];

        LogisticRegression.standardisation(rows, mean, scale);

        assertThat(mean).containsExactly(3.0, 5.0);
        // Column 0 deviations are -2, -1, 0, 3: the mean square is 14 / 4, and column 1 is constant.
        assertThat(scale[0]).isEqualTo(Math.sqrt(3.5));
        assertThat(scale[1]).isEqualTo(1.0);
    }

    @Test
    void aColumnWithNoSpreadHasWeightZeroAndTheBiasReachesTheBase() {
        float[][] rows = {{7}, {7}, {7}, {7}};
        int[] labels = {1, 0, 0, 0};

        LogisticRegression model = LogisticRegression.fit(rows, labels, LrParams.defaults());

        assertThat(model.base()).isCloseTo(Math.log(1.0 / 3), within(1e-15));
        assertThat(model.weights()).containsExactly(0.0);
        assertThat(model.bias()).isCloseTo(Math.log(1.0 / 3), within(1e-6));
        assertThat(model.predict(new float[] {7})).isCloseTo(0.25, within(1e-6));
        assertThat(model.stopped()).isEqualTo("gradient");
    }

    @Test
    void aStartThatIsAlreadyOptimalStopsAtOnceOnTheGradient() {
        float[][] rows = {{7}, {7}, {7}, {7}};
        int[] labels = {1, 0, 1, 0};

        LogisticRegression model = LogisticRegression.fit(rows, labels, LrParams.defaults());

        assertThat(model.iterations()).isZero();
        assertThat(model.stopped()).isEqualTo("gradient");
        assertThat(model.bias()).isEqualTo(0.0);
    }

    @Test
    void theObjectiveAndItsGradientMatchAHandComputedCase() {
        float[][] rows = {{1, 0}, {0, 2}, {-1, 1}};
        int[] labels = {1, 0, 1};
        double[] grad = new double[3];

        // w = (0.5, -0.25), b = 0.1, so z = 0.6, -0.4, -0.65; l2 = 2.
        double j = LogisticRegression.evaluate(rows, labels, new double[2], new double[] {1, 1}, 2.0,
                new double[] {0.5, -0.25, 0.1}, grad, new double[2]);

        assertThat(j).isCloseTo(2.3330585385885536, within(1e-12));
        assertThat(grad[0]).isCloseTo(1.3026667688992943, within(1e-12));
        assertThat(grad[1]).isCloseTo(-0.35438578289840283, within(1e-12));
        assertThat(grad[2]).isCloseTo(-0.6100418165601554, within(1e-12));
    }

    @Test
    void theObjectiveStandardisesEachValueWhenItIsNeeded() {
        double[] gradA = new double[2];
        double[] gradB = new double[2];
        double[] theta = {0.7, -0.2};

        // (1 - 3) / 2 = -1 and (5 - 3) / 2 = 1.
        double a = LogisticRegression.evaluate(new float[][] {{1}, {5}}, new int[] {1, 0}, new double[] {3},
                new double[] {2}, 1.0, theta, gradA, new double[1]);
        double b = LogisticRegression.evaluate(new float[][] {{-1}, {1}}, new int[] {1, 0}, new double[] {0},
                new double[] {1}, 1.0, theta, gradB, new double[1]);

        assertThat(a).isEqualTo(b);
        assertThat(gradA).containsExactly(gradB);
    }

    @Test
    void theGradientMatchesCentralFiniteDifferences() {
        double[] mean = new double[2];
        double[] scale = new double[2];
        LogisticRegression.standardisation(SIX, mean, scale);
        double[] theta = {0.4, -0.7, 0.2};
        double[] grad = new double[3];
        LogisticRegression.evaluate(SIX, SIX_LABELS, mean, scale, 0.5, theta, grad, new double[2]);

        double h = 1e-5;
        for (int k = 0; k < 3; k++) {
            double[] up = theta.clone();
            double[] down = theta.clone();
            up[k] += h;
            down[k] -= h;
            double costUp = LogisticRegression.evaluate(SIX, SIX_LABELS, mean, scale, 0.5, up, new double[3],
                    new double[2]);
            double costDown = LogisticRegression.evaluate(SIX, SIX_LABELS, mean, scale, 0.5, down, new double[3],
                    new double[2]);
            assertThat(grad[k]).as("component %d", k).isCloseTo((costUp - costDown) / (2 * h), within(1e-6));
        }
    }

    @Test
    void theOptimumMatchesNewtonsMethod() {
        LogisticRegression model = LogisticRegression.fit(FOUR, FOUR_LABELS, new LrParams(1.0, 300, 1e-12));

        double[] mean = new double[1];
        double[] scale = new double[1];
        LogisticRegression.standardisation(FOUR, mean, scale);
        double w = 0;
        double b = 0;
        for (int step = 0; step < 50; step++) {
            double gw = w;
            double gb = 0;
            double hww = 1;
            double hwb = 0;
            double hbb = 0;
            for (int i = 0; i < 4; i++) {
                double x = (FOUR[i][0] - mean[0]) / scale[0];
                double p = 1 / (1 + Math.exp(-(b + w * x)));
                gw += (p - FOUR_LABELS[i]) * x;
                gb += p - FOUR_LABELS[i];
                hww += p * (1 - p) * x * x;
                hwb += p * (1 - p) * x;
                hbb += p * (1 - p);
            }
            double det = hww * hbb - hwb * hwb;
            w -= (hbb * gw - hwb * gb) / det;
            b -= (-hwb * gw + hww * gb) / det;
        }

        assertThat(model.weights()[0]).isCloseTo(w, within(1e-6));
        assertThat(model.bias()).isCloseTo(b, within(1e-6));
        assertThat(model.stopped()).isEqualTo("gradient");
        // The base is the start, not the fitted bias: the classes are balanced, so the base is 0.
        assertThat(model.base()).isEqualTo(0.0);
        assertThat(Math.abs(model.bias())).isGreaterThan(1e-3);
    }

    @Test
    void aFitOverManyStepsEndsWithAGradientWithinTheTolerance() {
        int n = 200;
        int dimension = 20;
        float[][] rows = new float[n][dimension];
        int[] labels = new int[n];
        long state = 12345;
        for (int i = 0; i < n; i++) {
            double signal = 0;
            for (int j = 0; j < dimension; j++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                rows[i][j] = (float) ((state >>> 40) % 1000) / 100f;
                signal += (j % 5 + 1) * (rows[i][j] - 5);
            }
            state = state * 6364136223846793005L + 1442695040888963407L;
            double noise = ((state >>> 40) % 1000) / 100.0 - 5;
            labels[i] = signal + 40 * noise > 0 ? 1 : 0;
        }
        LrParams params = new LrParams(0.001, 1000, 1e-8);

        LogisticRegression model = LogisticRegression.fit(rows, labels, params);

        assertThat(model.stopped()).isEqualTo("gradient");
        assertThat(model.iterations()).isEqualTo(14);
        // Pinned from a run: fourteen steps, so the ring of ten stored pairs dropped its oldest four times.
        double[] expected = {-0.06090605975862793, 0.07562214343513933, 0.43153844065693786, 0.5462638679829909,
            0.024152909654961894, 0.2690012641731572, 0.19360970703756583, 0.0378289428856921,
            0.02702910878207354, 0.2619290572923433, 0.004548932357178924, -0.060762968481161805,
            -0.26326539591243725, 0.24079307708821718, 0.17311428271096593, 0.3549000424169753,
            -0.054497382006663216, 0.1915034729460059, -0.03270878573864454, 0.2417006311591127};
        for (int j = 0; j < dimension; j++) {
            assertThat(model.weights()[j]).isCloseTo(expected[j], within(1e-6));
        }
        double[] mean = new double[dimension];
        double[] scale = new double[dimension];
        LogisticRegression.standardisation(rows, mean, scale);
        double[] theta = new double[dimension + 1];
        System.arraycopy(model.weights(), 0, theta, 0, dimension);
        theta[dimension] = model.bias();
        double[] grad = new double[dimension + 1];
        LogisticRegression.evaluate(rows, labels, mean, scale, 0.001, theta, grad, new double[dimension]);
        for (double g : grad) {
            assertThat(Math.abs(g) / n).isLessThanOrEqualTo(1e-8);
        }
    }

    @Test
    void oneIterationStopsOnTheIterationLimit() {
        LogisticRegression model = LogisticRegression.fit(FOUR, FOUR_LABELS, new LrParams(1.0, 1, Double.MIN_VALUE));

        assertThat(model.stopped()).isEqualTo("iterations");
        assertThat(model.iterations()).isEqualTo(1);
    }

    @Test
    void separableRowsAndAnUnreachableToleranceStopOnTheLineSearch() {
        // Real, not a seam: on separable rows J keeps falling by less than rounding, and no step is accepted.
        float[][] rows = {{0}, {1}, {2}, {3}};
        int[] labels = {0, 0, 1, 1};

        LogisticRegression model = LogisticRegression.fit(rows, labels, new LrParams(1.0, 10_000, Double.MIN_VALUE));

        assertThat(model.stopped()).isEqualTo("lineSearch");
        // Pinned from a run: the ring of stored pairs wraps more than once, and each step backtracks as needed.
        assertThat(model.iterations()).isEqualTo(25);
    }

    @Test
    void theArmijoConditionNeedsADecreaseOfAtLeastTheMarginScaledByStepAndSlope() {
        // J = 1, step = 0.5, slope = -2: the threshold is 1 + 1e-4 * 0.5 * -2 = 0.9999.
        assertThat(LogisticRegression.sufficientDecrease(0.99985, 1.0, 0.5, -2.0)).isTrue();
        assertThat(LogisticRegression.sufficientDecrease(0.99995, 1.0, 0.5, -2.0)).isFalse();
        // Without the margin this would pass; a cost at J itself is not enough.
        assertThat(LogisticRegression.sufficientDecrease(1.0, 1.0, 0.5, -2.0)).isFalse();
        assertThat(LogisticRegression.sufficientDecrease(1.00001, 1.0, 1.0, 0.0)).isFalse();
        assertThat(LogisticRegression.sufficientDecrease(1.0, 1.0, 1.0, 0.0)).isTrue();
    }

    /** Pair i (1 to 11): s = (i, 1 + 0.1 i), y = (2i + 1, 0.5 + 0.3 i), every s . y above 0. */
    private static LogisticRegression.PairRing ringOfPairs(int from, int to) {
        LogisticRegression.PairRing ring = new LogisticRegression.PairRing(2);
        for (int i = from; i <= to; i++) {
            assertThat(ring.add(new double[] {i, 1 + 0.1 * i}, new double[] {2.0 * i + 1, 0.5 + 0.3 * i})).isTrue();
        }
        return ring;
    }

    private static double[] directionOf(LogisticRegression.PairRing ring) {
        double[] direction = new double[2];
        ring.direction(new double[] {1.5, -2.0}, direction);
        return direction;
    }

    @Test
    void theEleventhPairDropsTheOldestAndNotTheNewest() {
        LogisticRegression.PairRing eleven = ringOfPairs(1, 11);

        assertThat(eleven.stored()).isEqualTo(10);
        double[] direction = directionOf(eleven);
        // Computed apart from the code, by a reference two-loop over pairs 2 to 11 alone.
        assertThat(direction[0]).isCloseTo(-0.7191518021818357, within(1e-12));
        assertThat(direction[1]).isCloseTo(1.1159188026795321, within(1e-12));
        assertThat(direction).containsExactly(directionOf(ringOfPairs(2, 11)));
        // The direction from pairs 1 to 10, which a drop of the newest would give, is different.
        double[] wrong = directionOf(ringOfPairs(1, 10));
        assertThat(wrong[0]).isCloseTo(-0.7889419891094853, within(1e-12));
        assertThat(wrong[1]).isCloseTo(1.5907947917997682, within(1e-12));
    }

    @Test
    void aTwelfthPairDropsTheSecondOldest() {
        LogisticRegression.PairRing twelve = ringOfPairs(1, 11);
        twelve.add(new double[] {12, 2.2}, new double[] {25, 4.1});
        LogisticRegression.PairRing fresh = ringOfPairs(3, 11);
        fresh.add(new double[] {12, 2.2}, new double[] {25, 4.1});

        assertThat(directionOf(twelve)).containsExactly(directionOf(fresh));
    }

    @Test
    void aPairWithNoCurvatureIsNotStored() {
        LogisticRegression.PairRing ring = ringOfPairs(1, 3);
        double[] before = directionOf(ring);

        // s . y is 0, and exactly 1e-10 is not above 1e-10 either.
        assertThat(ring.add(new double[] {1, 0}, new double[] {0, 1})).isFalse();
        assertThat(ring.add(new double[] {1e-10, 0}, new double[] {1, 0})).isFalse();

        assertThat(ring.stored()).isEqualTo(3);
        assertThat(directionOf(ring)).containsExactly(before);
        assertThat(ring.add(new double[] {Math.nextUp(1e-10), 0}, new double[] {1, 0})).isTrue();
        assertThat(ring.stored()).isEqualTo(4);
    }

    @Test
    void anEmptyRingGivesTheNegativeGradientAtUnitLength() {
        double[] direction = new double[2];

        new LogisticRegression.PairRing(2).direction(new double[] {3, -4}, direction);

        assertThat(direction).containsExactly(-0.6, 0.8);
    }

    @Test
    void theHalvingStopsAfterFortyAndTheFortyFirstTrialIsTheLast() {
        double lastAccepted = Math.scalb(1.0, -40);
        double refused = Math.scalb(1.0, -41);

        double accepted = LogisticRegression.backtrack(step -> step <= lastAccepted ? 0.0 : 10.0, 1.0, -1.0);
        double none = LogisticRegression.backtrack(step -> step <= refused ? 0.0 : 10.0, 1.0, -1.0);

        assertThat(accepted).isEqualTo(lastAccepted);
        assertThat(none).isEqualTo(0.0);
    }

    @Test
    void theFirstStepThatPassesIsReturned() {
        List<Double> tried = new ArrayList<>();

        double step = LogisticRegression.backtrack(s -> {
            tried.add(s);
            return s <= 0.25 ? 0.0 : 10.0;
        }, 1.0, -1.0);

        assertThat(step).isEqualTo(0.25);
        assertThat(tried).containsExactly(1.0, 0.5, 0.25);
    }

    @Test
    void aSpreadBelowOneTrillionthCountsAsConstant() {
        float below = 2e-12f;
        while ((double) below / 2 >= 1e-12) {
            below = Math.nextDown(below);
        }
        float above = Math.nextUp(below);
        double[] meanBelow = new double[1];
        double[] scaleBelow = new double[1];
        double[] meanAbove = new double[1];
        double[] scaleAbove = new double[1];

        LogisticRegression.standardisation(new float[][] {{0}, {below}}, meanBelow, scaleBelow);
        LogisticRegression.standardisation(new float[][] {{0}, {above}}, meanAbove, scaleAbove);

        assertThat((double) below / 2).isLessThan(1e-12);
        assertThat((double) above / 2).isGreaterThanOrEqualTo(1e-12);
        assertThat(scaleBelow[0]).isEqualTo(1.0);
        assertThat(scaleAbove[0]).isEqualTo((double) above / 2);
    }

    @Test
    void aSpreadOfExactlyOneTrillionthIsKept() {
        assertThat(LogisticRegression.floorScale(1e-12)).isEqualTo(1e-12);
        assertThat(LogisticRegression.floorScale(Math.nextDown(1e-12))).isEqualTo(1.0);
        assertThat(LogisticRegression.floorScale(3.5)).isEqualTo(3.5);
    }

    @Test
    void aGradientExactlyAtTheToleranceStopsAtOnce() {
        // x standardises to -1, -1, 1, 1; at w = 0, b = 0 the gradient is (2, 0), so |g| / n is exactly 0.5.
        float[][] rows = {{-1}, {-1}, {1}, {1}};
        int[] labels = {1, 1, 0, 0};

        LogisticRegression model = LogisticRegression.fit(rows, labels, new LrParams(1.0, 10, 0.5));

        assertThat(model.iterations()).isZero();
        assertThat(model.stopped()).isEqualTo("gradient");
    }

    @Test
    void theFirstStepIsTheNegativeGradientScaledToUnitLength() {
        // The gradient at the start is (g, 0) with g < 0, so the unit step lands on w = 1, b = 0.
        LogisticRegression model = LogisticRegression.fit(FOUR, FOUR_LABELS, new LrParams(1.0, 1, Double.MIN_VALUE));

        assertThat(model.weights()[0]).isEqualTo(1.0);
        assertThat(model.bias()).isEqualTo(0.0);
    }

    @Test
    void aFitPinsItsPathAndItsResult() {
        LogisticRegression six = LogisticRegression.fit(SIX, SIX_LABELS, LrParams.defaults());
        LogisticRegression four = LogisticRegression.fit(FOUR, FOUR_LABELS, new LrParams(1.0, 300, 1e-12));

        assertThat(six.iterations()).isEqualTo(5);
        assertThat(six.weights()[0]).isCloseTo(0.7938541296060989, within(1e-6));
        assertThat(six.weights()[1]).isCloseTo(0.5976031394727027, within(1e-6));
        assertThat(six.bias()).isCloseTo(0.035476030687693685, within(1e-6));
        assertThat(four.iterations()).isEqualTo(10);
        assertThat(four.weights()[0]).isCloseTo(0.573168200677557, within(1e-6));
        assertThat(four.bias()).isCloseTo(0.014054951342901873, within(1e-6));
    }

    @Test
    void aOneDimensionModelWithNoStepsRoundTrips() {
        LogisticRegression model = LogisticRegression.fit(new float[][] {{7}, {7}, {7}, {7}},
                new int[] {1, 0, 1, 0}, LrParams.defaults());

        LogisticRegression read = LogisticRegression.fromJson(model.toJson());

        assertThat(read.dimension()).isEqualTo(1);
        assertThat(read.iterations()).isZero();
        assertThat(read.toJson()).isEqualTo(model.toJson());
    }

    @Test
    void aDimensionBeyondAnIntIsRefused() {
        refusesJson(mutated(root -> root.put("dimension", 4_294_967_296L)),
                "the model JSON's dimension is not an integer");
    }

    @Test
    void predictIsTheLogisticOfTheStandardisedScore() {
        LogisticRegression model = LogisticRegression.fit(SIX, SIX_LABELS, LrParams.defaults());
        double[] mean = new double[2];
        double[] scale = new double[2];
        LogisticRegression.standardisation(SIX, mean, scale);
        double[] w = model.weights();

        double z = model.bias() + w[0] * (2.5 - mean[0]) / scale[0] + w[1] * (4.5 - mean[1]) / scale[1];

        assertThat(model.predict(new float[] {2.5f, 4.5f})).isCloseTo(1 / (1 + Math.exp(-z)), within(1e-15));
        assertThat(model.dimension()).isEqualTo(2);
        assertThat(model.params()).isEqualTo(LrParams.defaults());
    }

    @Test
    void predictRefusesARowOfTheWrongLength() {
        LogisticRegression model = LogisticRegression.fit(SIX, SIX_LABELS, LrParams.defaults());

        assertThatThrownBy(() -> model.predict(new float[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the row needs 2 values");
    }

    @Test
    void twoFitsGiveByteIdenticalJson() {
        String a = LogisticRegression.fit(SIX, SIX_LABELS, LrParams.defaults()).toJson();
        String b = LogisticRegression.fit(SIX, SIX_LABELS, LrParams.defaults()).toJson();

        assertThat(a).isEqualTo(b);
    }

    @Test
    void theJsonHasTheDocumentedFieldsInOrder() {
        LogisticRegression model = LogisticRegression.fit(SIX, SIX_LABELS, new LrParams(2.0, 50, 1e-5));

        JsonNode root = MAPPER.readTree(model.toJson());

        List<String> names = new ArrayList<>(root.propertyNames());
        assertThat(names).containsExactly("dimension", "base", "params", "mean", "scale", "weights", "bias",
                "iterations", "stopped");
        assertThat(root.get("dimension").intValue()).isEqualTo(2);
        assertThat(root.get("base").doubleValue()).isEqualTo(0.0);
        assertThat(root.get("params").get("l2").doubleValue()).isEqualTo(2.0);
        assertThat(root.get("params").get("maxIterations").intValue()).isEqualTo(50);
        assertThat(root.get("params").get("tolerance").doubleValue()).isEqualTo(1e-5);
        assertThat(root.get("mean").get(0).doubleValue()).isEqualTo(3.5);
        assertThat(root.get("mean").size()).isEqualTo(2);
        assertThat(root.get("scale").size()).isEqualTo(2);
        assertThat(root.get("weights").get(1).doubleValue()).isEqualTo(model.weights()[1]);
        assertThat(root.get("bias").doubleValue()).isEqualTo(model.bias());
        assertThat(root.get("iterations").intValue()).isEqualTo(model.iterations());
        assertThat(root.get("stopped").stringValue()).isEqualTo(model.stopped());
    }

    @Test
    void aModelReadFromItsJsonPredictsIdentically() {
        LogisticRegression model = LogisticRegression.fit(SIX, SIX_LABELS, new LrParams(0.5, 100, 1e-7));

        LogisticRegression read = LogisticRegression.fromJson(model.toJson());

        for (float[] row : SIX) {
            assertThat(read.predict(row)).isEqualTo(model.predict(row));
        }
        assertThat(read.predict(new float[] {10, -3})).isEqualTo(model.predict(new float[] {10, -3}));
        assertThat(read.base()).isEqualTo(model.base());
        assertThat(read.params()).isEqualTo(model.params());
        assertThat(read.iterations()).isEqualTo(model.iterations());
        assertThat(read.stopped()).isEqualTo(model.stopped());
        assertThat(read.toJson()).isEqualTo(model.toJson());
    }

    // ---- input refusals ----

    private static void refusesFit(float[][] rows, int[] labels, String message) {
        assertThatThrownBy(() -> LogisticRegression.fit(rows, labels, LrParams.defaults()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void noRowsIsRefused() {
        refusesFit(new float[0][], new int[0], "no rows");
    }

    @Test
    void nullRowsIsRefused() {
        refusesFit(null, new int[0], "no rows");
    }

    @Test
    void aLabelCountThatDiffersIsRefused() {
        refusesFit(SIX, new int[] {0, 1}, "one label per row is needed");
    }

    @Test
    void nullLabelsIsRefused() {
        refusesFit(SIX, null, "one label per row is needed");
    }

    @Test
    void rowsWithNoValuesAreRefused() {
        refusesFit(new float[][] {{}, {}}, new int[] {0, 1}, "each row needs at least one value");
    }

    @Test
    void aNullFirstRowIsRefused() {
        refusesFit(new float[][] {null, {1}}, new int[] {0, 1}, "each row needs at least one value");
    }

    @Test
    void aRowOfTheWrongLengthIsRefused() {
        refusesFit(new float[][] {{1, 2}, {3}}, new int[] {0, 1}, "each row needs 2 values");
    }

    @Test
    void aNullLaterRowIsRefused() {
        refusesFit(new float[][] {{1, 2}, null}, new int[] {0, 1}, "each row needs 2 values");
    }

    @Test
    void aNaNIsRefused() {
        refusesFit(new float[][] {{1}, {Float.NaN}}, new int[] {0, 1}, "a row holds a NaN or infinite value");
    }

    @Test
    void anInfiniteValueIsRefused() {
        refusesFit(new float[][] {{1}, {Float.NEGATIVE_INFINITY}}, new int[] {0, 1},
                "a row holds a NaN or infinite value");
    }

    @Test
    void aLabelOfTwoIsRefused() {
        refusesFit(new float[][] {{1}, {2}}, new int[] {0, 2}, "label must be 0 or 1: 2");
    }

    @Test
    void aLabelBelowZeroIsRefused() {
        refusesFit(new float[][] {{1}, {2}}, new int[] {-1, 1}, "label must be 0 or 1: -1");
    }

    @Test
    void onlyFilmRowsAreRefused() {
        refusesFit(new float[][] {{1}, {2}}, new int[] {0, 0}, "both classes are needed");
    }

    @Test
    void onlyCreditsRowsAreRefused() {
        refusesFit(new float[][] {{1}, {2}}, new int[] {1, 1}, "both classes are needed");
    }

    // ---- JSON refusals ----

    private static String valid() {
        return LogisticRegression.fit(SIX, SIX_LABELS, LrParams.defaults()).toJson();
    }

    private static String mutated(Consumer<ObjectNode> change) {
        ObjectNode root = (ObjectNode) MAPPER.readTree(valid());
        change.accept(root);
        return MAPPER.writeValueAsString(root);
    }

    private static void refusesJson(String json, String message) {
        assertThatThrownBy(() -> LogisticRegression.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void textThatIsNotJsonIsRefused() {
        assertThatThrownBy(() -> LogisticRegression.fromJson("{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("the model JSON cannot be read: ");
    }

    @Test
    void eachMissingTopLevelFieldIsRefused() {
        for (String name : List.of("dimension", "base", "params", "mean", "scale", "weights", "bias", "iterations",
                "stopped")) {
            refusesJson(mutated(root -> root.remove(name)), "the model JSON has no " + name);
        }
    }

    @Test
    void eachMissingParamIsRefused() {
        for (String name : List.of("l2", "maxIterations", "tolerance")) {
            refusesJson(mutated(root -> ((ObjectNode) root.get("params")).remove(name)),
                    "the model JSON has no " + name);
        }
    }

    @Test
    void aDimensionThatIsNotAnIntegerIsRefused() {
        refusesJson(mutated(root -> root.put("dimension", "2")), "the model JSON's dimension is not an integer");
        refusesJson(mutated(root -> root.put("dimension", 2.5)), "the model JSON's dimension is not an integer");
    }

    @Test
    void aDimensionBelowOneIsRefused() {
        refusesJson(mutated(root -> root.put("dimension", 0)), "the model JSON's dimension is not at least 1");
    }

    @Test
    void aBaseThatIsNotANumberIsRefused() {
        refusesJson(mutated(root -> root.put("base", "x")), "the model JSON's base is not a finite number");
    }

    @Test
    void aBiasThatIsNotANumberIsRefused() {
        refusesJson(mutated(root -> root.put("bias", true)), "the model JSON's bias is not a finite number");
    }

    @Test
    void aNonFiniteNumberIsRefused() {
        String json = valid().replaceFirst("\"base\":[^,]+", "\"base\":1e999");

        refusesJson(json, "the model JSON's base is not a finite number");
    }

    @Test
    void anArrayFieldThatIsNotAnArrayIsRefused() {
        for (String name : List.of("mean", "scale", "weights")) {
            refusesJson(mutated(root -> root.put(name, 1)), "the model JSON's " + name + " is not an array");
        }
    }

    @Test
    void anArrayOfTheWrongLengthIsRefused() {
        for (String name : List.of("mean", "scale", "weights")) {
            refusesJson(mutated(root -> ((ArrayNode) root.get(name)).add(1.0)),
                    "the model JSON's " + name + " does not have 2 values");
        }
    }

    @Test
    void anArrayElementThatIsNotANumberIsRefused() {
        for (String name : List.of("mean", "scale", "weights")) {
            refusesJson(mutated(root -> ((ArrayNode) root.get(name)).set(1, "x")),
                    "the model JSON's " + name + " holds a value that is not a finite number");
        }
    }

    @Test
    void anArrayElementThatIsNotFiniteIsRefused() {
        String json = valid().replaceFirst("\"weights\":\\[[^,]+", "\"weights\":[1e999");

        refusesJson(json, "the model JSON's weights holds a value that is not a finite number");
    }

    @Test
    void aScaleNotAboveZeroIsRefused() {
        refusesJson(mutated(root -> ((ArrayNode) root.get("scale")).set(0, 0.0)),
                "the model JSON's scale is not above 0");
        refusesJson(mutated(root -> ((ArrayNode) root.get("scale")).set(1, -1.0)),
                "the model JSON's scale is not above 0");
    }

    @Test
    void aNegativeIterationCountIsRefused() {
        refusesJson(mutated(root -> root.put("iterations", -1)), "the model JSON's iterations is negative");
    }

    @Test
    void anIterationCountThatIsNotAnIntegerIsRefused() {
        refusesJson(mutated(root -> root.put("iterations", "3")), "the model JSON's iterations is not an integer");
    }

    @Test
    void aStopReasonThatIsNotAStringIsRefused() {
        refusesJson(mutated(root -> root.put("stopped", 3)), "the model JSON's stopped is not a string");
    }

    @Test
    void anUnknownStopReasonIsRefused() {
        refusesJson(mutated(root -> root.put("stopped", "converged")),
                "the model JSON's stopped is unknown: converged");
    }

    @Test
    void everyKnownStopReasonIsAccepted() {
        for (String reason : List.of("gradient", "iterations", "lineSearch")) {
            assertThat(LogisticRegression.fromJson(mutated(root -> root.put("stopped", reason))).stopped())
                    .isEqualTo(reason);
        }
    }

    @Test
    void settingsOutOfRangeAreRefused() {
        refusesJson(mutated(root -> ((ObjectNode) root.get("params")).put("l2", 0.0)),
                "l2 must be finite and above 0: 0.0");
        refusesJson(mutated(root -> ((ObjectNode) root.get("params")).put("maxIterations", 0)),
                "maxIterations must be 1 to 10000: 0");
    }
}

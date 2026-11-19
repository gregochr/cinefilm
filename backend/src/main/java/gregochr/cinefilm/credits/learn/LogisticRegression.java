package gregochr.cinefilm.credits.learn;

import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * L2-penalised logistic regression fitted by L-BFGS: the credits classifier's model B head over image embeddings.
 * Rows are standardised on the fly (the matrix is never copied), the fit is single-threaded and deterministic, so
 * two fits of the same rows and settings write the same JSON.
 *
 * <p>Labels are 1 for credits and 0 for film; {@link #predict} gives the probability of credits.
 */
final class LogisticRegression implements FrameModel {

    /** The stop reason when the largest {@code |gradient| / rows} reached the tolerance. */
    static final String STOP_GRADIENT = "gradient";
    /** The stop reason when the iteration limit was reached. */
    static final String STOP_ITERATIONS = "iterations";
    /** The stop reason when no step along the direction was accepted. */
    static final String STOP_LINE_SEARCH = "lineSearch";

    /** The most pairs the L-BFGS memory holds. */
    private static final int MEMORY = 10;
    /** A pair is stored only when {@code s . y} is above this. */
    private static final double MIN_CURVATURE = 1e-10;
    /** The most times the step is halved. */
    private static final int MAX_HALVINGS = 40;
    /** The sufficient-decrease constant of the line search. */
    private static final double ARMIJO = 1e-4;
    /** A spread below this counts as constant and becomes 1. */
    private static final double MIN_SCALE = 1e-12;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final double base;
    private final LrParams params;
    private final double[] mean;
    private final double[] scale;
    private final double[] weights;
    private final double bias;
    private final int iterations;
    private final String stopped;

    LogisticRegression(double base, LrParams params, double[] mean, double[] scale, double[] weights, double bias,
            int iterations, String stopped) {
        this.base = base;
        this.params = params;
        this.mean = mean.clone();
        this.scale = scale.clone();
        this.weights = weights.clone();
        this.bias = bias;
        this.iterations = iterations;
        this.stopped = stopped;
    }

    /**
     * Fits the model.
     *
     * @param rows the rows, each of the same length, in the order the caller sorted
     * @param labels one label per row: 1 for credits, 0 for film
     * @param params the settings
     * @return the fitted model
     * @throws IllegalArgumentException for no rows, a row of the wrong length, a NaN or infinite value, a label
     *     other than 0 or 1, a count of labels that differs from the rows, or a single class
     */
    static LogisticRegression fit(float[][] rows, int[] labels, LrParams params) {
        double base = checkInput(rows, labels);
        int dimension = rows[0].length;
        int n = rows.length;
        double[] mean = new double[dimension];
        double[] scale = new double[dimension];
        standardisation(rows, mean, scale);

        int size = dimension + 1;
        double[] theta = new double[size];
        theta[dimension] = base;
        double[] grad = new double[size];
        double[] trialTheta = new double[size];
        double[] trialGrad = new double[size];
        double[] direction = new double[size];
        double[] scratch = new double[dimension];
        double[] candidateStep = new double[size];
        double[] candidateDiff = new double[size];
        double[] trialCost = new double[1];
        PairRing ring = new PairRing(size);

        double cost = evaluate(rows, labels, mean, scale, params.l2(), theta, grad, scratch);
        int iterations = 0;
        String stopped;
        while (true) {
            if (maxAbs(grad) / n <= params.tolerance()) {
                stopped = STOP_GRADIENT;
                break;
            }
            if (iterations == params.maxIterations()) {
                stopped = STOP_ITERATIONS;
                break;
            }
            ring.direction(grad, direction);
            double slope = dot(grad, direction);
            double step = backtrack(at -> {
                for (int k = 0; k < size; k++) {
                    trialTheta[k] = theta[k] + at * direction[k];
                }
                trialCost[0] = evaluate(rows, labels, mean, scale, params.l2(), trialTheta, trialGrad, scratch);
                return trialCost[0];
            }, cost, slope);
            if (step == 0) {
                stopped = STOP_LINE_SEARCH;
                break;
            }
            for (int k = 0; k < size; k++) {
                candidateStep[k] = trialTheta[k] - theta[k];
                candidateDiff[k] = trialGrad[k] - grad[k];
            }
            ring.add(candidateStep, candidateDiff);
            System.arraycopy(trialTheta, 0, theta, 0, size);
            System.arraycopy(trialGrad, 0, grad, 0, size);
            cost = trialCost[0];
            iterations++;
        }
        return new LogisticRegression(base, params, mean, scale, Arrays.copyOf(theta, dimension),
                theta[dimension], iterations, stopped);
    }

    /**
     * The backtracking line search: from step 1, halving up to {@link #MAX_HALVINGS} times, it returns the first
     * step whose trial cost passes {@link #sufficientDecrease}. The last call to {@code costAt} is the accepted
     * step's, so the caller's trial state holds it.
     *
     * @param costAt the cost at a step along the direction
     * @param cost the cost at step 0
     * @param slope the gradient dotted with the direction
     * @return the accepted step, or 0 when none of the 41 trial steps passed
     */
    static double backtrack(DoubleUnaryOperator costAt, double cost, double slope) {
        double step = 1;
        for (int h = 0; h <= MAX_HALVINGS; h++) {
            if (sufficientDecrease(costAt.applyAsDouble(step), cost, step, slope)) {
                return step;
            }
            step /= 2;
        }
        return 0;
    }

    /**
     * The last {@link #MEMORY} (step, gradient difference) pairs of L-BFGS, oldest dropped first, and the two-loop
     * recursion over them.
     */
    static final class PairRing {

        private final double[][] steps = new double[MEMORY][];
        private final double[][] diffs = new double[MEMORY][];
        private final double[] rho = new double[MEMORY];
        private final double[] alpha = new double[MEMORY];
        private final int size;
        private int stored;
        private int oldest;

        PairRing(int size) {
            this.size = size;
            for (int i = 0; i < MEMORY; i++) {
                steps[i] = new double[size];
                diffs[i] = new double[size];
            }
        }

        /**
         * Stores a pair when {@code s . y} is above 1e-10; once ten are held, the oldest is overwritten.
         *
         * @param step the step {@code s}
         * @param diff the gradient difference {@code y}
         * @return whether the pair was stored
         */
        boolean add(double[] step, double[] diff) {
            double sy = dot(step, diff);
            if (!(sy > MIN_CURVATURE)) {
                return false;
            }
            int slot;
            if (stored < MEMORY) {
                slot = stored;
                stored++;
            } else {
                slot = oldest;
                oldest = (oldest + 1) % MEMORY;
            }
            System.arraycopy(step, 0, steps[slot], 0, size);
            System.arraycopy(diff, 0, diffs[slot], 0, size);
            rho[slot] = 1 / sy;
            return true;
        }

        /**
         * The number of pairs held.
         *
         * @return 0 to 10
         */
        int stored() {
            return stored;
        }

        /**
         * Writes the search direction {@code -H g} into {@code direction}: with no pairs, the negative gradient
         * scaled by {@code 1 / ||g||}; otherwise the two-loop recursion, scaled by {@code s . y / y . y} of the
         * newest pair.
         *
         * @param grad the gradient
         * @param direction receives the direction
         */
        void direction(double[] grad, double[] direction) {
            if (stored == 0) {
                double norm = Math.sqrt(dot(grad, grad));
                for (int k = 0; k < size; k++) {
                    direction[k] = -grad[k] / norm;
                }
                return;
            }
            System.arraycopy(grad, 0, direction, 0, size);
            for (int i = stored - 1; i >= 0; i--) {
                int slot = (oldest + i) % MEMORY;
                alpha[slot] = rho[slot] * dot(steps[slot], direction);
                for (int k = 0; k < size; k++) {
                    direction[k] -= alpha[slot] * diffs[slot][k];
                }
            }
            int newest = (oldest + stored - 1) % MEMORY;
            double gamma = 1 / (rho[newest] * dot(diffs[newest], diffs[newest]));
            for (int k = 0; k < size; k++) {
                direction[k] *= gamma;
            }
            for (int i = 0; i < stored; i++) {
                int slot = (oldest + i) % MEMORY;
                double beta = rho[slot] * dot(diffs[slot], direction);
                double coefficient = alpha[slot] - beta;
                for (int k = 0; k < size; k++) {
                    direction[k] += coefficient * steps[slot][k];
                }
            }
            for (int k = 0; k < size; k++) {
                direction[k] = -direction[k];
            }
        }
    }

    /** The Armijo condition: {@code J(new) <= J + 1e-4 * step * (g . d)}, with {@code slope} = {@code g . d}. */
    static boolean sufficientDecrease(double trialCost, double cost, double step, double slope) {
        return trialCost <= cost + ARMIJO * step * slope;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int k = 0; k < a.length; k++) {
            sum += a[k] * b[k];
        }
        return sum;
    }

    private static double maxAbs(double[] values) {
        double max = 0;
        for (double v : values) {
            max = Math.max(max, Math.abs(v));
        }
        return max;
    }

    /**
     * Fills {@code mean} and {@code scale}: the mean and the root mean squared deviation of each column, both in
     * {@code double} in row order, a spread below 1e-12 becoming 1. Both arrays arrive zeroed.
     */
    static void standardisation(float[][] rows, double[] mean, double[] scale) {
        int dimension = mean.length;
        int n = rows.length;
        for (float[] row : rows) {
            for (int j = 0; j < dimension; j++) {
                mean[j] += row[j];
            }
        }
        for (int j = 0; j < dimension; j++) {
            mean[j] /= n;
        }
        for (float[] row : rows) {
            for (int j = 0; j < dimension; j++) {
                double d = row[j] - mean[j];
                scale[j] += d * d;
            }
        }
        for (int j = 0; j < dimension; j++) {
            double s = Math.sqrt(scale[j] / n);
            scale[j] = floorScale(s);
        }
    }

    /** A spread below 1e-12 counts as constant and becomes 1. */
    static double floorScale(double spread) {
        return spread < MIN_SCALE ? 1 : spread;
    }

    /**
     * The objective and its gradient in one pass over the rows. {@code theta} is the weights then the bias;
     * {@code grad} receives the gradient in the same order; {@code scratch} holds one standardised row.
     *
     * @return {@code J}
     */
    static double evaluate(float[][] rows, int[] labels, double[] mean, double[] scale, double l2, double[] theta,
            double[] grad, double[] scratch) {
        int dimension = mean.length;
        double bias = theta[dimension];
        Arrays.fill(grad, 0);
        double cost = 0;
        double biasGrad = 0;
        for (int i = 0; i < rows.length; i++) {
            float[] row = rows[i];
            double z = bias;
            for (int j = 0; j < dimension; j++) {
                double x = (row[j] - mean[j]) / scale[j];
                scratch[j] = x;
                z += theta[j] * x;
            }
            double y = labels[i];
            cost += Math.max(z, 0) + Math.log1p(Math.exp(-Math.abs(z))) - y * z;
            double r = sigmoid(z) - y;
            biasGrad += r;
            for (int j = 0; j < dimension; j++) {
                grad[j] += r * scratch[j];
            }
        }
        double penalty = 0;
        for (int j = 0; j < dimension; j++) {
            grad[j] += l2 * theta[j];
            penalty += theta[j] * theta[j];
        }
        grad[dimension] = biasGrad;
        return cost + l2 / 2 * penalty;
    }

    private static double sigmoid(double z) {
        return 1 / (1 + Math.exp(-z));
    }

    private static double checkInput(float[][] rows, int[] labels) {
        if (rows == null || rows.length == 0) {
            throw new IllegalArgumentException("no rows");
        }
        if (labels == null || labels.length != rows.length) {
            throw new IllegalArgumentException("one label per row is needed");
        }
        if (rows[0] == null || rows[0].length == 0) {
            throw new IllegalArgumentException("each row needs at least one value");
        }
        int dimension = rows[0].length;
        int positives = 0;
        for (int i = 0; i < rows.length; i++) {
            float[] row = rows[i];
            if (row == null || row.length != dimension) {
                throw new IllegalArgumentException("each row needs " + dimension + " values");
            }
            for (float v : row) {
                if (!Float.isFinite(v)) {
                    throw new IllegalArgumentException("a row holds a NaN or infinite value");
                }
            }
            if (labels[i] != 0 && labels[i] != 1) {
                throw new IllegalArgumentException("label must be 0 or 1: " + labels[i]);
            }
            positives += labels[i];
        }
        if (positives == 0 || positives == rows.length) {
            throw new IllegalArgumentException("both classes are needed");
        }
        double p = (double) positives / rows.length;
        return Math.log(p / (1 - p));
    }

    /**
     * The probability of credits for one row.
     *
     * @param row the row, {@link #dimension()} long
     * @return {@code 1 / (1 + e^-z)}
     * @throws IllegalArgumentException when the row has the wrong length
     */
    double predict(float[] row) {
        if (row.length != weights.length) {
            throw new IllegalArgumentException("the row needs " + weights.length + " values");
        }
        double z = bias;
        for (int j = 0; j < weights.length; j++) {
            z += weights[j] * ((row[j] - mean[j]) / scale[j]);
        }
        return sigmoid(z);
    }

    /**
     * The start score: {@code ln(p / (1 − p))} for the share {@code p} of label 1 in the training labels. It is
     * not the fitted bias; it means what {@link GradientBoostedTrees#base()} means.
     *
     * @return the base
     */
    @Override
    public double base() {
        return base;
    }

    /**
     * The number of values in a row.
     *
     * @return the dimension
     */
    int dimension() {
        return weights.length;
    }

    /**
     * The settings the model was fitted with.
     *
     * @return the settings
     */
    LrParams params() {
        return params;
    }

    /**
     * The number of accepted L-BFGS steps.
     *
     * @return the iterations
     */
    int iterations() {
        return iterations;
    }

    /**
     * Why the fit stopped.
     *
     * @return {@code "gradient"}, {@code "iterations"} or {@code "lineSearch"}
     */
    String stopped() {
        return stopped;
    }

    /**
     * The fitted bias.
     *
     * @return the bias
     */
    double bias() {
        return bias;
    }

    /**
     * The fitted weights, on standardised values.
     *
     * @return a copy of the weights
     */
    double[] weights() {
        return weights.clone();
    }

    /**
     * Writes the model as JSON: {@code {"dimension", "base", "params", "mean", "scale", "weights", "bias",
     * "iterations", "stopped"}}.
     *
     * @return the JSON text
     */
    String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("dimension", weights.length);
        root.put("base", base);
        ObjectNode p = root.putObject("params");
        p.put("l2", params.l2());
        p.put("maxIterations", params.maxIterations());
        p.put("tolerance", params.tolerance());
        putArray(root, "mean", mean);
        putArray(root, "scale", scale);
        putArray(root, "weights", weights);
        root.put("bias", bias);
        root.put("iterations", iterations);
        root.put("stopped", stopped);
        return MAPPER.writeValueAsString(root);
    }

    private static void putArray(ObjectNode root, String name, double[] values) {
        ArrayNode array = root.putArray(name);
        for (double v : values) {
            array.add(v);
        }
    }

    /**
     * Reads a model written by {@link #toJson}.
     *
     * @param json the JSON text
     * @return the model
     * @throws IllegalArgumentException for text that is not JSON, a missing field, a field of the wrong type, an
     *     array whose length is not the dimension, a number that is not finite, a scale not above 0, settings out
     *     of range or an unknown stop reason
     */
    static LogisticRegression fromJson(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("the model JSON cannot be read: " + e.getOriginalMessage(), e);
        }
        int dimension = intField(root, "dimension");
        if (dimension < 1) {
            throw new IllegalArgumentException("the model JSON's dimension is not at least 1");
        }
        double base = doubleField(root, "base");
        JsonNode p = field(root, "params");
        LrParams params = new LrParams(doubleField(p, "l2"), intField(p, "maxIterations"),
                doubleField(p, "tolerance"));
        double[] mean = arrayField(root, "mean", dimension);
        double[] scale = arrayField(root, "scale", dimension);
        for (double s : scale) {
            if (!(s > 0)) {
                throw new IllegalArgumentException("the model JSON's scale is not above 0");
            }
        }
        double[] weights = arrayField(root, "weights", dimension);
        double bias = doubleField(root, "bias");
        int iterations = intField(root, "iterations");
        if (iterations < 0) {
            throw new IllegalArgumentException("the model JSON's iterations is negative");
        }
        JsonNode stoppedNode = field(root, "stopped");
        if (!stoppedNode.isString()) {
            throw new IllegalArgumentException("the model JSON's stopped is not a string");
        }
        String stopped = stoppedNode.stringValue();
        if (!STOP_GRADIENT.equals(stopped) && !STOP_ITERATIONS.equals(stopped)
                && !STOP_LINE_SEARCH.equals(stopped)) {
            throw new IllegalArgumentException("the model JSON's stopped is unknown: " + stopped);
        }
        return new LogisticRegression(base, params, mean, scale, weights, bias, iterations, stopped);
    }

    private static JsonNode field(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null) {
            throw new IllegalArgumentException("the model JSON has no " + name);
        }
        return value;
    }

    private static int intField(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("the model JSON's " + name + " is not an integer");
        }
        return value.intValue();
    }

    private static double doubleField(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("the model JSON's " + name + " is not a finite number");
        }
        return value.doubleValue();
    }

    private static double[] arrayField(JsonNode node, String name, int length) {
        JsonNode value = field(node, name);
        if (!value.isArray()) {
            throw new IllegalArgumentException("the model JSON's " + name + " is not an array");
        }
        if (value.size() != length) {
            throw new IllegalArgumentException("the model JSON's " + name + " does not have " + length + " values");
        }
        double[] out = new double[length];
        for (int j = 0; j < length; j++) {
            JsonNode element = value.get(j);
            if (!element.isNumber() || !Double.isFinite(element.doubleValue())) {
                throw new IllegalArgumentException(
                        "the model JSON's " + name + " holds a value that is not a finite number");
            }
            out[j] = element.doubleValue();
        }
        return out;
    }
}

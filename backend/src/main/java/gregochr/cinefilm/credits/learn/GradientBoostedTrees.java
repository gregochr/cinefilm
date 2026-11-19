package gregochr.cinefilm.credits.learn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gradient-boosted trees with logistic loss, quantile bins and unweighted classes: the credits classifier's tree
 * model. Single-threaded and deterministic, so two fits of the same rows and settings write the same JSON.
 *
 * <p>Labels are 1 for credits and 0 for film; {@link #predict} gives the probability of credits.
 */
final class GradientBoostedTrees implements FrameModel {

    /** The smallest gain a node's best split must exceed. */
    private static final double MIN_GAIN = 1e-12;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final double base;
    private final GbtParams params;
    private final List<Node> trees;

    GradientBoostedTrees(double base, GbtParams params, List<Node> trees) {
        this.base = base;
        this.params = params;
        this.trees = List.copyOf(trees);
    }

    /** A tree node: a split or a leaf. */
    sealed interface Node permits Split, Leaf {
    }

    /**
     * An inner node: a row goes left when its {@code feature} is at most {@code threshold}.
     *
     * @param feature the feature index
     * @param threshold the split value
     * @param left the subtree of rows at or below the threshold
     * @param right the subtree of the other rows
     */
    record Split(int feature, double threshold, Node left, Node right) implements Node {
    }

    /**
     * A leaf.
     *
     * @param value the amount added to the score
     */
    record Leaf(double value) implements Node {
    }

    /**
     * Fits the trees.
     *
     * @param rows the feature rows, each {@link CreditsFeatures#COUNT} long, in the order the caller sorted
     * @param labels one label per row: 1 for credits, 0 for film
     * @param params the settings
     * @return the fitted model
     * @throws IllegalArgumentException for no rows, a row of the wrong length, a NaN or infinite value, a label
     *     other than 0 or 1, a count of labels that differs from the rows, or a single class
     */
    static GradientBoostedTrees fit(double[][] rows, int[] labels, GbtParams params) {
        double base = checkInput(rows, labels);
        int n = rows.length;
        double[][] thresholds = new double[CreditsFeatures.COUNT][];
        byte[][] binOf = new byte[CreditsFeatures.COUNT][n];
        for (int f = 0; f < CreditsFeatures.COUNT; f++) {
            double[] column = new double[n];
            for (int i = 0; i < n; i++) {
                column[i] = rows[i][f];
            }
            thresholds[f] = thresholds(column, params.bins());
            for (int i = 0; i < n; i++) {
                binOf[f][i] = (byte) binIndex(thresholds[f], column[i]);
            }
        }
        Fit fit = new Fit(params, thresholds, binOf, n);
        double[] scores = new double[n];
        Arrays.fill(scores, base);
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        List<Node> trees = new ArrayList<>();
        for (int t = 0; t < params.trees(); t++) {
            for (int i = 0; i < n; i++) {
                double p = sigmoid(scores[i]);
                fit.g[i] = p - labels[i];
                fit.h[i] = p * (1 - p);
            }
            trees.add(fit.build(all, 0, scores));
        }
        return new GradientBoostedTrees(base, params, trees);
    }

    private static double checkInput(double[][] rows, int[] labels) {
        if (rows == null || rows.length == 0) {
            throw new IllegalArgumentException("no rows");
        }
        if (labels == null || labels.length != rows.length) {
            throw new IllegalArgumentException("one label per row is needed");
        }
        int positives = 0;
        for (int i = 0; i < rows.length; i++) {
            checkRow(rows[i]);
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

    private static void checkRow(double[] row) {
        if (row == null || row.length != CreditsFeatures.COUNT) {
            throw new IllegalArgumentException("each row needs " + CreditsFeatures.COUNT + " values");
        }
        for (double v : row) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("a row holds a NaN or infinite value");
            }
        }
    }

    /**
     * The candidate thresholds of one feature: the sorted values at index {@code (k * n) / bins} for
     * {@code k = 1 … bins − 1}, without repeats. A feature with one distinct value has none.
     *
     * @param column one feature's values over all rows
     * @param bins the number of bins
     * @return the thresholds, ascending
     */
    static double[] thresholds(double[] column, int bins) {
        double[] sorted = column.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if (sorted[0] == sorted[n - 1]) {
            return new double[0];
        }
        double[] out = new double[bins - 1];
        int count = 0;
        for (int k = 1; k < bins; k++) {
            double v = sorted[(int) ((long) k * n / bins)];
            if (count == 0 || out[count - 1] != v) {
                out[count++] = v;
            }
        }
        return Arrays.copyOf(out, count);
    }

    /** The index of the first threshold at or above {@code x}: the bin of {@code x}. */
    private static int binIndex(double[] thresholds, double x) {
        int lo = 0;
        int hi = thresholds.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (x <= thresholds[mid]) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    private static double sigmoid(double score) {
        return 1 / (1 + Math.exp(-score));
    }

    /** The state shared by the trees of one fit. */
    private static final class Fit {
        private final GbtParams params;
        private final double[][] thresholds;
        private final byte[][] binOf;
        private final double[] g;
        private final double[] h;

        Fit(GbtParams params, double[][] thresholds, byte[][] binOf, int rows) {
            this.params = params;
            this.thresholds = thresholds;
            this.binOf = binOf;
            this.g = new double[rows];
            this.h = new double[rows];
        }

        /** Builds a subtree over the rows {@code idx} (in row order) and adds its leaves to {@code scores}. */
        Node build(int[] idx, int depth, double[] scores) {
            double sumG = 0;
            double sumH = 0;
            for (int i : idx) {
                sumG += g[i];
                sumH += h[i];
            }
            Best best = depth < params.depth() ? bestSplit(idx, sumG, sumH) : null;
            if (best == null) {
                double value = -params.learningRate() * sumG / (sumH + params.lambda());
                for (int i : idx) {
                    scores[i] += value;
                }
                return new Leaf(value);
            }
            int[] left = new int[best.leftCount];
            int[] right = new int[idx.length - best.leftCount];
            int l = 0;
            int r = 0;
            for (int i : idx) {
                if ((binOf[best.feature][i] & 0xFF) <= best.bin) {
                    left[l++] = i;
                } else {
                    right[r++] = i;
                }
            }
            double threshold = thresholds[best.feature][best.bin];
            Node leftNode = build(left, depth + 1, scores);
            Node rightNode = build(right, depth + 1, scores);
            return new Split(best.feature, threshold, leftNode, rightNode);
        }

        /**
         * The split of the largest gain over every feature and threshold, scanned by feature then threshold and
         * replaced only by a strictly larger gain; null when none beats {@link #MIN_GAIN}. Row sums are taken
         * per bin in row order, then added bin by bin.
         */
        private Best bestSplit(int[] idx, double sumG, double sumH) {
            double lambda = params.lambda();
            double parent = sumG * sumG / (sumH + lambda);
            Best best = null;
            double bestGain = MIN_GAIN;
            for (int f = 0; f < thresholds.length; f++) {
                int cuts = thresholds[f].length;
                double[] hg = new double[cuts + 1];
                double[] hh = new double[cuts + 1];
                int[] hc = new int[cuts + 1];
                for (int i : idx) {
                    int b = binOf[f][i] & 0xFF;
                    hg[b] += g[i];
                    hh[b] += h[i];
                    hc[b]++;
                }
                double lg = 0;
                double lh = 0;
                int lc = 0;
                for (int j = 0; j < cuts; j++) {
                    lg += hg[j];
                    lh += hh[j];
                    lc += hc[j];
                    if (lc < params.minLeaf() || idx.length - lc < params.minLeaf()) {
                        continue;
                    }
                    double rg = sumG - lg;
                    double rh = sumH - lh;
                    double gain = lg * lg / (lh + lambda) + rg * rg / (rh + lambda) - parent;
                    if (gain > bestGain) {
                        bestGain = gain;
                        best = new Best(f, j, lc);
                    }
                }
            }
            return best;
        }
    }

    /** The winning split of a node: its feature, the index of its threshold and the rows going left. */
    private record Best(int feature, int bin, int leftCount) {
    }

    /**
     * The probability of credits for one row.
     *
     * @param row the {@link CreditsFeatures#COUNT} feature values
     * @return {@code 1 / (1 + e^−(base + Σ leaf values))}
     * @throws IllegalArgumentException for a row {@link #fit} would refuse
     */
    double predict(double[] row) {
        checkRow(row);
        double score = base;
        for (Node tree : trees) {
            Node node = tree;
            while (node instanceof Split split) {
                node = row[split.feature()] <= split.threshold() ? split.left() : split.right();
            }
            score += ((Leaf) node).value();
        }
        return sigmoid(score);
    }

    /**
     * The start score: {@code ln(p / (1 − p))} for the share {@code p} of label 1.
     *
     * @return the base
     */
    @Override
    public double base() {
        return base;
    }

    /**
     * The settings the model was fitted with.
     *
     * @return the settings
     */
    GbtParams params() {
        return params;
    }

    /**
     * The trees, in boosting order.
     *
     * @return an unmodifiable list of root nodes
     */
    List<Node> trees() {
        return trees;
    }

    /**
     * Writes the model as JSON: {@code {"featureVersion", "base", "params", "trees"}}.
     *
     * @return the JSON text
     */
    String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("featureVersion", CreditsFeatures.FEATURE_VERSION);
        root.put("base", base);
        ObjectNode p = root.putObject("params");
        p.put("trees", params.trees());
        p.put("depth", params.depth());
        p.put("learningRate", params.learningRate());
        p.put("bins", params.bins());
        p.put("minLeaf", params.minLeaf());
        p.put("lambda", params.lambda());
        ArrayNode array = root.putArray("trees");
        for (Node tree : trees) {
            array.add(toNode(tree));
        }
        return MAPPER.writeValueAsString(root);
    }

    private static ObjectNode toNode(Node node) {
        ObjectNode out = MAPPER.createObjectNode();
        if (node instanceof Split split) {
            out.put("feature", split.feature());
            out.put("threshold", split.threshold());
            out.set("left", toNode(split.left()));
            out.set("right", toNode(split.right()));
        } else {
            out.put("leaf", ((Leaf) node).value());
        }
        return out;
    }

    /**
     * Reads a model written by {@link #toJson}.
     *
     * @param json the JSON text
     * @return the model
     * @throws IllegalStateException when the file's feature version is not {@link CreditsFeatures#FEATURE_VERSION}
     * @throws IllegalArgumentException for any other bad content: text that is not JSON, a missing field, a field of
     *     the wrong type, settings out of range, a feature outside the row, or a number that is not finite
     */
    static GradientBoostedTrees fromJson(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("the model JSON cannot be read: " + e.getOriginalMessage(), e);
        }
        int version = intField(root, "featureVersion");
        if (version != CreditsFeatures.FEATURE_VERSION) {
            throw new IllegalStateException("the model has feature version " + version
                    + ", the code has " + CreditsFeatures.FEATURE_VERSION);
        }
        JsonNode p = field(root, "params");
        GbtParams params = new GbtParams(intField(p, "trees"), intField(p, "depth"),
                doubleField(p, "learningRate"), intField(p, "bins"), intField(p, "minLeaf"),
                doubleField(p, "lambda"));
        JsonNode array = field(root, "trees");
        if (!array.isArray()) {
            throw new IllegalArgumentException("the model JSON's trees is not an array");
        }
        List<Node> trees = new ArrayList<>();
        for (JsonNode tree : array) {
            trees.add(fromNode(tree));
        }
        return new GradientBoostedTrees(doubleField(root, "base"), params, trees);
    }

    private static Node fromNode(JsonNode node) {
        if (node.has("leaf")) {
            return new Leaf(doubleField(node, "leaf"));
        }
        int feature = intField(node, "feature");
        if (feature < 0 || feature >= CreditsFeatures.COUNT) {
            throw new IllegalArgumentException("the model JSON has a feature outside the row: " + feature);
        }
        return new Split(feature, doubleField(node, "threshold"), fromNode(field(node, "left")),
                fromNode(field(node, "right")));
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
}

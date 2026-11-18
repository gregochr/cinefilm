package gregochr.cinefilm.credits.learn;

/**
 * The settings of {@link GradientBoostedTrees#fit}.
 *
 * @param trees the number of trees, at least 1
 * @param depth the depth at which a node is a leaf, at least 1
 * @param learningRate the shrinkage of each leaf, in (0, 1]
 * @param bins the number of quantile bins of each feature, 2 to 256
 * @param minLeaf the fewest rows either side of a split may hold, at least 1
 * @param lambda the L2 term on leaf values, finite and above 0
 */
record GbtParams(int trees, int depth, double learningRate, int bins, int minLeaf, double lambda) {

    /** The most bins a feature may have: a bin index must fit a byte. */
    static final int MAX_BINS = 256;

    /**
     * Checks each setting.
     *
     * @throws IllegalArgumentException when a setting is out of its range
     */
    GbtParams {
        if (trees < 1) {
            throw new IllegalArgumentException("trees must be at least 1: " + trees);
        }
        if (depth < 1) {
            throw new IllegalArgumentException("depth must be at least 1: " + depth);
        }
        if (!(learningRate > 0 && learningRate <= 1)) {
            throw new IllegalArgumentException("learningRate must be in (0, 1]: " + learningRate);
        }
        if (bins < 2 || bins > MAX_BINS) {
            throw new IllegalArgumentException("bins must be 2 to " + MAX_BINS + ": " + bins);
        }
        if (minLeaf < 1) {
            throw new IllegalArgumentException("minLeaf must be at least 1: " + minLeaf);
        }
        if (!(lambda > 0 && Double.isFinite(lambda))) {
            throw new IllegalArgumentException("lambda must be finite and above 0: " + lambda);
        }
    }

    /**
     * The defaults of the plan: 200 trees of depth 3, learning rate 0.1, 32 bins, 20 rows a leaf, lambda 1.
     *
     * @return the default settings
     */
    static GbtParams defaults() {
        return new GbtParams(200, 3, 0.1, 32, 20, 1.0);
    }
}

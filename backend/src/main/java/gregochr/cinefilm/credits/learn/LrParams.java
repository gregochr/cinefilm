package gregochr.cinefilm.credits.learn;

/**
 * The settings of {@link LogisticRegression#fit}.
 *
 * @param l2 the L2 term on the weights (the bias is not penalised), finite and above 0
 * @param maxIterations the most L-BFGS steps, 1 to {@link #MAX_ITERATIONS}
 * @param tolerance the largest {@code |gradient| / rows} at which the fit stops, finite and above 0
 */
record LrParams(double l2, int maxIterations, double tolerance) {

    /** The most iterations a fit may be given. */
    static final int MAX_ITERATIONS = 10_000;

    /**
     * Checks each setting.
     *
     * @throws IllegalArgumentException when a setting is out of its range
     */
    LrParams {
        if (!(l2 > 0 && Double.isFinite(l2))) {
            throw new IllegalArgumentException("l2 must be finite and above 0: " + l2);
        }
        if (maxIterations < 1 || maxIterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("maxIterations must be 1 to " + MAX_ITERATIONS + ": " + maxIterations);
        }
        if (!(tolerance > 0 && Double.isFinite(tolerance))) {
            throw new IllegalArgumentException("tolerance must be finite and above 0: " + tolerance);
        }
    }

    /**
     * The defaults of the plan: l2 1, 300 iterations, tolerance 1e-6.
     *
     * @return the default settings
     */
    static LrParams defaults() {
        return new LrParams(1.0, 300, 1e-6);
    }
}

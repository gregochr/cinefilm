package gregochr.cinefilm.credits.learn;

/**
 * The six numbers {@link CreditsSegmenter} is calibrated with ({@code docs/engineering/credits-classifier-plan.md},
 * "Finding the start" and "L3c specification").
 *
 * @param lambda the per-frame penalty that leans every decision late, finite and at least 0
 * @param delta how far below the best stretch's score the start may fall to lean later, finite and at least 0
 * @param gapFrames {@code G} as a count of coarse frames (a run of {@code r} frames lasts {@code 5r} s), at least 1
 * @param sigma the least margin a {@code FOUND} start needs, finite
 * @param theta the least {@code p} of each of a start's first three coarse frames, in [0, 1]
 * @param thetaDense the least {@code p} of every dense frame of the 5 s that open the start, in [0, 1]
 */
record SegmenterParams(double lambda, double delta, int gapFrames, double sigma, double theta,
                       double thetaDense) {

    /**
     * Checks each number.
     *
     * @throws IllegalArgumentException when a number is out of its range
     */
    SegmenterParams {
        if (!(Double.isFinite(lambda) && lambda >= 0)) {
            throw new IllegalArgumentException("lambda must be finite and at least 0: " + lambda);
        }
        if (!(Double.isFinite(delta) && delta >= 0)) {
            throw new IllegalArgumentException("delta must be finite and at least 0: " + delta);
        }
        if (gapFrames < 1) {
            throw new IllegalArgumentException("gapFrames must be at least 1: " + gapFrames);
        }
        if (!Double.isFinite(sigma)) {
            throw new IllegalArgumentException("sigma must be finite: " + sigma);
        }
        if (!(theta >= 0 && theta <= 1)) {
            throw new IllegalArgumentException("theta must be in [0, 1]: " + theta);
        }
        if (!(thetaDense >= 0 && thetaDense <= 1)) {
            throw new IllegalArgumentException("thetaDense must be in [0, 1]: " + thetaDense);
        }
    }
}

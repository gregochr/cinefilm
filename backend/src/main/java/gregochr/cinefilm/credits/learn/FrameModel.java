package gregochr.cinefilm.credits.learn;

/**
 * A trained frame classifier of either kind: model A ({@link GradientBoostedTrees}, which predicts from a feature row)
 * or model B ({@link LogisticRegression}, which predicts from an embedding). The two predict from different inputs,
 * so the interface holds only what every evaluation needs of both: the start score, {@link #base()}.
 */
sealed interface FrameModel permits GradientBoostedTrees, LogisticRegression {

    /**
     * The start score: {@code ln(p / (1 − p))} for the share {@code p} of label 1 in the training labels.
     *
     * @return the base
     */
    double base();
}

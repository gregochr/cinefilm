package gregochr.cinefilm.journey;

/**
 * How good the link was at one track point; {@code UNKNOWN} when nothing says (plan: train-lookahead-plan.md,
 * decision 5).
 */
public enum PointState {
    GOOD,
    BAD,
    UNKNOWN
}

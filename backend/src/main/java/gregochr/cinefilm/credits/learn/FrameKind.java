package gregochr.cinefilm.credits.learn;

/** What one opening frame looks like (plan, "Opening type"). */
public enum FrameKind {
    /** The median luma is below 4. */
    DARK,
    /** A bright, flat card: a high mean and a narrow spread. */
    PLATE,
    /** Anything else. */
    LIT
}

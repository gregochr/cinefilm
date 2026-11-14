package gregochr.cinefilm.credits.learn;

/** Why a stretch of the window is neither credits nor film. */
public enum LeftOutReason {
    /** Within 5 seconds of a merged credits boundary. */
    BOUNDARY,
    /** After the end of the last credits marker. */
    AFTER_LAST_CREDITS
}

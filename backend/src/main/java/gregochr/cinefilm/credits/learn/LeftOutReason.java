package gregochr.cinefilm.credits.learn;

/** Why a stretch of the window is neither credits nor film. */
public enum LeftOutReason {
    /** Within 5 seconds of a merged credits boundary. */
    BOUNDARY,
    /** After the end of the last credits marker. */
    AFTER_LAST_CREDITS,
    /** Lit picture at the start of a credits interval, before its first two plain coarse frames (plan). */
    LIT_OPENING,
    /** A run of three or more lit coarse frames inside a credits interval (plan, "FrameLabels"). */
    LIT_RUN
}

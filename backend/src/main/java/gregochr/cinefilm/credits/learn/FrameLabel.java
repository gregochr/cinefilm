package gregochr.cinefilm.credits.learn;

/**
 * The label of one frame, as {@link FrameLabels#labelAt} gives it.
 *
 * @param kind credits, film, or left out
 * @param reason why the frame is left out; null unless {@code kind} is {@link Kind#LEFT_OUT}
 */
public record FrameLabel(Kind kind, LeftOutReason reason) {

    /** A frame in a credits interval. */
    public static final FrameLabel CREDITS = new FrameLabel(Kind.CREDITS, null);
    /** A frame in a film interval. */
    public static final FrameLabel FILM = new FrameLabel(Kind.FILM, null);

    /** The three outcomes. */
    public enum Kind {
        /** Credits. */
        CREDITS,
        /** Film. */
        FILM,
        /** Left out; the reason is on the label. */
        LEFT_OUT
    }

    /**
     * A left-out label.
     *
     * @param reason why the frame is left out
     * @return the label
     */
    public static FrameLabel leftOut(LeftOutReason reason) {
        return new FrameLabel(Kind.LEFT_OUT, reason);
    }
}

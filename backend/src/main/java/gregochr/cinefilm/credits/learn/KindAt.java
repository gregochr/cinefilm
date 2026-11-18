package gregochr.cinefilm.credits.learn;

/**
 * The kind of the coarse frame at one offset.
 *
 * @param offsetMs the frame's offset in ms
 * @param kind its kind
 */
public record KindAt(long offsetMs, FrameKind kind) {
}

package gregochr.cinefilm.credits;

/**
 * One frame of the dense pass (two a second around the candidate).
 *
 * @param offsetMs where in the file the frame was taken
 * @param frame the picture
 */
public record DenseFrame(long offsetMs, GreyFrame frame) {
}

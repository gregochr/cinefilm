package gregochr.cinefilm.credits;

/**
 * One coarse sample: the class of the frame taken at an offset (one every 5 seconds across the window).
 *
 * @param offsetMs where in the file the frame was taken
 * @param frameClass what it looks like
 */
public record CoarseSample(long offsetMs, FrameClass frameClass) {
}

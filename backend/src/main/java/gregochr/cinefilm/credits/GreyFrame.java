package gregochr.cinefilm.credits;

/**
 * One greyscale picture: width, height and one 8-bit luma value per pixel, row by row. A final class rather than a
 * record because a record would expose its array; the pixels are copied in and never handed out.
 */
public final class GreyFrame {

    /** The largest width or height accepted. */
    public static final int MAX_DIMENSION = 4096;

    private final int width;
    private final int height;
    private final byte[] pixels;

    /**
     * Builds a frame, copying the pixels.
     *
     * @param width the width, 1 to {@value #MAX_DIMENSION}
     * @param height the height, 1 to {@value #MAX_DIMENSION}
     * @param pixels width times height luma bytes, row by row (unsigned: 0 black, 255 white)
     * @throws IllegalArgumentException when a dimension is out of range or the pixel count does not match
     */
    public GreyFrame(int width, int height, byte[] pixels) {
        if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) {
            throw new IllegalArgumentException(
                "Frame size must be 1 to " + MAX_DIMENSION + " in each direction, was " + width + "x" + height);
        }
        if (pixels == null || pixels.length != width * height) {
            int actual = pixels == null ? 0 : pixels.length;
            throw new IllegalArgumentException(
                "A " + width + "x" + height + " frame needs " + width * height + " pixels, got " + actual);
        }
        this.width = width;
        this.height = height;
        this.pixels = pixels.clone();
    }

    /** The width in pixels. */
    public int width() {
        return width;
    }

    /** The height in pixels. */
    public int height() {
        return height;
    }

    /**
     * The luma of one pixel.
     *
     * @param x the column, 0 at the left
     * @param y the row, 0 at the top
     * @return 0 (black) to 255 (white)
     * @throws IllegalArgumentException when the pixel is outside the frame
     */
    public int luma(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException(
                "Pixel (" + x + ", " + y + ") is outside the " + width + "x" + height + " frame");
        }
        return pixels[y * width + x] & 0xFF;
    }
}

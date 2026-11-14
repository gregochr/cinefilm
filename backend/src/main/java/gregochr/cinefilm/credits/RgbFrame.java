package gregochr.cinefilm.credits;

/**
 * One colour picture: width, height and three 8-bit values (red, green, blue) per pixel, row by row. A final class
 * rather than a record because a record would expose its array; the pixels are copied in and never handed out.
 */
public final class RgbFrame {

    /** The largest width or height accepted. */
    public static final int MAX_DIMENSION = GreyFrame.MAX_DIMENSION;

    private final int width;
    private final int height;
    private final byte[] pixels;

    /**
     * Builds a frame, copying the pixels.
     *
     * @param width the width, 1 to {@value #MAX_DIMENSION}
     * @param height the height, 1 to {@value #MAX_DIMENSION}
     * @param pixels width times height times 3 bytes (red, green, blue per pixel), row by row, unsigned
     * @throws IllegalArgumentException when a dimension is out of range or the byte count does not match
     */
    public RgbFrame(int width, int height, byte[] pixels) {
        if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) {
            throw new IllegalArgumentException(
                "Frame size must be 1 to " + MAX_DIMENSION + " in each direction, was " + width + "x" + height);
        }
        int expected = width * height * 3;
        if (pixels == null || pixels.length != expected) {
            int actual = pixels == null ? 0 : pixels.length;
            throw new IllegalArgumentException(
                "A " + width + "x" + height + " frame needs " + expected + " bytes, got " + actual);
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
     * One pixel.
     *
     * @param x the column, 0 at the left
     * @param y the row, 0 at the top
     * @return red in bits 16 to 23, green in bits 8 to 15 and blue in bits 0 to 7 (each 0 to 255)
     * @throws IllegalArgumentException when the pixel is outside the frame
     */
    public int rgb(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException(
                "Pixel (" + x + ", " + y + ") is outside the " + width + "x" + height + " frame");
        }
        int at = (y * width + x) * 3;
        return (pixels[at] & 0xFF) << 16 | (pixels[at + 1] & 0xFF) << 8 | (pixels[at + 2] & 0xFF);
    }
}

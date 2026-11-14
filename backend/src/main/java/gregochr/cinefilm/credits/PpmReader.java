package gregochr.cinefilm.credits;

/**
 * Parses a binary PPM image ({@code P6}, maximum value 255), which is what ffmpeg writes for
 * {@code -f image2 ... .ppm} with {@code format=rgb24}. Anything else is refused with a clear message.
 */
public final class PpmReader {

    private PpmReader() {
    }

    /**
     * Reads a binary PPM.
     *
     * @param data the whole file
     * @return the frame
     * @throws IllegalArgumentException when it is not a {@code P6} file with maxval 255, the size is out of range, or
     *     the pixel data is not exactly width times height times 3 bytes
     */
    public static RgbFrame read(byte[] data) {
        Netpbm.Image image = Netpbm.read(data, "PPM", "P6", 3);
        return new RgbFrame(image.width(), image.height(), image.pixels());
    }
}

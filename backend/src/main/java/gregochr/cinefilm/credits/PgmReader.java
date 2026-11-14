package gregochr.cinefilm.credits;

/**
 * Parses a binary PGM image ({@code P5}, maximum value 255), which is what ffmpeg writes for
 * {@code -f image2 ... .pgm} with {@code format=gray}. Anything else is refused with a clear message.
 */
public final class PgmReader {

    private PgmReader() {
    }

    /**
     * Reads a binary PGM.
     *
     * @param data the whole file
     * @return the frame
     * @throws IllegalArgumentException when it is not a {@code P5} file with maxval 255, the size is out of range, or
     *     the pixel data is not exactly width times height bytes
     */
    public static GreyFrame read(byte[] data) {
        Netpbm.Image image = Netpbm.read(data, "PGM", "P5", 1);
        return new GreyFrame(image.width(), image.height(), image.pixels());
    }
}

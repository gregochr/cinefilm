package gregochr.cinefilm.credits;

import java.nio.charset.StandardCharsets;

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
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("The PGM file is empty");
        }
        Cursor cursor = new Cursor(data);
        String magic = cursor.token("magic number");
        if (!"P5".equals(magic)) {
            throw new IllegalArgumentException("Only binary PGM (P5) is supported, found " + magic);
        }
        int width = cursor.number("width");
        int height = cursor.number("height");
        int maxval = cursor.number("maximum value");
        if (maxval != 255) {
            throw new IllegalArgumentException("PGM maximum value must be 255, was " + maxval);
        }
        if (width < 1 || width > GreyFrame.MAX_DIMENSION || height < 1 || height > GreyFrame.MAX_DIMENSION) {
            throw new IllegalArgumentException(
                "PGM size must be 1 to " + GreyFrame.MAX_DIMENSION + " in each direction, was " + width + "x" + height);
        }
        cursor.singleWhitespace();
        int expected = width * height;
        int available = data.length - cursor.position();
        if (available < expected) {
            throw new IllegalArgumentException(
                "PGM data is truncated: " + available + " of " + expected + " pixel bytes");
        }
        if (available > expected) {
            throw new IllegalArgumentException("PGM has " + (available - expected) + " bytes after the pixels");
        }
        byte[] pixels = new byte[expected];
        System.arraycopy(data, cursor.position(), pixels, 0, expected);
        return new GreyFrame(width, height, pixels);
    }

    /** Walks the header, skipping whitespace and {@code #} comments between values. */
    private static final class Cursor {
        private final byte[] data;
        private int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        int position() {
            return pos;
        }

        String token(String what) {
            skipBlanks();
            int start = pos;
            while (pos < data.length && !isSpace(data[pos])) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("PGM header is truncated before the " + what);
            }
            return new String(data, start, pos - start, StandardCharsets.US_ASCII);
        }

        int number(String what) {
            String text = token(what);
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("PGM " + what + " is not a number: " + text, e);
            }
        }

        void singleWhitespace() {
            // A number token only stops at whitespace or at the end of the data, so only the end can be wrong here.
            if (pos >= data.length) {
                throw new IllegalArgumentException("PGM header must end with one whitespace byte before the pixels");
            }
            pos++;
        }

        private void skipBlanks() {
            while (pos < data.length) {
                if (data[pos] == '#') {
                    while (pos < data.length && data[pos] != '\n') {
                        pos++;
                    }
                } else if (isSpace(data[pos])) {
                    pos++;
                } else {
                    return;
                }
            }
        }

        private static boolean isSpace(byte b) {
            return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == '\f' || b == 0x0B;
        }
    }
}

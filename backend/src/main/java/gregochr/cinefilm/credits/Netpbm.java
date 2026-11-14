package gregochr.cinefilm.credits;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The binary Netpbm parser {@link PgmReader} ({@code P5}) and {@link PpmReader} ({@code P6}) share: a header of magic
 * number, width, height and maximum value (whitespace and {@code #} comments between them), one whitespace byte, then
 * exactly width times height times the channel count bytes. Only maximum value 255 is accepted.
 */
final class Netpbm {

    /**
     * What was read: the size and where the pixel bytes lie in the file. A class rather than a record because a
     * record over an array would compare and print the array by identity; the bytes are handed out as a copy, as
     * {@link GreyFrame} does, and the file's bytes are never changed.
     */
    static final class Image {
        private final int width;
        private final int height;
        private final byte[] data;
        private final int offset;
        private final int length;

        /** Refers to the file's bytes without copying them; the caller must not change the file while this is used. */
        private Image(int width, int height, byte[] data, int offset, int length) {
            this.width = width;
            this.height = height;
            this.data = data;
            this.offset = offset;
            this.length = length;
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }

        /** A new copy of the pixel bytes on every call. */
        byte[] pixels() {
            return Arrays.copyOfRange(data, offset, offset + length);
        }
    }

    private Netpbm() {
    }

    /**
     * Reads a binary Netpbm file.
     *
     * @param data the whole file
     * @param name the format's name in messages, {@code PGM} or {@code PPM}
     * @param magic the magic number the format needs, {@code P5} or {@code P6}
     * @param channels the bytes per pixel, 1 or 3
     * @return the size and the pixel bytes
     * @throws IllegalArgumentException when the file is not that format with maxval 255, the size is out of range, or
     *     the pixel data is not exactly the size it should be
     */
    static Image read(byte[] data, String name, String magic, int channels) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("The " + name + " file is empty");
        }
        Cursor cursor = new Cursor(data, name);
        String found = cursor.token("magic number");
        if (!magic.equals(found)) {
            throw new IllegalArgumentException(
                "Only binary " + name + " (" + magic + ") is supported, found " + found);
        }
        int width = cursor.number("width");
        int height = cursor.number("height");
        int maxval = cursor.number("maximum value");
        if (maxval != 255) {
            throw new IllegalArgumentException(name + " maximum value must be 255, was " + maxval);
        }
        if (width < 1 || width > GreyFrame.MAX_DIMENSION || height < 1 || height > GreyFrame.MAX_DIMENSION) {
            throw new IllegalArgumentException(name + " size must be 1 to " + GreyFrame.MAX_DIMENSION
                + " in each direction, was " + width + "x" + height);
        }
        cursor.singleWhitespace();
        int expected = width * height * channels;
        int available = data.length - cursor.position();
        if (available < expected) {
            throw new IllegalArgumentException(
                name + " data is truncated: " + available + " of " + expected + " pixel bytes");
        }
        if (available > expected) {
            throw new IllegalArgumentException(name + " has " + (available - expected) + " bytes after the pixels");
        }
        return new Image(width, height, data, cursor.position(), expected);
    }

    /** Walks the header, skipping whitespace and {@code #} comments between values. */
    private static final class Cursor {
        private final byte[] data;
        private final String name;
        private int pos;

        Cursor(byte[] data, String name) {
            this.data = data;
            this.name = name;
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
                throw new IllegalArgumentException(name + " header is truncated before the " + what);
            }
            return new String(data, start, pos - start, StandardCharsets.US_ASCII);
        }

        int number(String what) {
            String text = token(what);
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " " + what + " is not a number: " + text, e);
            }
        }

        void singleWhitespace() {
            // A number token only stops at whitespace or at the end of the data, so only the end can be wrong here.
            if (pos >= data.length) {
                throw new IllegalArgumentException(
                    name + " header must end with one whitespace byte before the pixels");
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

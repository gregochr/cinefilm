package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PgmReaderTest {

    private static byte[] pgm(String header, byte... pixels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(pixels);
        return out.toByteArray();
    }

    private static void assertRefused(byte[] data, String message) {
        assertThatThrownBy(() -> PgmReader.read(data))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(message);
    }

    @Test
    void readsAValidBinaryPgm() {
        GreyFrame frame = PgmReader.read(pgm("P5\n3 2\n255\n", (byte) 0, (byte) 1, (byte) 255, (byte) 10, (byte) 128,
            (byte) 20));

        assertThat(frame.width()).isEqualTo(3);
        assertThat(frame.height()).isEqualTo(2);
        assertThat(frame.luma(2, 0)).isEqualTo(255);
        assertThat(frame.luma(1, 1)).isEqualTo(128);
    }

    @Test
    void skipsCommentsAndOddWhitespaceInTheHeader() {
        byte[] data = pgm("P5\r\n# written by ffmpeg\n  2\t# the width\n 1 \n#maxval next\n255\n", (byte) 7, (byte) 9);

        GreyFrame frame = PgmReader.read(data);

        assertThat(frame.width()).isEqualTo(2);
        assertThat(frame.height()).isEqualTo(1);
        assertThat(frame.luma(0, 0)).isEqualTo(7);
        assertThat(frame.luma(1, 0)).isEqualTo(9);
    }

    @Test
    void aPixelThatLooksLikeWhitespaceIsStillAPixel() {
        GreyFrame frame = PgmReader.read(pgm("P5 2 1 255\n", (byte) '\n', (byte) ' '));

        assertThat(frame.luma(0, 0)).isEqualTo(10);
        assertThat(frame.luma(1, 0)).isEqualTo(32);
    }

    @Test
    void everyWhitespaceByteSeparatesHeaderValues() {
        GreyFrame frame = PgmReader.read(pgm("P5\f1\u000b1\t255\r", (byte) 3));

        assertThat(frame.width()).isEqualTo(1);
        assertThat(frame.luma(0, 0)).isEqualTo(3);
    }

    @Test
    void acceptsTheLargestSize() {
        byte[] pixels = new byte[4096];

        assertThat(PgmReader.read(pgm("P5 4096 1 255\n", pixels)).width()).isEqualTo(4096);
    }

    @Test
    void refusesAnAsciiPgm() {
        assertRefused(pgm("P2\n1 1\n255\n0\n"), "Only binary PGM (P5) is supported, found P2");
    }

    @Test
    void refusesAMaxvalOtherThan255() {
        assertRefused(pgm("P5\n1 1\n65535\n", (byte) 0, (byte) 0), "PGM maximum value must be 255, was 65535");
        assertRefused(pgm("P5\n1 1\n254\n", (byte) 0), "PGM maximum value must be 255, was 254");
    }

    @Test
    void refusesTruncatedPixelData() {
        assertRefused(pgm("P5\n2 2\n255\n", (byte) 1, (byte) 2, (byte) 3),
            "PGM data is truncated: 3 of 4 pixel bytes");
    }

    @Test
    void refusesBytesAfterThePixels() {
        assertRefused(pgm("P5\n1 1\n255\n", (byte) 1, (byte) 2), "PGM has 1 bytes after the pixels");
    }

    @Test
    void refusesAbsurdOrZeroDimensions() {
        assertRefused(pgm("P5\n4097 1\n255\n"), "PGM size must be 1 to 4096 in each direction, was 4097x1");
        assertRefused(pgm("P5\n1 4097\n255\n"), "PGM size must be 1 to 4096 in each direction, was 1x4097");
        assertRefused(pgm("P5\n0 1\n255\n"), "PGM size must be 1 to 4096 in each direction, was 0x1");
        assertRefused(pgm("P5\n1 -1\n255\n"), "PGM size must be 1 to 4096 in each direction, was 1x-1");
    }

    @Test
    void refusesAHeaderThatIsNotNumbers() {
        assertRefused(pgm("P5\nwide 1\n255\n"), "PGM width is not a number: wide");
        assertRefused(pgm("P5\n99999999999 1\n255\n"), "PGM width is not a number: 99999999999");
    }

    @Test
    void refusesATruncatedHeader() {
        assertRefused(pgm("P5\n2 2"), "PGM header is truncated before the maximum value");
        assertRefused(pgm("P5\n# only a comment"), "PGM header is truncated before the width");
        assertRefused(pgm("P5 1 1 255"), "PGM header must end with one whitespace byte before the pixels");
    }

    @Test
    void refusesAnEmptyOrMissingFile() {
        assertRefused(new byte[0], "The PGM file is empty");
        assertRefused(null, "The PGM file is empty");
    }

    @Test
    void refusesSomethingThatIsNotAPgmAtAll() {
        assertRefused(pgm("GIF89a\r\n"), "Only binary PGM (P5) is supported, found GIF89a");
    }
}

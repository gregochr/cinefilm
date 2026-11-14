package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PpmReaderTest {

    private static byte[] ppm(String header, byte... pixels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(pixels);
        return out.toByteArray();
    }

    private static void assertRefused(byte[] data, String message) {
        assertThatThrownBy(() -> PpmReader.read(data))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(message);
    }

    @Test
    void readsAValidBinaryPpm() {
        RgbFrame frame = PpmReader.read(ppm("P6\n2 1\n255\n", (byte) 1, (byte) 2, (byte) 3, (byte) 255, (byte) 128,
            (byte) 0));

        assertThat(frame.width()).isEqualTo(2);
        assertThat(frame.height()).isEqualTo(1);
        assertThat(frame.rgb(0, 0)).isEqualTo(0x010203);
        assertThat(frame.rgb(1, 0)).isEqualTo(0xFF8000);
    }

    @Test
    void skipsCommentsAndOddWhitespaceInTheHeader() {
        byte[] data = ppm("P6\r\n# written by ffmpeg\n  1\t# the width\n 1 \n#maxval next\n255\n", (byte) 7, (byte) 8,
            (byte) 9);

        RgbFrame frame = PpmReader.read(data);

        assertThat(frame.width()).isEqualTo(1);
        assertThat(frame.height()).isEqualTo(1);
        assertThat(frame.rgb(0, 0)).isEqualTo(0x070809);
    }

    @Test
    void aPixelThatLooksLikeWhitespaceIsStillAPixel() {
        RgbFrame frame = PpmReader.read(ppm("P6 1 1 255\n", (byte) '\n', (byte) ' ', (byte) '#'));

        assertThat(frame.rgb(0, 0)).isEqualTo(0x0A2023);
    }

    @Test
    void refusesAGreyscalePgm() {
        assertRefused(ppm("P5\n1 1\n255\n", (byte) 0), "Only binary PPM (P6) is supported, found P5");
    }

    @Test
    void refusesAnAsciiPpm() {
        assertRefused(ppm("P3\n1 1\n255\n0 0 0\n"), "Only binary PPM (P6) is supported, found P3");
    }

    @Test
    void refusesAMaxvalOtherThan255() {
        assertRefused(ppm("P6\n1 1\n65535\n", new byte[6]), "PPM maximum value must be 255, was 65535");
        assertRefused(ppm("P6\n1 1\n254\n", new byte[3]), "PPM maximum value must be 255, was 254");
    }

    @Test
    void refusesTruncatedPixelData() {
        assertRefused(ppm("P6\n2 1\n255\n", (byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5),
            "PPM data is truncated: 5 of 6 pixel bytes");
    }

    @Test
    void refusesBytesAfterThePixels() {
        assertRefused(ppm("P6\n1 1\n255\n", (byte) 1, (byte) 2, (byte) 3, (byte) 4),
            "PPM has 1 bytes after the pixels");
    }

    @Test
    void refusesAbsurdOrZeroDimensions() {
        assertRefused(ppm("P6\n4097 1\n255\n"), "PPM size must be 1 to 4096 in each direction, was 4097x1");
        assertRefused(ppm("P6\n1 4097\n255\n"), "PPM size must be 1 to 4096 in each direction, was 1x4097");
        assertRefused(ppm("P6\n0 1\n255\n"), "PPM size must be 1 to 4096 in each direction, was 0x1");
        assertRefused(ppm("P6\n1 -1\n255\n"), "PPM size must be 1 to 4096 in each direction, was 1x-1");
    }

    @Test
    void acceptsTheLargestWidth() {
        assertThat(PpmReader.read(ppm("P6 4096 1 255\n", new byte[4096 * 3])).width()).isEqualTo(4096);
        assertThat(PpmReader.read(ppm("P6 1 4096 255\n", new byte[4096 * 3])).height()).isEqualTo(4096);
    }

    @Test
    void refusesAHeaderThatIsNotNumbers() {
        assertRefused(ppm("P6\nwide 1\n255\n"), "PPM width is not a number: wide");
        assertRefused(ppm("P6\n99999999999 1\n255\n"), "PPM width is not a number: 99999999999");
    }

    @Test
    void refusesATruncatedHeader() {
        assertRefused(ppm("P6\n2 2"), "PPM header is truncated before the maximum value");
        assertRefused(ppm("P6\n# only a comment"), "PPM header is truncated before the width");
        assertRefused(ppm("P6 1 1 255"), "PPM header must end with one whitespace byte before the pixels");
    }

    @Test
    void refusesAnEmptyOrMissingFile() {
        assertRefused(new byte[0], "The PPM file is empty");
        assertRefused(null, "The PPM file is empty");
    }

    @Test
    void refusesSomethingThatIsNotAPpmAtAll() {
        assertRefused(ppm("GIF89a\r\n"), "Only binary PPM (P6) is supported, found GIF89a");
    }
}

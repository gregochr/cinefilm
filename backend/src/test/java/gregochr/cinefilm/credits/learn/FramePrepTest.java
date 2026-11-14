package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.ActiveArea;
import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.RgbFrame;
import org.junit.jupiter.api.Test;

class FramePrepTest {

    /** A frame from packed 0xRRGGBB pixels, row by row. */
    private static RgbFrame rgb(int width, int height, int... pixels) {
        byte[] bytes = new byte[pixels.length * 3];
        for (int i = 0; i < pixels.length; i++) {
            bytes[3 * i] = (byte) (pixels[i] >> 16);
            bytes[3 * i + 1] = (byte) (pixels[i] >> 8);
            bytes[3 * i + 2] = (byte) pixels[i];
        }
        return new RgbFrame(width, height, bytes);
    }

    private static GreyFrame grey(int width, int height, int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return new GreyFrame(width, height, bytes);
    }

    private static RgbFrame flat(int width, int height, int colour) {
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, colour);
        return rgb(width, height, pixels);
    }

    private static void assertSame(RgbFrame actual, RgbFrame expected) {
        assertThat(actual.width()).isEqualTo(expected.width());
        assertThat(actual.height()).isEqualTo(expected.height());
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                assertThat(actual.rgb(x, y)).as("pixel (%d, %d)", x, y).isEqualTo(expected.rgb(x, y));
            }
        }
    }

    private static void assertSame(GreyFrame actual, GreyFrame expected) {
        assertThat(actual.width()).isEqualTo(expected.width());
        assertThat(actual.height()).isEqualTo(expected.height());
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                assertThat(actual.luma(x, y)).as("pixel (%d, %d)", x, y).isEqualTo(expected.luma(x, y));
            }
        }
    }

    // ---- luma ----------------------------------------------------------------------------------------------------

    @Test
    void lumaWeighsRedGreenAndBlueByTheBt601IntegerWeights() {
        GreyFrame frame = FramePrep.luma(rgb(6, 1, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x000000, 0x0AC81E));

        assertThat(frame.width()).isEqualTo(6);
        assertThat(frame.height()).isEqualTo(1);
        // 77*255 >> 8, 150*255 >> 8, 29*255 >> 8.
        assertThat(frame.luma(0, 0)).isEqualTo(76);
        assertThat(frame.luma(1, 0)).isEqualTo(149);
        assertThat(frame.luma(2, 0)).isEqualTo(28);
        // The weights sum to 256, so white is exactly 255 and black 0 (full range).
        assertThat(frame.luma(3, 0)).isEqualTo(255);
        assertThat(frame.luma(4, 0)).isEqualTo(0);
        // (10, 200, 30): (770 + 30000 + 870) >> 8 = 31640 >> 8 = 123.
        assertThat(frame.luma(5, 0)).isEqualTo(123);
    }

    @Test
    void lumaKeepsTheShapeAndPutsEachPixelInItsOwnPlace() {
        GreyFrame frame = FramePrep.luma(rgb(2, 2, 0x000000, 0xFFFFFF, 0xFFFFFF, 0x000000));

        assertSame(frame, grey(2, 2, 0, 255, 255, 0));
    }

    // ---- boxHalf -------------------------------------------------------------------------------------------------

    @Test
    void boxHalfAveragesEachTwoByTwoBlockRoundingHalfUp() {
        // (1 + 2 + 2 + 2 + 2) >> 2 = 9 >> 2 = 2; (0 + 0 + 0 + 1 + 2) >> 2 = 3 >> 2 = 0; (0 + 0 + 1 + 1 + 2) >> 2 = 1.
        assertThat(FramePrep.boxHalf(grey(2, 2, 1, 2, 2, 2)).luma(0, 0)).isEqualTo(2);
        assertThat(FramePrep.boxHalf(grey(2, 2, 0, 0, 0, 1)).luma(0, 0)).isEqualTo(0);
        assertThat(FramePrep.boxHalf(grey(2, 2, 0, 0, 1, 1)).luma(0, 0)).isEqualTo(1);
        assertThat(FramePrep.boxHalf(grey(2, 2, 255, 255, 255, 255)).luma(0, 0)).isEqualTo(255);
    }

    @Test
    void boxHalfTakesTheBlockAtTwoXTwoY() {
        GreyFrame half = FramePrep.boxHalf(grey(4, 2,
            0, 0, 100, 100,
            0, 0, 100, 100));

        assertSame(half, grey(2, 1, 0, 100));
    }

    @Test
    void boxHalfDropsAnOddLastRowAndColumn() {
        GreyFrame half = FramePrep.boxHalf(grey(3, 3,
            4, 4, 255,
            4, 4, 255,
            255, 255, 255));

        assertSame(half, grey(1, 1, 4));
    }

    @Test
    void boxHalfRefusesAFrameSmallerThanTwoInEitherDirection() {
        assertThatThrownBy(() -> FramePrep.boxHalf(grey(1, 2, 0, 0))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A frame must be at least 2x2 to halve, was 1x2");
        assertThatThrownBy(() -> FramePrep.boxHalf(grey(2, 1, 0, 0))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A frame must be at least 2x2 to halve, was 2x1");
    }

    @Test
    void greyIsTheBoxHalfOfTheLuma() {
        RgbFrame frame = flat(480, 270, 0x0AC81E);

        GreyFrame result = FramePrep.grey(frame);

        assertThat(result.width()).isEqualTo(240);
        assertThat(result.height()).isEqualTo(135);
        assertSame(result, FramePrep.boxHalf(FramePrep.luma(frame)));
        assertThat(result.luma(100, 100)).isEqualTo(123);
    }

    // ---- crop ----------------------------------------------------------------------------------------------------

    /** The colour of pixel (x, y) of {@link #numbered()}: all three channels differ and no two pixels match. */
    private static int numberedAt(int x, int y) {
        int n = x + 16 * y;
        return (0x10 + n) << 16 | (0x40 + n) << 8 | (0x80 + n);
    }

    private static RgbFrame numbered() {
        int[] pixels = new int[4 * 3];
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 4; x++) {
                pixels[y * 4 + x] = numberedAt(x, y);
            }
        }
        return rgb(4, 3, pixels);
    }

    @Test
    void cropCutsExactlyTheArea() {
        RgbFrame cut = FramePrep.crop(numbered(), new ActiveArea(1, 1, 2, 2));

        assertSame(cut, rgb(2, 2, numberedAt(1, 1), numberedAt(2, 1), numberedAt(1, 2), numberedAt(2, 2)));
    }

    @Test
    void cropAcceptsTheWholeFrameAndAnAreaTouchingEachEdge() {
        assertSame(FramePrep.crop(numbered(), new ActiveArea(0, 0, 4, 3)), numbered());
        assertSame(FramePrep.crop(numbered(), new ActiveArea(3, 2, 1, 1)), rgb(1, 1, numberedAt(3, 2)));
        assertSame(FramePrep.crop(numbered(), new ActiveArea(0, 0, 1, 1)), rgb(1, 1, numberedAt(0, 0)));
        assertSame(FramePrep.crop(numbered(), new ActiveArea(2, 0, 2, 3)),
            rgb(2, 3, numberedAt(2, 0), numberedAt(3, 0), numberedAt(2, 1), numberedAt(3, 1), numberedAt(2, 2),
                numberedAt(3, 2)));
    }

    private static void assertCropRefused(ActiveArea area) {
        assertThatThrownBy(() -> FramePrep.crop(numbered(), area)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The area " + area + " does not lie inside the 4x3 frame");
    }

    @Test
    void cropRefusesAnAreaThatDoesNotLieFullyInsideTheFrame() {
        assertCropRefused(new ActiveArea(-1, 0, 2, 2));
        assertCropRefused(new ActiveArea(0, -1, 2, 2));
        assertCropRefused(new ActiveArea(3, 0, 2, 2));
        assertCropRefused(new ActiveArea(0, 2, 2, 2));
        assertCropRefused(new ActiveArea(0, 0, 5, 3));
        assertCropRefused(new ActiveArea(0, 0, 4, 4));
        assertCropRefused(new ActiveArea(0, 0, 0, 2));
        assertCropRefused(new ActiveArea(0, 0, 2, 0));
        // Each edge one pixel over, with the other three sides inside.
        assertCropRefused(new ActiveArea(1, 0, 4, 3));
        assertCropRefused(new ActiveArea(0, 1, 4, 3));
    }

    // ---- squash --------------------------------------------------------------------------------------------------

    @Test
    void squashToTheSameSizeReturnsIdenticalPixels() {
        RgbFrame frame = rgb(3, 2, 0x010203, 0xFF0080, 0x7F8081, 0x000000, 0xFFFFFF, 0x0AC81E);

        assertSame(FramePrep.squash(frame, 3, 2), frame);
    }

    @Test
    void squashUpscalesTwoPixelsToFourWithInterpolatedValuesAndClampedEdges() {
        // sx = 0.25 * (2x + 1) - 0.5 for x = 0..3 gives -0.25 (clamped to 0), 0.25, 0.75 and 1.25 (clamped to 1).
        // 0, 0.25 * 255 = 63.75 -> 64, 0.75 * 255 = 191.25 -> 191, 255.
        RgbFrame result = FramePrep.squash(rgb(2, 1, 0x000000, 0xFFFFFF), 4, 1);

        assertSame(result, rgb(4, 1, 0x000000, 0x404040, 0xBFBFBF, 0xFFFFFF));
    }

    @Test
    void squashDownscalesEachTwoByTwoBlockToItsMeanRoundedHalfUp() {
        // Red is x + 4y (0 to 15), green is constant 7 and blue is 255 minus red.
        byte[] pixels = new byte[4 * 4 * 3];
        for (int i = 0; i < 16; i++) {
            pixels[3 * i] = (byte) i;
            pixels[3 * i + 1] = 7;
            pixels[3 * i + 2] = (byte) (255 - i);
        }

        RgbFrame result = FramePrep.squash(new RgbFrame(4, 4, pixels), 2, 2);

        // Block means of red: 2.5, 4.5, 10.5 and 12.5 round up to 3, 5, 11 and 13; blue 252.5, 250.5, 244.5 and
        // 242.5 round up to 253, 251, 245 and 243.
        assertSame(result, rgb(2, 2,
            0x0307FD, 0x0507FB,
            0x0B07F5, 0x0D07F3));
    }

    @Test
    void squashInterpolatesRowsAsWellAsColumns() {
        // One column, two rows (0, 255) to four rows: the same weights down the column as across a row.
        RgbFrame result = FramePrep.squash(rgb(1, 2, 0x000000, 0xFFFFFF), 1, 4);

        assertSame(result, rgb(1, 4, 0x000000, 0x404040, 0xBFBFBF, 0xFFFFFF));
    }

    @Test
    void squashOfASinglePixelFillsAnySizeWithIt() {
        assertSame(FramePrep.squash(rgb(1, 1, 0x0AC81E), 3, 2), flat(3, 2, 0x0AC81E));
    }

    @Test
    void squashRefusesASizeOutOfRange() {
        RgbFrame frame = rgb(1, 1, 0);

        assertThatThrownBy(() -> FramePrep.squash(frame, 0, 1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 0x1");
        assertThatThrownBy(() -> FramePrep.squash(frame, 1, 4097)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 1x4097");
        assertThatThrownBy(() -> FramePrep.squash(frame, 4097, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FramePrep.squash(frame, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FramePrep.squash(frame, -1, 1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was -1x1");
        assertThatThrownBy(() -> FramePrep.squash(frame, 1, -1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 1x-1");
        assertThatThrownBy(() -> FramePrep.squash(frame, 100_000, 100_000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 100000x100000");
    }

    @Test
    void squashAcceptsTheSmallestAndLargestSizes() {
        RgbFrame frame = rgb(2, 2, 0x000000, 0x000000, 0x000000, 0x000000);

        assertSame(FramePrep.squash(frame, 1, 1), rgb(1, 1, 0));
        assertThat(FramePrep.squash(frame, 4096, 1).width()).isEqualTo(4096);
        assertThat(FramePrep.squash(frame, 1, 4096).height()).isEqualTo(4096);
    }

    // ---- jpeg and png --------------------------------------------------------------------------------------------

    @Test
    void jpegGivesTheSameBytesEveryTime() {
        RgbFrame frame = FramePrep.squash(
            rgb(4, 2, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x000000, 0x0AC81E, 0x808080, 0x123456), 224, 224);

        byte[] first = FramePrep.jpeg(frame);
        byte[] second = FramePrep.jpeg(frame);

        assertThat(first).isEqualTo(second);
        // A JPEG starts with the start-of-image marker.
        assertThat(first[0]).isEqualTo((byte) 0xFF);
        assertThat(first[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    void aFlatColourFrameSurvivesTheJpegRoundTripWithinTwoPerChannel() {
        RgbFrame decoded = FramePrep.decodeJpeg(FramePrep.jpeg(flat(224, 224, 0xC86432)));

        assertThat(decoded.width()).isEqualTo(224);
        assertThat(decoded.height()).isEqualTo(224);
        for (int y = 0; y < 224; y += 37) {
            for (int x = 0; x < 224; x += 41) {
                int pixel = decoded.rgb(x, y);
                assertThat(pixel >> 16 & 0xFF).isBetween(198, 202);
                assertThat(pixel >> 8 & 0xFF).isBetween(98, 102);
                assertThat(pixel & 0xFF).isBetween(48, 52);
            }
        }
    }

    @Test
    void decodeJpegRefusesBytesThatAreNotAPicture() {
        assertThatThrownBy(() -> FramePrep.decodeJpeg(new byte[] {1, 2, 3}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The bytes are not a readable JPEG picture");
    }

    @Test
    void colourJpegCropsSquashesAndEncodes() {
        RgbFrame frame = flat(480, 270, 0x6080A0);
        ActiveArea area = new ActiveArea(0, 30, 480, 210);

        byte[] jpeg = FramePrep.colourJpeg(frame, area);

        assertThat(jpeg).isEqualTo(FramePrep.jpeg(FramePrep.squash(FramePrep.crop(frame, area), 224, 224)));
        assertThat(FramePrep.COLOUR_SIZE).isEqualTo(224);
    }

    @Test
    void colourForModelIsTheDecodedStoredJpegAtTwoTwentyFourSquare() {
        byte[] pixels = new byte[480 * 270 * 3];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i * 7 % 251);
        }
        RgbFrame frame = new RgbFrame(480, 270, pixels);
        ActiveArea area = new ActiveArea(0, 30, 480, 210);

        RgbFrame model = FramePrep.colourForModel(frame, area);

        assertThat(model.width()).isEqualTo(224);
        assertThat(model.height()).isEqualTo(224);
        assertSame(model, FramePrep.decodeJpeg(FramePrep.colourJpeg(frame, area)));
    }

    @Test
    void pngRoundTripsAGreyFrameExactly() {
        byte[] pixels = new byte[240 * 135];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i * 31 % 256);
        }
        GreyFrame frame = new GreyFrame(240, 135, pixels);

        GreyFrame decoded = FramePrep.decodePng(FramePrep.png(frame));

        assertSame(decoded, frame);
    }

    @Test
    void pngKeepsBlackAndWhiteExactly() {
        GreyFrame decoded = FramePrep.decodePng(FramePrep.png(grey(3, 1, 0, 128, 255)));

        assertSame(decoded, grey(3, 1, 0, 128, 255));
    }

    @Test
    void anEncoderThatFailsIsAnUncheckedIoFailureNamingThePicture() {
        assertThatThrownBy(() -> FramePrep.encode("JPEG", out -> {
            throw new java.io.IOException("disk full");
        })).isInstanceOf(java.io.UncheckedIOException.class)
            .hasMessage("The JPEG could not be written")
            .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void aTruncatedJpegIsNotAReadablePicture() {
        byte[] whole = FramePrep.jpeg(flat(16, 16, 0x102030));
        byte[] cut = java.util.Arrays.copyOf(whole, 40);

        assertThatThrownBy(() -> FramePrep.decodeJpeg(cut)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("The JPEG picture could not be read: ")
            .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void decodePngRefusesBytesThatAreNotAGreyscalePng() {
        assertThatThrownBy(() -> FramePrep.decodePng(new byte[] {1, 2, 3}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The bytes are not a readable PNG picture");
        // A colour picture, however it is encoded, is not a grey frame.
        assertThatThrownBy(() -> FramePrep.decodePng(FramePrep.jpeg(flat(8, 8, 0x102030))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The PNG is not an 8-bit greyscale picture");
    }

    @Test
    void jpegIsEncodedAtQualityPointNineFiveNotTheEncodersDefault() throws java.io.IOException {
        byte[] pixels = new byte[64 * 64 * 3];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i * 37 % 256);
        }
        RgbFrame noisy = new RgbFrame(64, 64, pixels);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64, 64,
            java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, noisy.rgb(x, y));
            }
        }
        java.io.ByteArrayOutputStream standard = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", standard);

        // The encoder's default quality is 0.75; 0.95 keeps more detail and so takes more bytes.
        assertThat(FramePrep.jpeg(noisy).length).isGreaterThan(standard.size() * 3 / 2);
    }
}

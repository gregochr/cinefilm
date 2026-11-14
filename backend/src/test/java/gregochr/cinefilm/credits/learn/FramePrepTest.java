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

    private static final ActiveArea LETTERBOX_AREA = new ActiveArea(0, 30, 480, 210);

    /**
     * A 480x270 frame with red bars (the top and bottom 30 rows) around a picture of two halves: blue on the left,
     * yellow on the right, the seam at column 240.
     */
    private static RgbFrame letterboxed() {
        int[] pixels = new int[480 * 270];
        for (int y = 0; y < 270; y++) {
            for (int x = 0; x < 480; x++) {
                pixels[y * 480 + x] = y < 30 || y >= 240 ? 0xFF0000 : x < 240 ? 0x0000FF : 0xFFFF00;
            }
        }
        return rgb(480, 270, pixels);
    }

    /** Within 40 of the colour in every channel (a lossy encode and the resampling blur only the seam). */
    private static boolean near(int pixel, int colour) {
        for (int shift = 16; shift >= 0; shift -= 8) {
            if (Math.abs((pixel >> shift & 0xFF) - (colour >> shift & 0xFF)) > 40) {
                return false;
            }
        }
        return true;
    }

    /** Every pixel, but those near the seam at column 112, is the colour of its side and none is red. */
    private static void assertBarsGoneAndSidesKept(RgbFrame picture) {
        assertThat(picture.width()).isEqualTo(224);
        assertThat(picture.height()).isEqualTo(224);
        for (int y = 0; y < 224; y++) {
            for (int x = 0; x < 224; x++) {
                if (Math.abs(x - 112) < 12) {
                    continue;
                }
                int pixel = picture.rgb(x, y);
                assertThat(near(pixel, x < 112 ? 0x0000FF : 0xFFFF00))
                    .as("pixel (%d, %d) is %06X", x, y, pixel).isTrue();
            }
        }
    }

    /** The first column of a row whose red is over half: where the picture turns from blue to yellow. */
    private static int seamColumn(RgbFrame picture, int row) {
        for (int x = 0; x < picture.width(); x++) {
            if ((picture.rgb(x, row) >> 16 & 0xFF) > 128) {
                return x;
            }
        }
        throw new AssertionError("no yellow in row " + row);
    }

    @Test
    void colourJpegCutsTheBarsOffAndKeepsEachSideOfThePicture() {
        byte[] jpeg = FramePrep.colourJpeg(letterboxed(), LETTERBOX_AREA);

        RgbFrame decoded = FramePrep.decodeJpeg(jpeg);

        assertBarsGoneAndSidesKept(decoded);
        // 240 of 480 columns is 112 of 224; an area cut a few pixels to the side would move the seam.
        assertThat(seamColumn(decoded, 112)).isBetween(111, 113);
        assertThat(FramePrep.COLOUR_SIZE).isEqualTo(224);
    }

    @Test
    void colourForModelShowsTheModelTheCroppedPictureAtTwoTwentyFourSquare() {
        RgbFrame frame = letterboxed();

        RgbFrame model = FramePrep.colourForModel(frame, LETTERBOX_AREA);

        assertBarsGoneAndSidesKept(model);
        assertThat(seamColumn(model, 112)).isBetween(111, 113);
        assertSame(model, FramePrep.decodeJpeg(FramePrep.colourJpeg(frame, LETTERBOX_AREA)));
    }

    @Test
    void colourJpegOfAnAreaThatDoesNotStartAtTheLeftCutsFromTheAreasOwnLeft() {
        // The same picture, cut from column 8: the seam moves left by 8 columns of 472, to 232 of 472.
        RgbFrame decoded = FramePrep.decodeJpeg(FramePrep.colourJpeg(letterboxed(), new ActiveArea(8, 30, 472, 210)));

        assertThat(seamColumn(decoded, 112)).isBetween(109, 111);
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

    // ---- Golden fixtures -----------------------------------------------------------------------------------------

    /** A 224x224 gradient with a stripe of white columns and of black rows, so the encoder meets edges too. */
    private static RgbFrame goldenColour() {
        int[] pixels = new int[224 * 224];
        for (int y = 0; y < 224; y++) {
            for (int x = 0; x < 224; x++) {
                int pixel = (x * 255 / 223) << 16 | (y * 255 / 223) << 8 | (x * y % 256);
                if (x % 32 < 2) {
                    pixel = 0xFFFFFF;
                }
                if (y % 48 < 3) {
                    pixel = 0x000000;
                }
                pixels[y * 224 + x] = pixel;
            }
        }
        return rgb(224, 224, pixels);
    }

    private static GreyFrame goldenGrey() {
        byte[] pixels = new byte[240 * 135];
        for (int y = 0; y < 135; y++) {
            for (int x = 0; x < 240; x++) {
                pixels[y * 240 + x] = (byte) (x * 3 + y * 5 + (x / 16 + y / 16) % 2 * 80);
            }
        }
        return new GreyFrame(240, 135, pixels);
    }

    private static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] rawRgb(RgbFrame frame) {
        byte[] raw = new byte[frame.width() * frame.height() * 3];
        int at = 0;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int pixel = frame.rgb(x, y);
                raw[at++] = (byte) (pixel >> 16);
                raw[at++] = (byte) (pixel >> 8);
                raw[at++] = (byte) pixel;
            }
        }
        return raw;
    }

    // If one of these three fails, the JDK's JPEG or PNG encoder or decoder has changed (a new JDK, a different
    // base image). The stored dataset was written by one and the product reads with another, so training and the
    // product would no longer see the same pixels. Find out why before changing a hash; never re-baseline blindly.
    @Test
    void theJpegOfAFixedPatternIsByteForByteTheStoredOne() {
        assertThat(sha256(FramePrep.jpeg(goldenColour())))
            .isEqualTo("7b4a853b39eac1923d06fbd3119e27504e90ebde4fd587a039b3182c770a7583");
    }

    @Test
    void theDecodedJpegOfAFixedPatternIsPixelForPixelTheStoredOne() {
        assertThat(sha256(rawRgb(FramePrep.decodeJpeg(FramePrep.jpeg(goldenColour())))))
            .isEqualTo("1cbf323470d837435ad0a5695e7c12afb5714e8ef9fc0cf54792d367167d9663");
    }

    @Test
    void theGreyPngOfAFixedPatternIsByteForByteTheStoredOne() {
        assertThat(sha256(FramePrep.png(goldenGrey())))
            .isEqualTo("60952f6e9fd3b9050b944604b3813abec08d31663059fc40269df34712552567");
        assertSame(FramePrep.decodePng(FramePrep.png(goldenGrey())), goldenGrey());
    }

    // ---- Squash at uneven ratios ---------------------------------------------------------------------------------

    @Test
    void squashFiveByThreeToThreeByTwoHasExactValues() {
        // Red 40x + 25y, green 200 - 35x + 15y, blue 17 (xy + 1).
        byte[] pixels = new byte[5 * 3 * 3];
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 5; x++) {
                int at = (y * 5 + x) * 3;
                pixels[at] = (byte) (40 * x + 25 * y);
                pixels[at + 1] = (byte) (200 - 35 * x + 15 * y);
                pixels[at + 2] = (byte) (17 * (x * y + 1));
            }
        }

        RgbFrame result = FramePrep.squash(new RgbFrame(5, 3, pixels), 3, 2);

        // Working for output pixel (1, 1): sx = (1 + 0.5) * 5 / 3 - 0.5 = 2.0, so x0 = 2 and fx = 0.
        // sy = (1 + 0.5) * 3 / 2 - 0.5 = 1.75, so y0 = 1, y1 = 2 and fy = 0.75. With fx = 0 only column 2 counts:
        //   red   (2,1) = 80 + 25 = 105, (2,2) = 80 + 50 = 130: 0.25 * 105 + 0.75 * 130 = 123.75, floor(124.25) = 124
        //   green (2,1) = 145,           (2,2) = 160:           0.25 * 145 + 0.75 * 160 = 156.25, floor(156.75) = 156
        //   blue  (2,1) = 51,            (2,2) = 85:            0.25 * 51  + 0.75 * 85  = 76.5,   floor(77.0)    = 77
        // which is 0x7C9C4D. The other pixels follow the same formula.
        assertSame(result, rgb(3, 2,
            0x14C012, 0x56861A, 0x994B21,
            0x39D71B, 0x7C9C4D, 0xBE627E));
    }

    @Test
    void squashFifteenBySevenToSevenByThreeHasExactValues() {
        // About the 480 to 224 ratio in miniature, x and y at different ratios. The channels wrap so no two
        // neighbours are alike: red (17x + 29y) mod 256, green (3x^2 + 40y) mod 256, blue 255 - 13x - 7y.
        byte[] pixels = new byte[15 * 7 * 3];
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 15; x++) {
                int at = (y * 15 + x) * 3;
                pixels[at] = (byte) (17 * x + 29 * y);
                pixels[at + 1] = (byte) (3 * x * x + 40 * y);
                pixels[at + 2] = (byte) (255 - 13 * x - 7 * y);
            }
        }

        RgbFrame result = FramePrep.squash(new RgbFrame(15, 7, pixels), 7, 3);

        assertSame(result, rgb(7, 3,
            0x1D1CF3, 0x4131D7, 0x6662BB, 0x8AAE9F, 0xAF5F83, 0xD39968, 0xAE694C,
            0x617AE3, 0x858FC7, 0xAABFAB, 0xCE0B8F, 0xCE7373, 0x17AE57, 0x3B963B,
            0xA4D7D2, 0xC9AFB6, 0xA4359B, 0x12687F, 0x36C463, 0x5B5447, 0x7F852B));
    }
}

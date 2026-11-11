package gregochr.cinefilm.plex;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.plex.ArtworkResizer.Probe;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real {@code ImageIO} on images the test draws: sizes, formats, limits decided by the header, and bad input. */
class ArtworkResizerTest {

    private final ArtworkResizer resizer = new ArtworkResizer();

    @TempDir
    Path dir;

    /** A picture with detail (a gradient and noise) so a JPEG is not trivially tiny. */
    private static BufferedImage picture(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Random random = new Random(7);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 255 / width + random.nextInt(40)) & 0xff;
                int g = (y * 255 / height) & 0xff;
                int b = ((x + y) * 3 + random.nextInt(40)) & 0xff;
                image.setRGB(x, y, (type == BufferedImage.TYPE_INT_ARGB ? 0x80 << 24 : 0xff << 24)
                    | r << 16 | g << 8 | b);
            }
        }
        return image;
    }

    private Path write(String name, BufferedImage image, String format) throws IOException {
        Path file = dir.resolve(name);
        assertThat(ImageIO.write(image, format, file.toFile())).isTrue();
        return file;
    }

    private Path jpeg(int width, int height) throws IOException {
        return write("w" + width + "x" + height + ".jpg", picture(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static boolean hasMarker(byte[] bytes, int marker) {
        for (int i = 0; i < bytes.length - 1; i++) {
            if ((bytes[i] & 0xff) == 0xff && (bytes[i + 1] & 0xff) == marker) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aPosterIsScaledToTheWidthAndIsStillAProgressiveJpeg() throws IOException {
        Path poster = jpeg(600, 900);

        byte[] resized = resizer.resize(poster, "poster.jpg", 342).orElseThrow();

        BufferedImage image = decode(resized);
        assertThat(image.getWidth()).isEqualTo(342);
        assertThat(image.getHeight()).isEqualTo(513);
        assertThat(hasMarker(resized, 0xc2)).as("SOF2: progressive").isTrue();
        assertThat(hasMarker(resized, 0xc0)).as("SOF0: baseline").isFalse();
        assertThat(resized.length).isLessThan((int) Files.size(poster));
    }

    @Test
    void aBackdropIsHalvedThenScaledToTheExactSize() throws IOException {
        byte[] resized = resizer.resize(jpeg(1920, 1080), "backdrop.jpg", 780).orElseThrow();

        BufferedImage image = decode(resized);
        assertThat(image.getWidth()).isEqualTo(780);
        assertThat(image.getHeight()).isEqualTo(439);
    }

    @Test
    void aVeryTallImageKeepsAtLeastOnePixelOfHeight() throws IOException {
        byte[] resized = resizer.resize(jpeg(2000, 1), "strip.jpg", 500).orElseThrow();

        assertThat(decode(resized).getHeight()).isEqualTo(1);
    }

    @Test
    void aPngWithAlphaStaysAPngWithAlpha() throws IOException {
        Path file = write("alpha.png", picture(1000, 500, BufferedImage.TYPE_INT_ARGB), "png");

        byte[] resized = resizer.resize(file, "alpha.png", 500).orElseThrow();

        BufferedImage image = decode(resized);
        assertThat(image.getWidth()).isEqualTo(500);
        assertThat(image.getHeight()).isEqualTo(250);
        assertThat(image.getColorModel().hasAlpha()).isTrue();
        assertThat(Arrays.copyOf(resized, 4)).containsExactly((byte) 0x89, 'P', 'N', 'G');
        assertThat(image.getRGB(10, 10) >>> 24).isBetween(0x70, 0x90);
    }

    @Test
    void aPngWhoseTransparencyIsATrnsChunkKeepsItsTransparentPixelsTransparent() throws IOException {
        BufferedImage image = new BufferedImage(1000, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 1000; x++) {
                image.setRGB(x, y, x < 500 ? 0x000000 : 0xffffff);
            }
        }
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", plain)).isTrue();
        byte[] png = plain.toByteArray();
        // tRNS for an RGB image: one 16-bit sample per channel; black (0, 0, 0) is the transparent colour.
        byte[] type = {'t', 'R', 'N', 'S'};
        byte[] samples = new byte[6];
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(samples);
        ByteBuffer chunk = ByteBuffer.allocate(4 + 4 + 6 + 4).putInt(6).put(type).put(samples)
            .putInt((int) crc.getValue());
        int afterHeader = 8 + 4 + 4 + 13 + 4;
        ByteArrayOutputStream withTrns = new ByteArrayOutputStream();
        withTrns.write(png, 0, afterHeader);
        withTrns.write(chunk.array());
        withTrns.write(png, afterHeader, png.length - afterHeader);
        Path file = Files.write(dir.resolve("trns.png"), withTrns.toByteArray());

        BufferedImage resized = decode(resizer.resize(file, "trns.png", 500).orElseThrow());

        assertThat(resized.getColorModel().hasAlpha()).isTrue();
        assertThat(resized.getRGB(10, 10) >>> 24).isZero();
        assertThat(resized.getRGB(490, 10) >>> 24).isEqualTo(255);
    }

    @Test
    void aPngWithoutAlphaStaysAPngWithoutAlpha() throws IOException {
        Path file = write("opaque.png", picture(1000, 500, BufferedImage.TYPE_INT_RGB), "png");

        BufferedImage image = decode(resizer.resize(file, "opaque.png", 500).orElseThrow());

        assertThat(image.getColorModel().hasAlpha()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"499,NOT_WIDER", "500,NOT_WIDER", "501,RESIZABLE"})
    void onlyAnImageWiderThanTheTargetIsResizable(int width, Probe expected) throws IOException {
        Path file = jpeg(width, 40);

        assertThat(resizer.probe(file, "a.jpg", 500)).isEqualTo(expected);
        assertThat(resizer.resize(file, "a.jpg", 500).isPresent()).isEqualTo(expected == Probe.RESIZABLE);
    }

    @ParameterizedTest
    @CsvSource({"4096,1,RESIZABLE", "4097,1,CANNOT_RESIZE", "100,4096,RESIZABLE", "100,4097,CANNOT_RESIZE"})
    void theSideLimitIsAtFourThousandAndNinetySixPixels(int width, int height, Probe expected) throws IOException {
        Path file = jpeg(width, height);

        assertThat(resizer.probe(file, "a.jpg", 50)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"4096,1024,RESIZABLE", "2113,1985,CANNOT_RESIZE"})
    void thePixelLimitIsFourMegapixelsAndTheHeaderDecidesIt(int width, int height, Probe expected)
        throws IOException {
        // 4096 x 1024 is exactly 4,194,304 pixels; 2113 x 1985 is 4,194,305.
        Path file = jpeg(width, height);

        assertThat(resizer.probe(file, "a.jpg", 342)).isEqualTo(expected);
        assertThat(resizer.resize(file, "a.jpg", 342).isPresent()).isEqualTo(expected == Probe.RESIZABLE);
    }

    @Test
    void sixteenBitSamplesCannotBeResizedButEightBitCan() throws IOException {
        Path deep = write("deep.png", new BufferedImage(600, 20, BufferedImage.TYPE_USHORT_GRAY), "png");
        Path shallow = write("shallow.png", picture(600, 20, BufferedImage.TYPE_INT_RGB), "png");

        assertThat(resizer.probe(deep, "deep.png", 342)).isEqualTo(Probe.CANNOT_RESIZE);
        assertThat(resizer.resize(deep, "deep.png", 342)).isEmpty();
        assertThat(resizer.probe(shallow, "shallow.png", 342)).isEqualTo(Probe.RESIZABLE);
    }

    @Test
    void theProbeReadsOnlyTheHeaderSoATruncatedFileStillProbesResizable() throws IOException {
        Path whole = jpeg(600, 900);
        byte[] bytes = Files.readAllBytes(whole);
        Path cut = Files.write(dir.resolve("cut.jpg"), Arrays.copyOf(bytes, bytes.length / 2));

        assertThat(resizer.probe(cut, "cut.jpg", 342)).isEqualTo(Probe.RESIZABLE);
        assertThat(resizer.resize(cut, "cut.jpg", 342)).isEmpty();
    }

    @Test
    void aTruncatedPngCannotBeResized() throws IOException {
        Path whole = write("whole.png", picture(800, 800, BufferedImage.TYPE_INT_RGB), "png");
        byte[] bytes = Files.readAllBytes(whole);
        Path cut = Files.write(dir.resolve("cut.png"), Arrays.copyOf(bytes, bytes.length / 2));

        assertThat(resizer.resize(cut, "cut.png", 342)).isEmpty();
    }

    @Test
    void randomBytesCannotBeResizedAsEitherFormat() throws IOException {
        byte[] noise = new byte[2048];
        new Random(3).nextBytes(noise);
        Path jpg = Files.write(dir.resolve("noise.jpg"), noise);
        Path png = Files.write(dir.resolve("noise.png"), noise);

        assertThat(resizer.probe(jpg, "noise.jpg", 342)).isEqualTo(Probe.CANNOT_RESIZE);
        assertThat(resizer.probe(png, "noise.png", 342)).isEqualTo(Probe.CANNOT_RESIZE);
        assertThat(resizer.resize(jpg, "noise.jpg", 342)).isEqualTo(Optional.empty());
        assertThat(resizer.resize(png, "noise.png", 342)).isEqualTo(Optional.empty());
    }

    @Test
    void aMissingFileCannotBeResized() {
        Path missing = dir.resolve("missing.jpg");

        assertThat(resizer.probe(missing, "missing.jpg", 342)).isEqualTo(Probe.CANNOT_RESIZE);
        assertThat(resizer.resize(missing, "missing.jpg", 342)).isEmpty();
    }

    @Test
    void aJpegNamedAsPngCannotBeResized() throws IOException {
        Path file = write("wrong.png", picture(600, 20, BufferedImage.TYPE_INT_RGB), "jpg");

        assertThat(resizer.probe(file, "wrong.png", 342)).isEqualTo(Probe.CANNOT_RESIZE);
    }

    @Test
    void halvingsCountsWhileTheWidthIsAtLeastTwiceTheTarget() {
        assertThat(ArtworkResizer.halvings(199, 100)).isZero();
        assertThat(ArtworkResizer.halvings(200, 100)).isEqualTo(1);
        assertThat(ArtworkResizer.halvings(201, 100)).isEqualTo(1);
        assertThat(ArtworkResizer.halvings(399, 100)).isEqualTo(1);
        assertThat(ArtworkResizer.halvings(400, 100)).isEqualTo(2);
        assertThat(ArtworkResizer.halvings(1920, 342)).isEqualTo(2);
        assertThat(ArtworkResizer.halvings(600, 342)).isZero();
    }
}

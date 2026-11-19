package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import gregochr.cinefilm.credits.RgbFrame;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Runs on Linux only: ONNX Runtime 1.30 and later ship no macOS x86-64 natives, so the library cannot load on the
 * Intel dev Mac. CI's backend job runs on Ubuntu.
 */
@EnabledOnOs(OS.LINUX)
class OnnxImageEncoderTest {

    private static RgbFrame solid(int r, int g, int b) {
        byte[] pixels = new byte[224 * 224 * 3];
        for (int i = 0; i < pixels.length; i += 3) {
            pixels[i] = (byte) r;
            pixels[i + 1] = (byte) g;
            pixels[i + 2] = (byte) b;
        }
        return new RgbFrame(224, 224, pixels);
    }

    private static EncoderSpec fixture() throws IOException {
        return EncoderSpec.read(TinyFixture.dir());
    }

    private static EncoderSpec with(EncoderSpec spec, String input, String output, int dimension, List<Double> std) {
        return new EncoderSpec(spec.name(), spec.file(), spec.sha256(), input, output, spec.size(), spec.mean(), std,
            dimension);
    }

    @Test
    void twoFramesInOneBatchGiveTheirChannelMeansInOrder() throws IOException {
        // Red frame: R (1 - 0.25) / 0.5 = 1.5, G (0 - 0.5) / 0.25 = -2, B (0 - 0.75) / 2 = -0.375.
        // Green frame: R (0 - 0.25) / 0.5 = -0.5, G (1 - 0.5) / 0.25 = 2, B -0.375.
        try (OnnxImageEncoder encoder = new OnnxImageEncoder(fixture(), TinyFixture.dir())) {
            float[][] rows = encoder.encode(List.of(solid(255, 0, 0), solid(0, 255, 0)));

            assertThat(rows).hasDimensions(2, 3);
            assertThat(rows[0]).containsExactly(new float[] {1.5f, -2f, -0.375f}, within(1e-6f));
            assertThat(rows[1]).containsExactly(new float[] {-0.5f, 2f, -0.375f}, within(1e-6f));
        }
    }

    @Test
    void aSingleFrameBatchAndTheReversedOrderGiveTheSameRows() throws IOException {
        try (OnnxImageEncoder encoder = new OnnxImageEncoder(fixture(), TinyFixture.dir())) {
            float[][] reversed = encoder.encode(List.of(solid(0, 255, 0), solid(255, 0, 0)));
            float[][] single = encoder.encode(List.of(solid(255, 0, 0)));

            assertThat(reversed[1]).containsExactly(single[0], within(1e-6f));
            assertThat(reversed[0]).containsExactly(new float[] {-0.5f, 2f, -0.375f}, within(1e-6f));
        }
    }

    @Test
    void aFrameOfTheWrongSizeIsRefused() throws IOException {
        try (OnnxImageEncoder encoder = new OnnxImageEncoder(fixture(), TinyFixture.dir())) {
            List<RgbFrame> small = List.of(new RgbFrame(2, 2, new byte[12]));

            assertThatThrownBy(() -> encoder.encode(small)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aMissingInputIsRefusedAtOpen() throws IOException {
        EncoderSpec spec = with(fixture(), "nope", "image_embeds", 3, fixture().std());

        assertThatThrownBy(() -> new OnnxImageEncoder(spec, TinyFixture.dir()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("has no input nope and output image_embeds");
    }

    @Test
    void aMissingOutputIsRefusedAtOpen() throws IOException {
        EncoderSpec spec = with(fixture(), "pixel_values", "nope", 3, fixture().std());

        assertThatThrownBy(() -> new OnnxImageEncoder(spec, TinyFixture.dir()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("has no input pixel_values and output nope");
    }

    @Test
    void aFileThatIsNotAModelIsRefusedAtOpen() throws IOException {
        EncoderSpec real = fixture();
        EncoderSpec spec = new EncoderSpec(real.name(), "encoder.json", real.sha256(), real.input(), real.output(),
            real.size(), real.mean(), real.std(), 3);

        assertThatThrownBy(() -> new OnnxImageEncoder(spec, TinyFixture.dir()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("cannot open encoder.json: ");
    }

    @Test
    void aDimensionOtherThanTheModelsIsRefusedAtEncode() throws IOException {
        EncoderSpec spec = with(fixture(), "pixel_values", "image_embeds", 4, fixture().std());

        try (OnnxImageEncoder encoder = new OnnxImageEncoder(spec, TinyFixture.dir())) {
            List<RgbFrame> frames = List.of(solid(1, 2, 3));

            assertThatThrownBy(() -> encoder.encode(frames))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the output is not a [1, 4] tensor");
        }
    }

    @Test
    void anInfiniteOutputIsRefused() throws IOException {
        EncoderSpec spec = with(fixture(), "pixel_values", "image_embeds", 3, List.of(1e-300, 1e-300, 1e-300));

        try (OnnxImageEncoder encoder = new OnnxImageEncoder(spec, TinyFixture.dir())) {
            List<RgbFrame> frames = List.of(solid(255, 255, 255));

            assertThatThrownBy(() -> encoder.encode(frames))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the output holds a NaN or infinite value");
        }
    }
}

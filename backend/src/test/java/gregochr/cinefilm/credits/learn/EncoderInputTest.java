package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import gregochr.cinefilm.credits.RgbFrame;
import java.util.List;
import org.junit.jupiter.api.Test;

class EncoderInputTest {

    /** Red: (v / 255 - 0.5) / 0.5. Green: (v / 255 - 0.25) / 0.5. Blue: v / 255 / 0.25. */
    private static final EncoderSpec SPEC = new EncoderSpec("t", "t.onnx", "00", "in", "out", 2,
        List.of(0.5, 0.25, 0.0), List.of(0.5, 0.5, 0.25), 3);

    private static RgbFrame frame(int... rgbPerPixel) {
        byte[] pixels = new byte[rgbPerPixel.length];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) rgbPerPixel[i];
        }
        return new RgbFrame(2, 2, pixels);
    }

    @Test
    void aFrameBecomesThreePlanesRedGreenBlueEachRowByRow() {
        RgbFrame frame = frame(255, 0, 51, 0, 255, 0, 51, 0, 255, 0, 51, 0);

        float[] tensor = EncoderInput.tensor(List.of(frame), SPEC);

        float[] expected = {
            1f, -1f, -0.6f, -1f,
            -0.5f, 1.5f, -0.5f, -0.1f,
            0.8f, 0f, 4f, 0f};
        assertThat(tensor).hasSize(12);
        for (int i = 0; i < expected.length; i++) {
            assertThat(tensor[i]).as("value %d", i).isCloseTo(expected[i], within(1e-6f));
        }
    }

    @Test
    void framesKeepTheirListOrderOneAfterAnother() {
        RgbFrame first = frame(255, 0, 51, 0, 255, 0, 51, 0, 255, 0, 51, 0);
        RgbFrame second = frame(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        float[] tensor = EncoderInput.tensor(List.of(first, second), SPEC);

        assertThat(tensor).hasSize(24);
        assertThat(tensor[0]).isEqualTo(1f);
        assertThat(tensor[12]).isEqualTo(-1f);
        assertThat(tensor[15]).isEqualTo(-1f);
        assertThat(tensor[16]).isEqualTo(-0.5f);
        assertThat(tensor[20]).isEqualTo(0f);
        assertThat(tensor[23]).isEqualTo(0f);
    }

    @Test
    void anEmptyBatchIsAnEmptyTensor() {
        assertThat(EncoderInput.tensor(List.of(), SPEC)).isEmpty();
    }

    @Test
    void aFrameOfAnotherSizeIsRefusedWithItsSize() {
        RgbFrame big = new RgbFrame(3, 3, new byte[27]);

        assertThatThrownBy(() -> EncoderInput.tensor(List.of(big), SPEC))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("frame is 3x3, the encoder takes 2x2");
    }

    @Test
    void aFrameOfTheRightWidthButAnotherHeightIsRefused() {
        RgbFrame tall = new RgbFrame(2, 3, new byte[18]);

        assertThatThrownBy(() -> EncoderInput.tensor(List.of(tall), SPEC))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("frame is 2x3, the encoder takes 2x2");
    }

    @Test
    void aFrameOfTheRightHeightButAnotherWidthIsRefused() {
        RgbFrame wide = new RgbFrame(3, 2, new byte[18]);

        assertThatThrownBy(() -> EncoderInput.tensor(List.of(wide), SPEC))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("frame is 3x2, the encoder takes 2x2");
    }
}

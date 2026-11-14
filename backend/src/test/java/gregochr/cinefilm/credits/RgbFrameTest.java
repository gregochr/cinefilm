package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RgbFrameTest {

    @Test
    void readsPixelsRowByRowAsPackedUnsignedRgb() {
        RgbFrame frame = new RgbFrame(2, 2, new byte[] {
            1, 2, 3, (byte) 255, (byte) 128, 0,
            0, 0, (byte) 255, 10, 20, 30});

        assertThat(frame.width()).isEqualTo(2);
        assertThat(frame.height()).isEqualTo(2);
        assertThat(frame.rgb(0, 0)).isEqualTo(0x010203);
        assertThat(frame.rgb(1, 0)).isEqualTo(0xFF8000);
        assertThat(frame.rgb(0, 1)).isEqualTo(0x0000FF);
        assertThat(frame.rgb(1, 1)).isEqualTo(0x0A141E);
    }

    @Test
    void copiesThePixelsOnTheWayIn() {
        byte[] pixels = {5, 6, 7};
        RgbFrame frame = new RgbFrame(1, 1, pixels);

        pixels[0] = 99;

        assertThat(frame.rgb(0, 0)).isEqualTo(0x050607);
    }

    @Test
    void acceptsTheLargestAndSmallestSizes() {
        assertThat(new RgbFrame(1, 1, new byte[3]).rgb(0, 0)).isEqualTo(0);
        assertThat(new RgbFrame(4096, 1, new byte[4096 * 3]).width()).isEqualTo(4096);
        assertThat(new RgbFrame(1, 4096, new byte[4096 * 3]).height()).isEqualTo(4096);
    }

    @Test
    void refusesSizesOutsideOneTo4096() {
        assertThatThrownBy(() -> new RgbFrame(0, 1, new byte[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 0x1");
        assertThatThrownBy(() -> new RgbFrame(1, 0, new byte[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 1x0");
        assertThatThrownBy(() -> new RgbFrame(4097, 1, new byte[4097 * 3]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 4097x1");
        assertThatThrownBy(() -> new RgbFrame(1, 4097, new byte[4097 * 3]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 1x4097");
    }

    @Test
    void refusesAByteCountThatDoesNotMatch() {
        assertThatThrownBy(() -> new RgbFrame(2, 2, new byte[11]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A 2x2 frame needs 12 bytes, got 11");
        assertThatThrownBy(() -> new RgbFrame(2, 2, new byte[13]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A 2x2 frame needs 12 bytes, got 13");
        assertThatThrownBy(() -> new RgbFrame(2, 2, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A 2x2 frame needs 12 bytes, got 0");
    }

    @Test
    void refusesAPixelOutsideTheFrame() {
        RgbFrame frame = new RgbFrame(2, 1, new byte[6]);

        assertThatThrownBy(() -> frame.rgb(-1, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Pixel (-1, 0) is outside the 2x1 frame");
        assertThatThrownBy(() -> frame.rgb(2, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Pixel (2, 0) is outside the 2x1 frame");
        assertThatThrownBy(() -> frame.rgb(0, -1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Pixel (0, -1) is outside the 2x1 frame");
        assertThatThrownBy(() -> frame.rgb(0, 1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Pixel (0, 1) is outside the 2x1 frame");
    }
}

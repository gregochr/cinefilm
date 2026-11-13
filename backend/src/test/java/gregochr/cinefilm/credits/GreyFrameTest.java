package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GreyFrameTest {

    @Test
    void readsPixelsRowByRowAsUnsignedLuma() {
        GreyFrame frame = new GreyFrame(3, 2, new byte[] {0, 1, (byte) 255, 10, (byte) 128, 20});

        assertThat(frame.width()).isEqualTo(3);
        assertThat(frame.height()).isEqualTo(2);
        assertThat(frame.luma(0, 0)).isEqualTo(0);
        assertThat(frame.luma(2, 0)).isEqualTo(255);
        assertThat(frame.luma(0, 1)).isEqualTo(10);
        assertThat(frame.luma(1, 1)).isEqualTo(128);
        assertThat(frame.luma(2, 1)).isEqualTo(20);
    }

    @Test
    void copiesThePixelsOnTheWayIn() {
        byte[] pixels = {5, 6};
        GreyFrame frame = new GreyFrame(2, 1, pixels);

        pixels[0] = 99;

        assertThat(frame.luma(0, 0)).isEqualTo(5);
    }

    @Test
    void acceptsTheLargestAndSmallestSizes() {
        assertThat(new GreyFrame(1, 1, new byte[1]).luma(0, 0)).isEqualTo(0);
        assertThat(new GreyFrame(4096, 1, new byte[4096]).width()).isEqualTo(4096);
        assertThat(new GreyFrame(1, 4096, new byte[4096]).height()).isEqualTo(4096);
    }

    @Test
    void refusesSizesOutsideOneTo4096() {
        assertThatThrownBy(() -> new GreyFrame(0, 1, new byte[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 0x1");
        assertThatThrownBy(() -> new GreyFrame(1, 0, new byte[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 1x0");
        assertThatThrownBy(() -> new GreyFrame(4097, 1, new byte[4097]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 4097x1");
        assertThatThrownBy(() -> new GreyFrame(1, 4097, new byte[4097]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Frame size must be 1 to 4096 in each direction, was 1x4097");
    }

    @Test
    void refusesAPixelCountThatDoesNotMatch() {
        assertThatThrownBy(() -> new GreyFrame(2, 2, new byte[3]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A 2x2 frame needs 4 pixels, got 3");
        assertThatThrownBy(() -> new GreyFrame(2, 2, new byte[5]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A 2x2 frame needs 4 pixels, got 5");
        assertThatThrownBy(() -> new GreyFrame(2, 2, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A 2x2 frame needs 4 pixels, got 0");
    }

    @Test
    void refusesAPixelOutsideTheFrame() {
        GreyFrame frame = new GreyFrame(2, 2, new byte[4]);

        assertThatThrownBy(() -> frame.luma(-1, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Pixel (-1, 0) is outside the 2x2 frame");
        assertThatThrownBy(() -> frame.luma(2, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Pixel (2, 0) is outside the 2x2 frame");
        assertThatThrownBy(() -> frame.luma(0, -1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Pixel (0, -1) is outside the 2x2 frame");
        assertThatThrownBy(() -> frame.luma(0, 2)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Pixel (0, 2) is outside the 2x2 frame");
    }
}

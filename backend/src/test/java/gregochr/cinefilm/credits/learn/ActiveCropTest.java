package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.GreyFrame;
import org.junit.jupiter.api.Test;

class ActiveCropTest {

    /** A 16x16 grey frame (a 32x32 area in 480 px coordinates) whose pixel (x, y) is {@code y * 16 + x}. */
    private static GreyFrame ramp() {
        byte[] pixels = new byte[256];
        for (int i = 0; i < 256; i++) {
            pixels[i] = (byte) i;
        }
        return new GreyFrame(16, 16, pixels);
    }

    private static ActiveCrop crop(int left, int top, int width, int height) {
        return ActiveCrop.of(left, top, width, height, 16, 16);
    }

    @Test
    void aCropPicksColumnsX0ToX1AndRowsY0ToY1ExactlyPixelForPixel() {
        // left 2, width 28: x0 = 1, x1 = 15. top 4, height 20: y0 = 2, y1 = 12.
        GreyFrame out = crop(2, 4, 28, 20).apply(ramp());

        assertThat(out.width()).isEqualTo(14);
        assertThat(out.height()).isEqualTo(10);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 14; x++) {
                assertThat(out.luma(x, y)).isEqualTo((y + 2) * 16 + x + 1);
            }
        }
    }

    @Test
    void anOddLeftRoundsTheFirstColumnUpAndAnOddWidthRoundsTheLastDown() {
        // left 3: x0 = (3 + 1) / 2 = 2. width 27: x1 = (3 + 27) / 2 = 15. Top 1: y0 = 1; height 29: y1 = 15.
        GreyFrame out = crop(3, 1, 27, 29).apply(ramp());

        assertThat(out.width()).isEqualTo(13);
        assertThat(out.height()).isEqualTo(14);
        assertThat(out.luma(0, 0)).isEqualTo(1 * 16 + 2);
        assertThat(out.luma(12, 13)).isEqualTo(14 * 16 + 14);
    }

    @Test
    void anOddWidthAloneDropsAHalfBox() {
        // left 0, width 31: x1 = 15, so the 16th column is dropped.
        GreyFrame out = crop(0, 0, 31, 32).apply(ramp());

        assertThat(out.width()).isEqualTo(15);
        assertThat(out.height()).isEqualTo(16);
        assertThat(out.luma(14, 15)).isEqualTo(255 - 1);
    }

    @Test
    void theFullFrameKeepsEveryPixel() {
        GreyFrame out = crop(0, 0, 32, 32).apply(ramp());

        assertThat(out.width()).isEqualTo(16);
        assertThat(out.height()).isEqualTo(16);
        assertThat(out.luma(15, 15)).isEqualTo(255);
    }

    @Test
    void theWidthThrowsAtSevenPixelsAndPassesAtEight() {
        assertThatCode(() -> crop(0, 0, 16, 32)).doesNotThrowAnyException();
        assertThatThrownBy(() -> crop(0, 0, 15, 32))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The crop is 7x16, under 8 px a side");
    }

    @Test
    void theHeightThrowsAtSevenPixelsAndPassesAtEight() {
        assertThatCode(() -> crop(0, 0, 32, 16)).doesNotThrowAnyException();
        assertThatThrownBy(() -> crop(0, 0, 32, 15))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The crop is 16x7, under 8 px a side");
    }

    @Test
    void anOddLeftCanTakeTheWidthUnderEightWhereAnEvenLeftDoesNot() {
        // left 1, width 16: x0 = 1, x1 = 8 (7 wide). left 1, width 17: x1 = 9 (8 wide).
        assertThatThrownBy(() -> crop(1, 0, 16, 32))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The crop is 7x16, under 8 px a side");
        assertThatCode(() -> crop(1, 0, 17, 32)).doesNotThrowAnyException();
        assertThatThrownBy(() -> crop(0, 1, 32, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The crop is 16x7, under 8 px a side");
        assertThatCode(() -> crop(0, 1, 32, 17)).doesNotThrowAnyException();
    }

    @Test
    void aNegativeLeftOrTopThrowsAndZeroPasses() {
        assertThatCode(() -> crop(0, 0, 32, 32)).doesNotThrowAnyException();
        assertThatThrownBy(() -> crop(-1, 0, 32, 32))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The area starts at (-1, 0), before the frame");
        assertThatThrownBy(() -> crop(0, -1, 32, 32))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The area starts at (0, -1), before the frame");
    }

    @Test
    void aZeroWidthOrHeightThrowsAndOnePassesTheInputCheck() {
        assertThatThrownBy(() -> crop(0, 0, 0, 32))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The area is 0x32, under one pixel");
        assertThatThrownBy(() -> crop(0, 0, 32, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The area is 32x0, under one pixel");
        // One pixel wide passes the input check and meets the 8 px rule instead.
        assertThatThrownBy(() -> crop(0, 0, 1, 32))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The crop is 0x16, under 8 px a side");
        assertThatThrownBy(() -> crop(0, 0, 32, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The crop is 16x0, under 8 px a side");
    }

    @Test
    void anAreaExactlyTwiceTheGreyFramePassesAndOnePixelPastThrows() {
        assertThatCode(() -> crop(0, 0, 32, 32)).doesNotThrowAnyException();
        assertThatCode(() -> crop(4, 4, 28, 28)).doesNotThrowAnyException();
        assertThatThrownBy(() -> crop(0, 0, 33, 32))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The area 0,0 33x32 runs past the 32x32 frame");
        assertThatThrownBy(() -> crop(0, 0, 32, 33))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The area 0,0 32x33 runs past the 32x32 frame");
        assertThatThrownBy(() -> crop(5, 0, 28, 32)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> crop(0, 5, 32, 28)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAreaThatOverflowsAnIntStillThrows() {
        assertThatThrownBy(() -> crop(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 32))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runs past");
    }

    @Test
    void aGreySizeOfMaxIntegerThrowsInsteadOfOverflowing() {
        assertThatThrownBy(() -> ActiveCrop.of(Integer.MAX_VALUE, 0, 20, 32, Integer.MAX_VALUE, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The grey frame size must be 1 to 4096 in each direction, was 2147483647x16");
    }

    @Test
    void theGreyWidthIsCheckedAtZeroOneMaxAndMaxPlusOne() {
        assertThatThrownBy(() -> ActiveCrop.of(0, 0, 32, 32, 0, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The grey frame size must be 1 to 4096 in each direction, was 0x16");
        assertThatThrownBy(() -> ActiveCrop.of(0, 0, 8, 32, 1, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runs past");
        assertThatCode(() -> ActiveCrop.of(0, 0, 8192, 32, GreyFrame.MAX_DIMENSION, 16))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> ActiveCrop.of(0, 0, 32, 32, GreyFrame.MAX_DIMENSION + 1, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The grey frame size must be 1 to 4096 in each direction, was 4097x16");
    }

    @Test
    void theGreyHeightIsCheckedAtZeroOneMaxAndMaxPlusOne() {
        assertThatThrownBy(() -> ActiveCrop.of(0, 0, 32, 32, 16, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The grey frame size must be 1 to 4096 in each direction, was 16x0");
        assertThatThrownBy(() -> ActiveCrop.of(0, 0, 32, 8, 16, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runs past");
        assertThatCode(() -> ActiveCrop.of(0, 0, 32, 8192, 16, GreyFrame.MAX_DIMENSION))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> ActiveCrop.of(0, 0, 32, 32, 16, GreyFrame.MAX_DIMENSION + 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The grey frame size must be 1 to 4096 in each direction, was 16x4097");
    }

    @Test
    void applyRefusesAGreyFrameOfTheWrongSize() {
        ActiveCrop crop = crop(0, 0, 32, 32);

        assertThatThrownBy(() -> crop.apply(new GreyFrame(15, 16, new byte[240])))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The frame is 15x16, expected 16x16");
        assertThatThrownBy(() -> crop.apply(new GreyFrame(16, 15, new byte[240])))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The frame is 16x15, expected 16x16");
    }
}

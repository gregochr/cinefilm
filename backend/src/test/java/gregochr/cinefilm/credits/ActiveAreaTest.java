package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.SyntheticFrames.Canvas;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActiveAreaTest {

    @Test
    void findsTheLetterboxBars() {
        List<GreyFrame> samples = List.of(
            SyntheticFrames.letterboxedNight(20), SyntheticFrames.letterboxedNight(30),
            SyntheticFrames.letterboxedNight(40));

        assertThat(ActiveArea.of(samples)).isEqualTo(new ActiveArea(0, 11, 160, 68));
    }

    @Test
    void findsPillarboxBars() {
        GreyFrame frame = new Canvas(160, 90, 0).rect(20, 0, 120, 90, 100).frame();

        assertThat(ActiveArea.of(List.of(frame))).isEqualTo(new ActiveArea(20, 0, 120, 90));
    }

    @Test
    void findsBarsOnAllFourSides() {
        GreyFrame frame = new Canvas(160, 90, 0).rect(10, 5, 140, 80, 100).frame();

        assertThat(ActiveArea.of(List.of(frame))).isEqualTo(new ActiveArea(10, 5, 140, 80));
    }

    @Test
    void aFrameWithoutBarsIsTheWholeFrame() {
        assertThat(ActiveArea.of(List.of(SyntheticFrames.picture()))).isEqualTo(new ActiveArea(0, 0, 160, 90));
    }

    @Test
    void anAllBlackFrameIsTheWholeFrame() {
        assertThat(ActiveArea.of(List.of(SyntheticFrames.black(), SyntheticFrames.black())))
            .isEqualTo(new ActiveArea(0, 0, 160, 90));
    }

    @Test
    void aRowIsOnlyABarWhenItIsBlackInEverySample() {
        GreyFrame dark = new Canvas(160, 90, 0).rect(0, 10, 160, 70, 100).frame();
        GreyFrame lit = new Canvas(160, 90, 0).rect(0, 10, 160, 70, 100).rect(0, 5, 160, 1, 200).frame();

        assertThat(ActiveArea.of(List.of(dark))).isEqualTo(new ActiveArea(0, 10, 160, 70));
        assertThat(ActiveArea.of(List.of(dark, lit))).isEqualTo(new ActiveArea(0, 5, 160, 75));
    }

    @Test
    void aBlackRowInsideThePictureIsNotABar() {
        GreyFrame frame = new Canvas(160, 90, 100).rect(0, 0, 160, 10, 0).rect(0, 40, 160, 5, 0).frame();

        assertThat(ActiveArea.of(List.of(frame))).isEqualTo(new ActiveArea(0, 10, 160, 80));
    }

    @Test
    void barLumaIs18AndNineteenIsPicture() {
        GreyFrame limitedRangeBlack = new Canvas(160, 90, 18).rect(0, 20, 160, 50, 100).frame();
        GreyFrame lifted = new Canvas(160, 90, 19).rect(0, 20, 160, 50, 100).frame();

        assertThat(ActiveArea.of(List.of(limitedRangeBlack))).isEqualTo(new ActiveArea(0, 20, 160, 50));
        assertThat(ActiveArea.of(List.of(lifted))).isEqualTo(new ActiveArea(0, 0, 160, 90));
    }

    @Test
    void aBarToleratesTwoTenthsOfAPercentStrayPixels() {
        Canvas oneStray = new Canvas(500, 100, 0).rect(0, 20, 500, 60, 100).rect(0, 19, 1, 1, 255);
        Canvas twoStray = new Canvas(500, 100, 0).rect(0, 20, 500, 60, 100).rect(0, 19, 2, 1, 255);

        assertThat(ActiveArea.of(List.of(oneStray.frame()))).isEqualTo(new ActiveArea(0, 20, 500, 60));
        assertThat(ActiveArea.of(List.of(twoStray.frame()))).isEqualTo(new ActiveArea(0, 19, 500, 61));
    }

    @Test
    void halfTheFrameIsPlausibleAndLessIsNot() {
        GreyFrame half = new Canvas(160, 100, 0).rect(0, 25, 160, 50, 100).frame();
        GreyFrame lessThanHalf = new Canvas(160, 100, 0).rect(0, 26, 160, 48, 100).frame();
        GreyFrame narrowHalf = new Canvas(160, 100, 0).rect(40, 0, 80, 100, 100).frame();
        GreyFrame narrowerThanHalf = new Canvas(160, 100, 0).rect(41, 0, 78, 100, 100).frame();

        assertThat(ActiveArea.of(List.of(half))).isEqualTo(new ActiveArea(0, 25, 160, 50));
        assertThat(ActiveArea.of(List.of(lessThanHalf))).isEqualTo(new ActiveArea(0, 0, 160, 100));
        assertThat(ActiveArea.of(List.of(narrowHalf))).isEqualTo(new ActiveArea(40, 0, 80, 100));
        assertThat(ActiveArea.of(List.of(narrowerThanHalf))).isEqualTo(new ActiveArea(0, 0, 160, 100));
    }

    @Test
    void wholeCoversTheFrame() {
        assertThat(ActiveArea.whole(SyntheticFrames.black())).isEqualTo(new ActiveArea(0, 0, 160, 90));
    }

    @Test
    void speckleInEveryRowButNoColumnIsNotAPicture() {
        // Each row has three strays (over 0.2% of 1000) in one of the two samples, but no column has more than one
        // (the most a 500-pixel column tolerates), so every column is a bar and the whole frame is returned.
        Canvas first = new Canvas(1000, 500, 0);
        Canvas second = new Canvas(1000, 500, 0);
        for (int row = 0; row < 250; row++) {
            first.rect(3 * row, row, 3, 1, 255);
            second.rect(3 * row, row + 250, 3, 1, 255);
        }

        assertThat(ActiveArea.of(List.of(first.frame(), second.frame()))).isEqualTo(new ActiveArea(0, 0, 1000, 500));
    }

    @Test
    void refusesNoSamplesAndMixedSizes() {
        assertThatThrownBy(() -> ActiveArea.of(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("At least one sample frame is needed");
        assertThatThrownBy(() -> ActiveArea.of(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("At least one sample frame is needed");
        GreyFrame other = new GreyFrame(10, 10, new byte[100]);
        List<GreyFrame> mixed = List.of(SyntheticFrames.black(), other);
        assertThatThrownBy(() -> ActiveArea.of(mixed))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Sample frames must all be 160x90");
        List<GreyFrame> shorter = List.of(SyntheticFrames.black(), new GreyFrame(160, 80, new byte[160 * 80]));
        assertThatThrownBy(() -> ActiveArea.of(shorter))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Sample frames must all be 160x90");
    }
}

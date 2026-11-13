package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.SyntheticFrames.Canvas;
import java.util.List;
import org.junit.jupiter.api.Test;

class PeakLumaTest {

    private static final ActiveArea WHOLE = new ActiveArea(0, 0, 40, 25);

    /** A 40x25 frame (1000 pixels) with {@code bright} pixels at 200 and the rest at 10. */
    private static GreyFrame withBright(int bright) {
        Canvas canvas = new Canvas(40, 25, 10);
        for (int i = 0; i < bright; i++) {
            canvas.rect(i % 40, i / 40, 1, 1, 200);
        }
        return canvas.frame();
    }

    @Test
    void isThe99Point5thPercentile() {
        assertThat(PeakLuma.of(List.of(withBright(6)), WHOLE)).isEqualTo(200);
    }

    @Test
    void fewerThanHalfAPercentOfBrightPixelsDoNotSetThePeak() {
        assertThat(PeakLuma.of(List.of(withBright(5)), WHOLE)).isEqualTo(60);
    }

    @Test
    void whiteTextOnBlackIsSdrPeak() {
        List<GreyFrame> frames = List.of(SyntheticFrames.lines(0, 235, 12, 10, 30, 50, 70));

        assertThat(PeakLuma.of(frames, new ActiveArea(0, 0, 160, 90))).isEqualTo(235);
    }

    @Test
    void dimHdrTextSetsADimPeak() {
        List<GreyFrame> frames = List.of(SyntheticFrames.lines(8, 145, 12, 10, 30, 50, 70));

        assertThat(PeakLuma.of(frames, new ActiveArea(0, 0, 160, 90))).isEqualTo(145);
    }

    @Test
    void anAllDarkWindowIsFlooredAt60() {
        assertThat(PeakLuma.of(List.of(SyntheticFrames.black(), SyntheticFrames.fill(30)),
            new ActiveArea(0, 0, 160, 90))).isEqualTo(60);
    }

    @Test
    void aWindowAbove60IsNotRaisedToTheFloor() {
        assertThat(PeakLuma.of(List.of(SyntheticFrames.fill(61)), new ActiveArea(0, 0, 160, 90))).isEqualTo(61);
        assertThat(PeakLuma.of(List.of(SyntheticFrames.fill(60)), new ActiveArea(0, 0, 160, 90))).isEqualTo(60);
    }

    @Test
    void onlyThePixelsInsideTheAreaCount() {
        GreyFrame frame = new Canvas(40, 25, 10).rect(0, 0, 40, 5, 250).frame();

        assertThat(PeakLuma.of(List.of(frame), new ActiveArea(0, 5, 40, 20))).isEqualTo(60);
        assertThat(PeakLuma.of(List.of(frame), WHOLE)).isEqualTo(250);
    }

    @Test
    void pixelsPoolAcrossFrames() {
        // 10 bright of 2000 pixels is exactly 0.5%: not enough to set the peak. 11 is.
        assertThat(PeakLuma.of(List.of(withBright(5), withBright(5)), WHOLE)).isEqualTo(60);
        assertThat(PeakLuma.of(List.of(withBright(6), withBright(5)), WHOLE)).isEqualTo(200);
    }

    @Test
    void aWhiteFrameHasAPeakOf255() {
        assertThat(PeakLuma.of(List.of(SyntheticFrames.fill(255)), new ActiveArea(0, 0, 160, 90))).isEqualTo(255);
    }

    @Test
    void noFramesIsTheFloor() {
        assertThat(PeakLuma.of(List.of(), WHOLE)).isEqualTo(60);
    }
}

package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import gregochr.cinefilm.credits.SyntheticFrames.Canvas;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameFeaturesTest {

    private static final ActiveArea WHOLE = new ActiveArea(0, 0, 160, 90);

    private static FrameClass classify(GreyFrame frame, ActiveArea area, int peak) {
        return FrameFeatures.measure(frame, area, peak).frameClass();
    }

    @Test
    void aBlackFrameIsBlack() {
        FrameFeatures features = FrameFeatures.measure(SyntheticFrames.black(), WHOLE, 235);

        assertThat(features.darkShare()).isEqualTo(1.0);
        assertThat(features.brightShare()).isEqualTo(0.0);
        assertThat(features.textLike()).isFalse();
        assertThat(features.frameClass()).isEqualTo(FrameClass.BLACK);
    }

    @Test
    void limitedRangeBlackIsStillBlackWhenThePeakIsLow() {
        assertThat(classify(SyntheticFrames.fill(16), WHOLE, 60)).isEqualTo(FrameClass.BLACK);
    }

    @Test
    void whiteLinesOnBlackAreTextOnDark() {
        // Four lines of 12 blocks, each block 3 wide and 4 tall: 576 bright pixels.
        GreyFrame frame = SyntheticFrames.lines(0, 235, 12, 10, 30, 50, 70);

        FrameFeatures features = FrameFeatures.measure(frame, WHOLE, 235);

        assertThat(features.brightShare()).isEqualTo(576 / SyntheticFrames.PIXELS, offset(1e-12));
        assertThat(features.darkShare()).isEqualTo(1 - 576 / SyntheticFrames.PIXELS, offset(1e-12));
        assertThat(features.textLike()).isTrue();
        assertThat(features.frameClass()).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void aMidTonePictureIsPicture() {
        FrameFeatures features = FrameFeatures.measure(SyntheticFrames.picture(), WHOLE, 235);

        assertThat(features.darkShare()).isEqualTo(0.0);
        assertThat(features.frameClass()).isEqualTo(FrameClass.PICTURE);
    }

    @Test
    void aColourTitleSequenceIsPicture() {
        assertThat(classify(SyntheticFrames.title(), WHOLE, 235)).isEqualTo(FrameClass.PICTURE);
    }

    @Test
    void aCrawlFrameIsTextOnDark() {
        assertThat(classify(SyntheticFrames.crawl(5, 3), WHOLE, 235)).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void aCardIsTextOnDark() {
        assertThat(classify(SyntheticFrames.card(2), WHOLE, 235)).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void dimHdrTextIsTextOnDarkAgainstItsOwnPeak() {
        List<GreyFrame> window = List.of(SyntheticFrames.lines(8, 145, 12, 10, 30, 50, 70));
        int peak = PeakLuma.of(window, WHOLE);

        assertThat(peak).isEqualTo(145);
        assertThat(classify(window.getFirst(), WHOLE, peak)).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void theSameDimTextAgainstAnSdrPeakIsNotText() {
        GreyFrame frame = SyntheticFrames.lines(8, 145, 12, 10, 30, 50, 70);

        assertThat(FrameFeatures.measure(frame, WHOLE, 235).brightShare()).isEqualTo(0.0);
        assertThat(classify(frame, WHOLE, 235)).isEqualTo(FrameClass.PICTURE);
    }

    @Test
    void letterboxBarsDoNotPushANightSceneIntoTextOnDark() {
        List<GreyFrame> samples = List.of(SyntheticFrames.letterboxedNight(20), SyntheticFrames.letterboxedNight(30));
        ActiveArea area = ActiveArea.of(samples);
        GreyFrame night = SyntheticFrames.letterboxedNight(20);

        assertThat(area).isEqualTo(new ActiveArea(0, 11, 160, 68));
        assertThat(FrameFeatures.measure(night, area, 230).darkShare()).isEqualTo(8052 / 10880.0, offset(1e-12));
        assertThat(classify(night, area, 230)).isEqualTo(FrameClass.PICTURE);
        // Without the active area the bars count as dark and the windows look like text: the failure being avoided.
        assertThat(classify(night, WHOLE, 230)).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void aStarfieldIsBlackAndNeverTextLike() {
        FrameFeatures features = FrameFeatures.measure(SyntheticFrames.starfield(), WHOLE, 230);

        assertThat(features.textLike()).isFalse();
        assertThat(features.frameClass()).isEqualTo(FrameClass.BLACK);
    }

    @Test
    void textAgainstTheLeftEdgeIsNotCentral() {
        Canvas canvas = new Canvas(160, 90, 0);
        for (int top = 5; top < 70; top += 15) {
            canvas.line(top, 8, 235, 0, 3, 0);
        }

        FrameFeatures features = FrameFeatures.measure(canvas.frame(), WHOLE, 235);

        assertThat(features.brightShare()).isEqualTo(480 / SyntheticFrames.PIXELS, offset(1e-12));
        assertThat(features.textLike()).isFalse();
        assertThat(features.frameClass()).isEqualTo(FrameClass.PICTURE);
    }

    @Test
    void thickBlocksAreNotThinRuns() {
        // Bright runs of 10 pixels are over the 9-pixel limit (6% of 160).
        GreyFrame frame = new Canvas(160, 90, 0).line(10, 4, 235, 40, 10, 0).line(30, 4, 235, 40, 10, 0)
            .line(50, 4, 235, 40, 10, 0).frame();
        GreyFrame thin = new Canvas(160, 90, 0).line(10, 4, 235, 40, 9, 0).line(30, 4, 235, 40, 9, 0)
            .line(50, 4, 235, 40, 9, 0).frame();

        assertThat(classify(frame, WHOLE, 235)).isEqualTo(FrameClass.PICTURE);
        assertThat(classify(thin, WHOLE, 235)).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void softEdgesAreNotSharp() {
        GreyFrame soft = new Canvas(160, 90, 0).line(10, 8, 235, 40, 3, 130).line(30, 8, 235, 40, 3, 130)
            .line(50, 8, 235, 40, 3, 130).frame();
        GreyFrame crisp = new Canvas(160, 90, 0).line(10, 8, 235, 40, 3, 117).line(30, 8, 235, 40, 3, 117)
            .line(50, 8, 235, 40, 3, 117).frame();

        // A halo of 130 is above half the peak (117.5), so the edge is not sharp; 117 is not.
        assertThat(classify(soft, WHOLE, 235)).isEqualTo(FrameClass.PICTURE);
        assertThat(classify(crisp, WHOLE, 235)).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void aBlockTouchingTheRightEdgeIsNotSharpButTheLineStillIsText() {
        Canvas canvas = new Canvas(160, 90, 0);
        for (int top = 5; top < 70; top += 20) {
            canvas.line(top, 26, 235, 32, 3, 0);
        }

        assertThat(classify(canvas.frame(), WHOLE, 235)).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void aSoftRightEdgeAloneIsNotSharp() {
        Canvas canvas = new Canvas(160, 90, 0);
        for (int top = 5; top < 70; top += 20) {
            canvas.line(top, 10, 235);
            for (int k = 0; k < 10; k++) {
                canvas.rect(40 + 5 * k + 3, top, 1, 4, 130);
            }
        }

        assertThat(classify(canvas.frame(), WHOLE, 235)).isEqualTo(FrameClass.PICTURE);
    }

    @Test
    void aRowNeedsThreeRunsToBeALineOfText() {
        Canvas two = new Canvas(160, 90, 0);
        Canvas three = new Canvas(160, 90, 0);
        for (int top = 5; top < 70; top += 15) {
            two.line(top, 2, 235, 50, 9, 0);
            three.line(top, 3, 235, 50, 9, 0);
        }

        // Five lines of two 9-pixel blocks: 360 bright pixels, dark share 0.975, so only the runs decide.
        assertThat(FrameFeatures.measure(two.frame(), WHOLE, 235).brightShare())
            .isEqualTo(360 / SyntheticFrames.PIXELS, offset(1e-12));
        assertThat(classify(two.frame(), WHOLE, 235)).isEqualTo(FrameClass.PICTURE);
        assertThat(classify(three.frame(), WHOLE, 235)).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void measuresOnlyInsideTheActiveArea() {
        GreyFrame frame = new Canvas(160, 90, 0).rect(0, 0, 160, 11, 255).frame();

        FrameFeatures whole = FrameFeatures.measure(frame, WHOLE, 235);
        FrameFeatures inside = FrameFeatures.measure(frame, new ActiveArea(0, 11, 160, 79), 235);

        assertThat(whole.brightShare()).isEqualTo(11 / 90.0, offset(1e-12));
        assertThat(inside.brightShare()).isEqualTo(0.0);
        assertThat(inside.frameClass()).isEqualTo(FrameClass.BLACK);
    }

    @Test
    void blackStartsAtADarkShareOf098() {
        assertThat(new FrameFeatures(0.98, 0.0, false).frameClass()).isEqualTo(FrameClass.BLACK);
        assertThat(new FrameFeatures(0.9799, 0.0, false).frameClass()).isEqualTo(FrameClass.PICTURE);
        assertThat(new FrameFeatures(0.9799, 0.01, true).frameClass()).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void textOnDarkStartsAtADarkShareOf080() {
        assertThat(new FrameFeatures(0.80, 0.05, true).frameClass()).isEqualTo(FrameClass.TEXT_ON_DARK);
        assertThat(new FrameFeatures(0.7999, 0.05, true).frameClass()).isEqualTo(FrameClass.PICTURE);
    }

    @Test
    void textOnDarkNeedsABrightShareFrom0002To020() {
        assertThat(new FrameFeatures(0.9, 0.002, true).frameClass()).isEqualTo(FrameClass.TEXT_ON_DARK);
        assertThat(new FrameFeatures(0.9, 0.0019, true).frameClass()).isEqualTo(FrameClass.PICTURE);
        assertThat(new FrameFeatures(0.9, 0.20, true).frameClass()).isEqualTo(FrameClass.TEXT_ON_DARK);
        assertThat(new FrameFeatures(0.9, 0.2001, true).frameClass()).isEqualTo(FrameClass.PICTURE);
    }

    @Test
    void textOnDarkNeedsTextLikeBrightPixels() {
        assertThat(new FrameFeatures(0.9, 0.05, false).frameClass()).isEqualTo(FrameClass.PICTURE);
    }
}

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
    void aSparseOneLineCardIsTextOnDarkNotBlack() {
        FrameFeatures features = FrameFeatures.measure(SyntheticFrames.oneLineCard(), WHOLE, 235);

        assertThat(features.brightShare()).isEqualTo(96 / SyntheticFrames.PIXELS, offset(1e-12));
        assertThat(features.darkShare()).isGreaterThan(0.98);
        assertThat(features.frameClass()).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void aFewSpecksOnBlackStayBlack() {
        GreyFrame specks = new Canvas(160, 90, 0).rect(30, 20, 1, 1, 255).rect(100, 60, 2, 1, 255)
            .rect(70, 40, 1, 1, 200).frame();

        FrameFeatures features = FrameFeatures.measure(specks, WHOLE, 235);

        assertThat(features.textLike()).isFalse();
        assertThat(features.frameClass()).isEqualTo(FrameClass.BLACK);
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
    void thinGreyTextFarBelowTheWindowsPeakIsTextOnDarkAgainstItsOwnLimit() {
        // Text at 145 in a window whose peak is 235: its own 99.9th percentile (145) sets the limit, and 145 is
        // above half the peak (117), so the clamp leaves it alone.
        GreyFrame frame = SyntheticFrames.lines(8, 145, 12, 10, 30, 50, 70);

        FrameFeatures features = FrameFeatures.measure(frame, WHOLE, 235);

        assertThat(features.brightShare()).isEqualTo(576 / SyntheticFrames.PIXELS, offset(1e-12));
        assertThat(classify(frame, WHOLE, 235)).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void textBelowHalfTheWindowsPeakIsStillNotText() {
        // 70 is under 65% of the lowest text limit (peak / 2 = 117), which is 76.
        GreyFrame frame = SyntheticFrames.lines(8, 70, 12, 10, 30, 50, 70);

        assertThat(FrameFeatures.measure(frame, WHOLE, 235).brightShare()).isEqualTo(0.0);
        assertThat(classify(frame, WHOLE, 235)).isEqualTo(FrameClass.PICTURE);
    }

    @Test
    void theTextLimitNeverDropsBelowHalfThePeak() {
        // Peak 235: the lowest limit is 117 and its bright limit 76.05, so 77 is bright and 76 is not. Without the
        // clamp the frame's own limit (76 or 77) would make both bright.
        assertThat(FrameFeatures.measure(SyntheticFrames.lines(0, 77, 12, 10, 30, 50, 70), WHOLE, 235).brightShare())
            .isEqualTo(576 / SyntheticFrames.PIXELS, offset(1e-12));
        assertThat(FrameFeatures.measure(SyntheticFrames.lines(0, 76, 12, 10, 30, 50, 70), WHOLE, 235).brightShare())
            .isEqualTo(0.0);
        // An odd peak halves by integer division: 235 / 2 is 117, not 117.5.
        assertThat(FrameFeatures.measure(SyntheticFrames.lines(0, 117, 12, 10, 30, 50, 70), WHOLE, 235).frameClass())
            .isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void theTextLimitNeverRisesAboveTheWindowsPeak() {
        // Window peak 200, text at 235 and fainter lines at 140. Clamped to 200 the bright limit is 130, so both
        // kinds of line are bright; unclamped (235) it would be 152.75 and the faint lines would not be.
        GreyFrame frame = new Canvas(160, 90, 0).line(10, 12, 235).line(30, 12, 235).line(50, 12, 140)
            .line(70, 12, 140).frame();

        assertThat(FrameFeatures.measure(frame, WHOLE, 200).brightShare())
            .isEqualTo(576 / SyntheticFrames.PIXELS, offset(1e-12));
    }

    @Test
    void aTextLimitAtExactlyThePeakIsTheSameAsTheWindowsPeak() {
        GreyFrame frame = SyntheticFrames.lines(0, 235, 12, 10, 30, 50, 70);

        assertThat(FrameFeatures.measure(frame, WHOLE, 235).brightShare())
            .isEqualTo(576 / SyntheticFrames.PIXELS, offset(1e-12));
    }

    @Test
    void aFrameUnderTheTextDarkShareKeepsTheWindowsPeakAsItsLimit() {
        // A grey band takes the dark share under 70%, so the frame measures against the window's peak (235), and
        // text at 145 (under its bright limit of 152.75) is not bright.
        Canvas canvas = new Canvas(160, 90, 0).rect(0, 0, 160, 30, 100);
        for (int top = 40; top < 80; top += 10) {
            canvas.line(top, 12, 145);
        }
        GreyFrame frame = canvas.frame();

        assertThat(FrameFeatures.measure(frame, WHOLE, 235).darkShare()).isLessThan(0.70);
        assertThat(FrameFeatures.measure(frame, WHOLE, 235).brightShare()).isEqualTo(0.0);
    }

    @Test
    void whiteTextWhoseEdgesAreAntiAliasedScoringUnderThePhaseOneShareIsTextOnDark() {
        // Four lines of text (576 bright pixels) plus two thick bright blobs (300): 66% of the bright pixels are
        // text, which the phase 1 share of 0.8 refused and 0.3 accepts.
        Canvas canvas = new Canvas(160, 90, 0).rect(10, 10, 15, 10, 235).rect(130, 10, 15, 10, 235);
        for (int top = 30; top < 70; top += 10) {
            canvas.line(top, 12, 235);
        }

        FrameFeatures features = FrameFeatures.measure(canvas.frame(), WHOLE, 235);

        assertThat(features.textLike()).isTrue();
        assertThat(features.brightShare()).isEqualTo(876 / SyntheticFrames.PIXELS, offset(1e-12));
        assertThat(features.frameClass()).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void aTextLikeShareOfExactlyThirtyPercentIsTextLikeAndJustUnderIsNot() {
        // Five lines of 12 blocks (720 text pixels), between two thick blobs (840 pixels each) that are the rest of
        // the bright pixels: 720 / 2400 is 0.30 exactly; one more blob column drops it under.
        Canvas exactly = new Canvas(160, 90, 0).rect(5, 5, 14, 60, 235).rect(140, 5, 14, 60, 235);
        Canvas under = new Canvas(160, 90, 0).rect(5, 5, 14, 60, 235).rect(140, 5, 15, 60, 235);
        for (int top = 5; top < 55; top += 10) {
            exactly.line(top, 12, 235, 40, 3, 0);
            under.line(top, 12, 235, 40, 3, 0);
        }

        assertThat(FrameFeatures.measure(exactly.frame(), WHOLE, 235).textLike()).isTrue();
        assertThat(FrameFeatures.measure(under.frame(), WHOLE, 235).textLike()).isFalse();
    }

    @Test
    void denseColumnsThatLeaveOnly75PercentOfTheFrameDarkAreTextOnDark() {
        // Eight lines of fifteen 3-pixel blocks, each in a 100-grey halo: 3,600 pixels are not dark.
        Canvas canvas = new Canvas(160, 90, 0);
        for (int top = 8; top < 88; top += 10) {
            canvas.line(top, 15, 235, 40, 3, 100);
        }

        FrameFeatures features = FrameFeatures.measure(canvas.frame(), WHOLE, 235);

        assertThat(features.darkShare()).isEqualTo(0.75, offset(1e-12));
        assertThat(features.textLike()).isTrue();
        assertThat(features.frameClass()).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void burnedInSubtitlesInTheBottomBandOfADarkSceneAreNotTextLike() {
        // Two lines of subtitles at rows 70 to 73 and 78 to 81 of a dark (luma 10) scene.
        GreyFrame frame = new Canvas(160, 90, 10).line(70, 14, 235).line(78, 12, 235).frame();

        FrameFeatures features = FrameFeatures.measure(frame, WHOLE, 235);

        assertThat(features.brightShare()).isGreaterThan(0.0);
        assertThat(features.textLike()).isFalse();
        assertThat(features.frameClass()).isNotEqualTo(FrameClass.TEXT_ON_DARK);
    }

    /** Four lines of 12 blocks in the bottom band (rows 63 and below) and one in the rest, of {@code blocks}. */
    private static GreyFrame subtitlesWithOneLineAbove(int blocks) {
        return new Canvas(160, 90, 0).line(64, 12, 235).line(70, 12, 235).line(76, 12, 235).line(82, 12, 235)
            .line(20, blocks, 235).frame();
    }

    @Test
    void textThatIsExactlyEightyPercentInTheBottomBandIsNotTextLike() {
        // 576 of 720 text pixels are in the band.
        assertThat(FrameFeatures.measure(subtitlesWithOneLineAbove(12), WHOLE, 235).textLike()).isFalse();
    }

    @Test
    void textThatIsJustUnderEightyPercentInTheBottomBandIsTextLike() {
        // 576 of 732 text pixels (78.7%).
        assertThat(FrameFeatures.measure(subtitlesWithOneLineAbove(13), WHOLE, 235).textLike()).isTrue();
    }

    @Test
    void theBottomBandStartsAtSeventyPercentOfTheActiveHeight() {
        // Active area of 80 rows from row 5: the band starts at row 5 + 56 = 61. Lines at rows 61 and below are in
        // it; a line at rows 56 to 59 is not.
        ActiveArea area = new ActiveArea(0, 5, 160, 80);
        GreyFrame inside = new Canvas(160, 90, 0).line(61, 12, 235).line(66, 12, 235).line(71, 12, 235).frame();
        GreyFrame outside = new Canvas(160, 90, 0).line(56, 12, 235).line(66, 12, 235).line(71, 12, 235).frame();

        assertThat(FrameFeatures.measure(inside, area, 235).textLike()).isFalse();
        assertThat(FrameFeatures.measure(outside, area, 235).textLike()).isTrue();
    }

    @Test
    void letterboxBarsDoNotPushANightSceneWithAWiderLitStreetIntoTextOnDark() {
        List<GreyFrame> samples = List.of(SyntheticFrames.letterboxedNightWithAWiderStreet(20),
            SyntheticFrames.letterboxedNightWithAWiderStreet(30));
        ActiveArea area = ActiveArea.of(samples);
        GreyFrame night = SyntheticFrames.letterboxedNightWithAWiderStreet(20);

        assertThat(area).isEqualTo(new ActiveArea(0, 11, 160, 68));
        assertThat(FrameFeatures.measure(night, area, 230).darkShare()).isEqualTo(6772 / 10880.0, offset(1e-12));
        assertThat(classify(night, area, 230)).isEqualTo(FrameClass.PICTURE);
        // Without the active area the bars count as dark and the windows look like text: the failure being avoided.
        assertThat(classify(night, WHOLE, 230)).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void theOriginalLetterboxedNightSceneIs74PercentDarkAndNowReadsAsTextOnDarkInsideItsActiveArea() {
        // Known consequence of TEXT_DARK_SHARE 0.70 (it was 0.80, which kept this scene PICTURE): three bright
        // windows on a dark sky look like three runs of text. Only motion (CreditsFinderTest) keeps it from FOUND.
        List<GreyFrame> samples = List.of(SyntheticFrames.letterboxedNight(20), SyntheticFrames.letterboxedNight(30));
        ActiveArea area = ActiveArea.of(samples);
        GreyFrame night = SyntheticFrames.letterboxedNight(20);

        assertThat(area).isEqualTo(new ActiveArea(0, 11, 160, 68));
        assertThat(FrameFeatures.measure(night, area, 230).darkShare()).isEqualTo(8052 / 10880.0, offset(1e-12));
        assertThat(classify(night, area, 230)).isEqualTo(FrameClass.TEXT_ON_DARK);
        assertThat(classify(night, WHOLE, 230)).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void aStarfieldIsBlackAndNeverTextLike() {
        FrameFeatures features = FrameFeatures.measure(SyntheticFrames.starfield(), WHOLE, 230);

        assertThat(features.textLike()).isFalse();
        assertThat(features.frameClass()).isEqualTo(FrameClass.BLACK);
    }

    @Test
    void eightBlocksFromTheLeftEdgeNowScoreAboveTheTextLikeShareAndAreTextOnDark() {
        // The phase 1 fixture: one block in three of the first eight is central (9 of 24 bright pixels a row, 0.375),
        // which 0.8 refused and 0.3 accepts. Known consequence; a still frame like this never moves.
        Canvas canvas = new Canvas(160, 90, 0);
        for (int top = 5; top < 70; top += 15) {
            canvas.line(top, 8, 235, 0, 3, 0);
        }

        FrameFeatures features = FrameFeatures.measure(canvas.frame(), WHOLE, 235);

        assertThat(features.brightShare()).isEqualTo(480 / SyntheticFrames.PIXELS, offset(1e-12));
        assertThat(features.textLike()).isTrue();
        assertThat(features.frameClass()).isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void textAgainstTheLeftEdgeIsNotCentral() {
        Canvas canvas = new Canvas(160, 90, 0);
        for (int top = 5; top < 70; top += 15) {
            canvas.line(top, 6, 235, 0, 3, 0);
        }

        FrameFeatures features = FrameFeatures.measure(canvas.frame(), WHOLE, 235);

        // Six blocks reach x = 28, so only one lies in the central band (from 24): 3 of 18 bright pixels a row.
        assertThat(features.brightShare()).isEqualTo(360 / SyntheticFrames.PIXELS, offset(1e-12));
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
        assertThat(new FrameFeatures(0.99, 0.01, true).frameClass()).isEqualTo(FrameClass.TEXT_ON_DARK);
        assertThat(new FrameFeatures(0.999, 0.0001, true).frameClass()).isEqualTo(FrameClass.BLACK);
        assertThat(new FrameFeatures(0.99, 0.01, false).frameClass()).isEqualTo(FrameClass.BLACK);
    }

    @Test
    void textOnDarkStartsAtADarkShareOf070() {
        assertThat(new FrameFeatures(0.70, 0.05, true).frameClass()).isEqualTo(FrameClass.TEXT_ON_DARK);
        assertThat(new FrameFeatures(0.6999, 0.05, true).frameClass()).isEqualTo(FrameClass.PICTURE);
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

package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.CreditsMotion.Kind;
import gregochr.cinefilm.credits.SyntheticFrames.DenseBuilder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreditsMotionTest {

    private static final ActiveArea WHOLE = new ActiveArea(0, 0, 160, 90);
    private static final int PEAK = 235;

    private static Kind detect(List<GreyFrame> frames) {
        return CreditsMotion.detect(frames, WHOLE, PEAK);
    }

    @Test
    void aSteadyCrawlIsACrawl() {
        assertThat(detect(new DenseBuilder().crawl(60, 3).frames())).isEqualTo(Kind.CRAWL);
    }

    @Test
    void aSlowCrawlOfOnePixelAFrameIsACrawl() {
        assertThat(detect(new DenseBuilder().crawl(60, 1).frames())).isEqualTo(Kind.CRAWL);
    }

    @Test
    void theFastestCrawlIsASixthOfTheHeightAFrame() {
        assertThat(detect(new DenseBuilder().crawl(60, 15).frames())).isEqualTo(Kind.CRAWL);
        assertThat(detect(new DenseBuilder().crawl(60, 16).frames())).isEqualTo(Kind.NONE);
    }

    @Test
    void aCrawlAfterAShortFadeIsStillACrawl() {
        List<GreyFrame> frames = new DenseBuilder().add(SyntheticFrames.black(), 20).crawl(40, 3).frames();

        assertThat(detect(frames)).isEqualTo(Kind.CRAWL);
    }

    @Test
    void aCrawlOneStepOverTheLimitWithPhaseOneLineWidthsNowAliasesOntoAnotherLineAndIsACrawl() {
        // Known consequence of CRAWL_MATCH_MAX_ERROR 0.25: at 16 px a frame lines 12 px apart can match the line
        // below (shift 4) when neighbouring widths are close. The alternating-width crawl used above is refused.
        List<GreyFrame> frames = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            frames.add(SyntheticFrames.crawlWithIrregularWidths(i, 16));
        }

        assertThat(detect(frames)).isEqualTo(Kind.CRAWL);
        assertThat(detect(new DenseBuilder().crawl(60, 16).frames())).isEqualTo(Kind.NONE);
    }

    @Test
    void anUnsteadyCrawlIsNotACrawl() {
        assertThat(detect(SyntheticFrames.unsteadyCrawl(60, 2, 8))).isEqualTo(Kind.NONE);
    }

    @Test
    void cardsThatChangeAbruptlyAreCards() {
        assertThat(detect(new DenseBuilder().cards(5, 12).frames())).isEqualTo(Kind.CARDS);
    }

    @Test
    void oneChangeIsNotCardsAndTwoAre() {
        assertThat(detect(new DenseBuilder().cards(2, 30).frames())).isEqualTo(Kind.NONE);
        assertThat(detect(new DenseBuilder().cards(3, 20).frames())).isEqualTo(Kind.CARDS);
    }

    @Test
    void textThatNeverMovesOrChangesIsNeither() {
        assertThat(detect(new DenseBuilder().add(SyntheticFrames.card(0), 60).frames())).isEqualTo(Kind.NONE);
    }

    @Test
    void anEpilogueCardThenPictureIsNeither() {
        List<GreyFrame> frames = new DenseBuilder().add(SyntheticFrames.card(0), 4)
            .add(SyntheticFrames.picture(), 56).frames();

        assertThat(detect(frames)).isEqualTo(Kind.NONE);
    }

    @Test
    void aStarfieldIsNeither() {
        assertThat(detect(new DenseBuilder().add(SyntheticFrames.starfield(), 60).frames())).isEqualTo(Kind.NONE);
    }

    @Test
    void blackIsNeither() {
        assertThat(detect(new DenseBuilder().add(SyntheticFrames.black(), 60).frames())).isEqualTo(Kind.NONE);
    }

    @Test
    void aPictureIsNeitherEvenWhenItChanges() {
        List<GreyFrame> frames = new DenseBuilder().add(SyntheticFrames.picture(), 30)
            .add(SyntheticFrames.title(), 30).frames();

        assertThat(detect(frames)).isEqualTo(Kind.NONE);
    }

    @Test
    void eightFramesAreEnoughAndSevenAreNot() {
        assertThat(detect(new DenseBuilder().crawl(8, 3).frames())).isEqualTo(Kind.CRAWL);
        assertThat(detect(new DenseBuilder().crawl(7, 3).frames())).isEqualTo(Kind.NONE);
    }

    @Test
    void textThatRepeatsItselfDownTheFrameButNeverMovesIsNotACrawl() {
        SyntheticFrames.Canvas canvas = new SyntheticFrames.Canvas(160, 90, 0);
        for (int line = 0; line < 9; line++) {
            canvas.line(5 + 10 * line, 12, SyntheticFrames.TEXT);
        }

        assertThat(detect(new DenseBuilder().add(canvas.frame(), 60).frames())).isEqualTo(Kind.NONE);
    }

    @Test
    void textThatAppearsOnlyAtTheBottomOfTheFrameIsNotACrawl() {
        GreyFrame low = new SyntheticFrames.Canvas(160, 90, 0).line(76, 14, SyntheticFrames.TEXT)
            .line(84, 14, SyntheticFrames.TEXT).frame();

        assertThat(detect(new DenseBuilder().add(SyntheticFrames.black(), 4).add(low, 4).frames()))
            .isEqualTo(Kind.NONE);
    }

    @Test
    void changesWithLessThanHalfTheFramesStillAreNotCards() {
        List<GreyFrame> frames = new DenseBuilder().add(SyntheticFrames.card(0), 1).add(SyntheticFrames.card(1), 2)
            .add(SyntheticFrames.card(2), 1).add(SyntheticFrames.card(3), 2).add(SyntheticFrames.card(4), 1)
            .add(SyntheticFrames.card(0), 1).frames();

        assertThat(frames).hasSize(8);
        assertThat(detect(frames)).isEqualTo(Kind.NONE);
    }

    @Test
    void changesIntoAndOutOfPictureAreNotCardChanges() {
        List<GreyFrame> frames = new DenseBuilder().add(SyntheticFrames.card(0), 12)
            .add(SyntheticFrames.picture(), 2).add(SyntheticFrames.card(1), 12).add(SyntheticFrames.picture(), 2)
            .add(SyntheticFrames.card(2), 12).frames();

        assertThat(detect(frames)).isEqualTo(Kind.NONE);
    }

    @Test
    void cardChangesAcrossBlackCutsAreCardsStartingAtTheFirstChange() {
        DenseBuilder builder = new DenseBuilder().add(SyntheticFrames.card(0), 12).add(SyntheticFrames.black(), 2)
            .add(SyntheticFrames.card(1), 12).add(SyntheticFrames.black(), 2).add(SyntheticFrames.card(2), 12);

        CreditsMotion.Motion motion = CreditsMotion.analyse(builder.frames(), WHOLE, PEAK);

        assertThat(motion.kind()).isEqualTo(Kind.CARDS);
        assertThat(motion.startIndex()).isEqualTo(14);
    }

    // ---- Phase 2b: the measured failures ----------------------------------------------------------------------

    @Test
    void aCrawlEnteringFromTheBottomIsACrawlStartingWhenTextIsAboveTheBottomBand() {
        List<GreyFrame> frames = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            frames.add(SyntheticFrames.crawlFromBottom(i, 3));
        }

        CreditsMotion.Motion motion = CreditsMotion.analyse(frames, WHOLE, PEAK);

        // The first frames show only subtitle-like text in the bottom band, so they are not text and the crawl is
        // found later, not lost.
        assertThat(motion.kind()).isEqualTo(Kind.CRAWL);
        assertThat(motion.startIndex()).isEqualTo(13);
        assertThat(FrameFeatures.measure(frames.getFirst(), WHOLE, PEAK).frameClass())
            .isNotEqualTo(FrameClass.TEXT_ON_DARK);
        assertThat(FrameFeatures.measure(frames.get(motion.startIndex()), WHOLE, PEAK).frameClass())
            .isEqualTo(FrameClass.TEXT_ON_DARK);
    }

    @Test
    void aCrawlOfSmallNoisyTextNeedsTheRowSmoothing() {
        List<GreyFrame> frames = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            frames.add(SyntheticFrames.noisyCrawl(i, 3, 0.9));
        }

        assertThat(detect(frames)).isEqualTo(Kind.CRAWL);
    }

    @Test
    void cardsThatFadeThroughBlackAreCardsEvenWhenTextIsAThirdOfTheFrames() {
        // One credit per card: only 24 pixels change between cards (0.0017), and 8 of 24 frames show text.
        DenseBuilder builder = new DenseBuilder();
        for (int name = 0; name < 5; name++) {
            builder.add(SyntheticFrames.creditCard(name), 8).add(SyntheticFrames.black(), 16);
        }

        assertThat(detect(builder.frames())).isEqualTo(Kind.CARDS);
    }

    /**
     * Three lines of text (108 bright pixels) with a rectangle of {@code extra} pixels (9 wide, 11 or 12 rows, the
     * last row shorter) beside them that is there for 8 frames and gone for 8, four times.
     */
    private static List<GreyFrame> cardWithABlinkingRectangle(int extra) {
        SyntheticFrames.Canvas with = new SyntheticFrames.Canvas(160, 90, 0).line(20, 3, SyntheticFrames.TEXT)
            .line(30, 3, SyntheticFrames.TEXT).line(50, 3, SyntheticFrames.TEXT);
        with.rect(100, 60, 9, extra / 9, SyntheticFrames.TEXT);
        if (extra % 9 > 0) {
            with.rect(100, 60 + extra / 9, extra % 9, 1, SyntheticFrames.TEXT);
        }
        GreyFrame without = new SyntheticFrames.Canvas(160, 90, 0).line(20, 3, SyntheticFrames.TEXT)
            .line(30, 3, SyntheticFrames.TEXT).line(50, 3, SyntheticFrames.TEXT).frame();
        DenseBuilder builder = new DenseBuilder();
        for (int i = 0; i < 4; i++) {
            builder.add(with.frame(), 8).add(without, 8);
        }
        return builder.frames();
    }

    @Test
    void aCardChangeMustReplaceMoreThanHalfOfTheLargerFramesBrightPixels() {
        // 108 pixels of text; a rectangle of 108 more is exactly half of the 216 bright pixels, which is not enough
        // (one floor of windows toggling beside a fixed floor); 109 is just over.
        assertThat(detect(cardWithABlinkingRectangle(108))).isEqualTo(Kind.NONE);
        assertThat(detect(cardWithABlinkingRectangle(109))).isEqualTo(Kind.CARDS);
    }

    private static final ActiveArea NIGHT = new ActiveArea(0, 11, 160, 68);

    @Test
    void aWindowSwitchingOnAndOffInANightSceneIsNotCards() {
        // A fourth window (36 pixels, 0.0033 of the picture) every 20 frames: above the absolute minimum, but about
        // 1.3% of the bright pixels (the lit street counts as bright too), so not a card change.
        List<GreyFrame> frames = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            frames.add(SyntheticFrames.nightWithFourthWindow(i / 20 % 2 == 1));
        }

        assertThat(FrameFeatures.measure(frames.getFirst(), NIGHT, 230).frameClass())
            .isEqualTo(FrameClass.TEXT_ON_DARK);
        assertThat(CreditsMotion.analyse(frames, NIGHT, 230)).isEqualTo(new CreditsMotion.Motion(Kind.NONE, -1));
    }

    @Test
    void aFigureSteppingSixPixelsEveryTwentyFramesInALitWindowSceneIsNotCards() {
        List<GreyFrame> frames = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            frames.add(SyntheticFrames.nightWithFigure(10 + 6 * (i / 20)));
        }

        assertThat(CreditsMotion.analyse(frames, NIGHT, 230)).isEqualTo(new CreditsMotion.Motion(Kind.NONE, -1));
    }

    @Test
    void framesWithSmallChangesInsideACardAreNotStillAndSoNotCards() {
        // Four cards of 12 frames; inside each, every other frame has a 10-pixel flicker (0.0007 of the frame): over
        // the 0.0005 still limit, under the 0.001 a change needs. No pair is still, so these are not cards.
        DenseBuilder builder = new DenseBuilder();
        for (int c = 0; c < 4; c++) {
            for (int i = 0; i < 6; i++) {
                builder.add(SyntheticFrames.card(c), 1)
                    .add(SyntheticFrames.withRect(SyntheticFrames.card(c), 150, 85, 5, 2, SyntheticFrames.TEXT), 1);
            }
        }

        assertThat(detect(builder.frames())).isEqualTo(Kind.NONE);
    }

    @Test
    void theRowProfilesCountOnlyPixelsAboveTheWindowsPeakLimitSoStaticGreyDoesNotBreakACrawl() {
        List<GreyFrame> frames = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            frames.add(SyntheticFrames.crawlWithGreyStripes(i, 3));
        }

        assertThat(detect(frames)).isEqualTo(Kind.CRAWL);
    }

    @Test
    void aLineLeavingAtTheTopEdgeIsACrawlBecauseTheFirstRowOfTheProfileHasItsNeighbourBelow() {
        // One line (12 blocks, 4 rows) starting at row 3 and rising a pixel a frame: after 3 frames it is cut by the
        // top edge. The smoothed profile's first row is its own count plus the row below; without that the match
        // error rises over 0.25.
        List<GreyFrame> frames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            frames.add(new SyntheticFrames.Canvas(160, 90, 0).line(3 - i, 12, SyntheticFrames.TEXT).frame());
        }

        assertThat(CreditsMotion.analyse(frames, WHOLE, PEAK)).isEqualTo(new CreditsMotion.Motion(Kind.CRAWL, 0));
    }

    @Test
    void aSingleFlickerWhoseMiddlePairsAreSmallIsOneEventNotTwoCardChanges() {
        // F (72 px of lights) -> F+G (90 more) -> F+G+g (15 more) -> F+g -> F: four consecutive changed pairs, two of
        // which pass the half-of-the-text guard (90 of 162 and 90 of 177) and two (15 px) do not. One event.
        DenseBuilder builder = new DenseBuilder().add(SyntheticFrames.floors(false, false), 30)
            .add(SyntheticFrames.floors(true, false), 1).add(SyntheticFrames.floors(true, true), 1)
            .add(SyntheticFrames.floors(false, true), 1).add(SyntheticFrames.floors(false, false), 30);

        assertThat(detect(builder.frames())).isEqualTo(Kind.NONE);
    }

    @Test
    void twoSeparateFlickersAreTwoEventsAndSoCards() {
        DenseBuilder builder = new DenseBuilder().add(SyntheticFrames.floors(false, false), 30)
            .add(SyntheticFrames.floors(true, false), 1).add(SyntheticFrames.floors(true, true), 1)
            .add(SyntheticFrames.floors(false, true), 1).add(SyntheticFrames.floors(false, false), 30)
            .add(SyntheticFrames.floors(true, false), 1).add(SyntheticFrames.floors(false, false), 30);

        assertThat(detect(builder.frames())).isEqualTo(Kind.CARDS);
    }

    @Test
    void aChangeUnderTheMinimumShareIsNotACardChangeEvenWhenItIsMostOfTheLightsThatChanged() {
        // 12 pixels of lights toggle (0.00083 of the frame, under 0.001): the guard would pass (12 > half of the 12
        // bright pixels counted), so only the absolute minimum rejects it.
        DenseBuilder builder = new DenseBuilder();
        for (int i = 0; i < 4; i++) {
            builder.add(SyntheticFrames.dimTextWithLights(false), 8).add(SyntheticFrames.dimTextWithLights(true), 8);
        }

        assertThat(FrameFeatures.measure(SyntheticFrames.dimTextWithLights(false), WHOLE, PEAK).frameClass())
            .isEqualTo(FrameClass.TEXT_ON_DARK);
        assertThat(FrameFeatures.measure(SyntheticFrames.dimTextWithLights(true), WHOLE, PEAK).frameClass())
            .isEqualTo(FrameClass.TEXT_ON_DARK);
        assertThat(detect(builder.frames())).isEqualTo(Kind.NONE);
    }

    @Test
    void aMergedRunStartsAtItsFirstGuardPassingPairAndCountsEvenWhenItsFirstPairFailsTheGuard() {
        // Frame 30 adds 15 px (F+g: a changed pair that fails the guard), frame 31 adds 90 (passes), frame 32 is F
        // again (passes). The run starts at 30 but its change lands at 31. A second plain flicker makes two events.
        DenseBuilder builder = new DenseBuilder().add(SyntheticFrames.floors(false, false), 30)
            .add(SyntheticFrames.floors(false, true), 1).add(SyntheticFrames.floors(true, true), 1)
            .add(SyntheticFrames.floors(false, false), 30).add(SyntheticFrames.floors(true, false), 1)
            .add(SyntheticFrames.floors(false, false), 30);

        assertThat(CreditsMotion.analyse(builder.frames(), WHOLE, PEAK))
            .isEqualTo(new CreditsMotion.Motion(Kind.CARDS, 31));
    }

    /** Lights of {@code pixels} pixels toggling every 8 frames beside dim text, four times each way. */
    private static List<GreyFrame> dimLightsToggling(int pixels) {
        DenseBuilder builder = new DenseBuilder();
        for (int i = 0; i < 4; i++) {
            builder.add(SyntheticFrames.dimTextWithDimLights(0), 8)
                .add(SyntheticFrames.dimTextWithDimLights(pixels), 8);
        }
        return builder.frames();
    }

    @Test
    void aChangeNeedsAThousandthOfThePixelsFourteenIsUnderAndFifteenIsOverWithTheGuardPassingBoth() {
        // 14 px is 0.00097 of the 14,400, 15 px is 0.00104; every changed pixel is a bright one, so the guard passes.
        assertThat(detect(dimLightsToggling(14))).isEqualTo(Kind.NONE);
        assertThat(detect(dimLightsToggling(15))).isEqualTo(Kind.CARDS);
    }

    @Test
    void aRealCardThatDiffersOnlyInOneNameIsNotCardsALateOrNothingResult() {
        // Two fixed lines and a name that changes: 24 of 108 bright pixels. The guard refuses it, which costs a
        // missed card sequence (late or nothing) and is never an early marker.
        DenseBuilder builder = new DenseBuilder();
        for (int name = 0; name < 5; name++) {
            builder.add(SyntheticFrames.creditCardWithAFixedBody(name), 8).add(SyntheticFrames.black(), 16);
        }

        assertThat(detect(builder.frames())).isEqualTo(Kind.NONE);
    }

    @Test
    void aSlowTiltOverLitWindowsIsNeither() {
        List<GreyFrame> frames = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            frames.add(SyntheticFrames.tiltedWindows(i));
        }

        assertThat(detect(frames)).isEqualTo(Kind.NONE);
    }

    @Test
    void aStillDarkSceneWithASmallFigureMovingIsNeither() {
        List<GreyFrame> frames = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            frames.add(SyntheticFrames.figure(40 + 2 * i % 60));
        }

        assertThat(detect(frames)).isEqualTo(Kind.NONE);
    }

    @Test
    void noFramesIsNeither() {
        assertThat(detect(List.of())).isEqualTo(Kind.NONE);
    }
}

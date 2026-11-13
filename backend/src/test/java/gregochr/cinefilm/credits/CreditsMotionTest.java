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
        assertThat(motion.startIndex()).isGreaterThan(0);
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

    /** A card with a {@code w} x {@code h} rectangle that is there for 8 frames and gone for 8, four times. */
    private static List<GreyFrame> cardWithAFlickeringRectangle(int w, int h) {
        SyntheticFrames.Canvas with = new SyntheticFrames.Canvas(160, 90, 0).line(20, 3, SyntheticFrames.TEXT)
            .line(30, 3, SyntheticFrames.TEXT).line(50, 3, SyntheticFrames.TEXT)
            .rect(60, 50, w, h, SyntheticFrames.TEXT);
        GreyFrame without = new SyntheticFrames.Canvas(160, 90, 0).line(20, 3, SyntheticFrames.TEXT)
            .line(30, 3, SyntheticFrames.TEXT).line(50, 3, SyntheticFrames.TEXT).frame();
        DenseBuilder builder = new DenseBuilder();
        for (int i = 0; i < 4; i++) {
            builder.add(with.frame(), 8).add(without, 8);
        }
        return builder.frames();
    }

    @Test
    void aCardChangeNeedsAtLeastAThousandthOfThePixelsToChange() {
        // 14 pixels is 0.00097 of the 14,400; 15 is 0.00104.
        assertThat(detect(cardWithAFlickeringRectangle(7, 2))).isEqualTo(Kind.NONE);
        assertThat(detect(cardWithAFlickeringRectangle(5, 3))).isEqualTo(Kind.CARDS);
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

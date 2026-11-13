package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.CreditsMotion.Kind;
import gregochr.cinefilm.credits.SyntheticFrames.DenseBuilder;
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
    void noFramesIsNeither() {
        assertThat(detect(List.of())).isEqualTo(Kind.NONE);
    }
}

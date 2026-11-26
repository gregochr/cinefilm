package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.OffFitsRule.Decision;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The decision whether a download already fits the film as it is: pure. */
class OffFitsRuleTest {

    /** Twelve cue starts, irregularly spaced so that only the right offset lines two files up. */
    private static final List<Long> STARTS = IntStream.range(0, 12)
        .mapToObj(i -> 60_000L + i * 37_000L + i * i * 911L % 5_000).toList();
    private static final TimingKeys.EmbeddedReference SAME = new TimingKeys.EmbeddedReference(STARTS, null);

    @Test
    void originalCuesThatFitTheFilmsTrackFitAsIs() {
        assertThat(OffFitsRule.decide(true, SAME, STARTS)).isEqualTo(Decision.FITS_AS_IS);
    }

    @Test
    void originalCuesThatFitAfterASmallConstantOffsetStillFitAsIs() {
        List<Long> shifted = STARTS.stream().map(start -> start + 1_500).toList();

        assertThat(OffFitsRule.decide(true, new TimingKeys.EmbeddedReference(shifted, null), STARTS))
            .isEqualTo(Decision.FITS_AS_IS);
    }

    @Test
    void originalCuesThatDriftAgainstTheTrackAreRetimed() {
        List<Long> drifted = STARTS.stream().map(start -> Math.round(start * 1.0427)).toList();

        assertThat(OffFitsRule.decide(true, new TimingKeys.EmbeddedReference(drifted, null), STARTS))
            .isEqualTo(Decision.RETIME);
    }

    @Test
    void tooFewCuesToFitAreRetimed() {
        assertThat(OffFitsRule.decide(true, SAME, STARTS.subList(0, 3))).isEqualTo(Decision.RETIME);
    }

    @Test
    void aTrackWithNoLinesIsRetimed() {
        assertThat(OffFitsRule.decide(true, new TimingKeys.EmbeddedReference(List.of(), null), STARTS))
            .isEqualTo(Decision.RETIME);
    }

    @Test
    void aFilmFileReadAndFoundToHaveNoEnglishTrackIsRetimedAtOnce() {
        assertThat(OffFitsRule.decide(true, new TimingKeys.EmbeddedReference(List.of(), "No English track"), STARTS))
            .isEqualTo(Decision.RETIME);
    }

    @Test
    void aFilmThatMayHaveATrackWaitsUntilItIsRead() {
        assertThat(OffFitsRule.decide(true, null, STARTS)).isEqualTo(Decision.WAIT);
    }

    @Test
    void aFilmThatCannotHaveATrackIsRetimedWithoutWaiting() {
        assertThat(OffFitsRule.decide(false, null, STARTS)).isEqualTo(Decision.RETIME);
    }
}

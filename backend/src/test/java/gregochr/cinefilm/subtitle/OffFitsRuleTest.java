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
        assertThat(OffFitsRule.decide(true, true, SAME, STARTS)).isEqualTo(Decision.FITS_AS_IS);
    }

    @Test
    void originalCuesThatFitAfterASmallConstantOffsetStillFitAsIs() {
        List<Long> shifted = STARTS.stream().map(start -> start + 1_500).toList();

        assertThat(OffFitsRule.decide(true, true, new TimingKeys.EmbeddedReference(shifted, null), STARTS))
            .isEqualTo(Decision.FITS_AS_IS);
    }

    @Test
    void originalCuesThatDriftAgainstTheTrackAreRetimed() {
        List<Long> drifted = STARTS.stream().map(start -> Math.round(start * 1.0427)).toList();

        assertThat(OffFitsRule.decide(true, true, new TimingKeys.EmbeddedReference(drifted, null), STARTS))
            .isEqualTo(Decision.RETIME);
    }

    @Test
    void tooFewCuesToFitAreRetimed() {
        assertThat(OffFitsRule.decide(true, true, SAME, STARTS.subList(0, 3))).isEqualTo(Decision.RETIME);
    }

    @Test
    void aTrackWithNoLinesIsRetimed() {
        assertThat(OffFitsRule.decide(true, true, new TimingKeys.EmbeddedReference(List.of(), null), STARTS))
            .isEqualTo(Decision.RETIME);
    }

    @Test
    void aFilmFileReadAndFoundToHaveNoEnglishTrackIsRetimedAtOnce() {
        TimingKeys.EmbeddedReference none = new TimingKeys.EmbeddedReference(List.of(), "No English track");

        assertThat(OffFitsRule.decide(true, true, none, STARTS)).isEqualTo(Decision.RETIME);
    }

    @Test
    void aForcedDownloadTheTimingCheckHasNotSettledWaits() {
        assertThat(OffFitsRule.decide(true, false, null, STARTS)).isEqualTo(Decision.WAIT);
        assertThat(OffFitsRule.decide(true, false, SAME, STARTS)).isEqualTo(Decision.WAIT);
    }

    @Test
    void aSettledDownloadWithNoEmbeddedStartsIsDecidedByTheRates() {
        assertThat(OffFitsRule.decide(true, true, null, STARTS)).isEqualTo(Decision.RETIME);
    }

    @Test
    void aDownloadTheTimingCheckNeverFitsIsDecidedByTheRatesAtOnce() {
        assertThat(OffFitsRule.decide(false, false, null, STARTS)).isEqualTo(Decision.RETIME);
        assertThat(OffFitsRule.decide(false, false, SAME, STARTS)).isEqualTo(Decision.RETIME);
    }
}

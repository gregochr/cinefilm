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

    private static OffFitsState track(List<Long> starts) {
        return OffFitsState.ready(starts, null);
    }

    @Test
    void originalCuesThatFitTheFilmsTrackFitAsIs() {
        assertThat(OffFitsRule.decide(track(STARTS), STARTS)).isEqualTo(Decision.FITS_AS_IS);
    }

    @Test
    void originalCuesThatFitAfterASmallConstantOffsetStillFitAsIs() {
        List<Long> shifted = STARTS.stream().map(start -> start + 1_500).toList();

        assertThat(OffFitsRule.decide(track(shifted), STARTS)).isEqualTo(Decision.FITS_AS_IS);
    }

    @Test
    void originalCuesThatDriftAgainstTheTrackAreRetimed() {
        List<Long> drifted = STARTS.stream().map(start -> Math.round(start * 1.0427)).toList();

        assertThat(OffFitsRule.decide(track(drifted), STARTS)).isEqualTo(Decision.RETIME);
    }

    @Test
    void tooFewCuesToFitAreRetimed() {
        assertThat(OffFitsRule.decide(track(STARTS), STARTS.subList(0, 3))).isEqualTo(Decision.RETIME);
    }

    @Test
    void aTrackWithNoLinesIsRetimed() {
        assertThat(OffFitsRule.decide(track(List.of()), STARTS)).isEqualTo(Decision.RETIME);
    }

    @Test
    void aFilmFileReadAndFoundToHaveNoEnglishTrackIsRetimed() {
        OffFitsState none = OffFitsState.ready(List.of(), "No English track");

        assertThat(OffFitsRule.decide(none, STARTS)).isEqualTo(Decision.RETIME);
        assertThat(OffFitsRule.decide(OffFitsState.ready(STARTS, "No English track"), STARTS))
            .isEqualTo(Decision.RETIME);
    }

    @Test
    void aDownloadTheTimingCheckWillFitButHasNotYetWaits() {
        assertThat(OffFitsRule.decide(OffFitsState.PENDING, STARTS)).isEqualTo(Decision.WAIT);
    }

    @Test
    void aDownloadTheTimingCheckWillNeverFitIsDecidedByTheRates() {
        assertThat(OffFitsRule.decide(OffFitsState.NEVER, STARTS)).isEqualTo(Decision.RETIME);
    }
}

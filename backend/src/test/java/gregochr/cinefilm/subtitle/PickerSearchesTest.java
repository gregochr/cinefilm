package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.PickerSearches.Offered;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What the picker's search cache remembers, for how long, and for whom. */
class PickerSearchesTest {

    private static final String IMDB = "tt0936501";

    /** A clock the test moves. */
    private static final class MovableClock extends Clock {

        private Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MovableClock clock;
    private PickerSearches searches;

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        searches = new PickerSearches(clock);
    }

    private static OpenSubtitlesResult result(long fileId) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Release " + fileId, 23.976, 10, true, false, null,
            null, 1);
    }

    @Test
    void theWindowIsFifteenMinutes() {
        assertThat(PickerSearches.TTL).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void aFileAFreshSearchOfferedIsFoundWithTheImdbIdItWasSearchedFor() {
        searches.put(40L, SubtitleKind.FORCED, IMDB, List.of(result(5011), result(5012)));

        Offered offered = searches.find(40L, SubtitleKind.FORCED, 5012).orElseThrow();

        assertThat(offered.imdbId()).isEqualTo(IMDB);
        assertThat(offered.result()).isEqualTo(result(5012));
    }

    @Test
    void aFileAtOneMillisecondBeforeTheLimitIsAccepted() {
        searches.put(40L, SubtitleKind.FORCED, IMDB, List.of(result(5011)));
        clock.now = NOW.plus(PickerSearches.TTL).minusMillis(1);

        assertThat(searches.find(40L, SubtitleKind.FORCED, 5011)).isPresent();
    }

    @Test
    void aFileAtExactlyTheLimitIsAcceptedBecauseTheWindowIsInclusive() {
        searches.put(40L, SubtitleKind.FORCED, IMDB, List.of(result(5011)));
        clock.now = NOW.plus(PickerSearches.TTL);

        assertThat(searches.find(40L, SubtitleKind.FORCED, 5011)).isPresent();
    }

    @Test
    void aFileAtOneMillisecondPastTheLimitIsRefused() {
        searches.put(40L, SubtitleKind.FORCED, IMDB, List.of(result(5011)));
        clock.now = NOW.plus(PickerSearches.TTL).plusMillis(1);

        assertThat(searches.find(40L, SubtitleKind.FORCED, 5011)).isEmpty();
    }

    @Test
    void theWindowCountsFromTheLatestSearchOfTheSameEditionAndKind() {
        searches.put(40L, SubtitleKind.FORCED, IMDB, List.of(result(5011)));
        clock.now = NOW.plus(Duration.ofMinutes(10));
        searches.put(40L, SubtitleKind.FORCED, IMDB, List.of(result(5011)));
        clock.now = NOW.plus(Duration.ofMinutes(25));

        assertThat(searches.find(40L, SubtitleKind.FORCED, 5011)).isPresent();
        clock.now = NOW.plus(Duration.ofMinutes(25)).plusMillis(1);
        assertThat(searches.find(40L, SubtitleKind.FORCED, 5011)).isEmpty();
    }

    @Test
    void aFileFromAnotherEditionOrTheOtherKindIsRefused() {
        searches.put(40L, SubtitleKind.FORCED, IMDB, List.of(result(5011)));
        searches.put(41L, SubtitleKind.FULL, IMDB, List.of(result(6011)));

        assertThat(searches.find(41L, SubtitleKind.FORCED, 5011)).isEmpty();
        assertThat(searches.find(40L, SubtitleKind.FULL, 5011)).isEmpty();
        assertThat(searches.find(40L, SubtitleKind.FORCED, 6011)).isEmpty();
        assertThat(searches.find(41L, SubtitleKind.FULL, 6011)).isPresent();
    }

    @Test
    void aFileTheSearchDidNotOfferIsRefused() {
        searches.put(40L, SubtitleKind.FORCED, IMDB, List.of(result(5011)));

        assertThat(searches.find(40L, SubtitleKind.FORCED, 9999)).isEmpty();
    }

    @Test
    void aNewSearchReplacesTheOldOneSoAFileItNoLongerOffersIsRefused() {
        searches.put(40L, SubtitleKind.FORCED, IMDB, List.of(result(5011)));
        searches.put(40L, SubtitleKind.FORCED, IMDB, List.of(result(5012)));

        assertThat(searches.find(40L, SubtitleKind.FORCED, 5011)).isEmpty();
        assertThat(searches.find(40L, SubtitleKind.FORCED, 5012)).isPresent();
    }

    @Test
    void anEditionNeverSearchedHasNothing() {
        assertThat(searches.find(40L, SubtitleKind.FORCED, 5011)).isEmpty();
    }

    @Test
    void aNewSearchForgetsTheSearchesThatHaveExpiredButNotTheOnesThatHaveNot() {
        searches.put(40L, SubtitleKind.FORCED, IMDB, List.of(result(5011)));
        clock.now = NOW.plus(Duration.ofMinutes(10));
        searches.put(41L, SubtitleKind.FORCED, IMDB, List.of(result(5021)));
        clock.now = NOW.plus(PickerSearches.TTL).plusMillis(1);
        searches.put(42L, SubtitleKind.FORCED, IMDB, List.of(result(5031)));
        // Back to when the first search was still valid: only a search forgotten on the last put is now missing.
        clock.now = NOW.plus(Duration.ofMinutes(14));

        assertThat(searches.find(40L, SubtitleKind.FORCED, 5011)).isEmpty();
        assertThat(searches.find(41L, SubtitleKind.FORCED, 5021)).isPresent();
    }
}

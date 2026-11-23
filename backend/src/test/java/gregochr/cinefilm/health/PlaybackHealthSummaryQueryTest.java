package gregochr.cinefilm.health;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** The summary's "updated since" query on H2: where the 2-minute line falls, the order and the cap. */
class PlaybackHealthSummaryQueryTest extends ApiTestBase {

    private static final Instant TWO_MINUTES_AGO = NOW.minus(Duration.ofMinutes(2));

    @Autowired
    private PlaybackHealthRepository health;

    private long next;

    private PlaybackHealth row(Instant startedAt, Instant updatedAt, long watchedMs, boolean problem) {
        PlaybackHealth row = new PlaybackHealth();
        row.setPlaybackId(new UUID(0, ++next).toString());
        row.setEditionId(1L);
        row.setFilmId(1L);
        row.setFilmTitle("Film " + next);
        row.setStartedAt(startedAt);
        row.setUpdatedAt(updatedAt);
        row.setSeq(1);
        row.setSummary("{}");
        row.setFigures(new HealthFigures(watchedMs, problem ? 1 : 0, 0, 1_000L, problem, "home", "person"));
        return health.saveAndFlush(row);
    }

    private PlaybackHealth updated(Instant updatedAt) {
        return row(NOW.minusSeconds(3_600), updatedAt, 200_000, false);
    }

    // playing now

    @Test
    void updatedExactlyTwoMinutesAgoAndOneMillisecondNewerAreIncludedAndOneMillisecondOlderIsNot() {
        PlaybackHealth edge = updated(TWO_MINUTES_AGO);
        updated(TWO_MINUTES_AGO.minusMillis(1));
        PlaybackHealth over = updated(TWO_MINUTES_AGO.plusMillis(1));

        assertThat(health.updatedSince(TWO_MINUTES_AGO, PageRequest.ofSize(10)))
            .extracting(PlaybackHealth::getPlaybackId).containsExactly(over.getPlaybackId(), edge.getPlaybackId());
    }

    @Test
    void theNewestUpdateComesFirstAndTheHigherIdWinsATie() {
        PlaybackHealth oldest = updated(NOW.minusSeconds(90));
        PlaybackHealth tieLow = updated(NOW.minusSeconds(30));
        PlaybackHealth tieHigh = updated(NOW.minusSeconds(30));
        PlaybackHealth newest = updated(NOW.minusSeconds(5));

        List<PlaybackHealth> found = health.updatedSince(TWO_MINUTES_AGO, PageRequest.ofSize(10));

        assertThat(found).extracting(PlaybackHealth::getPlaybackId).containsExactly(newest.getPlaybackId(),
            tieHigh.getPlaybackId(), tieLow.getPlaybackId(), oldest.getPlaybackId());
    }

    @Test
    void thePageSizeCapsTheAnswerAndKeepsTheNewest() {
        for (int i = 0; i < 12; i++) {
            updated(NOW.minusSeconds(100 - i));
        }

        List<PlaybackHealth> found = health.updatedSince(TWO_MINUTES_AGO, PageRequest.ofSize(10));

        assertThat(found).hasSize(10);
        assertThat(found.get(0).getUpdatedAt()).isEqualTo(NOW.minusSeconds(100 - 11));
        assertThat(found.get(9).getUpdatedAt()).isEqualTo(NOW.minusSeconds(100 - 2));
    }
}

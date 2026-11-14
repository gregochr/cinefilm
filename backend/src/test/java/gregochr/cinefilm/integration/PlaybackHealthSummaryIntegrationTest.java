package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.health.HealthFigures;
import gregochr.cinefilm.health.PlaybackHealth;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** The Admin summary's playback queries on PostgreSQL, where timestamps compare as the production database does. */
class PlaybackHealthSummaryIntegrationTest extends IntegrationTestBase {

    private static final long MIN_WATCHED = 180_000;
    private static final Instant THIRTY_DAYS_AGO = NOW.minus(Duration.ofDays(30));
    private static final Instant TWO_MINUTES_AGO = NOW.minus(Duration.ofMinutes(2));

    @Autowired
    private PlaybackHealthRepository health;

    private long next;

    @BeforeEach
    void clean() {
        health.deleteAll();
        next = 0;
    }

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
        row.setFigures(new HealthFigures(watchedMs, problem ? 1 : 0, 0, 1_000L, problem, "home"));
        return health.saveAndFlush(row);
    }

    @Test
    void theCountsKeepTheThirtyDayAndThreeMinuteLinesAndSplitOutTheSmoothOnes() {
        row(THIRTY_DAYS_AGO, NOW, 180_000, false);
        row(THIRTY_DAYS_AGO.minusMillis(1), NOW, 200_000, false);
        row(NOW.minusSeconds(100), NOW, 179_999, false);
        row(NOW.minusSeconds(100), NOW, 200_000, true);

        assertThat(health.countFilmSessions(THIRTY_DAYS_AGO, MIN_WATCHED)).isEqualTo(2);
        assertThat(health.countSmoothFilmSessions(THIRTY_DAYS_AGO, MIN_WATCHED)).isEqualTo(1);
    }

    @Test
    void playingNowKeepsTheTwoMinuteLineOrdersNewestFirstAndCaps() {
        PlaybackHealth edge = row(NOW.minusSeconds(900), TWO_MINUTES_AGO, 200_000, false);
        row(NOW.minusSeconds(900), TWO_MINUTES_AGO.minusMillis(1), 200_000, false);
        PlaybackHealth newest = row(NOW.minusSeconds(900), NOW.minusSeconds(5), 200_000, false);

        assertThat(health.updatedSince(TWO_MINUTES_AGO, PageRequest.ofSize(10)))
            .extracting(PlaybackHealth::getPlaybackId).containsExactly(newest.getPlaybackId(), edge.getPlaybackId());
        assertThat(health.updatedSince(TWO_MINUTES_AGO, PageRequest.ofSize(1)))
            .extracting(PlaybackHealth::getPlaybackId).containsExactly(newest.getPlaybackId());
    }
}

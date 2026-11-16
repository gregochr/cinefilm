package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.health.HealthFigures;
import gregochr.cinefilm.health.PlaybackHealth;
import gregochr.cinefilm.health.HealthDtos.PlaybackCounts;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import gregochr.cinefilm.health.PlaybackHealthService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** The Admin summary's "playing now" query on PostgreSQL, where timestamps compare as the production database does. */
class PlaybackHealthSummaryIntegrationTest extends IntegrationTestBase {

    private static final Instant TWO_MINUTES_AGO = NOW.minus(Duration.ofMinutes(2));

    @Autowired
    private PlaybackHealthRepository health;

    @Autowired
    private PlaybackHealthService service;

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
    void theCountsKeepTheThirtyDayLineAndTheThreeMinuteLineAndSplitOutTheSmoothOnes() {
        Instant line = NOW.minus(Duration.ofDays(30));
        row(line, NOW, 180_000, false);
        row(line.minusMillis(1), NOW, 200_000, false);
        row(line.plusMillis(1), NOW, 200_000, false);
        row(NOW.minusSeconds(100), NOW, 179_999, false);
        row(NOW.minusSeconds(100), NOW, 180_001, false);
        row(NOW.minusSeconds(100), NOW, 200_000, true);

        assertThat(service.playbackCounts()).isEqualTo(new PlaybackCounts(4, 3));
    }

    @Test
    void playingNowKeepsTheTwoMinuteLineOrdersNewestFirstAndCaps() {
        PlaybackHealth edge = row(NOW.minusSeconds(900), TWO_MINUTES_AGO, 200_000, false);
        row(NOW.minusSeconds(900), TWO_MINUTES_AGO.minusMillis(1), 200_000, false);
        PlaybackHealth over = row(NOW.minusSeconds(900), TWO_MINUTES_AGO.plusMillis(1), 200_000, false);
        PlaybackHealth newest = row(NOW.minusSeconds(900), NOW.minusSeconds(5), 200_000, false);

        assertThat(health.updatedSince(TWO_MINUTES_AGO, PageRequest.ofSize(10)))
            .extracting(PlaybackHealth::getPlaybackId)
            .containsExactly(newest.getPlaybackId(), over.getPlaybackId(), edge.getPlaybackId());
        assertThat(health.updatedSince(TWO_MINUTES_AGO, PageRequest.ofSize(1)))
            .extracting(PlaybackHealth::getPlaybackId).containsExactly(newest.getPlaybackId());
    }
}

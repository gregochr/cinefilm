package gregochr.cinefilm.health;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.health.HealthSamples.JSON;
import static gregochr.cinefilm.health.HealthSamples.client;
import static gregochr.cinefilm.health.HealthSamples.example;
import static gregochr.cinefilm.health.HealthSamples.minutes;
import static gregochr.cinefilm.health.HealthSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.health.HealthDtos.PlaybackCounts;
import gregochr.cinefilm.health.HealthDtos.PlayingNow;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.node.ObjectNode;

/** The summary reads of {@link PlaybackHealthService}: the window arguments and the shaping of "playing now". */
@ExtendWith(MockitoExtension.class)
class PlaybackHealthSummaryTest {

    private static final String ID = "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b";

    @Mock
    private PlaybackHealthRepository repository;

    @Mock
    private EditionRepository editions;

    private PlaybackHealthService service;

    @BeforeEach
    void setUp() {
        service = new PlaybackHealthService(repository, editions, JSON, CLOCK);
    }

    private static PlaybackHealth stored(String playbackId, String summary, String environment) {
        PlaybackHealth row = new PlaybackHealth();
        row.setPlaybackId(playbackId);
        row.setEditionId(11L);
        row.setFilmId(7L);
        row.setFilmTitle("Blade Runner");
        row.setStartedAt(NOW.minusSeconds(600));
        row.setUpdatedAt(NOW.minusSeconds(30));
        row.setSummary(summary);
        row.setEnvironment(environment);
        return row;
    }

    private void playingRows(PlaybackHealth... rows) {
        when(repository.updatedSince(NOW.minusSeconds(120), PageRequest.ofSize(200))).thenReturn(List.of(rows));
    }

    @Test
    void theCountsAskForThirtyDaysBackAndThreeMinutesWatched() {
        when(repository.countFilmSessions(NOW.minus(Duration.ofDays(30)), 180_000L)).thenReturn(40L);
        when(repository.countSmoothFilmSessions(NOW.minus(Duration.ofDays(30)), 180_000L)).thenReturn(31L);

        assertThat(service.playbackCounts()).isEqualTo(new PlaybackCounts(40, 31));
    }

    @Test
    void playingNowAsksForTwoMinutesBackAndAtMostTenRows() {
        playingRows();

        assertThat(service.playingNow()).isEmpty();
    }

    @Test
    void anEntryCarriesTheIdsTheTitleTheUserAgentAndTheStoredEnvironment() {
        playingRows(stored(ID, text(example()), "home"));

        assertThat(service.playingNow()).containsExactly(new PlayingNow(ID, 11, "Blade Runner",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)", "home", "360p"));
    }

    @Test
    void theEnvironmentComesFromTheColumnNotTheReportText() {
        ObjectNode report = example();
        report.put("environment", "away");
        playingRows(stored(ID, text(report), "train"));

        assertThat(service.playingNow().get(0).environment()).isEqualTo("train");
    }

    @Test
    void aRowWithNoEnvironmentHasNull() {
        playingRows(stored(ID, text(example()), null));

        assertThat(service.playingNow().get(0).environment()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"1,original", "2,1080p", "3,720p", "4,480p", "5,360p", "6,240p"})
    void theQualityIsTheRungPlayedInTheLastMinute(int code, String quality) {
        ObjectNode report = example();
        minutes(report).removeAll();
        minutes(report).add(JSON.createArrayNode().add(0).add(900).add(2).add(1).add(0));
        minutes(report).add(JSON.createArrayNode().add(1).add(900).add(code).add(1).add(0));
        playingRows(stored(ID, text(report), "home"));

        assertThat(service.playingNow().get(0).quality()).isEqualTo(quality);
    }

    @Test
    void anUnknownRungCodeInTheLastMinuteFallsBackToTheQualityTheViewerChose() {
        ObjectNode report = example();
        minutes(report).removeAll();
        minutes(report).add(JSON.createArrayNode().add(0).add(900).add(4).add(1).add(0));
        minutes(report).add(JSON.createArrayNode().add(1).add(900).add(0).add(1).add(0));
        report.put("quality", "720p");
        playingRows(stored(ID, text(report), "home"));

        assertThat(service.playingNow().get(0).quality()).isEqualTo("720p");
    }

    @Test
    void aReportWithNoMinutesYetFallsBackToTheQualityTheViewerChose() {
        ObjectNode report = example();
        minutes(report).removeAll();
        playingRows(stored(ID, text(report), "home"));

        assertThat(service.playingNow().get(0).quality()).isEqualTo("auto");
    }

    @Test
    void aReportWithNoClientHasNoDevice() {
        ObjectNode report = example();
        report.remove("client");
        playingRows(stored(ID, text(report), "home"));

        assertThat(service.playingNow().get(0).device()).isNull();
    }

    @Test
    void aClientWithNoUserAgentHasNoDevice() {
        ObjectNode report = example();
        client(report).remove("userAgent");
        playingRows(stored(ID, text(report), "home"));

        assertThat(service.playingNow().get(0).device()).isNull();
    }

    @Test
    void anUnreadableReportStillListsWithNoDeviceAndNoQuality() {
        playingRows(stored(ID, "not json", "home"));

        assertThat(service.playingNow()).containsExactly(new PlayingNow(ID, 11, "Blade Runner", null, "home", null));
    }

    @Test
    void twoRowsOfOneEditionGiveOneEntryTheNewest() {
        String reloaded = "0a1b2c3d-0000-4000-8000-0123456789ab";
        playingRows(stored(reloaded, text(example()), "away"), stored(ID, text(example()), "home"));

        assertThat(service.playingNow()).extracting(PlayingNow::playbackId).containsExactly(reloaded);
        assertThat(service.playingNow().get(0).environment()).isEqualTo("away");
    }

    @Test
    void reloadsOfOneEditionCannotHideAnotherEdition() {
        PlaybackHealth[] rows = new PlaybackHealth[11];
        for (int i = 0; i < 10; i++) {
            rows[i] = stored(new UUID(0, i + 1).toString(), text(example()), "home");
        }
        rows[10] = stored("0a1b2c3d-0000-4000-8000-0123456789ab", text(example()), "away");
        rows[10].setEditionId(12L);
        playingRows(rows);

        assertThat(service.playingNow()).extracting(PlayingNow::editionId).containsExactly(11L, 12L);
    }

    @Test
    void exactlyTenEditionsAreAllKept() {
        PlaybackHealth[] rows = new PlaybackHealth[10];
        for (int i = 0; i < 10; i++) {
            rows[i] = stored(new UUID(0, i + 1).toString(), text(example()), "home");
            rows[i].setEditionId(100L + i);
        }
        playingRows(rows);

        assertThat(service.playingNow()).extracting(PlayingNow::editionId)
            .containsExactly(100L, 101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L, 109L);
    }

    @Test
    void moreThanTenEditionsAreCappedAtTenKeepingTheNewest() {
        PlaybackHealth[] rows = new PlaybackHealth[12];
        for (int i = 0; i < 12; i++) {
            rows[i] = stored(new UUID(0, i + 1).toString(), text(example()), "home");
            rows[i].setEditionId(100L + i);
        }
        playingRows(rows);

        assertThat(service.playingNow()).extracting(PlayingNow::editionId)
            .containsExactly(100L, 101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L, 109L);
    }

    @Test
    void rowsOfDifferentEditionsAreAllKept() {
        PlaybackHealth other = stored("0a1b2c3d-0000-4000-8000-0123456789ab", text(example()), "away");
        other.setEditionId(12L);
        playingRows(stored(ID, text(example()), "home"), other);

        assertThat(service.playingNow()).extracting(PlayingNow::editionId).containsExactly(11L, 12L);
    }

    @Test
    void theRowsKeepTheRepositorysNewestFirstOrder() {
        String other = "0a1b2c3d-0000-4000-8000-0123456789ab";
        PlaybackHealth second = stored(other, text(example()), "away");
        second.setEditionId(12L);
        playingRows(stored(ID, text(example()), "home"), second);

        assertThat(service.playingNow()).extracting(PlayingNow::playbackId).containsExactly(ID, other);
    }
}

package gregochr.cinefilm.health;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.health.HealthSamples.JSON;
import static gregochr.cinefilm.health.HealthSamples.client;
import static gregochr.cinefilm.health.HealthSamples.example;
import static gregochr.cinefilm.health.HealthSamples.minutes;
import static gregochr.cinefilm.health.HealthSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.health.HealthDtos.PlaybackCounts;
import gregochr.cinefilm.health.HealthDtos.PlayingNow;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

    @SuppressWarnings("unchecked")
    private void windowRows(PlaybackHealth... rows) {
        Sort oldestFirst = Sort.by(Sort.Direction.ASC, "startedAt").and(Sort.by(Sort.Direction.ASC, "id"));
        when(repository.findAll(any(Specification.class), eq(oldestFirst))).thenReturn(List.of(rows));
    }

    private static PlaybackHealth film(String playbackId, long filmId, long watchedMs, boolean problem) {
        PlaybackHealth row = stored(playbackId, text(example()), "home");
        row.setFilmId(filmId);
        row.setFigures(new HealthFigures(watchedMs, problem ? 1 : 0, 0, 1_000L, problem, "home", "person"));
        return row;
    }

    @Test
    void theCountsReadTheThirtyDayWindowAndCountViewingsOfThreeMinutesAndTheSmoothOnes() {
        windowRows(film(new UUID(0, 1).toString(), 1, 180_000, false), film(new UUID(0, 2).toString(), 2, 179_999,
            false), film(new UUID(0, 3).toString(), 3, 200_000, true));

        assertThat(service.playbackCounts()).isEqualTo(new PlaybackCounts(2, 1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCountsSelectRowsThatStartedAtOrAfterThirtyDaysBack() {
        windowRows();
        service.playbackCounts();
        ArgumentCaptor<Specification<PlaybackHealth>> spec = ArgumentCaptor.captor();
        verify(repository).findAll(spec.capture(),
            eq(Sort.by(Sort.Direction.ASC, "startedAt").and(Sort.by(Sort.Direction.ASC, "id"))));
        Root<PlaybackHealth> root = mock(Root.class);
        Path<Instant> startedAt = mock(Path.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        when(root.<Instant>get("startedAt")).thenReturn(startedAt);

        spec.getValue().toPredicate(root, mock(CriteriaQuery.class), builder);

        verify(builder).greaterThanOrEqualTo(startedAt, NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void thePartsOfOneViewingCountOnceAndAProblemInAnyPartIsNotSmooth() {
        PlaybackHealth first = film(new UUID(0, 1).toString(), 1, 100_000, false);
        PlaybackHealth second = film(new UUID(0, 2).toString(), 1, 100_000, true);
        first.setUpdatedAt(NOW.minusSeconds(500));
        second.setStartedAt(NOW.minusSeconds(490));
        windowRows(first, second);

        assertThat(service.playbackCounts()).isEqualTo(new PlaybackCounts(1, 0));
    }

    @Test
    void anEmptyWindowCountsZero() {
        windowRows();

        assertThat(service.playbackCounts()).isEqualTo(new PlaybackCounts(0, 0));
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
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)", null, "home", "360p"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theTouchHintIsPassedThroughAsTheReportStatesIt(boolean touch) {
        ObjectNode report = example();
        client(report).put("touch", touch);
        playingRows(stored(ID, text(report), "home"));

        assertThat(service.playingNow().get(0).touch()).isEqualTo(touch);
    }

    @Test
    void aReportFromBeforeTheTouchHintHasNoTouchValue() {
        playingRows(stored(ID, text(example()), "home"));

        assertThat(service.playingNow().get(0).touch()).isNull();
    }

    @Test
    void aReportWithNoClientHasNoTouchValue() {
        ObjectNode report = example();
        report.remove("client");
        playingRows(stored(ID, text(report), "home"));

        assertThat(service.playingNow().get(0).touch()).isNull();
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

        assertThat(service.playingNow()).containsExactly(new PlayingNow(ID, 11, "Blade Runner", null, null, "home",
            null));
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
    void nineEditionsAreAllKept() {
        PlaybackHealth[] rows = new PlaybackHealth[9];
        for (int i = 0; i < 9; i++) {
            rows[i] = stored(new UUID(0, i + 1).toString(), text(example()), "home");
            rows[i].setEditionId(100L + i);
        }
        playingRows(rows);

        assertThat(service.playingNow()).extracting(PlayingNow::editionId)
            .containsExactly(100L, 101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L);
    }

    @Test
    void elevenEditionsAreCappedAtTenKeepingTheNewest() {
        PlaybackHealth[] rows = new PlaybackHealth[11];
        for (int i = 0; i < 11; i++) {
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

package gregochr.cinefilm.health;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.health.HealthSamples.JSON;
import static gregochr.cinefilm.health.HealthSamples.client;
import static gregochr.cinefilm.health.HealthSamples.example;
import static gregochr.cinefilm.health.HealthSamples.fullMinute;
import static gregochr.cinefilm.health.HealthSamples.minutes;
import static gregochr.cinefilm.health.HealthSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.health.HealthDtos.PlaybackList;
import gregochr.cinefilm.health.HealthDtos.PlaybackRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class PlaybackHealthServiceTest {

    private static final String ID = "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b";
    private static final String OTHER_ID = "0a1b2c3d-0000-4000-8000-0123456789ab";
    private static final long EDITION_ID = 11L;
    private static final long FILM_ID = 7L;

    @Mock
    private PlaybackHealthRepository repository;

    @Mock
    private EditionRepository editions;

    private PlaybackHealthService service;

    @BeforeEach
    void setUp() {
        service = new PlaybackHealthService(repository, editions, JSON, CLOCK);
    }

    private void knownEdition() {
        Film film = film(FILM_ID, "Blade Runner", 1982, NOW);
        Edition edition = edition(film, EDITION_ID, "The Final Cut", 100 * MIN, null);
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));
    }

    private PlaybackHealth saved() {
        ArgumentCaptor<PlaybackHealth> captor = ArgumentCaptor.forClass(PlaybackHealth.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static PlaybackHealth stored(long rowId, String playbackId, String summary) {
        PlaybackHealth row = new PlaybackHealth();
        row.setId(rowId);
        row.setPlaybackId(playbackId);
        row.setEditionId(EDITION_ID);
        row.setFilmId(FILM_ID);
        row.setFilmTitle("Blade Runner");
        row.setStartedAt(NOW.minusSeconds(600));
        row.setUpdatedAt(NOW.minusSeconds(60));
        row.setSeq(4);
        row.setSummary(summary);
        return row;
    }

    private static String compactExample() {
        return text(example());
    }

    // report: a new viewing

    @Test
    void theFirstReportOfAViewingSavesEveryFieldOfTheRow() {
        knownEdition();
        when(repository.count()).thenReturn(1L);

        service.report(ID, compactExample());

        PlaybackHealth row = saved();
        assertThat(row.getPlaybackId()).isEqualTo(ID);
        assertThat(row.getEditionId()).isEqualTo(EDITION_ID);
        assertThat(row.getFilmId()).isEqualTo(FILM_ID);
        assertThat(row.getFilmTitle()).isEqualTo("Blade Runner");
        assertThat(row.getStartedAt()).isEqualTo(Instant.parse("2024-03-31T23:26:55Z"));
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        assertThat(row.getSeq()).isEqualTo(4);
        assertThat(JSON.readTree(row.getSummary())).isEqualTo(example());
    }

    @Test
    void startedAtIsTheClockMinusTheElapsedTimeOfTheReport() {
        knownEdition();
        when(repository.count()).thenReturn(1L);
        ObjectNode body = example();
        body.put("elapsedMs", 0);

        service.report(ID, text(body));

        assertThat(saved().getStartedAt()).isEqualTo(NOW);
    }

    @Test
    void theStoredSummaryIsTheServersOwnSerialisationNotTheClientsText() {
        knownEdition();
        when(repository.count()).thenReturn(1L);
        ObjectNode body = example();
        body.put("somethingNew", "a field of a later release");
        String clientsText = "  " + text(body).replace(",", " , ") + "  ";

        service.report(ID, clientsText);

        String summary = saved().getSummary();
        assertThat(summary).isEqualTo(compactExample());
        assertThat(summary).doesNotContain("somethingNew");
    }

    @Test
    void anUnknownEditionIsNotFound() {
        assertThatThrownBy(() -> service.report(ID, compactExample()))
            .isExactlyInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
    }

    @Test
    void aReportBelowTheCapDeletesNothing() {
        knownEdition();
        when(repository.count()).thenReturn(2_000L);

        assertThatCode(() -> service.report(ID, compactExample())).doesNotThrowAnyException();
    }

    @Test
    void aReportThatTakesTheTableOneOverTheCapDeletesTheOldestRow() {
        knownEdition();
        when(repository.count()).thenReturn(2_001L);
        when(repository.oldestIds(PageRequest.of(0, 1))).thenReturn(List.of(5L));

        service.report(ID, compactExample());

        verify(repository).deleteAllByIdInBatch(List.of(5L));
    }

    @Test
    void aReportThatTakesTheTableSeveralOverTheCapDeletesExactlyTheExcess() {
        knownEdition();
        when(repository.count()).thenReturn(2_003L);
        when(repository.oldestIds(PageRequest.of(0, 3))).thenReturn(List.of(5L, 6L, 9L));

        service.report(ID, compactExample());

        verify(repository).deleteAllByIdInBatch(List.of(5L, 6L, 9L));
    }

    // report: a known viewing

    @Test
    void aLaterReportOfAKnownViewingReplacesTheSummaryWithTheNewSeqAndTime() {
        when(repository.findByPlaybackId(ID)).thenReturn(Optional.of(stored(1, ID, "{}")));
        ObjectNode body = example();
        body.put("seq", 9);
        when(repository.replaceIfNewer(ID, 9, text(body), NOW)).thenReturn(1);

        assertThatCode(() -> service.report(ID, text(body))).doesNotThrowAnyException();
    }

    @Test
    void aReportThatIsNotNewerChangesNothingAndIsNotAnError() {
        when(repository.findByPlaybackId(ID)).thenReturn(Optional.of(stored(1, ID, "{}")));
        when(repository.replaceIfNewer(ID, 4, compactExample(), NOW)).thenReturn(0);

        assertThatCode(() -> service.report(ID, compactExample())).doesNotThrowAnyException();
    }

    @Test
    void aKnownViewingReportedForAnotherEditionIsRefused() {
        when(repository.findByPlaybackId(ID)).thenReturn(Optional.of(stored(1, ID, "{}")));
        ObjectNode body = example();
        body.put("editionId", EDITION_ID + 1);

        assertThatThrownBy(() -> service.report(ID, text(body)))
            .isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid health report");
    }

    // report: the id

    @ParameterizedTest
    @ValueSource(strings = {
        "3F2B8C1E-5D4A-4E7B-9A60-1C2D3E4F5A6B",
        "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6",
        "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b0",
        "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6g",
        "3f2b8c1e5d4a4e7b9a601c2d3e4f5a6b",
        " 3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b",
        "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b\n",
        "",
        "1"})
    void anIdThatIsNotALowerCaseUuidIsRefused(String id) {
        assertThatThrownBy(() -> service.report(id, compactExample()))
            .isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid health report");
    }

    @Test
    void noIdAtAllIsRefused() {
        assertThatThrownBy(() -> service.report(null, compactExample()))
            .isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid health report");
    }

    // report: the body

    @Test
    void aBodyOfExactlyTwentyThousandCharactersIsAccepted() {
        knownEdition();
        when(repository.count()).thenReturn(1L);
        String body = compactExample() + " ".repeat(20_000 - compactExample().length());
        assertThat(body).hasSize(20_000);

        service.report(ID, body);

        assertThat(saved().getSummary()).isEqualTo(compactExample());
    }

    @Test
    void aBodyOfTwentyThousandAndOneCharactersIsRefused() {
        String body = compactExample() + " ".repeat(20_001 - compactExample().length());
        assertThat(body).hasSize(20_001);

        assertThatThrownBy(() -> service.report(ID, body))
            .isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid health report");
    }

    @Test
    void noBodyIsRefused() {
        assertThatThrownBy(() -> service.report(ID, null))
            .isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid health report");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{", "null", "[]", "5", "\"text\"", "{}", "{\"seq\":\"abc\"}",
        "{\"seq\":4,\"minutes\":5}", "{\"seq\":4,\"client\":[]}"})
    void aBodyThatIsNotAReportIsRefused(String body) {
        assertThatThrownBy(() -> service.report(ID, body))
            .isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid health report");
    }

    @Test
    void aReportOutsideTheRulesIsRefused() {
        ObjectNode body = example();
        body.put("environment", "plane");

        assertThatThrownBy(() -> service.report(ID, text(body)))
            .isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid health report");
    }

    /** A report whose serialisation is exactly {@code length} characters, padded through the user agent. */
    private static ObjectNode reportOfSerialisedLength(int length) {
        ObjectNode node = example();
        client(node).put("userAgent", "");
        while (text(node).length() + 300 < length) {
            minutes(node).add(fullMinute());
        }
        int padding = length - text(node).length();
        client(node).put("userAgent", "u".repeat(padding));
        assertThat(text(node)).hasSize(length);
        return node;
    }

    @Test
    void aReportWhoseSerialisationIsExactlySixteenThousandCharactersIsStored() {
        knownEdition();
        when(repository.count()).thenReturn(1L);
        ObjectNode body = reportOfSerialisedLength(16_000);

        service.report(ID, text(body));

        assertThat(saved().getSummary()).hasSize(16_000).isEqualTo(text(body));
    }

    @Test
    void aReportWhoseSerialisationIsSixteenThousandAndOneCharactersIsRefused() {
        ObjectNode body = reportOfSerialisedLength(16_001);

        assertThatThrownBy(() -> service.report(ID, text(body)))
            .isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid health report");
    }

    // recent

    @Test
    void recentReturnsTheRowsWithTheirFilmAndTimesAndTheReportWithoutMinutes() {
        PlaybackHealth row = stored(1, ID, compactExample());
        when(repository.findTop20ByOrderByUpdatedAtDescIdDesc()).thenReturn(List.of(row));

        PlaybackList list = service.recent();

        assertThat(list.playbacks()).hasSize(1);
        PlaybackRow first = list.playbacks().get(0);
        assertThat(first.playbackId()).isEqualTo(ID);
        assertThat(first.filmId()).isEqualTo(FILM_ID);
        assertThat(first.filmTitle()).isEqualTo("Blade Runner");
        assertThat(first.editionId()).isEqualTo(EDITION_ID);
        assertThat(first.startedAt()).isEqualTo(NOW.minusSeconds(600));
        assertThat(first.updatedAt()).isEqualTo(NOW.minusSeconds(60));
        ObjectNode expected = example();
        expected.remove("minutes");
        assertThat(first.report()).isEqualTo(expected);
        assertThat(first.report().has("minutes")).isFalse();
    }

    @Test
    void recentLeavesOutARowWhoseSummaryCannotBeReadAndKeepsTheOrderOfTheRest() {
        when(repository.findTop20ByOrderByUpdatedAtDescIdDesc()).thenReturn(List.of(
            stored(3, "00000000-0000-4000-8000-000000000003", compactExample()),
            stored(2, "00000000-0000-4000-8000-000000000002", "not json"),
            stored(4, "00000000-0000-4000-8000-000000000004", "[1,2]"),
            stored(5, "00000000-0000-4000-8000-000000000005", ""),
            stored(1, "00000000-0000-4000-8000-000000000001", compactExample())));

        PlaybackList list = service.recent();

        assertThat(list.playbacks()).extracting(PlaybackRow::playbackId).containsExactly(
            "00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000001");
    }

    @Test
    void recentWithNothingStoredIsAnEmptyList() {
        assertThat(service.recent().playbacks()).isEmpty();
    }

    // one

    @Test
    void oneReturnsTheRowWithTheWholeReportIncludingMinutes() {
        when(repository.findByPlaybackId(ID)).thenReturn(Optional.of(stored(1, ID, compactExample())));

        PlaybackRow row = service.one(ID);

        assertThat(row.playbackId()).isEqualTo(ID);
        assertThat(row.filmTitle()).isEqualTo("Blade Runner");
        assertThat(row.report()).isEqualTo(example());
        JsonNode minutes = row.report().get("minutes");
        assertThat(minutes.size()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "", "3F2B8C1E-5D4A-4E7B-9A60-1C2D3E4F5A6B", "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b0"})
    void oneForAnIdThatIsNotAUuidIsNotFound(String id) {
        assertThatThrownBy(() -> service.one(id))
            .isExactlyInstanceOf(NotFoundException.class)
            .hasMessage("No such playback");
    }

    @Test
    void oneForAnIdThatIsNotAUuidIsNotFoundEvenWhenTheStoreWouldAnswerForIt() {
        // A repository that answers every lookup with a readable row, so only the id check can refuse "1".
        PlaybackHealthRepository answersEverything = mock(PlaybackHealthRepository.class,
            invocation -> Optional.of(stored(1, "1", compactExample())));
        PlaybackHealthService lenientStore = new PlaybackHealthService(answersEverything, editions, JSON, CLOCK);

        assertThatThrownBy(() -> lenientStore.one("1"))
            .isExactlyInstanceOf(NotFoundException.class)
            .hasMessage("No such playback");
    }

    @Test
    void oneForNoIdAtAllIsNotFound() {
        assertThatThrownBy(() -> service.one(null))
            .isExactlyInstanceOf(NotFoundException.class)
            .hasMessage("No such playback");
    }

    @Test
    void oneForAMissingRowIsNotFound() {
        assertThatThrownBy(() -> service.one(OTHER_ID))
            .isExactlyInstanceOf(NotFoundException.class)
            .hasMessage("No such playback");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[1]", ""})
    void oneForARowWhoseSummaryCannotBeReadIsNotFound(String summary) {
        when(repository.findByPlaybackId(ID)).thenReturn(Optional.of(stored(1, ID, summary)));

        assertThatThrownBy(() -> service.one(ID))
            .isExactlyInstanceOf(NotFoundException.class)
            .hasMessage("No such playback");
    }
}

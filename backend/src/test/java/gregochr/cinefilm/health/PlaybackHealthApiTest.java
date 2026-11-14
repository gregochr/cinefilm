package gregochr.cinefilm.health;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.health.HealthSamples.example;
import static gregochr.cinefilm.health.HealthSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.node.ObjectNode;

class PlaybackHealthApiTest extends ApiTestBase {

    private static final String ID = "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b";

    @Autowired
    private PlaybackHealthRepository health;

    private Film film;
    private Edition finalCut;
    private Edition theatrical;

    @BeforeEach
    void data() {
        film = film(null, "Blade Runner", 1982, NOW, "Sci-fi");
        finalCut = edition(film, null, "The Final Cut", 100 * MIN, null);
        theatrical = edition(film, null, "Theatrical Cut", 100 * MIN, null);
        file(finalCut, "/nowhere/final.mp4");
        file(theatrical, "/nowhere/theatrical.mp4");
        save(film);
    }

    private ObjectNode report(Edition edition, long seq) {
        return example(edition.getId(), seq);
    }

    private void send(String id, ObjectNode body) throws Exception {
        mvc.perform(put("/api/playback-health/{id}", id).content(text(body))).andExpect(status().isNoContent());
    }

    private static String idOf(long number) {
        return new UUID(0, number).toString();
    }

    private PlaybackHealth row(String playbackId, Instant updatedAt, String summary) {
        PlaybackHealth row = new PlaybackHealth();
        row.setPlaybackId(playbackId);
        row.setEditionId(finalCut.getId());
        row.setFilmId(film.getId());
        row.setFilmTitle("Blade Runner");
        row.setStartedAt(updatedAt.minusSeconds(60));
        row.setUpdatedAt(updatedAt);
        row.setSeq(1);
        row.setSummary(summary);
        return row;
    }

    // a viewing, end to end

    @Test
    void aFirstReportIsStoredAndTheListShowsItWithTheFilmAndTheReportWithoutMinutes() throws Exception {
        send(ID, report(finalCut, 4));

        mvc.perform(get("/api/admin/playback-health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playbacks.length()").value(1))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(ID))
            .andExpect(jsonPath("$.playbacks[0].filmId").value(film.getId()))
            .andExpect(jsonPath("$.playbacks[0].filmTitle").value("Blade Runner"))
            .andExpect(jsonPath("$.playbacks[0].editionId").value(finalCut.getId()))
            .andExpect(jsonPath("$.playbacks[0].startedAt").value("2024-03-31T23:26:55Z"))
            .andExpect(jsonPath("$.playbacks[0].updatedAt").value("2024-03-31T23:30:00Z"))
            .andExpect(jsonPath("$.playbacks[0].report.seq").value(4))
            .andExpect(jsonPath("$.playbacks[0].report.environment").value("train"))
            .andExpect(jsonPath("$.playbacks[0].report.operator").value("AS5089 Virgin Media Limited"))
            .andExpect(jsonPath("$.playbacks[0].report.latencyMs").value(48))
            .andExpect(jsonPath("$.playbacks[0].report.engine").value("hlsjs"))
            .andExpect(jsonPath("$.playbacks[0].report.quality").value("auto"))
            .andExpect(jsonPath("$.playbacks[0].report.autoplayRefused").value(false))
            .andExpect(jsonPath("$.playbacks[0].report.startupMs").value(2380))
            .andExpect(jsonPath("$.playbacks[0].report.watchedMs").value(171000))
            .andExpect(jsonPath("$.playbacks[0].report.stallCount").value(2))
            .andExpect(jsonPath("$.playbacks[0].report.stallMs").value(9400))
            .andExpect(jsonPath("$.playbacks[0].report.kbpsMedian").value(910))
            .andExpect(jsonPath("$.playbacks[0].report.rungMs['480p']").value(58000))
            .andExpect(jsonPath("$.playbacks[0].report.rungMs['360p']").value(113000))
            .andExpect(jsonPath("$.playbacks[0].report.client.screen").value("1512x982"))
            .andExpect(jsonPath("$.playbacks[0].report.client.cores").value(8))
            .andExpect(jsonPath("$.playbacks[0].report.minutes").doesNotExist());
    }

    @Test
    void theViewingByIdCarriesTheWholeReportIncludingMinutes() throws Exception {
        send(ID, report(finalCut, 4));

        mvc.perform(get("/api/admin/playback-health/{id}", ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playbackId").value(ID))
            .andExpect(jsonPath("$.filmTitle").value("Blade Runner"))
            .andExpect(jsonPath("$.report.seq").value(4))
            .andExpect(jsonPath("$.report.minutes.length()").value(4))
            .andExpect(jsonPath("$.report.minutes[0][1]").value(900))
            .andExpect(jsonPath("$.report.minutes[1][4]").value(9400))
            .andExpect(jsonPath("$.report.minutes[3][3]").value(11400))
            .andExpect(jsonPath("$.report.minutes[0].length()").value(5))
            .andExpect(jsonPath("$.report.minutes[3].length()").value(7))
            .andExpect(jsonPath("$.report.minutes[3][5]").value(42))
            .andExpect(jsonPath("$.report.minutes[3][6]").value(4));
    }

    @Test
    void aViewingThatDoesNotExistIsA404() throws Exception {
        mvc.perform(get("/api/admin/playback-health/{id}", ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such playback"));
        mvc.perform(get("/api/admin/playback-health/{id}", "1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such playback"));
    }

    @Test
    void theListWithNothingRecordedIsEmpty() throws Exception {
        mvc.perform(get("/api/admin/playback-health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playbacks.length()").value(0));
    }

    // replacing

    @Test
    void aReportWithAHigherSeqReplacesTheStoredOneButNotTheFilmNorTheStart() throws Exception {
        send(ID, report(finalCut, 4));
        ObjectNode later = report(finalCut, 5);
        later.put("stallCount", 7);
        later.put("elapsedMs", 999_000);

        send(ID, later);

        mvc.perform(get("/api/admin/playback-health/{id}", ID))
            .andExpect(jsonPath("$.report.seq").value(5))
            .andExpect(jsonPath("$.report.stallCount").value(7))
            .andExpect(jsonPath("$.startedAt").value("2024-03-31T23:26:55Z"))
            .andExpect(jsonPath("$.filmTitle").value("Blade Runner"));
        assertThat(health.count()).isEqualTo(1);
        assertThat(health.findByPlaybackId(ID).orElseThrow().getSeq()).isEqualTo(5);
    }

    @Test
    void aReportWithTheSameSeqChangesNothing() throws Exception {
        send(ID, report(finalCut, 4));
        ObjectNode same = report(finalCut, 4);
        same.put("stallCount", 99);

        send(ID, same);

        mvc.perform(get("/api/admin/playback-health/{id}", ID))
            .andExpect(jsonPath("$.report.seq").value(4))
            .andExpect(jsonPath("$.report.stallCount").value(2));
    }

    @Test
    void aReportWithALowerSeqChangesNothing() throws Exception {
        send(ID, report(finalCut, 4));
        ObjectNode older = report(finalCut, 3);
        older.put("stallCount", 100);

        send(ID, older);

        mvc.perform(get("/api/admin/playback-health/{id}", ID))
            .andExpect(jsonPath("$.report.seq").value(4))
            .andExpect(jsonPath("$.report.stallCount").value(2));
        assertThat(health.findByPlaybackId(ID).orElseThrow().getSeq()).isEqualTo(4);
    }

    @Test
    void theSameReportSentTwiceLeavesOneRowWithTheSameValues() throws Exception {
        send(ID, report(finalCut, 4));
        send(ID, report(finalCut, 4));

        assertThat(health.count()).isEqualTo(1);
        mvc.perform(get("/api/admin/playback-health/{id}", ID))
            .andExpect(jsonPath("$.report.seq").value(4))
            .andExpect(jsonPath("$.report.stallCount").value(2));
    }

    // refusals

    @Test
    void aKnownViewingReportedForAnotherEditionIsA400AndKeepsTheStoredReport() throws Exception {
        send(ID, report(finalCut, 4));
        ObjectNode other = report(theatrical, 9);
        other.put("stallCount", 50);

        mvc.perform(put("/api/playback-health/{id}", ID).content(text(other)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid health report"));

        PlaybackHealth stored = health.findByPlaybackId(ID).orElseThrow();
        assertThat(stored.getEditionId()).isEqualTo(finalCut.getId());
        assertThat(stored.getSeq()).isEqualTo(4);
    }

    @Test
    void anUnknownEditionIsA404AndStoresNothing() throws Exception {
        mvc.perform(put("/api/playback-health/{id}", ID).content(text(example(999_999, 4))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));

        assertThat(health.count()).isZero();
    }

    @Test
    void aBodyThatIsNotAValidReportIsA400AndStoresNothing() throws Exception {
        for (String body : List.of("not json", "{}", "[]", "null", "{\"seq\":\"abc\"}")) {
            mvc.perform(put("/api/playback-health/{id}", ID).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid health report"));
        }
        ObjectNode outsideTheRules = report(finalCut, 4);
        outsideTheRules.put("environment", "plane");
        mvc.perform(put("/api/playback-health/{id}", ID).content(text(outsideTheRules)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid health report"));

        assertThat(health.count()).isZero();
    }

    @Test
    void aMissingBodyIsA400WithTheHealthReportMessage() throws Exception {
        mvc.perform(put("/api/playback-health/{id}", ID))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid health report"));
    }

    @Test
    void anIdThatIsNotALowerCaseUuidIsA400() throws Exception {
        mvc.perform(put("/api/playback-health/{id}", ID.toUpperCase()).content(text(report(finalCut, 4))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid health report"));
        mvc.perform(put("/api/playback-health/{id}", "abc").content(text(report(finalCut, 4))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid health report"));
    }

    @Test
    void aBodyOverTwentyThousandCharactersIsA400() throws Exception {
        String body = text(report(finalCut, 4)) + " ".repeat(20_000);

        mvc.perform(put("/api/playback-health/{id}", ID).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid health report"));
    }

    @Test
    void theAnswerToAReportHasNoBody() throws Exception {
        mvc.perform(put("/api/playback-health/{id}", ID).content(text(report(finalCut, 4))))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
    }

    // the cap

    private List<String> saveRows(int count) {
        List<PlaybackHealth> rows = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Row 0 was updated longest ago; every row is older than the report the test then sends.
            rows.add(row(idOf(i + 1), NOW.minusSeconds(10_000L - i), "{}"));
            ids.add(idOf(i + 1));
        }
        health.saveAll(rows);
        health.flush();
        return ids;
    }

    @Test
    void aReportThatBringsTheTableToExactlyTheCapDeletesNothing() throws Exception {
        List<String> ids = saveRows(1_999);

        send(ID, report(finalCut, 4));

        assertThat(health.count()).isEqualTo(2_000);
        assertThat(health.findByPlaybackId(ids.get(0)).isPresent()).isTrue();
        assertThat(health.findByPlaybackId(ID).isPresent()).isTrue();
    }

    @Test
    void aReportThatBringsTheTableOneOverTheCapDeletesTheOldestByUpdatedAt() throws Exception {
        List<String> ids = saveRows(2_000);

        send(ID, report(finalCut, 4));

        assertThat(health.count()).isEqualTo(2_000);
        assertThat(health.findByPlaybackId(ids.get(0)).isPresent()).isFalse();
        assertThat(health.findByPlaybackId(ids.get(1)).isPresent()).isTrue();
        assertThat(health.findByPlaybackId(ids.get(1_999)).isPresent()).isTrue();
        assertThat(health.findByPlaybackId(ID).isPresent()).isTrue();
    }

    @Test
    void aReplacingReportNeverTrimsTheTable() throws Exception {
        List<String> ids = saveRows(2_000);
        health.save(row(ID, NOW.minusSeconds(5), text(report(finalCut, 1))));
        health.flush();
        assertThat(health.count()).isEqualTo(2_001);

        send(ID, report(finalCut, 4));

        assertThat(health.count()).isEqualTo(2_001);
        assertThat(health.findByPlaybackId(ids.get(0)).isPresent()).isTrue();
    }

    // the list

    @Test
    void theListHoldsTheTwentyMostRecentlyUpdatedNewestFirstAndSkipsAnUnreadableOne() throws Exception {
        String summary = text(report(finalCut, 1));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            // Row 0 is the oldest, row 24 the newest.
            String id = idOf(100 + i);
            ids.add(id);
            health.save(row(id, NOW.minusSeconds(3_000L - i * 60L), summary));
        }
        health.save(row(idOf(999), NOW.minusSeconds(1), "not json"));
        health.flush();

        mvc.perform(get("/api/admin/playback-health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playbacks.length()").value(19))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(ids.get(24)))
            .andExpect(jsonPath("$.playbacks[1].playbackId").value(ids.get(23)))
            .andExpect(jsonPath("$.playbacks[18].playbackId").value(ids.get(6)));
    }

    @Test
    void theListStopsAtTwentyRows() throws Exception {
        String summary = text(report(finalCut, 1));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String id = idOf(100 + i);
            ids.add(id);
            health.save(row(id, NOW.minusSeconds(3_000L - i * 60L), summary));
        }
        health.flush();

        mvc.perform(get("/api/admin/playback-health"))
            .andExpect(jsonPath("$.playbacks.length()").value(20))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(ids.get(24)))
            .andExpect(jsonPath("$.playbacks[19].playbackId").value(ids.get(5)));
    }

    @Test
    void rowsUpdatedAtTheSameInstantComeNewestIdFirst() throws Exception {
        String summary = text(report(finalCut, 1));
        health.save(row(idOf(1), NOW, summary));
        health.save(row(idOf(2), NOW, summary));
        health.flush();

        mvc.perform(get("/api/admin/playback-health"))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(idOf(2)))
            .andExpect(jsonPath("$.playbacks[1].playbackId").value(idOf(1)));
    }

    // the figure columns

    @Test
    void aFirstReportWritesTheFigureColumns() throws Exception {
        send(ID, report(finalCut, 4));

        PlaybackHealth stored = health.findByPlaybackId(ID).orElseThrow();
        assertThat(stored.getWatchedMs()).isEqualTo(171_000);
        assertThat(stored.getStallCount()).isEqualTo(2);
        assertThat(stored.getStallMs()).isEqualTo(9_400);
        assertThat(stored.getStartupMs()).isEqualTo(2_380);
        assertThat(stored.isHasProblem()).isTrue();
        assertThat(stored.getEnvironment()).isEqualTo("train");
    }

    @Test
    void aNewerReportReplacesTheFigureColumnsToo() throws Exception {
        send(ID, report(finalCut, 4));
        ObjectNode later = report(finalCut, 5);
        later.put("watchedMs", 400_000);
        later.put("stallCount", 0);
        later.put("stallMs", 0);
        later.put("reconnectCount", 0);
        later.put("stepsDown", 0);
        later.put("netErrors", 0);
        later.put("timeouts", 0);
        later.put("environment", "home");
        later.putNull("startupMs");

        send(ID, later);

        PlaybackHealth stored = health.findByPlaybackId(ID).orElseThrow();
        assertThat(stored.getWatchedMs()).isEqualTo(400_000);
        assertThat(stored.getStallCount()).isZero();
        assertThat(stored.getStallMs()).isZero();
        assertThat(stored.getStartupMs()).isNull();
        assertThat(stored.isHasProblem()).isFalse();
        assertThat(stored.getEnvironment()).isEqualTo("home");
    }

    @Test
    void anOlderReportLeavesTheFigureColumnsAlone() throws Exception {
        send(ID, report(finalCut, 4));
        ObjectNode older = report(finalCut, 3);
        older.put("watchedMs", 1);
        older.put("stallCount", 100);
        older.put("environment", "away");

        send(ID, older);

        PlaybackHealth stored = health.findByPlaybackId(ID).orElseThrow();
        assertThat(stored.getWatchedMs()).isEqualTo(171_000);
        assertThat(stored.getStallCount()).isEqualTo(2);
        assertThat(stored.getEnvironment()).isEqualTo("train");
    }

    // the filtered list

    private PlaybackHealth figureRow(long number, Instant startedAt, long watchedMs, boolean problem) {
        PlaybackHealth row = row(idOf(number), startedAt.plusSeconds(60), text(report(finalCut, 1)));
        row.setStartedAt(startedAt);
        row.setFigures(new HealthFigures(watchedMs, problem ? 1 : 0, 0, 1_000L, problem, "home"));
        return health.saveAndFlush(row);
    }

    @Test
    void theListWithNoParametersKeepsItsShapeWithNoTotal() throws Exception {
        send(ID, report(finalCut, 4));

        mvc.perform(get("/api/admin/playback-health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playbacks.length()").value(1))
            .andExpect(jsonPath("$.total").doesNotExist());
    }

    @Test
    void minWatchedMsKeepsExactlyThoseAtOrOverIt() throws Exception {
        figureRow(1, NOW.minusSeconds(300), 179_999, false);
        figureRow(2, NOW.minusSeconds(200), 180_000, false);

        mvc.perform(get("/api/admin/playback-health").param("minWatchedMs", "180000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.playbacks.length()").value(1))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(idOf(2)));
    }

    @Test
    void sinceComparesTheStartAndKeepsASessionThatStartedExactlyThen() throws Exception {
        Instant boundary = NOW.minusSeconds(1_000);
        figureRow(1, boundary.minusSeconds(1), 200_000, false);
        figureRow(2, boundary, 200_000, false);
        figureRow(3, boundary.plusSeconds(1), 200_000, false);

        mvc.perform(get("/api/admin/playback-health").param("since", boundary.toString()))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(idOf(3)))
            .andExpect(jsonPath("$.playbacks[1].playbackId").value(idOf(2)));
    }

    @Test
    void problemsTrueKeepsOnlyTheSessionsWithOneAndFalseOnlyThoseWithout() throws Exception {
        figureRow(1, NOW.minusSeconds(300), 200_000, true);
        figureRow(2, NOW.minusSeconds(200), 200_000, false);

        mvc.perform(get("/api/admin/playback-health").param("problems", "true"))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(idOf(1)));
        mvc.perform(get("/api/admin/playback-health").param("problems", "false"))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(idOf(2)));
    }

    @Test
    void filtersCombine() throws Exception {
        figureRow(1, NOW.minusSeconds(300), 200_000, true);
        figureRow(2, NOW.minusSeconds(200), 100_000, true);
        figureRow(3, NOW.minusSeconds(100), 200_000, false);

        mvc.perform(get("/api/admin/playback-health").param("problems", "true").param("minWatchedMs", "180000"))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(idOf(1)));
    }

    @Test
    void pagesAreNewestStartFirstWithTheHigherIdFirstOnATieAndATotalOfAll() throws Exception {
        // 1 and 2 started at the same instant; 3, 4 and 5 each a second later than the one before.
        for (int i = 1; i <= 5; i++) {
            figureRow(i, NOW.minusSeconds(i <= 2 ? 500 : 500 - i), 200_000, false);
        }

        mvc.perform(get("/api/admin/playback-health").param("size", "2").param("page", "0"))
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.playbacks.length()").value(2))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(idOf(5)))
            .andExpect(jsonPath("$.playbacks[1].playbackId").value(idOf(4)));
        mvc.perform(get("/api/admin/playback-health").param("size", "2").param("page", "1"))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(idOf(3)))
            .andExpect(jsonPath("$.playbacks[1].playbackId").value(idOf(2)));
        mvc.perform(get("/api/admin/playback-health").param("size", "2").param("page", "2"))
            .andExpect(jsonPath("$.playbacks.length()").value(1))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(idOf(1)));
    }

    @Test
    void theFilteredRowsHaveNoMinutes() throws Exception {
        figureRow(1, NOW.minusSeconds(300), 200_000, false);

        mvc.perform(get("/api/admin/playback-health").param("page", "0"))
            .andExpect(jsonPath("$.playbacks[0].report.minutes").doesNotExist())
            .andExpect(jsonPath("$.playbacks[0].report.seq").value(1));
    }

    @ParameterizedTest
    @CsvSource({"size,0", "size,101", "size,-1", "page,-1", "minWatchedMs,-1", "since,yesterday", "problems,maybe",
        "size,ten"})
    void aBadParameterIsA400(String name, String value) throws Exception {
        mvc.perform(get("/api/admin/playback-health").param(name, value))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void aPageSizeOfAHundredIsAccepted() throws Exception {
        mvc.perform(get("/api/admin/playback-health").param("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
    }

    // the chart

    @Test
    void theChartHoldsOnlyFilmSessionsOldestFirstWithOnlyTheChartsFields() throws Exception {
        ObjectNode filmLength = report(finalCut, 4);
        filmLength.put("watchedMs", 200_000);
        send(ID, filmLength);
        figureRow(1, NOW.minusSeconds(86_400), 179_999, false);
        figureRow(2, NOW.minusSeconds(86_400), 180_000, false);

        mvc.perform(get("/api/admin/playback-health/chart"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessions.length()").value(2))
            .andExpect(jsonPath("$.sessions[0].playbackId").value(idOf(2)))
            .andExpect(jsonPath("$.sessions[0].watchedMs").value(180_000))
            .andExpect(jsonPath("$.sessions[1].playbackId").value(ID))
            .andExpect(jsonPath("$.sessions[1].filmId").value(film.getId()))
            .andExpect(jsonPath("$.sessions[1].filmTitle").value("Blade Runner"))
            .andExpect(jsonPath("$.sessions[1].startedAt").value("2024-03-31T23:26:55Z"))
            .andExpect(jsonPath("$.sessions[1].watchedMs").value(200_000))
            .andExpect(jsonPath("$.sessions[1].stallCount").value(2))
            .andExpect(jsonPath("$.sessions[1].stallMs").value(9_400))
            .andExpect(jsonPath("$.sessions[1].startupMs").value(2_380))
            .andExpect(jsonPath("$.sessions[1].environment").value("train"))
            .andExpect(jsonPath("$.sessions[1].rungMs['480p']").value(58_000))
            .andExpect(jsonPath("$.sessions[1].userAgent").value("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"))
            .andExpect(jsonPath("$.sessions[1].report").doesNotExist())
            .andExpect(jsonPath("$.sessions[1].editionId").doesNotExist());
    }

    @Test
    void theChartAnswersForSinceAndKeepsASessionThatStartedExactlyThen() throws Exception {
        Instant boundary = NOW.minusSeconds(1_000);
        figureRow(1, boundary.minusSeconds(1), 200_000, false);
        figureRow(2, boundary, 200_000, false);

        mvc.perform(get("/api/admin/playback-health/chart").param("since", boundary.toString()))
            .andExpect(jsonPath("$.sessions.length()").value(1))
            .andExpect(jsonPath("$.sessions[0].playbackId").value(idOf(2)));
    }

    @Test
    void theChartWithNothingRecordedIsEmptyAndABadSinceIsA400() throws Exception {
        mvc.perform(get("/api/admin/playback-health/chart"))
            .andExpect(jsonPath("$.sessions.length()").value(0));
        mvc.perform(get("/api/admin/playback-health/chart").param("since", "x"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void thePlaybackIdRouteStillWorksBesideTheChartRoute() throws Exception {
        send(ID, report(finalCut, 4));

        mvc.perform(get("/api/admin/playback-health/{id}", ID))
            .andExpect(jsonPath("$.playbackId").value(ID));
        mvc.perform(get("/api/admin/playback-health/chart"))
            .andExpect(jsonPath("$.sessions").isArray());
    }
}

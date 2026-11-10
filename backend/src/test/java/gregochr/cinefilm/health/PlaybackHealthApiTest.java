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
            .andExpect(jsonPath("$.report.minutes[3][3]").value(11400));
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
}

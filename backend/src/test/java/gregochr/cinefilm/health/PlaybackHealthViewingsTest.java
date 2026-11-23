package gregochr.cinefilm.health;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.health.HealthSamples.client;
import static gregochr.cinefilm.health.HealthSamples.example;
import static gregochr.cinefilm.health.HealthSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.health.HealthDtos.ChartPart;
import gregochr.cinefilm.health.HealthDtos.ChartRow;
import gregochr.cinefilm.health.HealthDtos.PlaybackCounts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.node.ObjectNode;

/** The chart and the Admin counts over real rows (H2): playbacks of one viewing are joined before they are counted. */
class PlaybackHealthViewingsTest extends ApiTestBase {

    private static final String MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/130";
    private static final String OPERATOR = "AS398830 Icomera US, Inc.";
    private static final Instant THIRTY_DAYS_AGO = NOW.minus(Duration.ofDays(30));

    @Autowired
    private PlaybackHealthRepository health;

    @Autowired
    private PlaybackHealthService service;

    private long next;

    private ObjectNode report(String operator, String userAgent, Long startPosition, Long endPosition) {
        ObjectNode report = example();
        report.put("operator", operator);
        client(report).put("userAgent", userAgent);
        if (startPosition != null) {
            report.put("startPositionMs", startPosition);
        }
        if (endPosition != null) {
            report.put("endPositionMs", endPosition);
        }
        return report;
    }

    private PlaybackHealth save(String id, long filmId, Instant startedAt, Instant updatedAt, long watchedMs,
                                boolean problem, ObjectNode report) {
        PlaybackHealth row = new PlaybackHealth();
        row.setPlaybackId(id);
        row.setEditionId(1L);
        row.setFilmId(filmId);
        row.setFilmTitle("Film " + filmId);
        row.setStartedAt(startedAt);
        row.setUpdatedAt(updatedAt);
        row.setSeq(1);
        row.setSummary(text(report));
        row.setFigures(new HealthFigures(watchedMs, problem ? 1 : 0, problem ? 1_000 : 0, 900L, problem,
            "train", "person"));
        return health.saveAndFlush(row);
    }

    private PlaybackHealth playback(long filmId, Instant startedAt, Instant updatedAt, long watchedMs,
                                    boolean problem) {
        return save(new UUID(0, ++next).toString(), filmId, startedAt, updatedAt, watchedMs, problem,
            report(OPERATOR, MAC, null, null));
    }

    // the 12 November pair

    @Test
    void theTwelfthOfNovemberPairIsOneColumnOf138Minutes() {
        ObjectNode first = report(OPERATOR, MAC, null, null);
        first.put("stallCount", 4).put("stallMs", 20_000);
        ObjectNode second = report(OPERATOR, MAC, null, null);
        second.put("stallCount", 155).put("stallMs", 2_011_000);
        save("fb22ac5e-0000-4000-8000-000000000001", 1, Instant.parse("2026-11-12T17:23:30.519Z"),
            Instant.parse("2026-11-12T17:56:47.761Z"), 32 * 60_000L, true, first);
        save("323111c2-0000-4000-8000-000000000002", 1, Instant.parse("2026-11-12T17:56:53.956Z"),
            Instant.parse("2026-11-12T20:33:53.080Z"), 106 * 60_000L, true, second);

        List<ChartRow> sessions = service.chart(null).sessions();

        assertThat(sessions).hasSize(1);
        ChartRow rock = sessions.get(0);
        assertThat(rock.playbackId()).isEqualTo("fb22ac5e-0000-4000-8000-000000000001");
        assertThat(rock.startedAt()).isEqualTo(Instant.parse("2026-11-12T17:23:30.519Z"));
        assertThat(rock.endedAt()).isEqualTo(Instant.parse("2026-11-12T20:33:53.080Z"));
        assertThat(rock.watchedMs()).isEqualTo(138 * 60_000L);
        assertThat(rock.parts()).containsExactly(
            new ChartPart("fb22ac5e-0000-4000-8000-000000000001", Instant.parse("2026-11-12T17:23:30.519Z"),
                Instant.parse("2026-11-12T17:56:47.761Z"), 32 * 60_000L, "train"),
            new ChartPart("323111c2-0000-4000-8000-000000000002", Instant.parse("2026-11-12T17:56:53.956Z"),
                Instant.parse("2026-11-12T20:33:53.080Z"), 106 * 60_000L, "train"));
    }

    // the chart

    @Test
    void aJoinedViewingSumsItsPartsAndKeepsTheFirstPartsStartupAndKey() {
        Instant start = NOW.minusSeconds(86_400);
        PlaybackHealth a = playback(1, start, start.plusSeconds(600), 100_000, true);
        a.setFigures(new HealthFigures(100_000, 1, 1_000, 2_000L, true, "train", "person"));
        health.saveAndFlush(a);
        playback(1, start.plusSeconds(606), start.plusSeconds(1_200), 90_000, false);

        List<ChartRow> sessions = service.chart(null).sessions();

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).playbackId()).isEqualTo(a.getPlaybackId());
        assertThat(sessions.get(0).watchedMs()).isEqualTo(190_000);
        assertThat(sessions.get(0).stallCount()).isEqualTo(1);
        assertThat(sessions.get(0).startupMs()).isEqualTo(2_000);
        assertThat(sessions.get(0).rungMs()).containsEntry("480p", 116_000L).containsEntry("360p", 226_000L);
        assertThat(sessions.get(0).parts()).hasSize(2);
    }

    @Test
    void aShortPartBetweenTwoFilmLengthOnesJoinsThemAndTheThreeMinuteLineAppliesToTheTotal() {
        Instant start = NOW.minusSeconds(86_400);
        playback(1, start, start.plusSeconds(600), 1_800_000, false);
        playback(1, start.plusSeconds(606), start.plusSeconds(726), 60_000, false);
        playback(1, start.plusSeconds(732), start.plusSeconds(2_000), 1_800_000, false);

        List<ChartRow> sessions = service.chart(null).sessions();

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).parts()).hasSize(3);
        assertThat(sessions.get(0).watchedMs()).isEqualTo(3_660_000);
    }

    @Test
    void twoShortPartsThatTogetherReachThreeMinutesAreAViewingAndOneShortPartIsNot() {
        Instant start = NOW.minusSeconds(86_400);
        playback(1, start, start.plusSeconds(100), 90_000, false);
        playback(1, start.plusSeconds(106), start.plusSeconds(200), 90_000, false);
        playback(2, start.plusSeconds(300), start.plusSeconds(400), 179_999, false);

        List<ChartRow> sessions = service.chart(null).sessions();

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).watchedMs()).isEqualTo(180_000);
        assertThat(sessions.get(0).filmId()).isEqualTo(1);
    }

    @Test
    void viewingsComeOldestFirstAndASinceKeepsAPlaybackThatStartedExactlyThen() {
        Instant boundary = NOW.minusSeconds(1_000);
        playback(1, boundary.minusSeconds(1), boundary.plusSeconds(100), 200_000, false);
        PlaybackHealth kept = playback(2, boundary, boundary.plusSeconds(100), 200_000, false);
        PlaybackHealth later = playback(3, boundary.plusSeconds(5), boundary.plusSeconds(100), 200_000, false);

        assertThat(service.chart(boundary).sessions()).extracting(ChartRow::playbackId)
            .containsExactly(kept.getPlaybackId(), later.getPlaybackId());
    }

    @Test
    void aPlaybackBeforeSinceIsNotReadSoItsLaterPartStandsAlone() {
        Instant boundary = NOW.minusSeconds(1_000);
        playback(1, boundary.minusSeconds(700), boundary.minusSeconds(100), 200_000, false);
        PlaybackHealth later = playback(1, boundary.minusSeconds(94), boundary.plusSeconds(500), 200_000, false);
        PlaybackHealth inWindow = playback(1, boundary.plusSeconds(20), boundary.plusSeconds(600), 200_000, false);

        List<ChartRow> sessions = service.chart(boundary).sessions();

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).playbackId()).isEqualTo(inWindow.getPlaybackId());
        assertThat(sessions).extracting(ChartRow::playbackId).doesNotContain(later.getPlaybackId());
    }

    @Test
    void positionsOnBothSidesDecideTheJoinWhateverTheNetwork() {
        Instant start = NOW.minusSeconds(86_400);
        save(new UUID(0, 1).toString(), 1, start, start.plusSeconds(600), 200_000, false,
            report(OPERATOR, MAC, 0L, 1_000_000L));
        save(new UUID(0, 2).toString(), 1, start.plusSeconds(606), start.plusSeconds(1_200), 200_000, false,
            report("AS5089 Virgin Media Limited", MAC, 1_060_000L, 2_000_000L));
        save(new UUID(0, 3).toString(), 1, start.plusSeconds(1_300), start.plusSeconds(1_900), 200_000, false,
            report("AS5089 Virgin Media Limited", MAC, 0L, 900_000L));

        List<ChartRow> sessions = service.chart(null).sessions();

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).parts()).hasSize(2);
        assertThat(sessions.get(1).parts()).hasSize(1);
    }

    @Test
    void anEmptyTableHasNoViewings() {
        assertThat(service.chart(null).sessions()).isEmpty();
        assertThat(service.playbackCounts()).isEqualTo(new PlaybackCounts(0, 0));
    }

    // the counts

    @Test
    void theCountsJoinPartsKeepTheThirtyDayLineAndSplitOutTheSmoothOnes() {
        playback(1, THIRTY_DAYS_AGO, THIRTY_DAYS_AGO.plusSeconds(600), 180_000, false);
        playback(2, THIRTY_DAYS_AGO.minusMillis(1), THIRTY_DAYS_AGO.plusSeconds(600), 200_000, false);
        playback(3, THIRTY_DAYS_AGO.plusMillis(1), THIRTY_DAYS_AGO.plusSeconds(600), 200_000, false);
        // film 4: two parts of 100 s with a problem in the second: one film, not smooth
        playback(4, NOW.minusSeconds(5_000), NOW.minusSeconds(4_000), 100_000, false);
        playback(4, NOW.minusSeconds(3_990), NOW.minusSeconds(3_000), 100_000, true);
        // film 5: smooth
        playback(5, NOW.minusSeconds(2_000), NOW.minusSeconds(1_000), 180_001, false);

        assertThat(service.playbackCounts()).isEqualTo(new PlaybackCounts(4, 3));
    }

    @Test
    void theCountsLeaveOutARobotsViewingButTheChartKeepsItWithItsSource() {
        Instant start = NOW.minusSeconds(86_400);
        PlaybackHealth person = playback(1, start, start.plusSeconds(600), 200_000, false);
        PlaybackHealth robot = playback(1, start.plusSeconds(606), start.plusSeconds(1_200), 200_000, false);
        robot.setSource("robot");
        ObjectNode headless = report(OPERATOR, "Mozilla/5.0 (X11; Linux x86_64) HeadlessChrome/150.0.0.0", null, null);
        robot.setSummary(text(headless));
        health.saveAndFlush(robot);

        assertThat(service.playbackCounts()).isEqualTo(new PlaybackCounts(1, 1));
        assertThat(service.chart(null).sessions()).extracting(ChartRow::playbackId, ChartRow::source)
            .containsExactly(tuple(person.getPlaybackId(), "person"),
                tuple(robot.getPlaybackId(), "robot"));
    }

    @Test
    void theCountsAgreeWithTheChart() {
        Instant start = NOW.minusSeconds(86_400);
        playback(1, start, start.plusSeconds(600), 100_000, false);
        playback(1, start.plusSeconds(606), start.plusSeconds(1_200), 90_000, false);
        playback(2, start, start.plusSeconds(600), 50_000, false);

        assertThat(service.playbackCounts().films()).isEqualTo(service.chart(null).sessions().size()).isEqualTo(1);
    }
}

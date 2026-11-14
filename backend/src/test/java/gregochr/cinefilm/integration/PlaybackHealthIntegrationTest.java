package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.health.HealthSamples.example;
import static gregochr.cinefilm.health.HealthSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.health.HealthFigures;
import gregochr.cinefilm.health.PlaybackHealth;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** The playback health table on PostgreSQL, committed like production: the migration, the column and the update. */
@AutoConfigureMockMvc
class PlaybackHealthIntegrationTest extends IntegrationTestBase {

    private static final String ID = "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b";
    private static final HealthFigures FIGURES = new HealthFigures(300_000, 1, 500, 800L, true, "away");

    @Autowired
    private PlaybackHealthRepository health;

    @Autowired
    private FilmRepository films;

    @Autowired
    private FilmPreferenceRepository preferences;

    @Autowired
    private PlaybackStateRepository states;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PlatformTransactionManager transactions;

    private long editionId;

    @BeforeEach
    void data() {
        health.deleteAll();
        preferences.deleteAll();
        states.deleteAll();
        films.deleteAll();
        Film film = film(null, "Blade Runner", 1982, NOW, "Sci-fi");
        file(edition(film, null, "The Final Cut", 100 * MIN, null), "/nowhere/final.mp4");
        films.save(film);
        editionId = film.getEditions().get(0).getId();
    }

    private <T> T inNewTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    private static PlaybackHealth row(String playbackId, int seq, String summary) {
        PlaybackHealth row = new PlaybackHealth();
        row.setPlaybackId(playbackId);
        row.setEditionId(1L);
        row.setFilmId(1L);
        row.setFilmTitle("Blade Runner");
        row.setStartedAt(NOW.minusSeconds(60));
        row.setUpdatedAt(NOW);
        row.setSeq(seq);
        row.setSummary(summary);
        return row;
    }

    @Test
    void theTableAndItsIndexExistAfterTheMigrations() {
        Integer tables = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'playback_health'", Integer.class);
        Integer indexes = jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname = 'playback_health_updated_at_idx'", Integer.class);

        assertThat(tables).isEqualTo(1);
        assertThat(indexes).isEqualTo(1);
    }

    @Test
    void aSummaryOfSixteenThousandCharactersRoundTrips() {
        String summary = "{\"pad\":\"" + "x".repeat(16_000 - 10) + "\"}";
        assertThat(summary).hasSize(16_000);

        health.saveAndFlush(row(ID, 1, summary));

        PlaybackHealth loaded = health.findByPlaybackId(ID).orElseThrow();
        assertThat(loaded.getSummary()).isEqualTo(summary);
    }

    @Test
    void aSecondRowWithTheSamePlaybackIdIsRefusedAndTheIdCanStillBeLookedUpInANewTransaction() {
        health.saveAndFlush(row(ID, 1, "{}"));

        assertThatThrownBy(() -> inNewTransaction(() -> health.saveAndFlush(row(ID, 2, "{}"))))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(inNewTransaction(() -> health.findByPlaybackId(ID)).isPresent()).isTrue();
        assertThat(inNewTransaction(() -> health.findByPlaybackId(ID).orElseThrow().getSeq())).isEqualTo(1);
        assertThat(health.count()).isEqualTo(1);
    }

    @Test
    void theConditionalUpdateChangesOneRowForAHigherSeqAndNoneForAnEqualOrLowerOne() {
        health.saveAndFlush(row(ID, 4, "{\"v\":4}"));
        Instant later = NOW.plusSeconds(30);

        int higher = inNewTransaction(() -> health.replaceIfNewer(ID, 5, "{\"v\":5}", later, FIGURES));
        int equal = inNewTransaction(
            () -> health.replaceIfNewer(ID, 5, "{\"v\":\"equal\"}", later.plusSeconds(1), FIGURES));
        int lower = inNewTransaction(() -> health.replaceIfNewer(ID, 3, "{\"v\":3}", later.plusSeconds(2), FIGURES));

        assertThat(higher).isEqualTo(1);
        assertThat(equal).isZero();
        assertThat(lower).isZero();
        PlaybackHealth stored = health.findByPlaybackId(ID).orElseThrow();
        assertThat(stored.getSeq()).isEqualTo(5);
        assertThat(stored.getSummary()).isEqualTo("{\"v\":5}");
        assertThat(stored.getUpdatedAt()).isEqualTo(later);
        assertThat(stored.getStartedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(stored.getWatchedMs()).isEqualTo(300_000);
        assertThat(stored.getStallCount()).isEqualTo(1);
        assertThat(stored.getStallMs()).isEqualTo(500);
        assertThat(stored.getStartupMs()).isEqualTo(800);
        assertThat(stored.isHasProblem()).isTrue();
        assertThat(stored.getEnvironment()).isEqualTo("away");
    }

    @Test
    void aNullStartupTimeIsWrittenByTheConditionalUpdate() {
        health.saveAndFlush(row(ID, 1, "{}"));

        inNewTransaction(() -> health.replaceIfNewer(ID, 2, "{}", NOW,
            new HealthFigures(0, 0, 0, null, false, null)));

        PlaybackHealth stored = health.findByPlaybackId(ID).orElseThrow();
        assertThat(stored.getStartupMs()).isNull();
        assertThat(stored.getEnvironment()).isNull();
    }

    @Test
    void theStartedAtIndexExists() {
        Integer indexes = jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname = 'playback_health_started_at_idx'", Integer.class);

        assertThat(indexes).isEqualTo(1);
    }

    @Test
    void aReportAndAHigherOneOverHttpLeaveOneRowWithTheNewerValues() throws Exception {
        send(example(editionId, 4));
        ObjectNode later = example(editionId, 5);
        later.put("stallCount", 7);
        send(later);

        assertThat(health.count()).isEqualTo(1);
        PlaybackHealth stored = health.findByPlaybackId(ID).orElseThrow();
        assertThat(stored.getSeq()).isEqualTo(5);
        assertThat(stored.getEditionId()).isEqualTo(editionId);
        assertThat(stored.getFilmTitle()).isEqualTo("Blade Runner");
        assertThat(stored.getSummary()).contains("\"stallCount\":7");
    }

    private void send(ObjectNode body) throws Exception {
        mvc.perform(MockMvcRequestBuilders.put("/api/playback-health/" + ID)
                .with(user("owner").roles("OWNER")).header("X-CineFilm", "1")
                .contentType("application/json").content(text(body)))
            .andExpect(status().isNoContent());
    }
}

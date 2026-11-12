package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.ImdbRef;
import gregochr.cinefilm.film.FilmRepository.NeedRow;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** V15's columns, the due query and the targeted save and reset on PostgreSQL. */
@Transactional
class FilmOpenSubtitlesIntegrationTest extends IntegrationTestBase {

    /** A best result as 2c now writes it (with the file count): only a result without one is due again. */
    private static final String BEST = "{\"id\":1,\"fileId\":2,\"fileCount\":1}";
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));

    @Autowired
    private FilmRepository films;

    @Autowired
    private EntityManager entityManager;

    private Film saved(String title, String imdbId) {
        Film film = film(null, title, 2000, NOW);
        film.setImdbId(imdbId);
        return films.saveAndFlush(film);
    }

    @Test
    void theFourColumnsRoundTripIncludingAFullLengthBest() {
        Film film = film(null, "Round Trip", 2008, NOW);
        film.setImdbId("tt0936501");
        film.setOsForcedCount(7);
        film.setOsSearchedAt(NOW);
        film.setOsSearchedImdbId("tt0936501");
        film.setOsBest("x".repeat(2000));
        Long id = films.saveAndFlush(film).getId();
        // The test is transactional: without clearing, findById would hand back this same instance, not the row.
        entityManager.clear();

        Film loaded = films.findById(id).orElseThrow();

        assertThat(loaded.getOsForcedCount()).isEqualTo(7);
        assertThat(loaded.getOsSearchedAt()).isEqualTo(NOW);
        assertThat(loaded.getOsSearchedImdbId()).isEqualTo("tt0936501");
        assertThat(loaded.getOsBest()).hasSize(2000);
    }

    @Test
    void theDueQueryRunsAndFollowsTheRules() {
        Film never = saved("Never Searched", "tt0000011");
        Film zeroOld = saved("Zero Old", "tt0000012");
        Film zeroFresh = saved("Zero Fresh", "tt0000013");
        Film results = saved("Has Results", "tt0000014");
        Film other = saved("Searched Other", "tt0000015");
        saved("No Imdb Id", null);
        films.saveOpenSubtitles(zeroOld.getId(), "tt0000012", 0, null, CUTOFF.minusMillis(1));
        films.saveOpenSubtitles(zeroFresh.getId(), "tt0000013", 0, null, CUTOFF);
        films.saveOpenSubtitles(results.getId(), "tt0000014", 4, BEST, NOW.minus(Duration.ofDays(900)));
        films.saveOpenSubtitles(other.getId(), "tt0000015", 4, BEST, NOW);
        entityManager.clear();
        // The film then gets another IMDb id.
        Film moved = films.findById(other.getId()).orElseThrow();
        moved.setImdbId("tt0000099");
        films.saveAndFlush(moved);
        films.clearOpenSubtitles(other.getId());
        films.saveOpenSubtitles(other.getId(), "tt0000099", 4, BEST, NOW);
        films.flush();
        entityManager.clear();
        // A search of the old id is still on the row for this one: due again.
        Film stale = saved("Stale Marker", "tt0000016");
        films.saveOpenSubtitles(stale.getId(), "tt0000016", 4, BEST, NOW);
        entityManager.clear();
        Film staleLoaded = films.findById(stale.getId()).orElseThrow();
        staleLoaded.setOsSearchedImdbId("tt0000001");
        films.saveAndFlush(staleLoaded);

        List<ImdbRef> due = films.findOpenSubtitlesDue(CUTOFF);

        assertThat(due).extracting(ImdbRef::id).containsExactly(never.getId(), zeroOld.getId(), stale.getId());
    }

    @Test
    void theTargetedSaveStampsTheFilmForItsImdbIdAndLeavesAChangedOneAlone() {
        Film due = saved("Due", "tt0000021");
        Film changed = saved("Changed", "tt0000022");

        int saved = films.saveOpenSubtitles(due.getId(), "tt0000021", 3, "{\"id\":1}", NOW);
        int refused = films.saveOpenSubtitles(changed.getId(), "tt0000999", 3, "{\"id\":1}", NOW);
        entityManager.clear();

        assertThat(saved).isEqualTo(1);
        assertThat(refused).isZero();
        Film stamped = films.findById(due.getId()).orElseThrow();
        assertThat(stamped.getOsForcedCount()).isEqualTo(3);
        assertThat(stamped.getOsBest()).isEqualTo("{\"id\":1}");
        assertThat(stamped.getOsSearchedImdbId()).isEqualTo("tt0000021");
        assertThat(stamped.getOsSearchedAt()).isEqualTo(NOW);
        assertThat(films.findById(changed.getId()).orElseThrow().getOsSearchedAt()).isNull();
    }

    @Test
    void theResetAndTheNeedRowsAndTheLatestSearchRunOnPostgres() {
        Film film = saved("Reset", "tt0000031");
        films.saveOpenSubtitles(film.getId(), "tt0000031", 2, "{}", NOW);
        entityManager.clear();

        List<NeedRow> rows = films.findNeedRows();
        Instant latest = films.findLatestOpenSubtitlesSearchedAt();
        int cleared = films.clearOpenSubtitles(film.getId());
        entityManager.clear();

        assertThat(rows).filteredOn(row -> row.id().equals(film.getId())).singleElement().satisfies(row -> {
            assertThat(row.imdbId()).isEqualTo("tt0000031");
            assertThat(row.osForcedCount()).isEqualTo(2);
            assertThat(row.osSearchedImdbId()).isEqualTo("tt0000031");
        });
        assertThat(latest).isEqualTo(NOW);
        assertThat(cleared).isEqualTo(1);
        Film reloaded = films.findById(film.getId()).orElseThrow();
        assertThat(reloaded.getOsForcedCount()).isNull();
        assertThat(reloaded.getOsSearchedAt()).isNull();
        assertThat(reloaded.getOsSearchedImdbId()).isNull();
        assertThat(reloaded.getOsBest()).isNull();
    }
}

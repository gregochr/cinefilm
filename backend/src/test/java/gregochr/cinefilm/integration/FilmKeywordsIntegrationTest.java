package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmKeywords;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.TmdbRef;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V33 on PostgreSQL: the keywords table and stamp round-trip, the backfill query and the guarded save work. */
@Transactional
class FilmKeywordsIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private FilmKeywords keywords;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private Film saved(String title, Long tmdbId) {
        Film film = film(null, title, 2000, NOW);
        film.setTmdbId(tmdbId);
        return films.saveAndFlush(film);
    }

    @Test
    void theKeywordsAndTheStampRoundTripThroughTheKeywordTable() {
        Film film = film(null, "Keywords Round Trip", 1988, NOW);
        film.getKeywords().add("christmas");
        film.getKeywords().add("skyscraper");
        film.setKeywordsFetchedAt(NOW);
        Long id = films.saveAndFlush(film).getId();
        entityManager.clear();

        Film loaded = films.findById(id).orElseThrow();

        assertThat(loaded.getKeywords()).containsExactlyInAnyOrder("christmas", "skyscraper");
        assertThat(loaded.getKeywordsFetchedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("select count(*) from film_keyword where film_id = ?", Integer.class, id))
            .isEqualTo(2);
    }

    @Test
    void theBackfillQueryFindsOnlyMatchedFilmsWithNoStamp() {
        Film due = saved("Keywords Due", 771_001L);
        Film stamped = saved("Keywords Stamped", 771_002L);
        stamped.setKeywordsFetchedAt(NOW);
        films.saveAndFlush(stamped);
        Film unmatched = saved("Keywords Unmatched", null);

        List<TmdbRef> found = films.findWithoutKeywords();

        assertThat(found).contains(new TmdbRef(due.getId(), 771_001L));
        assertThat(found).extracting(TmdbRef::id).doesNotContain(stamped.getId(), unmatched.getId());
    }

    @Test
    void theGuardedSaveStoresForTheTmdbIdAskedAndLeavesARematchedFilmAlone() {
        Film due = saved("Keywords Save", 771_003L);
        Film rematched = saved("Keywords Rematched", 771_004L);

        boolean stored = keywords.save(due.getId(), 771_003L, List.of("christmas"), NOW);
        boolean refused = keywords.save(rematched.getId(), 771_999L, List.of("christmas"), NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(stored).isTrue();
        assertThat(refused).isFalse();
        Film reloaded = films.findById(due.getId()).orElseThrow();
        assertThat(reloaded.getKeywords()).containsExactly("christmas");
        assertThat(reloaded.getKeywordsFetchedAt()).isEqualTo(NOW);
        Film untouched = films.findById(rematched.getId()).orElseThrow();
        assertThat(untouched.getKeywords()).isEmpty();
        assertThat(untouched.getKeywordsFetchedAt()).isNull();
    }
}

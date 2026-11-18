package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmKeywords;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The keywords backfill and a rematch on two real transactions on PostgreSQL. The rematch loads a film with no
 * keywords, the backfill commits the old TMDB film's keywords and stamp in between, and the rematch then applies the
 * new film: the entity would skip the stamp (it loaded null, sets null), so only the explicit statements remove the
 * old film's keywords and stamp. Not transactional itself: the database is fresh per class.
 */
class FilmKeywordsInterleavingIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private FilmKeywords keywords;

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aBackfillThatCommitsBetweenTheRematchesLoadAndItsWriteLeavesNoOldKeywordsAndNoStamp() {
        TransactionTemplate rematch = new TransactionTemplate(transactions);
        TransactionTemplate backfill = new TransactionTemplate(transactions);
        backfill.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Film seed = film(null, "Keywords Interleaved", 1995, NOW);
        seed.setTmdbId(881_001L);
        Long id = films.saveAndFlush(seed).getId();
        MovieDetails newFilm = new MovieDetails(881_002L, "Keywords Interleaved", "Keywords Interleaved", 1995, null,
            100, List.of(), null, null, 0, List.of(), List.of(), null, null, null, List.of());

        rematch.executeWithoutResult(status -> {
            Film loaded = films.findById(id).orElseThrow();
            // Initialise the lazy keywords now, so Hibernate has no reason to reload and delete them after the
            // backfill commits: only the explicit row delete can remove what the backfill wrote.
            assertThat(loaded.getKeywords()).isEmpty();
            Boolean saved = backfill.execute(inner -> keywords.save(id, 881_001L, List.of("christmas"), NOW));
            assertThat(saved).isTrue();

            ingest.applyMovie(loaded, newFilm);
        });

        Film result = films.findById(id).orElseThrow();
        assertThat(result.getTmdbId()).isEqualTo(881_002L);
        assertThat(result.getKeywordsFetchedAt()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from film_keyword where film_id = ?", Integer.class, id))
            .isZero();
        assertThat(keywords.save(id, 881_001L, List.of("christmas"), NOW)).as("a late backfill for the old id")
            .isFalse();
        assertThat(films.findWithoutKeywords()).extracting(r -> r.id()).contains(id);
    }
}

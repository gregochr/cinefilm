package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The languages backfill and a rematch on two real transactions on PostgreSQL. The rematch loads a film with no
 * languages, the backfill commits the old TMDB film's languages in between, and the rematch then applies a film
 * whose original language is null: the entity would skip that column (it loaded null, sets null), so only the
 * explicit statement removes the old film's languages. Not transactional itself: the database is fresh per class.
 */
class FilmLanguagesInterleavingIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void aBackfillThatCommitsBetweenTheRematchesLoadAndItsWriteDoesNotLeaveTheOldFilmsLanguages() {
        TransactionTemplate rematch = new TransactionTemplate(transactions);
        TransactionTemplate backfill = new TransactionTemplate(transactions);
        backfill.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Film seed = film(null, "Interleaved", 1995, NOW);
        seed.setTmdbId(880_001L);
        Long id = films.saveAndFlush(seed).getId();
        MovieDetails newFilm = new MovieDetails(880_002L, "Interleaved", "Interleaved", 1995, null, 100, List.of(),
            null, null, 0, List.of(), List.of(), null, null, null, List.of());

        rematch.executeWithoutResult(status -> {
            Film loaded = films.findById(id).orElseThrow();
            Integer written = backfill.execute(inner -> films.saveLanguages(id, 880_001L, "fr", "fr,de", NOW));
            assertThat(written).isEqualTo(1);

            ingest.applyMovie(loaded, newFilm);
        });

        Film result = films.findById(id).orElseThrow();
        assertThat(result.getTmdbId()).isEqualTo(880_002L);
        assertThat(result.getOriginalLanguage()).isNull();
        assertThat(result.getSpokenLanguages()).isEmpty();
        assertThat(result.getLanguagesFetchedAt()).isEqualTo(NOW);
        assertThat(films.saveLanguages(id, 880_001L, "fr", "fr", NOW)).as("a late backfill for the old id").isZero();
    }
}

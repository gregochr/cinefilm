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
 * The subtitle search and a rematch on two real transactions on PostgreSQL. The rematch loads a film that has no
 * search, the search commits the old IMDb id's result in between, and the rematch then applies a film with another
 * IMDb id: the entity would skip the columns (it loaded null, sets null), so only the explicit statement removes the
 * old result. A search that was still waiting then finds the IMDb id gone. Not transactional itself: the database is
 * fresh per class.
 */
class FilmOpenSubtitlesInterleavingIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void aSearchThatCommitsBetweenTheRematchesLoadAndItsWriteDoesNotLeaveTheOldFilmsResult() {
        TransactionTemplate rematch = new TransactionTemplate(transactions);
        TransactionTemplate search = new TransactionTemplate(transactions);
        search.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Film seed = film(null, "Interleaved", 1995, NOW);
        seed.setImdbId("tt0880001");
        Long id = films.saveAndFlush(seed).getId();
        MovieDetails newFilm = new MovieDetails(880_002L, "Interleaved", "Interleaved", 1995, null, 100, List.of(),
            null, null, 0, List.of(), List.of(), null, "tt0880002");

        rematch.executeWithoutResult(status -> {
            Film loaded = films.findById(id).orElseThrow();
            Integer written = search.execute(
                inner -> films.saveOpenSubtitles(id, "tt0880001", 7, "{\"id\":1}", "[]", NOW));
            assertThat(written).isEqualTo(1);

            ingest.applyMovie(loaded, newFilm);
        });

        Film result = films.findById(id).orElseThrow();
        assertThat(result.getImdbId()).isEqualTo("tt0880002");
        assertThat(result.getOsForcedCount()).isNull();
        assertThat(result.getOsSearchedAt()).isNull();
        assertThat(result.getOsSearchedImdbId()).isNull();
        assertThat(result.getOsBest()).isNull();
        assertThat(films.saveOpenSubtitles(id, "tt0880001", 7, "{}", "[]", NOW)).as("a late search for the old id")
            .isZero();
    }
}

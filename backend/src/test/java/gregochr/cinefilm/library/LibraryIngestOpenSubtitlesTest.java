package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What OpenSubtitles said about a film is dropped, on the row, whenever TMDB gives the film another IMDb id. */
class LibraryIngestOpenSubtitlesTest extends ApiTestBase {

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private EntityManager em;

    private static MovieDetails movie(String imdbId) {
        return new MovieDetails(949, "Heat", "Heat", 1995, null, 170, List.of(), null, null, 0, List.of(),
            List.of(), null, imdbId);
    }

    private Long searchedFilm(String imdbId) {
        Film film = film(null, "Heat", 1995, NOW);
        film.setImdbId(imdbId);
        film.setOsForcedCount(7);
        film.setOsSearchedAt(NOW);
        film.setOsSearchedImdbId(imdbId);
        film.setOsBest("{\"id\":1}");
        Long id = films.saveAndFlush(film).getId();
        em.clear();
        return id;
    }

    private void assertRowForgotten(Long id) {
        em.flush();
        em.clear();
        Film row = films.findById(id).orElseThrow();
        assertThat(row.getOsForcedCount()).isNull();
        assertThat(row.getOsSearchedAt()).isNull();
        assertThat(row.getOsSearchedImdbId()).isNull();
        assertThat(row.getOsBest()).isNull();
    }

    @Test
    void aDifferentImdbIdClearsTheSearchOnTheFilmAndOnTheRow() {
        Long id = searchedFilm("tt0000001");
        Film loaded = films.findById(id).orElseThrow();

        ingest.applyMovie(loaded, movie("tt0113277"));

        assertThat(loaded.getImdbId()).isEqualTo("tt0113277");
        assertThat(loaded.getOsForcedCount()).isNull();
        assertRowForgotten(id);
        assertThat(films.findById(id).orElseThrow().getImdbId()).isEqualTo("tt0113277");
    }

    @Test
    void theSameImdbIdKeepsTheSearch() {
        Long id = searchedFilm("tt0113277");
        Film loaded = films.findById(id).orElseThrow();

        ingest.applyMovie(loaded, movie("tt0113277"));
        em.flush();
        em.clear();

        Film row = films.findById(id).orElseThrow();
        assertThat(row.getOsForcedCount()).isEqualTo(7);
        assertThat(row.getOsSearchedAt()).isEqualTo(NOW);
        assertThat(row.getOsSearchedImdbId()).isEqualTo("tt0113277");
        assertThat(row.getOsBest()).isEqualTo("{\"id\":1}");
    }

    @Test
    void aBlankOrMissingImdbIdChangesNothing() {
        for (String none : new String[] {null, "", "   "}) {
            Long id = searchedFilm("tt0113277");
            Film loaded = films.findById(id).orElseThrow();

            ingest.applyMovie(loaded, movie(none));
            em.flush();
            em.clear();

            assertThat(films.findById(id).orElseThrow().getOsForcedCount()).isEqualTo(7);
        }
    }

    @Test
    void aSearchSavedForTheOldImdbIdAfterTheRematchLoadedTheFilmIsRemovedAndALateOneIsRefused() {
        Film seed = film(null, "Heat", 1995, NOW);
        seed.setImdbId("tt0000001");
        Long id = films.saveAndFlush(seed).getId();
        em.clear();
        Film loaded = films.findById(id).orElseThrow();
        // Meanwhile the search saves the old IMDb id's result: the entity loaded nulls and will set nulls.
        assertThat(films.saveOpenSubtitles(id, "tt0000001", 7, "{\"id\":1}", "[]", NOW)).isEqualTo(1);

        ingest.applyMovie(loaded, movie("tt0113277"));

        assertRowForgotten(id);
        assertThat(films.saveOpenSubtitles(id, "tt0000001", 7, "{\"id\":1}", "[]", NOW)).as("a late search").isZero();
        assertThat(films.findById(id).orElseThrow().getOsForcedCount()).isNull();
    }

    @Test
    void aFilmNotSavedYetGainsAnImdbIdWithNothingToClear() {
        Film fresh = film(null, "Heat", 1995, NOW);

        ingest.applyMovie(fresh, movie("tt0113277"));

        assertThat(fresh.getImdbId()).isEqualTo("tt0113277");
        assertThat(fresh.getOsForcedCount()).isNull();
    }
}

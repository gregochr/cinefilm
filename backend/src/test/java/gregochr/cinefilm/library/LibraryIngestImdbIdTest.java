package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TMDB's IMDb id is kept on the film, and a different one drops the ratings fetched for the old film. */
class LibraryIngestImdbIdTest extends ApiTestBase {

    @Autowired
    private LibraryIngest ingest;

    private static MovieDetails movie(String imdbId) {
        return new MovieDetails(949, "Heat", "Heat", 1995, null, 170, List.of(), null, null, 0, List.of(),
            List.of(), null, imdbId);
    }

    private static Film rated() {
        Film film = film(null, "Heat", 1995, NOW);
        film.setImdbId("tt0113277");
        film.setImdbRating(new BigDecimal("8.3"));
        film.setImdbVotes(712345);
        film.setRtScore(84);
        film.setRtUrl("https://www.rottentomatoes.com/m/heat_1995");
        film.setRatingsFetchedAt(NOW);
        return film;
    }

    private static void assertStillRated(Film film) {
        assertThat(film.getImdbId()).isEqualTo("tt0113277");
        assertThat(film.getImdbRating()).isEqualByComparingTo("8.3");
        assertThat(film.getImdbVotes()).isEqualTo(712345);
        assertThat(film.getRtScore()).isEqualTo(84);
        assertThat(film.getRtUrl()).isEqualTo("https://www.rottentomatoes.com/m/heat_1995");
        assertThat(film.getRatingsFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void theImdbIdFromTmdbIsStoredOnTheFilm() {
        Film film = film(null, "Heat", 1995, NOW);

        ingest.applyMovie(film, movie("tt0113277"));

        assertThat(film.getImdbId()).isEqualTo("tt0113277");
        assertThat(film.getRatingsFetchedAt()).isNull();
    }

    @Test
    void anEmptyOrBlankOrMissingIdLeavesTheFilmWithNone() {
        for (String none : new String[] {null, "", "   "}) {
            Film film = film(null, "Heat", 1995, NOW);

            ingest.applyMovie(film, movie(none));

            assertThat(film.getImdbId()).isNull();
        }
    }

    @Test
    void anIdThatWouldNotFitTheColumnIsNotStored() {
        Film film = film(null, "Heat", 1995, NOW);

        ingest.applyMovie(film, movie("tt12345678901234567"));

        assertThat(film.getImdbId()).isNull();
    }

    @Test
    void anIdOfExactlySixteenCharactersIsStoredAndSurroundingSpaceIsTrimmed() {
        Film film = film(null, "Heat", 1995, NOW);

        ingest.applyMovie(film, movie(" tt1234567890123 "));

        assertThat(film.getImdbId()).isEqualTo("tt1234567890123");
    }

    @Test
    void theSameIdAgainKeepsTheRatingsAndTheirStamp() {
        Film film = rated();

        ingest.applyMovie(film, movie("tt0113277"));

        assertStillRated(film);
    }

    @Test
    void aMissingIdKeepsAnIdFoundEarlierAndItsRatings() {
        Film film = rated();

        ingest.applyMovie(film, movie(null));

        assertStillRated(film);
    }

    @Test
    void aDifferentIdMeansADifferentFilmSoTheOldRatingsAreDroppedToBeFetchedAgain() {
        Film film = rated();

        ingest.applyMovie(film, movie("tt0000001"));

        assertThat(film.getImdbId()).isEqualTo("tt0000001");
        assertThat(film.getImdbRating()).isNull();
        assertThat(film.getImdbVotes()).isNull();
        assertThat(film.getRtScore()).isNull();
        assertThat(film.getRtUrl()).isNull();
        assertThat(film.getRatingsFetchedAt()).isNull();
    }
}

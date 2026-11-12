package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What OpenSubtitles said belongs to the film's IMDb id: it is forgotten whenever that id changes. */
class FilmImdbIdOpenSubtitlesTest {

    private static Film searched(String imdbId) {
        Film film = film(null, "Taken", 2008, NOW);
        film.setImdbId(imdbId);
        film.setOsForcedCount(7);
        film.setOsSearchedAt(NOW);
        film.setOsSearchedImdbId(imdbId);
        film.setOsBest("{\"id\":1}");
        return film;
    }

    private static void assertForgotten(Film film) {
        assertThat(film.getOsForcedCount()).isNull();
        assertThat(film.getOsSearchedAt()).isNull();
        assertThat(film.getOsSearchedImdbId()).isNull();
        assertThat(film.getOsBest()).isNull();
    }

    private static void assertKept(Film film) {
        assertThat(film.getOsForcedCount()).isEqualTo(7);
        assertThat(film.getOsSearchedAt()).isEqualTo(NOW);
        assertThat(film.getOsSearchedImdbId()).isEqualTo(film.getImdbId());
        assertThat(film.getOsBest()).isEqualTo("{\"id\":1}");
    }

    @Test
    void aDifferentImdbIdClearsAllFourColumns() {
        Film film = searched("tt0936501");

        film.setImdbId("tt0113277");

        assertForgotten(film);
        assertThat(film.getImdbId()).isEqualTo("tt0113277");
    }

    @Test
    void gainingAnImdbIdAndLosingOneClearThemToo() {
        Film gained = searched(null);
        Film lost = searched("tt0936501");

        gained.setImdbId("tt0936501");
        lost.setImdbId(null);

        assertForgotten(gained);
        assertForgotten(lost);
        assertThat(lost.getImdbId()).isNull();
    }

    @Test
    void theSameImdbIdSetAgainKeepsThem() {
        Film film = searched("tt0936501");

        film.setImdbId("tt0936501");

        assertKept(film);
    }

    @Test
    void settingNullOnAFilmWithNoImdbIdKeepsThem() {
        Film film = searched(null);

        film.setImdbId(null);

        assertThat(film.getOsForcedCount()).isEqualTo(7);
        assertThat(film.getOsBest()).isEqualTo("{\"id\":1}");
    }
}

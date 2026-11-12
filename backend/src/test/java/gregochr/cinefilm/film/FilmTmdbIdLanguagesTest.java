package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A film's languages belong to its TMDB film: they are forgotten whenever the TMDB id changes. */
class FilmTmdbIdLanguagesTest {

    private static Film withLanguages(Long tmdbId) {
        Film film = film(null, "Heat", 1995, NOW);
        film.setTmdbId(tmdbId);
        film.setOriginalLanguage("fr");
        film.setSpokenLanguages("fr,de");
        film.setLanguagesFetchedAt(NOW);
        return film;
    }

    private static void assertForgotten(Film film) {
        assertThat(film.getOriginalLanguage()).isNull();
        assertThat(film.getSpokenLanguages()).isNull();
        assertThat(film.getLanguagesFetchedAt()).isNull();
    }

    @Test
    void aDifferentTmdbIdClearsTheLanguages() {
        Film film = withLanguages(111L);

        film.setTmdbId(949L);

        assertForgotten(film);
        assertThat(film.getTmdbId()).isEqualTo(949L);
    }

    @Test
    void gainingATmdbIdAndLosingOneClearTheLanguagesToo() {
        Film gained = withLanguages(null);
        Film lost = withLanguages(111L);

        gained.setTmdbId(949L);
        lost.setTmdbId(null);

        assertForgotten(gained);
        assertForgotten(lost);
        assertThat(lost.getTmdbId()).isNull();
    }

    @Test
    void theSameTmdbIdSetAgainKeepsTheLanguages() {
        Film film = withLanguages(111L);

        film.setTmdbId(111L);

        assertThat(film.getOriginalLanguage()).isEqualTo("fr");
        assertThat(film.getSpokenLanguages()).isEqualTo("fr,de");
        assertThat(film.getLanguagesFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void settingNullOnAFilmWithNoTmdbIdKeepsTheLanguages() {
        Film film = withLanguages(null);

        film.setTmdbId(null);

        assertThat(film.getOriginalLanguage()).isEqualTo("fr");
        assertThat(film.getLanguagesFetchedAt()).isEqualTo(NOW);
    }
}

package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A film's keywords belong to its TMDB film: they are forgotten whenever the TMDB id changes, kept otherwise. */
class FilmTmdbIdKeywordsTest {

    private static Film withKeywords(Long tmdbId) {
        Film film = film(null, "Heat", 1995, NOW);
        film.setTmdbId(tmdbId);
        film.getKeywords().add("christmas");
        film.setKeywordsFetchedAt(NOW);
        return film;
    }

    @Test
    void aDifferentTmdbIdClearsTheKeywordsAndTheStamp() {
        Film film = withKeywords(111L);

        film.setTmdbId(949L);

        assertThat(film.getKeywords()).isEmpty();
        assertThat(film.getKeywordsFetchedAt()).isNull();
    }

    @Test
    void theSameTmdbIdKeepsThem() {
        Film film = withKeywords(949L);

        film.setTmdbId(949L);

        assertThat(film.getKeywords()).containsExactly("christmas");
        assertThat(film.getKeywordsFetchedAt()).isEqualTo(NOW);
    }
}

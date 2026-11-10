package gregochr.cinefilm.film;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Gaining a TMDB id retries the ratings of a film that was stamped only for lack of any identity. */
class FilmTmdbIdRatingsTest {

    private static final Instant STAMP = Instant.parse("2026-11-01T00:00:00Z");

    private static Film stamped(Long tmdbId, String imdbId) {
        Film film = new Film();
        film.setTmdbId(tmdbId);
        film.setImdbId(imdbId);
        film.setImdbRating(new BigDecimal("7.5"));
        film.setImdbVotes(10);
        film.setRtScore(80);
        film.setRtUrl("https://www.rottentomatoes.com/m/x");
        film.setRatingsFetchedAt(STAMP);
        return film;
    }

    @Test
    void aDifferentTmdbIdOnAFilmWithNoImdbIdClearsTheStampAndRatings() {
        Film film = stamped(null, null);

        film.setTmdbId(949L);

        assertThat(film.getTmdbId()).isEqualTo(949L);
        assertThat(film.getRatingsFetchedAt()).isNull();
        assertThat(film.getImdbRating()).isNull();
        assertThat(film.getImdbVotes()).isNull();
        assertThat(film.getRtScore()).isNull();
        assertThat(film.getRtUrl()).isNull();
    }

    @Test
    void aChangedNonNullTmdbIdAlsoClearsTheStamp() {
        Film film = stamped(1L, null);

        film.setTmdbId(2L);

        assertThat(film.getRatingsFetchedAt()).isNull();
    }

    @Test
    void theSameTmdbIdKeepsTheStamp() {
        Film film = stamped(949L, null);

        film.setTmdbId(949L);

        assertThat(film.getRatingsFetchedAt()).isEqualTo(STAMP);
        assertThat(film.getRtScore()).isEqualTo(80);
    }

    @Test
    void aFilmWithAnImdbIdKeepsItsStampAndRatings() {
        Film film = stamped(null, "tt0113277");

        film.setTmdbId(949L);

        assertThat(film.getRatingsFetchedAt()).isEqualTo(STAMP);
        assertThat(film.getImdbRating()).isEqualByComparingTo("7.5");
    }

    @Test
    void clearingTheTmdbIdKeepsTheStamp() {
        Film film = stamped(949L, null);

        film.setTmdbId(null);

        assertThat(film.getTmdbId()).isNull();
        assertThat(film.getRatingsFetchedAt()).isEqualTo(STAMP);
    }
}

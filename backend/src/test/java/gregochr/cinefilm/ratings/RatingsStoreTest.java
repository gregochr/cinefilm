package gregochr.cinefilm.ratings;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.ratings.RatingsStore.Target;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RatingsStoreTest {

    private final FilmRepository films = mock(FilmRepository.class);
    private final RatingsStore store = new RatingsStore(films);
    private final Film heat = film(1L, "Heat", 1995, NOW);

    @Test
    void aTargetCarriesTheFilmsImdbAndTmdbIds() {
        heat.setImdbId("tt0113277");
        heat.setTmdbId(949L);
        when(films.findById(1L)).thenReturn(Optional.of(heat));

        assertThat(store.target(1L)).contains(new Target("tt0113277", 949L));
    }

    @Test
    void aFilmThatHasGoneHasNoTarget() {
        when(films.findById(2L)).thenReturn(Optional.empty());

        assertThat(store.target(2L)).isEmpty();
    }

    @Test
    void anImdbIdFoundLaterIsStoredOnTheFilm() {
        when(films.findById(1L)).thenReturn(Optional.of(heat));

        store.saveImdbId(1L, "tt0113277");

        assertThat(heat.getImdbId()).isEqualTo("tt0113277");
    }

    @Test
    void savingAnImdbIdForAFilmThatHasGoneDoesNothing() {
        when(films.findById(2L)).thenReturn(Optional.empty());

        store.saveImdbId(2L, "tt0113277");
    }

    @Test
    void ratingsAreStoredAndTheFilmIsStamped() {
        when(films.findById(1L)).thenReturn(Optional.of(heat));
        Duration later = Duration.ofMinutes(5);

        store.saveRatings(1L, new OmdbRatings(new BigDecimal("8.3"), 712345, 84, "https://rt.example/heat"),
            NOW.plus(later));

        assertThat(heat.getImdbRating()).isEqualByComparingTo("8.3");
        assertThat(heat.getImdbVotes()).isEqualTo(712345);
        assertThat(heat.getRtScore()).isEqualTo(84);
        assertThat(heat.getRtUrl()).isEqualTo("https://rt.example/heat");
        assertThat(heat.getRatingsFetchedAt()).isEqualTo(NOW.plus(later));
    }

    @Test
    void noRatingsClearsEveryFieldButStillStamps() {
        heat.setImdbRating(new BigDecimal("8.3"));
        heat.setImdbVotes(1);
        heat.setRtScore(84);
        heat.setRtUrl("https://rt.example/heat");
        when(films.findById(1L)).thenReturn(Optional.of(heat));

        store.saveRatings(1L, null, NOW);

        assertThat(heat.getImdbRating()).isNull();
        assertThat(heat.getImdbVotes()).isNull();
        assertThat(heat.getRtScore()).isNull();
        assertThat(heat.getRtUrl()).isNull();
        assertThat(heat.getRatingsFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void savingRatingsForAFilmThatHasGoneDoesNothing() {
        when(films.findById(2L)).thenReturn(Optional.empty());

        store.saveRatings(2L, null, NOW);
    }
}

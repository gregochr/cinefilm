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
    private static final OmdbRatings HEAT = new OmdbRatings(new BigDecimal("8.3"), 1, 84, null);
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
        when(films.findByIdForUpdate(1L)).thenReturn(Optional.of(heat));

        assertThat(store.saveImdbId(1L, "tt0113277")).isTrue();

        assertThat(heat.getImdbId()).isEqualTo("tt0113277");
    }

    @Test
    void anImdbIdAScanWroteMeanwhileIsNotOverwrittenByTheBackfill() {
        heat.setImdbId("tt0113277");
        when(films.findByIdForUpdate(1L)).thenReturn(Optional.of(heat));

        assertThat(store.saveImdbId(1L, "tt9999999")).isFalse();

        assertThat(heat.getImdbId()).isEqualTo("tt0113277");
    }

    @Test
    void savingAnImdbIdForAFilmThatHasGoneDoesNothing() {
        when(films.findByIdForUpdate(2L)).thenReturn(Optional.empty());

        assertThat(store.saveImdbId(2L, "tt0113277")).isFalse();
    }

    @Test
    void ratingsAreStoredAndTheFilmIsStamped() {
        heat.setImdbId("tt0113277");
        when(films.findByIdForUpdate(1L)).thenReturn(Optional.of(heat));
        Duration later = Duration.ofMinutes(5);

        boolean saved = store.saveRatings(1L, "tt0113277", new OmdbRatings(new BigDecimal("8.3"), 712345, 84, "https://rt.example/heat"),
            NOW.plus(later));

        assertThat(saved).isTrue();
        assertThat(heat.getImdbRating()).isEqualByComparingTo("8.3");
        assertThat(heat.getImdbVotes()).isEqualTo(712345);
        assertThat(heat.getRtScore()).isEqualTo(84);
        assertThat(heat.getRtUrl()).isEqualTo("https://rt.example/heat");
        assertThat(heat.getRatingsFetchedAt()).isEqualTo(NOW.plus(later));
    }

    @Test
    void noRatingsClearsEveryFieldButStillStamps() {
        heat.setImdbId("tt0113277");
        heat.setImdbRating(new BigDecimal("8.3"));
        heat.setImdbVotes(1);
        heat.setRtScore(84);
        heat.setRtUrl("https://rt.example/heat");
        when(films.findByIdForUpdate(1L)).thenReturn(Optional.of(heat));

        assertThat(store.saveRatings(1L, "tt0113277", null, NOW)).isTrue();

        assertThat(heat.getImdbRating()).isNull();
        assertThat(heat.getImdbVotes()).isNull();
        assertThat(heat.getRtScore()).isNull();
        assertThat(heat.getRtUrl()).isNull();
        assertThat(heat.getRatingsFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void savingRatingsForAFilmThatHasGoneDoesNothing() {
        when(films.findByIdForUpdate(2L)).thenReturn(Optional.empty());

        assertThat(store.saveRatings(2L, null, null, NOW)).isFalse();
    }

    @Test
    void ratingsFoundForAnIdTheFilmNoLongerHasAreDiscardedAndNotStamped() {
        heat.setImdbId("tt0113277");
        when(films.findByIdForUpdate(1L)).thenReturn(Optional.of(heat));

        // the refresher read the film with no id; a scan then gave it one
        boolean saved = store.saveRatings(1L, null, null, NOW);

        assertThat(saved).isFalse();
        assertThat(heat.getRatingsFetchedAt()).isNull();
        assertThat(heat.getImdbRating()).isNull();
    }

    @Test
    void aFilmThatStillHasNoIdTakesTheNoRatingsStamp() {
        when(films.findByIdForUpdate(1L)).thenReturn(Optional.of(heat));

        assertThat(store.saveRatings(1L, null, null, NOW)).isTrue();

        assertThat(heat.getRatingsFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void ratingsFoundForAnIdThatWasReplacedAreDiscarded() {
        heat.setImdbId("tt0000002");
        when(films.findByIdForUpdate(1L)).thenReturn(Optional.of(heat));

        assertThat(store.saveRatings(1L, "tt0000001", HEAT, NOW)).isFalse();

        assertThat(heat.getRatingsFetchedAt()).isNull();
    }
}

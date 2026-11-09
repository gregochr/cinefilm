package gregochr.cinefilm.ratings;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.tmdb.TmdbApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RatingsSchedulerTest {

    private final OmdbApi omdb = mock(OmdbApi.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final RatingsRefresher refresher = new RatingsRefresher(omdb, mock(TmdbApi.class),
        mock(FilmRepository.class), mock(RatingsStore.class), mock(RatingsBudget.class), duration -> { }, CLOCK,
        queued::add, 900);

    @Test
    void theDailyRunStartsARefreshOfTheDueFilms() {
        when(omdb.enabled()).thenReturn(true);

        new RatingsScheduler(refresher).daily();

        assertThat(refresher.isRunning()).isTrue();
        assertThat(queued).hasSize(1);
    }

    @Test
    void theDailyRunStartsNothingWithoutAnOmdbKey() {
        when(omdb.enabled()).thenReturn(false);

        new RatingsScheduler(refresher).daily();

        assertThat(refresher.isRunning()).isFalse();
        assertThat(queued).isEmpty();
    }
}

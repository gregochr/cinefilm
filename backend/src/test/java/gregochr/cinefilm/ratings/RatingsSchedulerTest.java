package gregochr.cinefilm.ratings;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class RatingsSchedulerTest {

    private final RatingsRefresher refresher = mock(RatingsRefresher.class);

    @Test
    void theDailyRunRefreshesTheDueFilms() {
        new RatingsScheduler(refresher).daily();

        verify(refresher).startDue();
    }
}

package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.tmdb.TmdbApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

class KeywordsSchedulerTest {

    private final TmdbApi tmdb = mock(TmdbApi.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final KeywordsRefresher refresher = new KeywordsRefresher(tmdb, mock(FilmRepository.class),
        mock(FilmKeywords.class), CLOCK, queued::add, 30, true);

    @Test
    void theNightlyRunStartsAKeywordsPass() {
        when(tmdb.enabled()).thenReturn(true);

        new KeywordsScheduler(refresher).nightly();

        assertThat(refresher.isRunning()).isTrue();
        assertThat(queued).hasSize(1);
    }

    @Test
    void theNightlyRunStartsNothingWithoutATmdbToken() {
        when(tmdb.enabled()).thenReturn(false);

        new KeywordsScheduler(refresher).nightly();

        assertThat(refresher.isRunning()).isFalse();
        assertThat(queued).isEmpty();
    }

    @Test
    void itRunsAtHalfPastThreeByDefaultFromAPropertyAndIsOffWithTheScanScheduleOrItsOwnSwitch()
        throws NoSuchMethodException {
        Scheduled scheduled = KeywordsScheduler.class.getMethod("nightly").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = KeywordsScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(scheduled.cron()).isEqualTo("${cinefilm.keywords.refresh-cron:0 30 3 * * *}");
        assertThat(scheduled.zone()).isEqualTo("${cinefilm.time-zone}");
        assertThat(condition.name()).containsExactly("cinefilm.scan.schedule", "cinefilm.keywords.refresh-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}

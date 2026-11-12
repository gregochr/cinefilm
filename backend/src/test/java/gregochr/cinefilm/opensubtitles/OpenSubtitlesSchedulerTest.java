package gregochr.cinefilm.opensubtitles;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.FilmRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;

class OpenSubtitlesSchedulerTest {

    private final OpenSubtitlesApi api = mock(OpenSubtitlesApi.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final OpenSubtitlesRefresher refresher = new OpenSubtitlesRefresher(api, mock(FilmRepository.class), CLOCK,
        queued::add, mock(ApplicationEventPublisher.class));

    @Test
    void theDailyRunStartsASearchOfTheDueFilms() {
        when(api.enabled()).thenReturn(true);

        new OpenSubtitlesScheduler(refresher).daily();

        assertThat(refresher.isRunning()).isTrue();
        assertThat(queued).hasSize(1);
    }

    @Test
    void theDailyRunStartsNothingWithoutAnApiKey() {
        when(api.enabled()).thenReturn(false);

        new OpenSubtitlesScheduler(refresher).daily();

        assertThat(refresher.isRunning()).isFalse();
        assertThat(queued).isEmpty();
    }

    @Test
    void itRunsAtSixInTheMorningInTheApplicationTimeZoneAndIsOffWithTheScanSchedule() throws NoSuchMethodException {
        Scheduled scheduled = OpenSubtitlesScheduler.class.getMethod("daily").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = OpenSubtitlesScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(scheduled.cron()).isEqualTo("0 0 6 * * *");
        assertThat(scheduled.zone()).isEqualTo("${cinefilm.time-zone}");
        assertThat(condition.name()).containsExactly("cinefilm.scan.schedule");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}

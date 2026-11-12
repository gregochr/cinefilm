package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

class ForcedDownloadSchedulerTest {

    @Test
    void theDailyRunAsksTheRefresherToDownloadForTheDueEditions() {
        ForcedDownloadRefresher refresher = mock(ForcedDownloadRefresher.class);

        new ForcedDownloadScheduler(refresher).daily();

        verify(refresher).startDue();
    }

    @Test
    void theHourlyNudgeOnlyRetriesWhatWasDeferred() throws NoSuchMethodException {
        ForcedDownloadRefresher refresher = mock(ForcedDownloadRefresher.class);

        new ForcedDownloadScheduler(refresher).hourly();

        verify(refresher).startIfDeferred();
        Scheduled scheduled = ForcedDownloadScheduler.class.getMethod("hourly").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 20 * * * *");
        assertThat(scheduled.zone()).isEqualTo("${cinefilm.time-zone}");
    }

    @Test
    void itRunsAtQuarterToSevenInTheApplicationTimeZoneAndIsOffWithTheScanSchedule() throws NoSuchMethodException {
        Scheduled scheduled = ForcedDownloadScheduler.class.getMethod("daily").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = ForcedDownloadScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(scheduled.cron()).isEqualTo("0 45 6 * * *");
        assertThat(scheduled.zone()).isEqualTo("${cinefilm.time-zone}");
        assertThat(condition.name()).containsExactly("cinefilm.scan.schedule");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}

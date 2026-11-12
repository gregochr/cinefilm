package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

class SidecarCheckSchedulerTest {

    @Test
    void theDailyRunAsksTheRefresherToCheckTheDueEditions() {
        SidecarCheckRefresher refresher = mock(SidecarCheckRefresher.class);

        new SidecarCheckScheduler(refresher).daily();

        verify(refresher).startDue();
    }

    @Test
    void itRunsAtHalfPastSixInTheApplicationTimeZoneAndIsOffWithTheScanSchedule() throws NoSuchMethodException {
        Scheduled scheduled = SidecarCheckScheduler.class.getMethod("daily").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = SidecarCheckScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(scheduled.cron()).isEqualTo("0 30 6 * * *");
        assertThat(scheduled.zone()).isEqualTo("${cinefilm.time-zone}");
        assertThat(condition.name()).containsExactly("cinefilm.scan.schedule");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}

package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

class CreditsDatasetSchedulerTest {

    @Test
    void theHourlyNudgeResumesOnlyAWaitingJob() {
        CreditsDatasetJob job = mock(CreditsDatasetJob.class);

        new CreditsDatasetScheduler(job).hourly();

        verify(job).startIfDeferred();
        verifyNoMoreInteractions(job);
    }

    @Test
    void itRunsHourlyInTheApplicationTimeZoneAndIsOffWithTheScanSchedule() throws NoSuchMethodException {
        Scheduled hourly = CreditsDatasetScheduler.class.getMethod("hourly").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = CreditsDatasetScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(hourly.cron()).isEqualTo("0 45 * * * *");
        assertThat(hourly.zone()).isEqualTo("${cinefilm.time-zone}");
        assertThat(condition.name()).containsExactly("cinefilm.scan.schedule");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}

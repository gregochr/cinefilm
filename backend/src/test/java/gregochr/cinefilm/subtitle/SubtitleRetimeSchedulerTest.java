package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

class SubtitleRetimeSchedulerTest {

    @Test
    void theNightlyRunAsksTheJobToStart() {
        SubtitleRetimeJob job = mock(SubtitleRetimeJob.class);

        new SubtitleRetimeScheduler(job).daily();

        verify(job).start();
    }

    @Test
    void itRunsAtTwentyToSixInTheApplicationTimeZoneAndIsOffWithTheScanSchedule() throws NoSuchMethodException {
        Scheduled scheduled = SubtitleRetimeScheduler.class.getMethod("daily").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = SubtitleRetimeScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(scheduled.cron()).isEqualTo("0 45 5 * * *");
        assertThat(scheduled.zone()).isEqualTo("${cinefilm.time-zone}");
        assertThat(condition.name()).containsExactly("cinefilm.scan.schedule");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}

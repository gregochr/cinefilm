package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

class CreditsSchedulerTest {

    @Test
    void theHourlyNudgeOnlyPicksUpWorkALastRunLeft() {
        CreditsRefresher refresher = mock(CreditsRefresher.class);

        new CreditsScheduler(refresher).hourly();

        verify(refresher).startIfDeferred();
        verifyNoMoreInteractions(refresher);
    }

    @Test
    void itRunsHourlyInTheApplicationTimeZoneAndIsOffWithTheScanSchedule() throws NoSuchMethodException {
        Scheduled hourly = CreditsScheduler.class.getMethod("hourly").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = CreditsScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(hourly.cron()).isEqualTo("0 45 * * * *");
        assertThat(hourly.zone()).isEqualTo("${cinefilm.time-zone}");
        assertThat(condition.name()).containsExactly("cinefilm.scan.schedule");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }

    @Test
    void theExecutorIsASingleDaemonThreadOfItsOwn() throws Exception {
        Executor executor = new CreditsConfig().creditsExecutor();
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get().getName()).isEqualTo("credits-detection");
        assertThat(ran.get().isDaemon()).isTrue();
    }
}

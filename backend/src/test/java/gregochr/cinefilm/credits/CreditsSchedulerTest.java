package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

class CreditsSchedulerTest {

    @Test
    void theHourlyNudgeOnlyPicksUpWorkALastRunLeft() {
        CreditsRefresher refresher = mock(CreditsRefresher.class);

        new CreditsScheduler(refresher, mock(CreditsApplier.class)).hourly();

        verify(refresher).startIfDeferred();
        verifyNoMoreInteractions(refresher);
    }

    @Test
    void theHourlyNudgeAsksTheApplierForAPass() {
        CreditsApplier applier = mock(CreditsApplier.class);

        new CreditsScheduler(mock(CreditsRefresher.class), applier).hourly();

        verify(applier).requestPass();
        verifyNoMoreInteractions(applier);
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
    void theApplyExecutorIsASingleDaemonThreadOfItsOwn() throws Exception {
        ExecutorService executor = new CreditsConfig().creditsApplyExecutor();
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        try {
            executor.execute(() -> {
                ran.set(Thread.currentThread());
                done.countDown();
            });

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ran.get().getName()).isEqualTo("credits-apply");
            assertThat(ran.get().isDaemon()).isTrue();
        } finally {
            executor.shutdownNow();
        }
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

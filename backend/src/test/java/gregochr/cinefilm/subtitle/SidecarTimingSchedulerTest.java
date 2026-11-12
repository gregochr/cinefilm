package gregochr.cinefilm.subtitle;

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

class SidecarTimingSchedulerTest {

    @Test
    void theDailyRunAsksTheRefresherToCheckTheDueFilms() {
        SidecarTimingRefresher refresher = mock(SidecarTimingRefresher.class);

        new SidecarTimingScheduler(refresher).daily();

        verify(refresher).startDue();
        verifyNoMoreInteractions(refresher);
    }

    @Test
    void theHourlyNudgeOnlyPicksUpWorkALastRunLeft() {
        SidecarTimingRefresher refresher = mock(SidecarTimingRefresher.class);

        new SidecarTimingScheduler(refresher).hourly();

        verify(refresher).startIfDeferred();
        verifyNoMoreInteractions(refresher);
    }

    @Test
    void itRunsAtSevenInTheApplicationTimeZoneAndIsOffWithTheScanSchedule() throws NoSuchMethodException {
        Scheduled daily = SidecarTimingScheduler.class.getMethod("daily").getAnnotation(Scheduled.class);
        Scheduled hourly = SidecarTimingScheduler.class.getMethod("hourly").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = SidecarTimingScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(daily.cron()).isEqualTo("0 0 7 * * *");
        assertThat(daily.zone()).isEqualTo("${cinefilm.time-zone}");
        assertThat(hourly.cron()).isEqualTo("0 15 * * * *");
        assertThat(hourly.zone()).isEqualTo("${cinefilm.time-zone}");
        assertThat(condition.name()).containsExactly("cinefilm.scan.schedule");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }

    @Test
    void theExecutorIsASingleDaemonThreadOfItsOwn() throws Exception {
        Executor executor = new TimingCheckConfig().timingCheckExecutor();
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get().getName()).isEqualTo("subtitle-timing");
        assertThat(ran.get().isDaemon()).isTrue();
    }
}

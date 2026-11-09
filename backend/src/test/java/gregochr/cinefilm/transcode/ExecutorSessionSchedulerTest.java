package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExecutorSessionSchedulerTest {

    private final ExecutorSessionScheduler scheduler = new ExecutorSessionScheduler();

    @Test
    void theUpkeepRunsOnANamedDaemonThread() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<Thread> thread = new AtomicReference<>();

        Runnable cancel = scheduler.scheduleEvery(Duration.ofMillis(1), () -> {
            thread.set(Thread.currentThread());
            ran.countDown();
        });

        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
        cancel.run();
        scheduler.shutdown();
        assertThat(thread.get().getName()).isEqualTo("session-upkeep");
        assertThat(thread.get().isDaemon()).isTrue();
    }

    @Test
    void theTaskRepeatsAndARunThatThrowsDoesNotStopTheNext() throws InterruptedException {
        CountDownLatch runs = new CountDownLatch(3);
        AtomicInteger calls = new AtomicInteger();

        Runnable cancel = scheduler.scheduleEvery(Duration.ofMillis(1), () -> {
            runs.countDown();
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("first run fails");
            }
        });

        assertThat(runs.await(10, TimeUnit.SECONDS)).isTrue();
        cancel.run();
        scheduler.shutdown();
    }
}

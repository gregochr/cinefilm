package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TranscodeConfigTest {

    @Test
    void theIndexThreadIsANamedDaemonAtMinimumPriority() throws Exception {
        ExecutorService executor = (ExecutorService) new TranscodeConfig().indexExecutor();
        AtomicReference<Thread> ran = new AtomicReference<>();

        executor.submit(() -> ran.set(Thread.currentThread())).get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(ran.get().getName()).isEqualTo("keyframe-index");
        assertThat(ran.get().isDaemon()).isTrue();
        assertThat(ran.get().getPriority()).isEqualTo(Thread.MIN_PRIORITY);
    }

    @Test
    void sessionSettingsCarryTheConfiguredLimits() {
        SessionSettings settings = new TranscodeConfig().sessionSettings(Duration.ofMinutes(2),
            Duration.ofMinutes(10), 5_368_709_120L, 2, "/dev/dri/renderD129");

        assertThat(settings).isEqualTo(new SessionSettings(Duration.ofMinutes(2), Duration.ofMinutes(10),
            5_368_709_120L, 2, "/dev/dri/renderD129"));
    }
}

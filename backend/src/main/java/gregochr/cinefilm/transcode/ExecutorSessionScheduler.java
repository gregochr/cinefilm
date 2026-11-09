package gregochr.cinefilm.transcode;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** {@link SessionScheduler} on one daemon thread. */
@Component
class ExecutorSessionScheduler implements SessionScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorSessionScheduler.class);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "session-upkeep");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Runnable scheduleEvery(Duration period, Runnable task) {
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.warn("Session upkeep failed: {}", e.toString());
            }
        }, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    /** Stops the thread on shutdown. */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}

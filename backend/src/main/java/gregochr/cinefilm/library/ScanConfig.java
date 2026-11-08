package gregochr.cinefilm.library;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wiring for scanning: one background thread for scans, and scheduling for the nightly run. */
@Configuration
@EnableScheduling
public class ScanConfig {

    /** A single daemon thread: only one scan ever runs at a time. */
    @Bean
    public Executor scanExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "library-scan");
            thread.setDaemon(true);
            return thread;
        });
    }
}

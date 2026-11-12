package gregochr.cinefilm.subtitle;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the subtitle timing check: one background thread of its own. */
@Configuration
public class TimingCheckConfig {

    /** A single daemon thread: only one timing check ever runs at a time. */
    @Bean
    public Executor timingCheckExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "subtitle-timing");
            thread.setDaemon(true);
            return thread;
        });
    }
}

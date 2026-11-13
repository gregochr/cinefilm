package gregochr.cinefilm.credits;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for credits detection: one background thread of its own. */
@Configuration
public class CreditsConfig {

    /** A single daemon thread: only one credits run ever happens at a time. */
    @Bean
    public Executor creditsExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "credits-detection");
            thread.setDaemon(true);
            return thread;
        });
    }
}

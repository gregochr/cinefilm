package gregochr.cinefilm.subtitle;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the TMDB languages backfill: one background thread of its own. */
@Configuration
public class LanguagesConfig {

    /** A single daemon thread: only one languages refresh ever runs at a time. */
    @Bean
    public Executor languagesExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "languages-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }
}

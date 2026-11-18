package gregochr.cinefilm.film;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the TMDB keywords backfill: one background thread of its own. */
@Configuration
public class KeywordsConfig {

    /** A single daemon thread: only one keywords refresh ever runs at a time. */
    @Bean
    public Executor keywordsExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "keywords-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }
}

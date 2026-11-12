package gregochr.cinefilm.subtitle;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the download of forced subtitles: one background thread of its own. */
@Configuration
public class ForcedDownloadConfig {

    /** A single daemon thread: only one download run ever goes at a time. */
    @Bean
    public Executor forcedDownloadExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "subtitle-download");
            thread.setDaemon(true);
            return thread;
        });
    }
}

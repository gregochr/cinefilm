package gregochr.cinefilm.transcode;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for transcoding: the low-priority index thread and the session limits. */
@Configuration
public class TranscodeConfig {

    /** One daemon thread at minimum priority: indexing never competes with playback. */
    @Bean
    public Executor indexExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "keyframe-index");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    /** How many files playback may index at once. */
    static final int SESSION_INDEX_THREADS = 2;

    /**
     * Indexing a playing film's later parts on demand, apart from the library-wide {@link #indexExecutor()} (which
     * {@code IndexJob} holds for a whole pass). Two daemon threads; the container shuts it down with the context. A
     * file the library pass is indexing at the same moment is indexed once ({@code KeyframeIndexService} locks per
     * file).
     */
    @Bean
    public ExecutorService sessionIndexExecutor() {
        return Executors.newFixedThreadPool(SESSION_INDEX_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "session-index");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The session limits from {@code cinefilm.transcode.idle-stop} (2 minutes), {@code expiry} (10 minutes),
     * {@code hls-cap-bytes} (5 GB) and {@code threads}.
     */
    @Bean
    public SessionSettings sessionSettings(@Value("${cinefilm.transcode.idle-stop:2m}") Duration idleStop,
                                           @Value("${cinefilm.transcode.expiry:10m}") Duration expiry,
                                           @Value("${cinefilm.transcode.hls-cap-bytes:5368709120}") long hlsCapBytes,
                                           @Value("${cinefilm.transcode.threads:2}") int threads,
                                           @Value("${cinefilm.transcode.device:/dev/dri/renderD128}")
                                           String device) {
        return new SessionSettings(idleStop, expiry, hlsCapBytes, threads, device);
    }
}

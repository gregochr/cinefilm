package gregochr.cinefilm.transcode;

import java.time.Duration;
import java.util.concurrent.Executor;
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

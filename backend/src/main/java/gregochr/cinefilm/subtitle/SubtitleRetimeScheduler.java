package gregochr.cinefilm.subtitle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly retime of downloaded subtitles, 05:45 in the application time zone (before the checks at 06:30 and the
 * timing fits at 07:00, so they see the cues as retimed). Switched off with the scan schedule
 * ({@code cinefilm.scan.schedule=false}, as in tests); the job itself does nothing while
 * {@code cinefilm.subtitles.retime} is {@code off} (it is {@code on} by default).
 */
@Component
@ConditionalOnProperty(name = "cinefilm.scan.schedule", havingValue = "true", matchIfMissing = true)
public class SubtitleRetimeScheduler {

    private final SubtitleRetimeJob job;

    /** Creates the scheduler. */
    public SubtitleRetimeScheduler(SubtitleRetimeJob job) {
        this.job = job;
    }

    /** Starts a run. */
    @Scheduled(cron = "0 45 5 * * *", zone = "${cinefilm.time-zone}")
    public void daily() {
        job.start();
    }
}

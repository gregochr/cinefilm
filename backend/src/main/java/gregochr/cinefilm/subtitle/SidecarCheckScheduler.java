package gregochr.cinefilm.subtitle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily check of the owner's forced subtitle files against OpenSubtitles, 06:30 in the application time zone (after
 * the daily subtitle search at 06:00, which also starts a check when it ends; this one catches a day's new download
 * budget). Switched off with the scan schedule ({@code cinefilm.scan.schedule=false}, as in tests).
 */
@Component
@ConditionalOnProperty(name = "cinefilm.scan.schedule", havingValue = "true", matchIfMissing = true)
public class SidecarCheckScheduler {

    private final SidecarCheckRefresher refresher;

    /** Creates the scheduler. */
    public SidecarCheckScheduler(SidecarCheckRefresher refresher) {
        this.refresher = refresher;
    }

    /** Checks the editions that are due. */
    @Scheduled(cron = "0 30 6 * * *", zone = "${cinefilm.time-zone}")
    public void daily() {
        refresher.startDue();
    }
}

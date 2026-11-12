package gregochr.cinefilm.opensubtitles;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily subtitle search, 06:00 in the application time zone (after the nightly scan at 04:30 and the ratings
 * refresh at 05:30). Switched off with the scan schedule ({@code cinefilm.scan.schedule=false}, as in tests).
 */
@Component
@ConditionalOnProperty(name = "cinefilm.scan.schedule", havingValue = "true", matchIfMissing = true)
public class OpenSubtitlesScheduler {

    private final OpenSubtitlesRefresher refresher;

    /** Creates the scheduler. */
    public OpenSubtitlesScheduler(OpenSubtitlesRefresher refresher) {
        this.refresher = refresher;
    }

    /** Searches the films that are due. */
    @Scheduled(cron = "0 0 6 * * *", zone = "${cinefilm.time-zone}")
    public void daily() {
        refresher.startDue();
    }
}

package gregochr.cinefilm.subtitle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily download of missing and replacement forced subtitles, 06:45 in the application time zone (after the search
 * at 06:00 and the checks at 06:30, each of which also starts a run when it ends; this one catches a day's new
 * download budget). Switched off with the scan schedule ({@code cinefilm.scan.schedule=false}, as in tests).
 */
@Component
@ConditionalOnProperty(name = "cinefilm.scan.schedule", havingValue = "true", matchIfMissing = true)
public class ForcedDownloadScheduler {

    private final ForcedDownloadRefresher refresher;

    /** Creates the scheduler. */
    public ForcedDownloadScheduler(ForcedDownloadRefresher refresher) {
        this.refresher = refresher;
    }

    /** Starts a run only when the last one found the library busy. */
    @Scheduled(cron = "0 20 * * * *", zone = "${cinefilm.time-zone}")
    public void hourly() {
        refresher.startIfDeferred();
    }

    /** Downloads for the editions that are due. */
    @Scheduled(cron = "0 45 6 * * *", zone = "${cinefilm.time-zone}")
    public void daily() {
        refresher.startDue();
    }
}

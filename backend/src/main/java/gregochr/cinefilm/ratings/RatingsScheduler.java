package gregochr.cinefilm.ratings;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily ratings refresh, 05:30 in the application time zone (after the nightly scan at 04:30). Switched off with
 * the scan schedule ({@code cinefilm.scan.schedule=false}, as in tests).
 */
@Component
@ConditionalOnProperty(name = "cinefilm.scan.schedule", havingValue = "true", matchIfMissing = true)
public class RatingsScheduler {

    private final RatingsRefresher refresher;

    /** Creates the scheduler. */
    public RatingsScheduler(RatingsRefresher refresher) {
        this.refresher = refresher;
    }

    /** Refreshes the films whose ratings are due. */
    @Scheduled(cron = "0 30 5 * * *", zone = "${cinefilm.time-zone}")
    public void daily() {
        refresher.startDue();
    }
}

package gregochr.cinefilm.film;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly keywords pass, 03:30 in the application time zone by default (before the scan at 04:30): it fetches
 * keywords for films never fetched and refetches those fetched more than {@code cinefilm.keywords.refresh-days} ago.
 * Change the time with {@code cinefilm.keywords.refresh-cron}; switch it off with
 * {@code cinefilm.keywords.refresh-enabled=false} or, with every schedule, {@code cinefilm.scan.schedule=false} (as
 * in tests).
 */
@Component
@ConditionalOnProperty(name = {"cinefilm.scan.schedule", "cinefilm.keywords.refresh-enabled"}, havingValue = "true",
    matchIfMissing = true)
public class KeywordsScheduler {

    private final KeywordsRefresher refresher;

    /** Creates the scheduler. */
    public KeywordsScheduler(KeywordsRefresher refresher) {
        this.refresher = refresher;
    }

    /** Starts the keywords pass (nothing happens without a TMDB token, or while one is running). */
    @Scheduled(cron = "${cinefilm.keywords.refresh-cron:0 30 3 * * *}", zone = "${cinefilm.time-zone}")
    public void nightly() {
        refresher.startDue();
    }
}

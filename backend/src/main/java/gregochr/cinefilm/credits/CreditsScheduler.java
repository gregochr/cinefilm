package gregochr.cinefilm.credits;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * An hourly nudge that picks up the credits analysis a run left because something was playing or a scan was running,
 * so a backfill does not wait for the next scan. New films are analysed after each scan (including the nightly one)
 * and at start-up by {@link CreditsRefresher} itself. Switched off with the scan schedule
 * ({@code cinefilm.scan.schedule=false}, as in tests).
 */
@Component
@ConditionalOnProperty(name = "cinefilm.scan.schedule", havingValue = "true", matchIfMissing = true)
public class CreditsScheduler {

    private final CreditsRefresher refresher;

    /** Creates the scheduler. */
    public CreditsScheduler(CreditsRefresher refresher) {
        this.refresher = refresher;
    }

    /** Starts a run only when the last one stopped to let playback or an edit go first. */
    @Scheduled(cron = "0 45 * * * *", zone = "${cinefilm.time-zone}")
    public void hourly() {
        refresher.startIfDeferred();
    }
}

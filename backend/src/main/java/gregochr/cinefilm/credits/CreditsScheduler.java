package gregochr.cinefilm.credits;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * An hourly nudge that picks up the credits analysis a run left because something was playing or a scan was running,
 * so a backfill does not wait for the next scan. New films are analysed after each scan (including the nightly one)
 * and at start-up by {@link CreditsRefresher} itself. Switched off with the scan schedule
 * ({@code cinefilm.scan.schedule=false}, as in tests). It also has {@link CreditsApplier} retry a pass
 * that was refused (the library lock stayed busy) or failed.
 */
@Component
@ConditionalOnProperty(name = "cinefilm.scan.schedule", havingValue = "true", matchIfMissing = true)
public class CreditsScheduler {

    private final CreditsRefresher refresher;
    private final CreditsApplier applier;

    /** Creates the scheduler. */
    public CreditsScheduler(CreditsRefresher refresher, CreditsApplier applier) {
        this.refresher = refresher;
        this.applier = applier;
    }

    /**
     * Starts an analysis run only when the last one stopped to let playback or an edit go first, and has the applier
     * retry a pass that was refused (the library lock stayed busy) or failed.
     */
    @Scheduled(cron = "0 45 * * * *", zone = "${cinefilm.time-zone}")
    public void hourly() {
        refresher.startIfDeferred();
        applier.retryIfRefused();
    }
}

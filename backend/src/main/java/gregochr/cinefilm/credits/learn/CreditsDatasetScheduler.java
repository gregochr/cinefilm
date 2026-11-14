package gregochr.cinefilm.credits.learn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * An hourly nudge that resumes the credits dataset job when it was left waiting for playback or for the credits
 * detection. Switched off with the scan schedule ({@code cinefilm.scan.schedule=false}, as in tests).
 *
 * <p>It fires at the same minute as {@code CreditsScheduler}. The order of the two does not matter: both only submit
 * work to the single credits thread, and the dataset job yields to the refresher's due files whenever it starts a
 * file, so the refresher's films are done first either way.
 */
@Component
@ConditionalOnProperty(name = "cinefilm.scan.schedule", havingValue = "true", matchIfMissing = true)
public class CreditsDatasetScheduler {

    private final CreditsDatasetJob job;

    /** Creates the scheduler. */
    public CreditsDatasetScheduler(CreditsDatasetJob job) {
        this.job = job;
    }

    /** Resumes the job only when it is waiting. */
    @Scheduled(cron = "0 45 * * * *", zone = "${cinefilm.time-zone}")
    public void hourly() {
        job.startIfDeferred();
    }
}

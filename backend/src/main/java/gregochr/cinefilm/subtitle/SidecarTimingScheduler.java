package gregochr.cinefilm.subtitle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily timing check of the owner's forced subtitle files against the film's own English track, 07:00 in the
 * application time zone, plus an hourly nudge that picks up work a run left because playback was encoding or a scan was
 * running. Switched off with the scan schedule ({@code cinefilm.scan.schedule=false}, as in tests).
 */
@Component
@ConditionalOnProperty(name = "cinefilm.scan.schedule", havingValue = "true", matchIfMissing = true)
public class SidecarTimingScheduler {

    private final SidecarTimingRefresher refresher;

    /** Creates the scheduler. */
    public SidecarTimingScheduler(SidecarTimingRefresher refresher) {
        this.refresher = refresher;
    }

    /** Checks the films that are due. */
    @Scheduled(cron = "0 0 7 * * *", zone = "${cinefilm.time-zone}")
    public void daily() {
        refresher.startDue();
    }

    /** Starts a run only when the last one stopped to let playback or an edit go first. */
    @Scheduled(cron = "0 15 * * * *", zone = "${cinefilm.time-zone}")
    public void hourly() {
        refresher.startIfDeferred();
    }
}

package gregochr.cinefilm.library;

import gregochr.cinefilm.film.FilmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * When scans start by themselves: at start-up if the library is empty, and nightly at 04:30 Europe/London (not Monday
 * 04:00 UTC, when goldenhour's security scan runs). Admin starts the third kind on demand.
 */
@Component
@ConditionalOnProperty(name = "cinefilm.scan.schedule", havingValue = "true", matchIfMissing = true)
public class ScanScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ScanScheduler.class);

    private final ScanService scans;
    private final FilmRepository films;

    /** Creates the scheduler. */
    public ScanScheduler(ScanService scans, FilmRepository films) {
        this.scans = scans;
        this.films = films;
    }

    /** Scans once the application is up, but only when there are no films yet. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (films.count() == 0) {
            begin("startup");
        }
    }

    /** The nightly scan, 04:30 in the application time zone. */
    @Scheduled(cron = "0 30 4 * * *", zone = "${cinefilm.time-zone}")
    public void nightly() {
        begin("nightly");
    }

    private void begin(String trigger) {
        try {
            scans.start(trigger);
        } catch (ScanAlreadyRunningException e) {
            LOG.info("Skipping {} scan: a scan or import is already running", trigger);
        }
    }
}

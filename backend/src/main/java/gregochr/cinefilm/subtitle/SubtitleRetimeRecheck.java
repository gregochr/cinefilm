package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.SidecarTimingRefresher.Fit;
import gregochr.cinefilm.subtitle.SubtitleRetimeService.TouchedEdition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The re-check that follows a retime run (docs/engineering/subtitle-frame-rate-plan.md, "The re-check runs at once"):
 * for each edition whose downloads were rewritten or restored, in turn, the timing fit of its film first and then the
 * checks of the edition, since a download's check waits for its fit. So a download that failed, or one whose proof was
 * taken away, is judged again within the run and not at the next scheduled pass. A fit that comes back busy (a scan or
 * an Admin edit holds the library lock) is tried again a bounded number of times ({@link BusyRetry}). The checks of an
 * edition run only when its film was fitted here: when the fit is left to a booked timing pass (it runs on this same
 * thread, after the job) or stays busy, the checks are left to the pass that follows it, which announces itself.
 * Nothing here throws to its caller.
 */
@Component
public class SubtitleRetimeRecheck {

    private static final Logger LOG = LoggerFactory.getLogger(SubtitleRetimeRecheck.class);

    private final SubtitleRetimeService service;
    private final SidecarTimingRefresher timing;
    private final SidecarCheckRefresher checks;
    private final BusyRetry retry;

    /** Creates the re-check. */
    public SubtitleRetimeRecheck(SubtitleRetimeService service, SidecarTimingRefresher timing,
                                 SidecarCheckRefresher checks, BusyRetry retry) {
        this.service = service;
        this.timing = timing;
        this.checks = checks;
        this.retry = retry;
    }

    /** The timing fit of a film, tried again while the library is busy. */
    private Fit fit(Long filmId) {
        Fit[] last = new Fit[1];
        retry.until("Timing fit of film " + filmId, () -> {
            last[0] = timing.fitFilm(filmId);
            return last[0] != Fit.BUSY;
        });
        return last[0];
    }

    /**
     * Fits and checks the editions the given downloads were made for.
     *
     * @param sidecarIds the downloads whose cues were rewritten or restored
     */
    public void run(Set<Long> sidecarIds) {
        try {
            Map<Long, Fit> fitted = new HashMap<>();
            List<TouchedEdition> touched = service.touchedEditions(sidecarIds);
            for (TouchedEdition edition : touched) {
                recheck(edition, fitted);
            }
            LOG.info("Subtitle retime: {} editions fitted and checked again", touched.size());
        } catch (RuntimeException e) {
            LOG.error("Subtitle retime re-check failed", e);
        }
    }

    private void recheck(TouchedEdition edition, Map<Long, Fit> fitted) {
        try {
            Fit fit = fitted.computeIfAbsent(edition.filmId(), this::fit);
            if (fit == Fit.DONE) {
                checks.checkEditions(List.of(edition.editionId()));
            } else {
                // A stale fit would count as "the fit exists" and let the check compare and download before the fit
                // that may prove the file: the checks follow the timing pass that is booked (it announces them).
                LOG.info("Edition {} not checked yet: the timing fit of film {} is {}", edition.editionId(),
                    edition.filmId(), fit);
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not fit and check edition {} again: {}", edition.editionId(),
                e.getClass().getSimpleName());
        }
    }
}

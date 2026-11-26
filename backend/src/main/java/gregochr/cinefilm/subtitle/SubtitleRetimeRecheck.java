package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.SubtitleRetimeService.TouchedEdition;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The re-check that follows a retime run (docs/engineering/subtitle-frame-rate-plan.md, "The re-check runs at once"):
 * for each edition whose downloads were rewritten or restored, in turn, the timing fit of its film first and then the
 * checks of the edition, since a download's check waits for its fit. So a download that failed, or one whose proof was
 * taken away, is judged again within the run and not at the next scheduled pass. A fit that comes back busy (a scan or
 * an Admin edit holds the library lock) is tried again a bounded number of times ({@link BusyRetry}); one that stays
 * busy is left due for the normal pass. Nothing here throws to its caller.
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

    /**
     * Fits and checks the editions the given downloads were made for.
     *
     * @param sidecarIds the downloads whose cues were rewritten or restored
     */
    public void run(Set<Long> sidecarIds) {
        try {
            Set<Long> fitted = new HashSet<>();
            List<TouchedEdition> touched = service.touchedEditions(sidecarIds);
            for (TouchedEdition edition : touched) {
                recheck(edition, fitted);
            }
            LOG.info("Subtitle retime: {} editions fitted and checked again", touched.size());
        } catch (RuntimeException e) {
            LOG.error("Subtitle retime re-check failed", e);
        }
    }

    private void recheck(TouchedEdition edition, Set<Long> fitted) {
        try {
            if (fitted.add(edition.filmId())) {
                retry.until("Timing fit of film " + edition.filmId(), () -> timing.fitFilm(edition.filmId()));
            }
            checks.checkEditions(List.of(edition.editionId()));
        } catch (RuntimeException e) {
            LOG.warn("Could not fit and check edition {} again: {}", edition.editionId(),
                e.getClass().getSimpleName());
        }
    }
}

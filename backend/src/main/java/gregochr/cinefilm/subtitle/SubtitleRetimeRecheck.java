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
 * for each touched edition in turn, the timing fit of its film first, then the edition's checks (a download's check
 * waits for its fit), so a failed download is judged again within the run. A fit that comes back busy is tried again
 * ({@link BusyRetry}); the first film whose fit stays busy stops the walk. The checks of an edition run only when its
 * film was fitted here: a fit left to a booked timing pass (it runs on this thread after the job) or a busy one leaves
 * them to the pass that follows the timing pass; a failed fit is kept by the timing refresher for its hourly retry,
 * and that pass announces itself. After the walk, with every film fitted, one check pass is asked for. Nothing here
 * throws to its caller.
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

    /** The full-file probe and the timing references forget these sidecars, so their next pass measures them again. */
    public void forgetMeasured(Set<Long> sidecarIds) {
        checks.onCuesRewritten(new SubtitleCuesRewritten(sidecarIds));
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
     * @return true when every touched film was fitted here, so a full check pass may be started
     *     ({@link #startFullPass}); false when a fit is still to come or the re-check failed
     */
    public boolean run(Set<Long> sidecarIds) {
        try {
            Map<Long, Fit> fitted = new HashMap<>();
            List<TouchedEdition> touched = service.touchedEditions(sidecarIds);
            for (TouchedEdition edition : touched) {
                recheck(edition, fitted);
                if (fitted.containsValue(Fit.BUSY)) {
                    // Each busy film costs its whole retry; the rest would wait as long. The deferred timing pass is
                    // already booked and fits them, and its end starts the checks.
                    LOG.info("Subtitle retime re-check stops: the library stayed busy, the rest is left to the "
                        + "deferred timing pass");
                    break;
                }
            }
            LOG.info("Subtitle retime: {} touched editions looked at", touched.size());
            // A full pass may follow only when no film's fit is still to come (a stale fit would let the pass compare
            // and download before it): the timing pass that fits it announces its end, which starts the checks.
            return fitted.values().stream().allMatch(fit -> fit == Fit.DONE);
        } catch (RuntimeException e) {
            LOG.error("Subtitle retime re-check failed", e);
            return false;
        }
    }

    /**
     * One request for a full check pass, so whatever the re-check left is caught. The caller makes it after the
     * rewritten sidecars were forgotten and the event published, so the pass measures each of them once.
     */
    public void startFullPass() {
        checks.startDue();
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

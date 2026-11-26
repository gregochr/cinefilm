package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.SidecarTimingRefresher.Fit;
import gregochr.cinefilm.subtitle.SubtitleRetimeService.TouchedEdition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The re-check that follows a retime run (docs/engineering/subtitle-frame-rate-plan.md, "The re-check runs at once"):
 * for each touched edition in turn, the timing fit of its film first, then the edition's checks, so a failed download
 * is judged again within the run. A fit that comes back busy is tried again ({@link BusyRetry}); the first film whose
 * fit stays busy stops the walk. An edition is checked only when its film was fitted here: a fit left to a booked
 * timing pass, a busy one or a failed one leaves it to the pass the event books after the job, then the daily pass.
 * {@link #run} says whether any fit was saved: the caller then announces it ({@link #announceFits}). Nothing here
 * throws to its caller.
 */
@Component
public class SubtitleRetimeRecheck {

    private static final Logger LOG = LoggerFactory.getLogger(SubtitleRetimeRecheck.class);

    private final SubtitleRetimeService service;
    private final ReferenceRetimeService referenceService;
    private final SidecarTimingRefresher timing;
    private final SidecarCheckRefresher checks;
    private final BusyRetry retry;

    /** Creates the re-check. */
    public SubtitleRetimeRecheck(SubtitleRetimeService service, ReferenceRetimeService referenceService,
                                 SidecarTimingRefresher timing, SidecarCheckRefresher checks, BusyRetry retry) {
        this.service = service;
        this.referenceService = referenceService;
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
     * @param referenceIds the stored references whose cues were rewritten or restored: every edition of their films
     *     is looked at too, after the downloads' editions
     * @return true when at least one fit was saved, so the caller should announce it ({@link #announceFits})
     */
    public boolean run(Set<Long> sidecarIds, Set<Long> referenceIds) {
        try {
            Map<Long, Fit> fitted = new HashMap<>();
            List<Long> fittedEditions = new ArrayList<>();
            List<TouchedEdition> touched = touched(sidecarIds, referenceIds);
            for (TouchedEdition edition : touched) {
                Fit fit = fitted.computeIfAbsent(edition.filmId(), this::fit);
                if (fit == Fit.DONE || fit == Fit.FITTED) {
                    fittedEditions.add(edition.editionId());
                } else {
                    // A stale fit would let the check compare and download before the fit: the booked pass checks it.
                    LOG.info("Edition {} not checked yet: the timing fit of film {} is {}", edition.editionId(),
                        edition.filmId(), fit);
                }
                if (fit == Fit.BUSY) {
                    // Each busy film costs its whole retry; the rest would wait as long. The deferred timing pass is
                    // already booked and fits them, and its end starts the checks.
                    LOG.info("Subtitle retime re-check stops: the library stayed busy, the rest is left to the "
                        + "deferred timing pass");
                    break;
                }
            }
            // One walk for every fitted edition: one due query, one announcement at its end. A stop (budget, outage)
            // ends the whole walk; the later editions stay due for the next pass. The fits are announced anyway.
            if (!fittedEditions.isEmpty()) {
                try {
                    checks.checkEditions(fittedEditions);
                } catch (RuntimeException e) {
                    LOG.warn("Could not check the retimed editions: {}", e.getClass().getSimpleName());
                }
            }
            LOG.info("Subtitle retime: {} touched editions looked at", touched.size());
            return fitted.containsValue(Fit.FITTED);
        } catch (RuntimeException e) {
            LOG.error("Subtitle retime re-check failed", e);
            return false;
        }
    }

    /** The editions of the changed downloads, then those of the changed references' films, each edition once. */
    private List<TouchedEdition> touched(Set<Long> sidecarIds, Set<Long> referenceIds) {
        Map<Long, TouchedEdition> touched = new LinkedHashMap<>();
        if (!sidecarIds.isEmpty()) {
            service.touchedEditions(sidecarIds).forEach(e -> touched.putIfAbsent(e.editionId(), e));
        }
        if (!referenceIds.isEmpty()) {
            referenceService.touchedEditions(referenceIds).forEach(e -> touched.putIfAbsent(e.editionId(), e));
        }
        return List.copyOf(touched.values());
    }

    /** Announces the fits the re-check saved. */
    public void announceFits() {
        timing.announce();
    }
}

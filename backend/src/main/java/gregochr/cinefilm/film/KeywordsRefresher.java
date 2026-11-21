package gregochr.cinefilm.film;

import gregochr.cinefilm.film.FilmRepository.TmdbRef;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.plex.PlexImportFinished;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Fills in TMDB's keywords for every film with a TMDB id and no keywords stamp (films stored before keywords were
 * kept, and films a scan or import has just added), in the background and one run at a time, after a scan, after a
 * Plex import and at start-up. TMDB is paced by its client. Each result is saved in one transaction that only matches
 * while the film still has the TMDB id it was fetched for, so a film rematched meanwhile keeps what the rematch
 * wrote and is left for the next run. A TMDB outage stops the run without stamping anything, so the films are asked
 * again; a film TMDB no longer has is stamped with no keywords so it is not asked every time. Nothing here throws to
 * its caller.
 */
@Service
public class KeywordsRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(KeywordsRefresher.class);

    private final TmdbApi tmdb;
    private final FilmRepository films;
    private final FilmKeywords store;
    private final Clock clock;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean lastRunIncomplete = new AtomicBoolean();

    /** Creates the service; the executor is {@code keywordsExecutor}. */
    public KeywordsRefresher(TmdbApi tmdb, FilmRepository films, FilmKeywords store, Clock clock,
                              @Qualifier("keywordsExecutor") Executor executor) {
        this.tmdb = tmdb;
        this.films = films;
        this.store = store;
        this.clock = clock;
        this.executor = executor;
    }

    /** True while a refresh is running. */
    public boolean isRunning() {
        return running.get();
    }

    /** True when a TMDB token is configured, so a backfill can run at all. */
    public boolean isEnabled() {
        return tmdb.enabled();
    }

    /** True when the last finished run stopped on a TMDB outage or could not refresh some film. */
    public boolean lastRunIncomplete() {
        return lastRunIncomplete.get();
    }

    /** Backfills at start-up, and says so when TMDB is off. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!startDue() && !tmdb.enabled()) {
            LOG.info("Film keywords are off: no TMDB token is configured");
        }
    }

    /** Backfills after a library scan (new films may have arrived). */
    @EventListener
    public void onScanFinished(ScanFinished event) {
        startDue();
    }

    /** Backfills after a Fix match (it can give a film a new TMDB id, whose keywords are not yet known). */
    @EventListener
    public void onFilmMatchFixed(FilmMatchFixed event) {
        startDue();
    }

    /** Backfills after a Plex import (it may have added films). */
    @EventListener
    public void onPlexImportFinished(PlexImportFinished event) {
        startDue();
    }

    /**
     * Asks for a backfill in the background, unless TMDB is off. When one is already running the request is not lost:
     * the running one goes round again before it finishes, so films a scan or import just added are covered.
     * Several requests during one run cause one more pass.
     *
     * @return true when this call started a run; false when TMDB is off or a run was already going
     */
    public boolean startDue() {
        if (!tmdb.enabled()) {
            return false;
        }
        // Set before trying to take the run: a run that is just finishing re-checks it after giving the run up.
        pending.set(true);
        return launch();
    }

    private boolean launch() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(this::worker);
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        return true;
    }

    /** Passes over the films for as long as a request is pending, then gives the run up. */
    private void worker() {
        try {
            while (pending.getAndSet(false)) {
                run();
            }
        } finally {
            running.set(false);
        }
        // A request that arrived after the last check but before the run was given up would otherwise be lost.
        if (pending.get()) {
            launch();
        }
    }

    /** One pass over the films without keywords. */
    void run() {
        int saved = 0;
        int skipped = 0;
        int failed = 0;
        boolean stopped = false;
        try {
            List<TmdbRef> due = films.findWithoutKeywords();
            for (TmdbRef ref : due) {
                try {
                    if (refresh(ref)) {
                        saved++;
                    } else {
                        skipped++;
                    }
                } catch (TmdbUnavailableException e) {
                    LOG.warn("Keywords refresh stopped: {}", e.getMessage());
                    stopped = true;
                    break;
                } catch (RuntimeException e) {
                    failed++;
                    LOG.warn("Could not refresh the keywords of film {}: {}", ref.id(), e.getClass().getSimpleName());
                }
            }
            LOG.info("Keywords refresh finished: {} saved, {} changed meanwhile, {} failed", saved, skipped, failed);
            lastRunIncomplete.set(stopped || failed > 0);
        } catch (RuntimeException e) {
            LOG.error("Keywords refresh failed", e);
            lastRunIncomplete.set(true);
        }
    }

    /** Asks TMDB and saves; false when the film changed or went while TMDB was asked. */
    private boolean refresh(TmdbRef ref) {
        Optional<List<String>> keywords = tmdb.keywords(ref.tmdbId());
        return store.save(ref.id(), ref.tmdbId(), keywords.orElse(List.of()), clock.instant());
    }
}

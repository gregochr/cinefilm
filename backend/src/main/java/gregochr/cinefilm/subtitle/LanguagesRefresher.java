package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.TmdbRef;
import gregochr.cinefilm.film.SpokenLanguages;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.plex.PlexImportFinished;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
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
 * Fills in TMDB's original and spoken languages for films stored before they were kept: every film with a TMDB id
 * and no languages stamp, in the background and one run at a time, after a scan, after a Plex import and at start-up.
 * TMDB is paced by its client. Each result is saved with one statement that only matches while the film still has
 * the TMDB id it was fetched for, so a film rematched meanwhile keeps what the rematch wrote and is left for the next
 * run. A TMDB outage stops the run without stamping anything, so the films are asked again; a film TMDB no longer
 * has is stamped with no languages so it is not asked every time. Nothing here throws to its caller.
 */
@Service
public class LanguagesRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(LanguagesRefresher.class);

    private final TmdbApi tmdb;
    private final FilmRepository films;
    private final Clock clock;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    /** Creates the service; the executor is {@code languagesExecutor}. */
    public LanguagesRefresher(TmdbApi tmdb, FilmRepository films, Clock clock,
                              @Qualifier("languagesExecutor") Executor executor) {
        this.tmdb = tmdb;
        this.films = films;
        this.clock = clock;
        this.executor = executor;
    }

    /** True while a refresh is running. */
    public boolean isRunning() {
        return running.get();
    }

    /** Backfills at start-up, and says so when TMDB is off. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!startDue() && !tmdb.enabled()) {
            LOG.info("Film languages are off: no TMDB token is configured");
        }
    }

    /** Backfills after a library scan (new films may have arrived). */
    @EventListener
    public void onScanFinished(ScanFinished event) {
        startDue();
    }

    /** Backfills after a Plex import (it applies TMDB details, which may have left films unstamped). */
    @EventListener
    public void onPlexImportFinished(PlexImportFinished event) {
        startDue();
    }

    /**
     * Starts a backfill in the background, unless TMDB is off or one is already running.
     *
     * @return true when a backfill was started
     */
    public boolean startDue() {
        if (!tmdb.enabled() || !running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    run();
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        return true;
    }

    /** One pass over the films without languages. */
    void run() {
        int saved = 0;
        int skipped = 0;
        int failed = 0;
        try {
            List<TmdbRef> due = films.findWithoutLanguages();
            for (TmdbRef ref : due) {
                try {
                    if (refresh(ref)) {
                        saved++;
                    } else {
                        skipped++;
                    }
                } catch (TmdbUnavailableException e) {
                    LOG.warn("Languages refresh stopped: {}", e.getMessage());
                    break;
                } catch (RuntimeException e) {
                    failed++;
                    LOG.warn("Could not refresh the languages of film {}: {}", ref.id(), e.getClass().getSimpleName());
                }
            }
            LOG.info("Languages refresh finished: {} saved, {} changed meanwhile, {} failed", saved, skipped, failed);
        } catch (RuntimeException e) {
            LOG.error("Languages refresh failed", e);
        }
    }

    /** Asks TMDB and saves; false when the film changed or went while TMDB was asked. */
    private boolean refresh(TmdbRef ref) {
        Optional<MovieDetails> movie = tmdb.movie(ref.tmdbId());
        String original = movie.map(MovieDetails::originalLanguage).orElse(null);
        String spoken = movie.map(m -> SpokenLanguages.store(m.spokenLanguages())).orElse(null);
        return films.saveLanguages(ref.id(), ref.tmdbId(), original, spoken, clock.instant()) > 0;
    }
}

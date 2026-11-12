package gregochr.cinefilm.opensubtitles;

import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.ImdbRef;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.plex.PlexImportFinished;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Searches OpenSubtitles for English foreign-parts-only subtitles of every film that has an IMDb id, in the
 * background and one run at a time: after a scan, after a Plex import, at start-up and daily. A film is due when it was
 * never searched, was searched under another IMDb id, or was searched with no result more than 30 days ago (subtitles
 * get uploaded later); never searched first, then the oldest. A search costs no download quota, and films that already
 * have a subtitle file are searched too (a later phase compares with the best result). The client paces the requests.
 * The top single-file candidates are saved with the best result (a legacy film with none is searched again, first).
 * Each result is saved with one statement that only matches while the film still has the IMDb id it was searched
 * for, so a film whose IMDb id changed meanwhile keeps what the change wrote and is left for the next run. An outage
 * stops the run without stamping anything, so the films are searched again; it is never recorded as "no results".
 * When a pass ends an {@link OpenSubtitlesSearchFinished} event is published (the check of the owner's subtitle files
 * listens). Nothing here throws to its caller.
 */
@Service
public class OpenSubtitlesRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSubtitlesRefresher.class);

    /** A search that found nothing is repeated when it is older than this. */
    static final Duration RESEARCH_AFTER = Duration.ofDays(30);

    private final OpenSubtitlesApi api;
    private final FilmRepository films;
    private final Clock clock;
    private final Executor executor;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();

    /** Creates the service; the executor is {@code openSubtitlesExecutor}. */
    public OpenSubtitlesRefresher(OpenSubtitlesApi api, FilmRepository films, Clock clock,
                                  @Qualifier("openSubtitlesExecutor") Executor executor,
                                  ApplicationEventPublisher events) {
        this.api = api;
        this.films = films;
        this.clock = clock;
        this.executor = executor;
        this.events = events;
    }

    /** True when an OpenSubtitles API key is configured. */
    public boolean enabled() {
        return api.enabled();
    }

    /** True while a search run is going. */
    public boolean isRunning() {
        return running.get();
    }

    /** Searches at start-up, and says so when the search is off. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!startDue() && !api.enabled()) {
            LOG.info("Subtitle search is off: no OpenSubtitles API key is configured (OPENSUBTITLES_API_KEY)");
        }
    }

    /** Searches after a library scan (new films may have arrived). */
    @EventListener
    public void onScanFinished(ScanFinished event) {
        startDue();
    }

    /** Searches after a Plex import (it applies TMDB details, so IMDb ids may have arrived or changed). */
    @EventListener
    public void onPlexImportFinished(PlexImportFinished event) {
        startDue();
    }

    /**
     * Asks for a search run in the background, unless the search is off. When one is already running the request is
     * not lost: the running one goes round again before it finishes, so films a scan or import just added are
     * covered. Several requests during one run cause one more pass.
     *
     * @return true when this call started a run; false when the search is off or a run was already going
     */
    public boolean startDue() {
        if (!api.enabled()) {
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

    /** Passes over the due films for as long as a request is pending, then gives the run up. */
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

    /** One pass over the films that are due. */
    void run() {
        int saved = 0;
        int skipped = 0;
        int failed = 0;
        try {
            List<ImdbRef> due = films.findOpenSubtitlesDue(clock.instant().minus(RESEARCH_AFTER));
            for (ImdbRef ref : due) {
                try {
                    if (search(ref)) {
                        saved++;
                    } else {
                        skipped++;
                    }
                } catch (OpenSubtitlesUnavailableException e) {
                    LOG.warn("Subtitle search stopped: {}", e.getMessage());
                    break;
                } catch (RuntimeException e) {
                    failed++;
                    LOG.warn("Could not search subtitles for film {}: {}", ref.id(), e.getClass().getSimpleName());
                }
            }
            LOG.info("Subtitle search finished: {} saved, {} changed meanwhile, {} failed", saved, skipped, failed);
        } catch (RuntimeException e) {
            LOG.error("Subtitle search failed", e);
        }
        announce();
    }

    /** Says the pass has ended, so the best results can be used; a listener's failure does not touch the run. */
    private void announce() {
        try {
            events.publishEvent(new OpenSubtitlesSearchFinished());
        } catch (RuntimeException e) {
            LOG.warn("A listener of the subtitle search failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Searches and saves; false when the film's IMDb id changed or the film went while OpenSubtitles was asked. */
    private boolean search(ImdbRef ref) {
        OpenSubtitlesSearch found = api.searchForced(ref.imdbId());
        String best = found.best() == null ? null : found.best().toJson();
        String candidates = OpenSubtitlesResult.listToJson(found.candidates());
        return films.saveOpenSubtitles(ref.id(), ref.imdbId(), found.count(), best, candidates, clock.instant()) > 0;
    }
}

package gregochr.cinefilm.ratings;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.plex.PlexImportFinished;
import gregochr.cinefilm.ratings.OmdbResult.Outcome;
import gregochr.cinefilm.ratings.RatingsStore.Target;
import gregochr.cinefilm.tmdb.Sleeper;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Keeps each film's IMDb and Rotten Tomatoes ratings fresh from OMDb, in the background and one refresh at a time.
 * A film is due when its ratings were never fetched or were fetched more than 30 days ago (never first, then the
 * oldest). Each OMDb call spends from a daily budget (counted per UTC day and kept in the database, so it survives a
 * restart; OMDb's free tier allows 1,000); when it is spent, or OMDb says its limit is reached, the batch stops
 * until tomorrow. A film with no IMDb id gets one from TMDB first; a film that still has none is stamped "no
 * ratings" without asking OMDb. A result is saved only if the film still has the IMDb id it was looked up under.
 * Nothing here throws to its caller: an outage is logged and the batch stops, leaving the film's stamp unchanged so
 * it is tried again.
 */
@Service
public class RatingsRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(RatingsRefresher.class);

    /** A film's ratings are refetched when they are older than this. */
    static final Duration STALE_AFTER = Duration.ofDays(30);

    /** The least time between two OMDb requests. */
    static final Duration PACE = Duration.ofMillis(250);

    private static final int IMDB_ID_MAX = 16;

    private final OmdbApi omdb;
    private final TmdbApi tmdb;
    private final FilmRepository films;
    private final RatingsStore store;
    private final RatingsBudget budget;
    private final Sleeper sleeper;
    private final Clock clock;
    private final Executor executor;
    private final int dailyLimit;
    private final AtomicBoolean running = new AtomicBoolean();

    /** Creates the service; the executor is {@code ratingsExecutor}, the limit {@code cinefilm.omdb.daily-limit}. */
    public RatingsRefresher(OmdbApi omdb, TmdbApi tmdb, FilmRepository films, RatingsStore store, RatingsBudget budget,
                            Sleeper sleeper, Clock clock, @Qualifier("ratingsExecutor") Executor executor,
                            @Value("${cinefilm.omdb.daily-limit:900}") int dailyLimit) {
        this.omdb = omdb;
        this.tmdb = tmdb;
        this.films = films;
        this.store = store;
        this.budget = budget;
        this.sleeper = sleeper;
        this.clock = clock;
        this.executor = executor;
        this.dailyLimit = dailyLimit;
    }

    /** True when an OMDb key is configured. */
    public boolean enabled() {
        return omdb.enabled();
    }

    /** True while a refresh is running. */
    public boolean isRunning() {
        return running.get();
    }

    /** Says once, at start-up, when ratings are switched off. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!enabled()) {
            LOG.info("Ratings are off: no OMDb API key is configured (OMDB_API_KEY)");
        }
    }

    /** Refreshes due films after a library scan (new films may have arrived). */
    @EventListener
    public void onScanFinished(ScanFinished event) {
        startDue();
    }

    /** Refreshes due films after a Plex import (it applies TMDB details, so IMDb ids may have arrived). */
    @EventListener
    public void onPlexImportFinished(PlexImportFinished event) {
        startDue();
    }

    /**
     * Starts a refresh of the due films in the background, unless ratings are off or one is already running.
     *
     * @return true when a refresh was started
     */
    public boolean startDue() {
        return enabled() && launch(false);
    }

    /**
     * Starts a refresh of every film, oldest first, ignoring the 30-day window (the daily budget still applies).
     *
     * @throws NotFoundException when no OMDb key is configured
     * @throws ConflictException when a refresh is already running
     */
    public void startForced() {
        if (!enabled()) {
            throw new NotFoundException("Ratings need an OMDb API key");
        }
        if (!launch(true)) {
            throw new ConflictException("A ratings refresh is already running");
        }
    }

    private boolean launch(boolean force) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    run(force);
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

    /** What to do after one film. */
    private enum Step { NEXT, STOP }

    /** State of one batch. */
    private static final class Batch {
        private boolean tmdbDown;
        private boolean identityChanged;
        private int asked;
        private int stamped;
        private int failed;
    }

    /** One pass over the films, in the order the repository gives them. */
    void run(boolean force) {
        Batch batch = new Batch();
        try {
            List<Long> ids = force ? films.findIdsByRatingsOldestFirst()
                : films.findIdsWithRatingsDueBefore(clock.instant().minus(STALE_AFTER));
            for (Long id : ids) {
                if (refresh(id, batch) == Step.STOP) {
                    break;
                }
            }
            LOG.info("Ratings refresh finished: {} OMDb requests, {} films stamped, {} failed", batch.asked,
                batch.stamped, batch.failed);
        } catch (RuntimeException e) {
            LOG.error("Ratings refresh failed", e);
        }
    }

    private Step refresh(Long id, Batch batch) {
        try {
            return refreshFilm(id, batch);
        } catch (RuntimeException e) {
            batch.failed++;
            LOG.warn("Could not refresh the ratings of film {}: {}", id, e.getClass().getSimpleName());
            return Step.NEXT;
        }
    }

    private Step refreshFilm(Long id, Batch batch) {
        Optional<Target> target = store.target(id);
        if (target.isEmpty()) {
            return Step.NEXT;
        }
        String imdbId = target.get().imdbId();
        Long tmdbId = target.get().tmdbId();
        if (imdbId == null && tmdbId != null) {
            // Stored before the id was kept: ask TMDB. If it cannot answer we do not know yet, so leave the film
            // unstamped and try again next time rather than recording "no ratings".
            if (batch.tmdbDown || !tmdb.enabled()) {
                return Step.NEXT;
            }
            batch.identityChanged = false;
            imdbId = backfill(id, tmdbId, batch);
            if (batch.tmdbDown || batch.identityChanged) {
                return Step.NEXT;
            }
        }
        if (imdbId == null) {
            return stamp(id, null, null, batch);
        }
        if (!takeCall()) {
            LOG.info("Ratings refresh stopped: the daily OMDb budget of {} requests is spent", dailyLimit);
            return Step.STOP;
        }
        return ask(id, imdbId, batch);
    }

    /** The film's IMDb id from TMDB, stored when found; null when TMDB has none (or is down: see the batch). */
    private String backfill(Long id, Long tmdbId, Batch batch) {
        try {
            String found = tmdb.imdbId(tmdbId).filter(s -> s.length() <= IMDB_ID_MAX).orElse(null);
            if (found != null && !store.saveImdbId(id, found)) {
                // The film got an id (or went) while TMDB was asked: leave it for the next run to start from.
                batch.identityChanged = true;
            }
            return found;
        } catch (TmdbUnavailableException e) {
            batch.tmdbDown = true;
            LOG.warn("TMDB could not give IMDb ids for the ratings refresh: {}", e.getMessage());
            return null;
        }
    }

    private Step ask(Long id, String imdbId, Batch batch) {
        if (batch.asked > 0 && !pace()) {
            return Step.STOP;
        }
        batch.asked++;
        OmdbResult result;
        try {
            result = omdb.lookup(imdbId);
        } catch (OmdbUnavailableException e) {
            LOG.warn("Ratings refresh stopped: {}", e.getMessage());
            return Step.STOP;
        }
        if (result.outcome() == Outcome.LIMIT_REACHED) {
            exhaustBudget();
            LOG.warn("Ratings refresh stopped: OMDb's request limit is reached");
            return Step.STOP;
        }
        return stamp(id, imdbId, result.outcome() == Outcome.FOUND ? result.ratings() : null, batch);
    }

    /** Saves the outcome for the IMDb id it was found under; a film whose id changed meanwhile is left unstamped. */
    private Step stamp(Long id, String imdbId, OmdbRatings ratings, Batch batch) {
        if (store.saveRatings(id, imdbId, ratings, clock.instant())) {
            batch.stamped++;
        } else {
            LOG.info("Ratings of film {} were not saved: it changed or went while OMDb was asked", id);
        }
        return Step.NEXT;
    }

    /** Waits out the pause between OMDb requests; false when interrupted. */
    private boolean pace() {
        try {
            sleeper.sleep(PACE);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean takeCall() {
        return budget.take(today(), dailyLimit);
    }

    private void exhaustBudget() {
        budget.exhaust(today(), dailyLimit);
    }

    /** The budget is per UTC day: a new day starts it again. */
    private LocalDate today() {
        return clock.instant().atOffset(ZoneOffset.UTC).toLocalDate();
    }
}

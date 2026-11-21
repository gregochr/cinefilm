package gregochr.cinefilm.credits;

import gregochr.cinefilm.credits.CreditsApply.Applied;
import gregochr.cinefilm.credits.CreditsApply.Part;
import gregochr.cinefilm.credits.CreditsApply.PlexRow;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.library.EditionsArranged;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.plex.PlexCreditsImportFinished;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import gregochr.cinefilm.plex.PlexImportFinished;
import gregochr.cinefilm.tmdb.Sleeper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies Plex's credits markers to editions: one {@link #runPass() pass} over every edition, under the library lock
 * (so an Edit editions save or an Admin marker change cannot land between the read and the write), in batches of
 * short transactions with a fixed number of queries each. Only editions whose marker or source changes are written.
 *
 * <p>Passes run on the applier's own thread. A trigger (start-up, a finished scan, Plex import or Plex credits import,
 * a saved Edit editions arrangement, the hourly nudge) only {@link #requestPass() asks}: it sets a flag and wakes the
 * worker, so it never runs inside the publisher's lock or transaction. A request that arrives during a pass gives
 * exactly one more pass; a pass that cannot take the lock leaves the flag set for the next trigger or nudge.
 * {@code LibraryChanged} is deliberately not a trigger (playback publishes it on every progress report). Off with
 * {@code cinefilm.credits.apply.enabled=false}, which stops the triggers and the nudge only.
 */
@Service
public class CreditsApplier {

    private static final Logger LOG = LoggerFactory.getLogger(CreditsApplier.class);
    /** How often a pass tries for the lock, and how long it waits between tries (10 minutes in all). */
    static final int LOCK_ATTEMPTS = 120;
    static final Duration LOCK_WAIT = Duration.ofSeconds(5);
    /** How many editions one transaction reads and writes. */
    static final int BATCH_SIZE = 100;

    /**
     * What one pass did.
     *
     * @param lockTaken false when the library lock could not be taken and nothing was read or written
     * @param plexSet editions that now have a {@code PLEX} marker (changed or not)
     * @param changed editions written
     * @param cleared editions written with no marker
     * @param ownerKept editions whose {@code OWNER} marker was left alone
     * @param unchanged editions not written
     * @param inProgressPastMarker changed editions' states in progress at or past the new marker
     */
    public record PassResult(boolean lockTaken, int plexSet, int changed, int cleared, int ownerKept, int unchanged,
                      long inProgressPastMarker) {

        static PassResult notTaken() {
            return new PassResult(false, 0, 0, 0, 0, 0, 0);
        }
    }

    private record Batch(int plexSet, int changed, int cleared, int ownerKept, long inProgress) {
    }

    private final EditionRepository editions;
    private final PlexCreditsMarkerRepository markers;
    private final PlaybackStateRepository states;
    private final LibraryLock lock;
    private final Sleeper sleeper;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();

    /** Creates the applier; the executor is {@code creditsApplyExecutor}. */
    public CreditsApplier(EditionRepository editions, PlexCreditsMarkerRepository markers,
                          PlaybackStateRepository states, LibraryLock lock, Sleeper sleeper,
                          PlatformTransactionManager transactions,
                          @Qualifier("creditsApplyExecutor") Executor executor,
                          @Value("${cinefilm.credits.apply.enabled:true}") boolean enabled) {
        this.editions = editions;
        this.markers = markers;
        this.states = states;
        this.lock = lock;
        this.sleeper = sleeper;
        this.tx = new TransactionTemplate(transactions);
        this.executor = executor;
        this.enabled = enabled;
    }

    /** Asks for a pass at start-up. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        requestPass();
    }

    /** Asks for a pass after a library scan (a file may have been added, replaced or moved). */
    @EventListener
    public void onScanFinished(ScanFinished event) {
        requestPass();
    }

    /** Asks for a pass after a Plex import (the library may have changed under the editions). */
    @EventListener
    public void onPlexImportFinished(PlexImportFinished event) {
        requestPass();
    }

    /** Asks for a pass after a Plex credits import (new rows to apply). */
    @EventListener
    public void onPlexCreditsImportFinished(PlexCreditsImportFinished event) {
        requestPass();
    }

    /** Asks for a pass after an Edit editions save (editions, parts or markers may have moved). */
    @EventListener
    public void onEditionsArranged(EditionsArranged event) {
        requestPass();
    }

    /**
     * Asks for a pass in the background, unless applying is off. Never runs one inline. When a pass is already going
     * the request is not lost: it goes round again once before it finishes, however many requests arrive.
     */
    public void requestPass() {
        if (!enabled) {
            return;
        }
        // Set before trying to take the worker: a worker that is just finishing re-checks it after giving up.
        pending.set(true);
        launch();
    }

    /**
     * The hourly nudge: starts a pass only when the last one could not take the library lock and left its request
     * pending. After a pass that ran, nothing is pending and this does nothing.
     */
    public void retryIfRefused() {
        if (enabled && pending.get()) {
            launch();
        }
    }

    private void launch() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::worker);
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    private void worker() {
        boolean refused = false;
        try {
            while (!refused && pending.getAndSet(false)) {
                try {
                    refused = !runPass().lockTaken();
                } catch (RuntimeException e) {
                    LOG.error("Credits apply pass failed", e);
                }
                if (refused && pending.getAndSet(true)) {
                    // pending was cleared when this pass began, so a request arrived while it waited for the lock
                    // (a scan or import publishes its event while still holding it): give it one more pass.
                    refused = false;
                }
            }
        } finally {
            running.set(false);
        }
        // A request that arrived after the last check but before the worker was given up would otherwise be lost.
        if (!refused && pending.get()) {
            launch();
        }
    }

    /**
     * Runs one pass: waits for the library lock (bounded), applies every edition in batches, releases the lock.
     *
     * @return what it did; {@code lockTaken} false when the lock stayed busy (or the wait was interrupted)
     */
    public PassResult runPass() {
        if (!acquireLock()) {
            return PassResult.notTaken();
        }
        try {
            return applyAll();
        } finally {
            lock.release();
        }
    }

    private boolean acquireLock() {
        String holder = "another job";
        for (int attempt = 0; attempt < LOCK_ATTEMPTS; attempt++) {
            if (lock.tryAcquire(LibraryLock.CREDITS)) {
                return true;
            }
            holder = Objects.requireNonNullElse(lock.holder(), holder);
            try {
                sleeper.sleep(LOCK_WAIT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("Credits apply pass interrupted while waiting for the library lock; nothing was changed");
                return false;
            }
        }
        LOG.info("Credits apply pass gave up: the library stayed locked by \"{}\" for {} minutes",
            holder, LOCK_WAIT.multipliedBy(LOCK_ATTEMPTS).toMinutes());
        return false;
    }

    private PassResult applyAll() {
        List<Long> ids = editions.findAllIds();
        int plexSet = 0;
        int changed = 0;
        int cleared = 0;
        int ownerKept = 0;
        long inProgress = 0;
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + BATCH_SIZE));
            Batch done = tx.execute(status -> applyBatch(batch));
            plexSet += done.plexSet();
            changed += done.changed();
            cleared += done.cleared();
            ownerKept += done.ownerKept();
            inProgress += done.inProgress();
        }
        PassResult result = new PassResult(true, plexSet, changed, cleared, ownerKept, ids.size() - changed,
            inProgress);
        LOG.info("Credits apply pass: {} with a Plex marker, {} changed, {} cleared, {} owner markers kept, "
                + "{} unchanged; {} saved positions in progress are at or past their new marker",
            result.plexSet(), result.changed(), result.cleared(), result.ownerKept(), result.unchanged(),
            result.inProgressPastMarker());
        return result;
    }

    /** Reads, computes and writes one batch: a fixed number of reads however many editions it holds. */
    private Batch applyBatch(List<Long> batch) {
        List<Edition> loaded = editions.findWithFilesByIdIn(batch);
        List<Long> lastPartIds = new ArrayList<>();
        for (Edition edition : loaded) {
            List<MediaFile> originals = edition.originalParts();
            if (!originals.isEmpty()) {
                lastPartIds.add(originals.get(originals.size() - 1).getId());
            }
        }
        Map<Long, PlexCreditsMarker> rows = lastPartIds.isEmpty() ? Map.of()
            : markers.findAllById(lastPartIds).stream()
                .collect(Collectors.toMap(PlexCreditsMarker::getMediaFileId, m -> m));
        int plexSet = 0;
        int cleared = 0;
        int ownerKept = 0;
        List<Edition> toSave = new ArrayList<>();
        for (Edition edition : loaded) {
            List<MediaFile> originals = edition.originalParts();
            List<Part> parts = originals.stream().map(f -> new Part(f.getId(), f.timelineDurationMs())).toList();
            MediaFile last = originals.isEmpty() ? null : originals.get(originals.size() - 1);
            PlexCreditsMarker row = last == null ? null : rows.get(last.getId());
            Applied applied = CreditsApply.apply(parts, last == null ? null : last.fingerprint(),
                edition.getCreditsMarkerMs(), edition.getCreditsMarkerSource(),
                row == null ? null : new PlexRow(row.getFingerprint(), row.getStartMs()));
            if (applied.source() == CreditsMarkerSource.PLEX) {
                plexSet++;
            }
            if (applied.source() == CreditsMarkerSource.OWNER) {
                ownerKept++;
            }
            if (Objects.equals(applied.markerMs(), edition.getCreditsMarkerMs())
                && applied.source() == edition.getCreditsMarkerSource()) {
                continue;
            }
            edition.setCreditsMarkerMs(applied.markerMs());
            edition.setCreditsMarkerSource(applied.source());
            toSave.add(edition);
            if (applied.markerMs() == null) {
                cleared++;
            }
        }
        long inProgress = 0;
        if (!toSave.isEmpty()) {
            editions.saveAll(toSave);
            inProgress = states.countInProgressAtOrPastMarker(toSave.stream().map(Edition::getId).toList());
        }
        return new Batch(plexSet, toSave.size(), cleared, ownerKept, inProgress);
    }
}

package gregochr.cinefilm.credits;

import gregochr.cinefilm.credits.CreditsApply.Applied;
import gregochr.cinefilm.credits.CreditsApply.Part;
import gregochr.cinefilm.credits.CreditsApply.AutoInputs;
import gregochr.cinefilm.credits.CreditsApply.PlexRow;
import gregochr.cinefilm.credits.CreditsDenyFile.DenyList;
import gregochr.cinefilm.credits.CreditsDenyFile.Parsed;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.FilmMatchFixed;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies Plex's credits markers to editions: one {@link #runPass() pass} over every edition, under the library lock
 * (so an Edit editions save or an Admin marker change cannot land between the read and the write), in batches of
 * short transactions with a fixed number of queries each. Only editions whose marker or source changes are written.
 *
 * <p>Passes run on the applier's own thread. A trigger (start-up, a finished scan, Plex import or Plex credits import,
 * a saved Edit editions arrangement) only {@link #requestPass() asks}: it sets {@code requested} and wakes the worker,
 * so it never runs inside the publisher's lock or transaction. A request that arrives during a pass gives exactly one
 * more pass, refused or not, so the loop is bounded by requests. A pass that is refused (the lock stayed busy) or
 * fails sets {@code owed}, which is cleared when the next pass starts. The hourly nudge is not a trigger:
 * {@link #retryIfRefused()} only retries a pass that is owed.
 * {@code LibraryChanged} is deliberately not a trigger (playback publishes it on every progress report). Off with
 * {@code cinefilm.credits.apply.enabled=false}, which stops the triggers and the nudge only.
 */
@Service
public class CreditsApplier {

    private static final Logger LOG = LoggerFactory.getLogger(CreditsApplier.class);
    /** How often a pass tries for the lock, and how long it waits between tries (10 minutes in all). */
    static final int LOCK_ATTEMPTS = 120;
    static final Duration LOCK_WAIT = Duration.ofSeconds(5);
    /** The order of the start-up listener: after the scan scheduler's, which starts the first scan. */
    public static final int STARTUP_ORDER = 10;
    /** How many editions one transaction reads and writes. */
    static final int BATCH_SIZE = 100;

    /**
     * What one pass did.
     *
     * @param lockTaken false when the library lock could not be taken and nothing was read or written
     * @param plexSet editions that now have a {@code PLEX} marker (changed or not)
     * @param autoSet editions that now have an {@code AUTO} marker (changed or not)
     * @param changed editions written
     * @param cleared editions written with no marker
     * @param ownerKept editions whose {@code OWNER} marker was left alone
     * @param unchanged editions not written
     * @param inProgressPastMarker changed editions' states in progress at or past the new marker
     */
    public record PassResult(boolean lockTaken, int plexSet, int autoSet, int changed, int cleared, int ownerKept,
                             int unchanged, long inProgressPastMarker) {

        static PassResult notTaken() {
            return new PassResult(false, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private record Batch(int plexSet, int autoSet, int changed, int cleared, int ownerKept, long inProgress) {
    }

    private final EditionRepository editions;
    private final PlexCreditsMarkerRepository markers;
    private final PlaybackStateRepository states;
    private final LibraryLock lock;
    private final Sleeper sleeper;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final CreditsDenyListKeeper denyList;
    private final CreditsAutoReader auto;
    private final boolean enabled;
    private final AtomicBoolean requested = new AtomicBoolean(); // a trigger asked
    private final AtomicBoolean owed = new AtomicBoolean(); // the last pass was refused or failed
    private final AtomicBoolean running = new AtomicBoolean();
    /** Run by the worker just before it gives itself up; a test calls {@link #requestPass()} here. */
    Runnable beforeWorkerEnds = () -> { };

    /** Creates the applier; the executor is {@code creditsApplyExecutor}. */
    public CreditsApplier(EditionRepository editions, PlexCreditsMarkerRepository markers,
                          PlaybackStateRepository states, LibraryLock lock, Sleeper sleeper,
                          PlatformTransactionManager transactions,
                          @Qualifier("creditsApplyExecutor") Executor executor, CreditsDenyListKeeper denyList,
                          CreditsAutoReader auto,
                          @Value("${cinefilm.credits.apply.enabled:true}") boolean enabled) {
        this.editions = editions;
        this.markers = markers;
        this.states = states;
        this.lock = lock;
        this.sleeper = sleeper;
        this.tx = new TransactionTemplate(transactions);
        this.executor = executor;
        this.denyList = denyList;
        this.auto = auto;
        this.enabled = enabled;
    }

    /** Asks for a pass at start-up. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(STARTUP_ORDER)
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

    /** Asks for a pass after a version 2 {@code FOUND} was stored or replaced (an {@code AUTO} marker may follow). */
    @EventListener
    public void onCreditsDetected(CreditsDetected event) {
        requestPass();
    }

    /** Asks for a pass after a Fix match (a film's id, title, year or collection may have changed the deny rules). */
    @EventListener
    public void onFilmMatchFixed(FilmMatchFixed event) {
        requestPass();
    }

    /**
     * Asks for a pass in the background, unless applying is off. Never runs one inline. When a pass is already going
     * the request is not lost: it gives exactly one more pass once that one ends, however many requests arrive.
     */
    public void requestPass() {
        if (!enabled) {
            return;
        }
        requested.set(true);
        launch();
    }

    /**
     * The hourly nudge: starts a pass only when the last one was refused (the library lock stayed busy) or failed.
     * After a pass that ran, nothing is owed and this does nothing.
     */
    public void retryIfRefused() {
        // owed can only be set by a pass that a request started, and requests need the property on: no check here.
        if (owed.get()) {
            requested.set(true);
            launch();
        }
    }

    private void launch() {
        if (running.compareAndSet(false, true)) {
            try {
                executor.execute(this::worker);
            } catch (RejectedExecutionException e) {
                running.set(false);
                throw e;
            }
        }
    }

    private void worker() {
        try {
            while (requested.getAndSet(false)) {
                owed.set(false);
                boolean done;
                try {
                    done = runPass().lockTaken();
                } catch (RuntimeException e) {
                    LOG.error("Credits apply pass failed", e);
                    done = false;
                }
                owed.set(!done);
            }
            beforeWorkerEnds.run();
        } finally {
            running.set(false);
        }
        // A request that arrived after the last check but before the worker was given up would otherwise be lost.
        if (requested.get()) {
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
        DenyList list = readDenyList();
        Map<Long, String> listedUnitKeys = auto.listedUnitKeys(list);
        List<Long> ids = editions.findAllIds();
        int plexSet = 0;
        int autoSet = 0;
        int changed = 0;
        int cleared = 0;
        int ownerKept = 0;
        long inProgress = 0;
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + BATCH_SIZE));
            Batch done = tx.execute(status -> applyBatch(batch, list, listedUnitKeys));
            plexSet += done.plexSet();
            autoSet += done.autoSet();
            changed += done.changed();
            cleared += done.cleared();
            ownerKept += done.ownerKept();
            inProgress += done.inProgress();
        }
        PassResult result = new PassResult(true, plexSet, autoSet, changed, cleared, ownerKept,
            ids.size() - changed, inProgress);
        LOG.info("Credits apply pass: {} with a Plex marker, {} with an automatic marker, {} changed, {} cleared, "
                + "{} owner markers kept, {} unchanged; {} saved positions in progress are at or past their new "
                + "marker",
            result.plexSet(), result.autoSet(), result.changed(), result.cleared(), result.ownerKept(),
            result.unchanged(),
            result.inProgressPastMarker());
        return result;
    }

    /**
     * Reads the deny file at the start of the pass, whatever the switch says, and logs what is in force: the header's
     * date and count, never an entry. Says once why {@code AUTO} is off, when it is.
     *
     * @return the list in force; null when there is none
     */
    private DenyList readDenyList() {
        CreditsDenyListKeeper.Refreshed refreshed = denyList.refresh();
        Parsed inForce = refreshed.inForce();
        if (inForce == null) {
            LOG.info("Credits deny file: could not be read and no earlier copy exists, so no deny list is in force");
        } else if (refreshed.fellBack()) {
            LOG.info("Credits deny file: could not be read, so the copy read earlier stays in force "
                + "(manifest {}, {} entries)", inForce.manifestDate(), inForce.count());
        } else {
            LOG.info("Credits deny file: read (manifest {}, {} entries)", inForce.manifestDate(), inForce.count());
        }
        if (!auto.enabled() || inForce == null) {
            String why = !auto.enabled() && inForce == null ? "the switch is off and no deny list is in force"
                : !auto.enabled() ? "the switch is off" : "no deny list is in force";
            LOG.info("Credits AUTO is off: {}", why);
        }
        return inForce == null ? null : inForce.list();
    }

    /** Reads, computes and writes one batch: a fixed number of reads however many editions it holds. */
    private Batch applyBatch(List<Long> batch, DenyList list, Map<Long, String> listedUnitKeys) {
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
        CreditsAutoReader.Lookup lookup = auto.lookup(loaded, list, listedUnitKeys);
        int plexSet = 0;
        int autoSet = 0;
        int cleared = 0;
        int ownerKept = 0;
        List<Edition> toSave = new ArrayList<>();
        for (Edition edition : loaded) {
            Applied applied = applyEdition(edition, rows, edition.getCreditsMarkerSource(), lookup.inputs(edition));
            if (applied.source() == CreditsMarkerSource.PLEX) {
                plexSet++;
            }
            if (applied.source() == CreditsMarkerSource.AUTO) {
                autoSet++;
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
        return new Batch(plexSet, autoSet, toSave.size(), cleared, ownerKept, inProgress);
    }

    /**
     * What one edition's marker and source should be, as one pass step: {@link CreditsApply#apply} over the edition's
     * original parts and Plex's row for the last one. Shared with the Admin End credits clear, which passes a null
     * {@code source} to let the automatic rules decide again.
     *
     * @param edition the edition, with its files loaded
     * @param rows Plex's rows by media file id (the last part's row is the one used)
     * @param source the source to treat the edition's marker as having
     * @param autoInputs step 3's inputs; null leaves step 3 off
     */
    static Applied applyEdition(Edition edition, Map<Long, PlexCreditsMarker> rows, CreditsMarkerSource source,
                                AutoInputs autoInputs) {
        List<MediaFile> originals = edition.originalParts();
        List<Part> parts = originals.stream().map(f -> new Part(f.getId(), f.timelineDurationMs())).toList();
        MediaFile last = originals.isEmpty() ? null : originals.get(originals.size() - 1);
        PlexCreditsMarker row = last == null ? null : rows.get(last.getId());
        return CreditsApply.apply(parts, last == null ? null : last.fingerprint(), edition.getCreditsMarkerMs(),
            source, row == null ? null : new PlexRow(row.getFingerprint(), row.getStartMs()), autoInputs);
    }
}
